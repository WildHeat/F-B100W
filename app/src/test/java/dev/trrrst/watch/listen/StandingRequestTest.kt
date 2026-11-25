package dev.trrrst.watch.listen

import dev.trrrst.watch.listen.StandingRequest.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StandingRequestTest {
    private fun next(now: Long = 0, end: Long = 1_000, done: Boolean = false, bt: Boolean = true, idle: Boolean = true) =
        StandingRequest.next(now, end, done, bt, idle)

    @Test fun setsTheRequestWhenAllIsWell() = assertEquals(Step.SET, next())

    @Test fun waitsForBluetooth() {
        assertEquals(Step.WAIT_FOR_BLUETOOTH, next(bt = false))
        // Bluetooth off matters more than the app being busy (nothing can connect anyway).
        assertEquals(Step.WAIT_FOR_BLUETOOTH, next(bt = false, idle = false))
    }

    @Test fun waitsForTheApp() = assertEquals(Step.WAIT_FOR_APP, next(idle = false))

    @Test fun stopsWhenOverOrDone() {
        assertEquals(Step.STOP, next(now = 1_000))
        assertEquals(Step.STOP, next(now = 2_000, bt = false))
        assertEquals(Step.STOP, next(done = true))
    }
}
