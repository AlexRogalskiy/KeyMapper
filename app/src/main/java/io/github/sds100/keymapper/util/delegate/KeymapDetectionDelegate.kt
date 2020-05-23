package io.github.sds100.keymapper.util.delegate

import android.view.KeyEvent
import androidx.collection.SparseArrayCompat
import androidx.collection.keyIterator
import androidx.lifecycle.MutableLiveData
import io.github.sds100.keymapper.data.model.Action
import io.github.sds100.keymapper.data.model.Extra
import io.github.sds100.keymapper.data.model.KeyMap
import io.github.sds100.keymapper.data.model.Trigger
import io.github.sds100.keymapper.util.Event
import io.github.sds100.keymapper.util.IClock
import io.github.sds100.keymapper.util.result.onFailure
import io.github.sds100.keymapper.util.result.onSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag
import splitties.bitflags.withFlag
import timber.log.Timber

/**
 * Created by sds100 on 05/05/2020.
 */

class KeymapDetectionDelegate(private val mCoroutineScope: CoroutineScope,
                              val preferences: KeymapDetectionPreferences,
                              iClock: IClock) : IClock by iClock {

    companion object {
        /**
         * The time in ms between repeating an action while holding down.
         */
        private const val REPEAT_DELAY = 50L

        /**
         * How long a key should be held down to repeatedly perform an action in ms.
         */
        private const val HOLD_DOWN_DELAY = 400L

        /**
         * Some keys need to be consumed on the up event to prevent them from working they way they are intended to.
         */
        private val KEYS_TO_CONSUME_UP_EVENT = listOf(
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH
        )

        //FLAGS
        //0 to 1023 is reserved for keycodes
        private const val FLAG_SHORT_PRESS = 1024
        private const val FLAG_LONG_PRESS = 2048
        private const val FLAG_DOUBLE_PRESS = 4096
        private const val FLAG_INTERNAL_DEVICE = 8192

        //the states for keys awaiting a double press
        private const val NOT_PRESSED = -1
        private const val SINGLE_PRESSED = 0
        private const val DOUBLE_PRESSED = 1

        private fun createDeviceDescriptorMap(descriptors: Set<String>): SparseArrayCompat<String> {
            var key = 16384
            val map = SparseArrayCompat<String>()

            descriptors.forEach {
                map.put(key, it)
                key *= 2
            }

            return map
        }

        private fun createActionMap(actions: Set<Action>): SparseArrayCompat<Action> {
            var key = 0

            val map = SparseArrayCompat<Action>()

            actions.forEach {
                map.put(key, it)
                key++
            }

            return map
        }
    }

    /**
     * A cached copy of the keymaps in the database
     */
    var keyMapListCache: List<KeyMap> = listOf()
        set(value) {
            mDeviceDescriptorMap.clear()
            mActionMap.clear()

            // If there are no keymaps with actions then keys don't need to be detected.
            if (!value.any { it.actionList.isNotEmpty() }) {
                field = value
                mDetectKeymaps = false
                return
            }

            if (value.isEmpty()) {
                mDetectKeymaps = false
            } else {
                mDetectKeymaps = true

                val sequenceKeyMaps = mutableListOf<KeyMap>()
                val parallelKeyMaps = mutableListOf<KeyMap>()
                val longPressSequenceEvents = mutableSetOf<Int>()
                val doublePressSequenceEvents = mutableSetOf<Int>()

                mActionMap = createActionMap(value.flatMap { it.actionList }.toSet())

                // Extract all the external device descriptors used in enabled keymaps because the list is used later
                val deviceDescriptors = mutableSetOf<String>()
                val sequenceTriggerTimeouts = mutableListOf<Int>()
                val sequenceTriggerEvents = mutableListOf<IntArray>()
                val sequenceTriggerActions = mutableListOf<IntArray>()
                val sequenceTriggerVibrate = mutableListOf<Boolean>()

                val parallelTriggerEvents = mutableListOf<IntArray>()
                val parallelTriggerActions = mutableListOf<IntArray>()
                val parallelTriggerVibrate = mutableListOf<Boolean>()

                for (keyMap in value) {
                    if (!keyMap.isEnabled) {
                        continue
                    }

                    keyMap.trigger.keys.forEach {

                        if (it.deviceId != Trigger.Key.DEVICE_ID_THIS_DEVICE &&
                            it.deviceId != Trigger.Key.DEVICE_ID_ANY_DEVICE) {
                            deviceDescriptors.add(it.deviceId)
                        }
                    }
                }

                mDeviceDescriptorMap = createDeviceDescriptorMap(deviceDescriptors)

                for (keyMap in value) {
                    // ignore the keymap if it has no action.
                    if (keyMap.actionList.isEmpty()) {
                        continue
                    }

                    if (!keyMap.isEnabled) {
                        continue
                    }

                    //TRIGGER STUFF
                    when (keyMap.trigger.mode) {
                        Trigger.PARALLEL -> parallelKeyMaps.add(keyMap)
                        Trigger.SEQUENCE -> sequenceKeyMaps.add(keyMap)
                    }

                    val encodedTriggerList = mutableListOf<Int>()

                    keyMap.trigger.keys.forEach { key ->

                        if (keyMap.trigger.mode == Trigger.SEQUENCE) {
                            when (key.clickType) {
                                Trigger.LONG_PRESS -> {
                                    longPressSequenceEvents.add(encodeEvent(key.keyCode, key.clickType, key.deviceId))
                                }

                                Trigger.DOUBLE_PRESS -> {
                                    doublePressSequenceEvents.add(encodeEvent(key.keyCode, key.clickType, key.deviceId))
                                }
                            }
                        }

                        when (key.deviceId) {
                            Trigger.Key.DEVICE_ID_THIS_DEVICE -> {
                                mDetectInternalEvents = true
                            }

                            Trigger.Key.DEVICE_ID_ANY_DEVICE -> {
                                mDetectInternalEvents = true
                                mDetectExternalEvents = true
                            }

                            else -> {
                                mDetectExternalEvents = true
                            }
                        }

                        encodedTriggerList.add(encodeEvent(key.keyCode, key.clickType, key.deviceId))
                    }

                    val encodedActionList = encodeActionList(keyMap.actionList)

                    when (keyMap.trigger.mode) {
                        Trigger.SEQUENCE -> {
                            sequenceTriggerEvents.add(encodedTriggerList.toIntArray())
                            sequenceTriggerActions.add(encodedActionList)
                            sequenceTriggerVibrate.add(keyMap.flags.hasFlag(KeyMap.KEYMAP_FLAG_VIBRATE))

                            keyMap.trigger.getExtraData(Extra.EXTRA_SEQUENCE_TRIGGER_TIMEOUT)
                                .onSuccess {
                                    sequenceTriggerTimeouts.add(it.toInt())
                                }.onFailure {
                                    val default = Trigger.DEFAULT_TIMEOUT

                                    sequenceTriggerTimeouts.add(default)
                                }
                        }

                        Trigger.PARALLEL -> {
                            parallelTriggerEvents.add(encodedTriggerList.toIntArray())
                            parallelTriggerActions.add(encodedActionList)
                            parallelTriggerVibrate.add(keyMap.flags.hasFlag(KeyMap.KEYMAP_FLAG_VIBRATE))
                        }
                    }
                }

                mDetectSequenceTriggers = sequenceTriggerEvents.isNotEmpty()
                mSequenceTriggerEvents = sequenceTriggerEvents.toTypedArray()
                mSequenceTriggerActions = sequenceTriggerActions.toTypedArray()
                mSequenceTriggerVibrate = sequenceTriggerVibrate.toTypedArray()
                mSequenceTriggerTimeouts = sequenceTriggerTimeouts.toIntArray()
                mSequenceTriggersTimeoutTimes = LongArray(mSequenceTriggerEvents.size) { -1 }
                mLastMatchedSequenceEventIndices = IntArray(mSequenceTriggerEvents.size) { -1 }

                mDetectParallelTriggers = parallelTriggerEvents.isNotEmpty()
                mParallelTriggerEvents = parallelTriggerEvents.toTypedArray()
                mParallelTriggerActions = parallelTriggerActions.toTypedArray()
                mParallelTriggerVibrate = parallelTriggerVibrate.toTypedArray()
                mLastMatchedParallelEventIndices = IntArray(mParallelTriggerEvents.size) { -1 }

                mDetectSequenceLongPresses = longPressSequenceEvents.isNotEmpty()
                mLongPressSequenceEvents = longPressSequenceEvents.toIntArray()

                mDetectSequenceDoublePresses = doublePressSequenceEvents.isNotEmpty()
                mDoublePressSequenceEvents = doublePressSequenceEvents.toIntArray()
                mDoublePressEventStates = IntArray(doublePressSequenceEvents.size) { NOT_PRESSED }
                mDoublePressTimeoutTimes = LongArray(doublePressSequenceEvents.size) { -1L }
            }

            field = value
        }

    private var mDetectKeymaps = false
    private var mDetectInternalEvents = false
    private var mDetectExternalEvents = false
    private var mDetectSequenceTriggers = false
    private var mDetectSequenceLongPresses = false
    private var mDetectSequenceDoublePresses = false

    private var mDetectParallelTriggers = false

    /**
     * All events that have the long press click type.
     */
    private var mLongPressSequenceEvents = intArrayOf()

    /**
     * All events that have the double press click type.
     */
    private var mDoublePressSequenceEvents = intArrayOf()
    private var mDoublePressEventStates = intArrayOf()

    /**
     * The user has an amount of time to double press a key for it to be registered as a double press.
     * The order matches with [mDoublePressSequenceEvents]. This array stores the time when the corresponding trigger in will
     * timeout. If the key isn't waiting to timeout, the value is -1.
     */
    private var mDoublePressTimeoutTimes = longArrayOf()

    private var mDeviceDescriptorMap = SparseArrayCompat<String>()
    private var mActionMap = SparseArrayCompat<Action>()

    /**
     * The events to detect for each sequence trigger.
     */
    private var mSequenceTriggerEvents = arrayOf<IntArray>()

    /**
     * An array of whether to vibrate when each trigger is detected.
     */
    private var mSequenceTriggerVibrate = arrayOf<Boolean>()

    /**
     * The actions to perform when each trigger is detected. The order matches with
     * [mSequenceTriggerEvents].
     */
    private var mSequenceTriggerActions = arrayOf<IntArray>()

    /**
     * An array of the user-defined timeouts for each sequence trigger. The order matches with
     * [mSequenceTriggerEvents].
     */
    private var mSequenceTriggerTimeouts = intArrayOf()

    /**
     * Sequence triggers timeout after the first key has been pressed. The order matches with [mSequenceTriggerEvents].
     * This array stores the time when the corresponding trigger in will timeout. If the trigger in
     * isn't waiting to timeout, the value is -1.
     */
    private var mSequenceTriggersTimeoutTimes = longArrayOf()

    /**
     * An array of the index of the last matched event in each sequence trigger.
     */
    private var mLastMatchedSequenceEventIndices = intArrayOf()

    /**
     * The events to detect for each parallel trigger.
     */
    private var mParallelTriggerEvents = arrayOf<IntArray>()

    /**
     * An array of whether to vibrate when each trigger is detected.
     */
    private var mParallelTriggerVibrate = arrayOf<Boolean>()

    /**
     * The actions to perform when each trigger is detected. The order matches with
     * [mSequenceTriggerEvents].
     */
    private var mParallelTriggerActions = arrayOf<IntArray>()

    /**
     * An array of the index of the last matched event in each parallel trigger.
     */
    private var mLastMatchedParallelEventIndices = intArrayOf()

    private val mEventDownTimeMap = mutableMapOf<Int, Long>()

    val performAction: MutableLiveData<Event<Action>> = MutableLiveData()
    val imitateButtonPress: MutableLiveData<Event<Int>> = MutableLiveData()
    val vibrate: MutableLiveData<Event<Unit>> = MutableLiveData()

    /**
     * @return whether to consume the [KeyEvent].
     */
    fun onKeyEvent(keyCode: Int, action: Int, descriptor: String, isExternal: Boolean): Boolean {
        if (!mDetectKeymaps) return false

        if ((isExternal && !mDetectExternalEvents) || (!isExternal && !mDetectInternalEvents)) {
            return false
        }

        val encodedEvent =
            if (isExternal) {
                encodeEvent(keyCode, Trigger.UNDETERMINED, descriptor)
            } else {
                encodeEvent(keyCode, Trigger.UNDETERMINED, Trigger.Key.DEVICE_ID_THIS_DEVICE)
            }

        when (action) {
            KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, encodedEvent)
            KeyEvent.ACTION_UP -> return onKeyUp(keyCode, encodedEvent)
        }

        return false
    }

    /**
     * @return whether to consume the [KeyEvent].
     */
    private fun onKeyDown(keyCode: Int, encodedEvent: Int): Boolean {
        mEventDownTimeMap[encodedEvent] = currentTime

        var consumeEvent = false

        //consume sequence trigger keys until their timeout has been reached
        mSequenceTriggersTimeoutTimes.forEachIndexed { triggerIndex, timeoutTime ->
            if (timeoutTime != -1L && currentTime >= timeoutTime) {
                mLastMatchedSequenceEventIndices[triggerIndex] = -1
                mSequenceTriggersTimeoutTimes[triggerIndex] = -1
            } else {
                //consume the event if the trigger contains this keycode.
                if (mSequenceTriggerEvents[triggerIndex].hasKeycode(keyCode)) {
                    consumeEvent = true
                }
            }
        }

        mDoublePressTimeoutTimes.forEachIndexed { doublePressEventIndex, timeoutTime ->
            if (currentTime >= timeoutTime) {
                mDoublePressTimeoutTimes[doublePressEventIndex] = -1
                mDoublePressEventStates[doublePressEventIndex] = NOT_PRESSED

            } else {
                consumeEvent = true
            }
        }

        if (mDetectParallelTriggers) {
            for ((triggerIndex, lastMatchedIndex) in mLastMatchedParallelEventIndices.withIndex()) {
                val nextIndex = lastMatchedIndex + 1

                if (mParallelTriggerEvents[triggerIndex].hasEventAtIndex(
                        encodedEvent.withFlag(FLAG_SHORT_PRESS), nextIndex)) {

                    consumeEvent = true
                    mLastMatchedParallelEventIndices[triggerIndex] = nextIndex

                    if (nextIndex == mParallelTriggerEvents[triggerIndex].lastIndex) {
                        mParallelTriggerActions[triggerIndex].forEach {
                            val action = mActionMap[it] ?: return@forEach
                            performAction.value = Event(action)

                            if (mParallelTriggerVibrate[triggerIndex] || preferences.forceVibrate) {
                                vibrate.value = Event(Unit)
                            }
                        }
                    }
                }

                if (mParallelTriggerEvents[triggerIndex].hasEventAtIndex(
                        encodedEvent.withFlag(FLAG_LONG_PRESS), nextIndex)) {

                    consumeEvent = true
                    mLastMatchedParallelEventIndices[triggerIndex] = nextIndex

                    if (nextIndex == mParallelTriggerEvents[triggerIndex].lastIndex) {
                        mCoroutineScope.launch {
                            delay(preferences.longPressDelay.toLong())

                            if (mLastMatchedParallelEventIndices[triggerIndex] ==
                                mParallelTriggerEvents[triggerIndex].lastIndex) {

                                mLastMatchedParallelEventIndices[triggerIndex] = -1

                                mParallelTriggerActions[triggerIndex].forEach {
                                    val action = mActionMap[it] ?: return@forEach
                                    performAction.value = Event(action)

                                    if (mParallelTriggerVibrate[triggerIndex] || preferences.forceVibrate) {
                                        vibrate.value = Event(Unit)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (consumeEvent) {
            Timber.d("consume down")
            return true
        }

        if (mDetectSequenceDoublePresses) {
            if (mDoublePressSequenceEvents.hasEvent(encodedEvent.withFlag(FLAG_DOUBLE_PRESS))) {
                Timber.d("consume down")
                return true
            }
        }

        if (mDetectSequenceLongPresses) {
            if (mDoublePressSequenceEvents.hasEvent(encodedEvent.withFlag(FLAG_LONG_PRESS))) {
                Timber.d("consume down")
                return true
            }
        }

        return false
    }

    /**
     * @return whether to consume the event.
     */
    private fun onKeyUp(keyCode: Int, encodedEvent: Int): Boolean {
        val downTime = mEventDownTimeMap[encodedEvent]!!
        mEventDownTimeMap.remove(encodedEvent)

        var consumeEvent = false
        var imitateButtonPress = false

        var hasVibrateFlag = false
        var mappedToLongPress = false
        var mappedToDoublePress = false
        var successfulLongPress = false
        var successfulDoublePress = false

        var imitateKeyAfterDoublePressTimeout = false
        var matchedDoublePressEventIndex = -1
        val actionKeysToPerform = mutableSetOf<Int>()

        if (mDetectSequenceLongPresses &&
            mLongPressSequenceEvents.hasEvent(encodedEvent.withFlag(FLAG_LONG_PRESS))) {
            mappedToLongPress = true

            if ((currentTime - downTime) >= preferences.longPressDelay) {
                successfulLongPress = true
            } else {
                imitateButtonPress = true
            }
        }

        if (mDetectSequenceDoublePresses) {
            //iterate over each possible double press event to detect
            for (index in mDoublePressSequenceEvents.indices) {
                if (mDoublePressSequenceEvents.hasEventAtIndex(encodedEvent.withFlag(FLAG_DOUBLE_PRESS), index)) {

                    mappedToDoublePress = true
                    //increment the double press event state.
                    mDoublePressEventStates[index] = mDoublePressEventStates[index] + 1

                    when (mDoublePressEventStates[index]) {
                        /*if the key is in the single pressed state, set the timeout time and start the timer
                        * to imitate the key if it isn't double pressed in the end */
                        SINGLE_PRESSED -> {
                            mDoublePressTimeoutTimes[index] = currentTime + preferences.doublePressDelay

                            imitateKeyAfterDoublePressTimeout = true
                            matchedDoublePressEventIndex = index

                            consumeEvent = true
                        }

                        /* When the key is double pressed */
                        DOUBLE_PRESSED -> {

                            successfulDoublePress = true
                            mDoublePressEventStates[index] = NOT_PRESSED
                            mDoublePressTimeoutTimes[index] = -1
                        }
                    }
                }
            }
        }

        if (mDetectSequenceTriggers) {
            for ((triggerIndex, lastMatchedEventIndex) in mLastMatchedSequenceEventIndices.withIndex()) {
                //the index of the next event to match in the trigger
                val nextIndex = lastMatchedEventIndex + 1

                val encodedEventWithClickType = when {
                    successfulLongPress -> encodedEvent.withFlag(FLAG_LONG_PRESS)
                    successfulDoublePress -> encodedEvent.withFlag(FLAG_DOUBLE_PRESS)
                    else -> encodedEvent.withFlag(FLAG_SHORT_PRESS)
                }
                //if the next event matches the event just pressed
                if (mSequenceTriggerEvents[triggerIndex].hasEventAtIndex(encodedEventWithClickType, nextIndex)) {
                    consumeEvent = true

                    mLastMatchedSequenceEventIndices[triggerIndex] = nextIndex

                    /*
                    If the next index is 0, then the first event in the trigger has been matched, which means the timer
                    needs to start for this trigger.
                     */
                    if (nextIndex == 0) {
                        val startTime = currentTime
                        val timeout = mSequenceTriggerTimeouts[triggerIndex]

                        mSequenceTriggersTimeoutTimes[triggerIndex] = startTime + timeout
                    }

                    /*
                    If the last event in a trigger has been matched, then the action needs to be performed and the timer
                    reset.
                     */
                    if (nextIndex == mSequenceTriggerEvents[triggerIndex].lastIndex) {

                        actionKeysToPerform.addAll(mSequenceTriggerActions[triggerIndex].toList())
                        hasVibrateFlag = mSequenceTriggerVibrate[triggerIndex]
                        mLastMatchedSequenceEventIndices[triggerIndex] = -1
                        mSequenceTriggersTimeoutTimes[triggerIndex] = -1
                    }
                }
            }
        }

        if (mDetectParallelTriggers) {
            for ((triggerIndex, lastMatchedIndex) in mLastMatchedParallelEventIndices.withIndex()) {
                if (lastMatchedIndex == -1) {
                    continue
                }

                if (mParallelTriggerEvents[triggerIndex].hasEvent(encodedEvent.withFlag(FLAG_SHORT_PRESS))) {
                    consumeEvent = true
                    mLastMatchedParallelEventIndices[triggerIndex] = -1
                }

                if (mParallelTriggerEvents[triggerIndex].hasEvent(encodedEvent.withFlag(FLAG_LONG_PRESS))) {
                    consumeEvent = true

                    if (lastMatchedIndex > -1 &&
                        lastMatchedIndex < mParallelTriggerEvents[triggerIndex].lastIndex) {
                        imitateButtonPress = true
                    }

                    mLastMatchedParallelEventIndices[triggerIndex] = -1
                }
            }
        }

        actionKeysToPerform.forEach {
            val action = mActionMap[it] ?: return@forEach

            performAction.value = Event(action)
            if (hasVibrateFlag || preferences.forceVibrate) {
                vibrate.value = Event(Unit)
            }
        }

        //only imitate a key if an action isn't going to be performed
        if (imitateButtonPress && actionKeysToPerform.isEmpty()) {
            this.imitateButtonPress.value = Event(keyCode)
        }

        if (imitateKeyAfterDoublePressTimeout && !imitateButtonPress && actionKeysToPerform.isEmpty()) {
            mCoroutineScope.launch {
                delay(preferences.doublePressDelay.toLong())

                /*
                If the key has still only been single pressed, imitate it.
                 */
                if (mDoublePressEventStates[matchedDoublePressEventIndex] == SINGLE_PRESSED) {
                    this@KeymapDetectionDelegate.imitateButtonPress.value = Event(keyCode)
                }
            }
        }

        if (consumeEvent) {
            Timber.d("consume up")
        }

        return consumeEvent
    }

    fun reset() {
        mSequenceTriggersTimeoutTimes = LongArray(mSequenceTriggerEvents.size) { -1 }
        mLastMatchedSequenceEventIndices = IntArray(mSequenceTriggerEvents.size) { -1 }
        mLastMatchedParallelEventIndices = IntArray(mParallelTriggerEvents.size) { -1 }
    }

    /**
     * Key presses will be encoded as an integer to improve performance and simplify the data structures that
     * could be needed. Attributes will be stored as flags added to the keycode.
     *
     * - 0 to 1023 will be reserved for the keycode.
     * - 1024, 2048, 4096 are the click types.
     * - An 8192 flag means the event came from an internal device.
     * - If the key is from an external device, a flag greater than 8192 is for the key that points to the descriptor
     * in the [mDeviceDescriptorMap].
     */
    private fun encodeEvent(keyCode: Int, @Trigger.ClickType clickType: Int, deviceId: String): Int {
        val clickTypeFlag = when (clickType) {
            Trigger.SHORT_PRESS -> FLAG_SHORT_PRESS
            Trigger.LONG_PRESS -> FLAG_LONG_PRESS
            Trigger.DOUBLE_PRESS -> FLAG_DOUBLE_PRESS
            else -> 0
        }

        return when (deviceId) {
            Trigger.Key.DEVICE_ID_THIS_DEVICE ->
                keyCode.withFlag(clickTypeFlag).withFlag(FLAG_INTERNAL_DEVICE)

            Trigger.Key.DEVICE_ID_ANY_DEVICE ->
                keyCode.withFlag(clickTypeFlag)

            else -> {
                val descriptorKey = getDescriptorKey(deviceId)

                if (descriptorKey == -1) {
                    keyCode.withFlag(clickTypeFlag)
                } else {
                    keyCode.withFlag(clickTypeFlag).withFlag(descriptorKey)
                }
            }
        }
    }

    private fun encodeActionList(actions: List<Action>): IntArray {
        return actions.map { getActionKey(it) }.toIntArray()
    }

    /**
     * @return the key for the action in [mActionMap]. Returns -1 if the [action] can't be found.
     */
    private fun getActionKey(action: Action): Int {
        mActionMap.keyIterator().forEach { key ->
            if (mActionMap[key] == action) {
                return key
            }
        }

        throw Exception("Action ${action.uniqueId} not in the action map!")
    }

    /**
     * @return the key for the device descriptor in [mDeviceDescriptorMap]. Returns -1 if this descriptor isn't in
     * [mDeviceDescriptorMap]
     */
    private fun getDescriptorKey(descriptor: String): Int {
        mDeviceDescriptorMap.keyIterator().forEach { key ->
            if (mDeviceDescriptorMap[key] == descriptor) {
                return key
            }
        }

        return -1
    }

    private val Int.internalDevice
        get() = this.hasFlag(FLAG_INTERNAL_DEVICE)

    private val Int.externalDevice
        get() = this > 16384

    private val Int.anyDevice
        get() = this < 8192

    private val Int.keyCode
        get() = this and 1023

    private val IntArray.keyCodes: IntArray
        get() {
            val array = IntArray(size)

            forEachIndexed { index, key ->
                array[index] = key.keyCode
            }

            return array
        }

    private fun IntArray.hasKeycode(keyCode: Int) = this.any { it.keyCode == keyCode }

    private fun IntArray.hasEvent(event: Int): Boolean {
        for (i in this.indices) {
            if (this.hasEventAtIndex(event, i)) {
                return true
            }
        }

        return false
    }

    private fun IntArray.hasEventAtIndex(event: Int, index: Int): Boolean {
        val triggerEvent = this[index]

        if (triggerEvent.anyDevice || event.anyDevice) {
            if (triggerEvent.keyCode == event.keyCode && triggerEvent.clickType == event.clickType) {
                return true
            }
        } else {
            if (triggerEvent == event) {
                return true
            }
        }

        return false
    }

    private val Int.clickType
        //bit shift right 10x and only keep last 3 bits
        get() = (this shr 10) and 7

    private val Int.deviceDescriptor
        get() = (this shr 13) shl 13
}