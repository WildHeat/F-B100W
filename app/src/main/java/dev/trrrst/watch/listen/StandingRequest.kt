package dev.trrrst.watch.listen

/** What the listening window's standing connection request should do next (PLAN Phase 3,
 *  cleanup). Pure, unit-tested.
 *
 *  On 23 Nov at 06:22 Bluetooth was off when the window started; the request was set
 *  anyway and sat idle for the whole window. Now it waits for Bluetooth to come back. */
object StandingRequest {
    enum class Step {
        /** The window is over (or the one connection has been made): stop. */
        STOP,
        /** Bluetooth is off: check again in [RECHECK_MS]. */
        WAIT_FOR_BLUETOOTH,
        /** The app itself is using the watch: check again in [RECHECK_MS]. */
        WAIT_FOR_APP,
        /** Set the standing request now. */
        SET,
    }

    const val RECHECK_MS = 10_000L

    fun next(now: Long, end: Long, done: Boolean, bluetoothOn: Boolean, appIdle: Boolean): Step = when {
        done || now >= end -> Step.STOP
        !bluetoothOn -> Step.WAIT_FOR_BLUETOOTH
        !appIdle -> Step.WAIT_FOR_APP
        else -> Step.SET
    }
}
