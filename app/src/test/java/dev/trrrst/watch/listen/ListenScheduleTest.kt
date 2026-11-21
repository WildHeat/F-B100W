package dev.trrrst.watch.listen

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ListenScheduleTest {
    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, london)
    private val manual = ListenSchedule.MANUAL_TIMES
    private fun eight() = listOf(LocalTime.of(8, 0))

    @Test fun laterTodayStartsTenMinutesEarly() {
        val w = ListenSchedule.next(at(2026, 11, 18, 6, 0), eight())
        assertEquals(at(2026, 11, 18, 7, 50), w.start)
        assertEquals(at(2026, 11, 18, 8, 0), w.syncAt)
        assertEquals(at(2026, 11, 18, 8, 20), w.end)
        assertEquals("08:00", w.label)
    }

    @Test fun passedWindowMeansTomorrow() {
        val w = ListenSchedule.next(at(2026, 11, 18, 9, 0), eight())
        assertEquals(at(2026, 11, 19, 7, 50), w.start)
        assertEquals(at(2026, 11, 19, 8, 0), w.syncAt)
    }

    @Test fun insideWindowStartsNow() {
        val now = at(2026, 11, 18, 8, 5)
        val w = ListenSchedule.next(now, eight())
        assertEquals(now, w.start)
        assertEquals(at(2026, 11, 18, 8, 20), w.end)
    }

    @Test fun windowEndMeansNextOne() {
        val w = ListenSchedule.next(at(2026, 11, 18, 8, 20), eight())
        assertEquals(at(2026, 11, 19, 7, 50), w.start)
    }

    @Test fun manualTimesInOrder() {
        val w = ListenSchedule.next(at(2026, 11, 18, 2, 6), manual)
        assertEquals(at(2026, 11, 18, 6, 20), w.start)
        assertEquals(at(2026, 11, 18, 6, 30), w.syncAt)
        val evening = ListenSchedule.next(at(2026, 11, 18, 13, 0), manual)
        assertEquals(at(2026, 11, 18, 18, 20), evening.start)
    }

    @Test fun windowAroundMidnight() {
        // 00:20 to 00:50 for the 00:30 sync; at 00:40 it's still running.
        val w = ListenSchedule.next(at(2026, 11, 18, 0, 40), manual)
        assertEquals(at(2026, 11, 18, 0, 40), w.start)
        assertEquals(at(2026, 11, 18, 0, 50), w.end)
        val late = ListenSchedule.next(at(2026, 11, 18, 23, 0), manual)
        assertEquals(at(2026, 11, 19, 0, 20), late.start)
    }

    @Test fun nearbyTimesAreMerged() {
        val times = ListenSchedule.times(true, 6, 45)
        val w = ListenSchedule.next(at(2026, 11, 18, 2, 0), times)
        assertEquals(at(2026, 11, 18, 6, 20), w.start)
        assertEquals(at(2026, 11, 18, 6, 30), w.syncAt)
        assertEquals(at(2026, 11, 18, 7, 5), w.end)
        assertEquals("06:30, 06:45", w.label)
    }

    @Test fun watchSettingAddedOnlyWhenOn() {
        assertEquals(5, ListenSchedule.times(true, 8, 0).size)
        assertEquals(manual, ListenSchedule.times(false, 8, 0))
        assertEquals(manual, ListenSchedule.times(null, 8, 0))
        assertEquals(4, ListenSchedule.times(true, 6, 30).size)
    }

    @Test fun summerTimeStartKeepsWallClock() {
        // UK clocks go forward on 29 March 2026 at 01:00.
        val w = ListenSchedule.next(at(2026, 3, 28, 20, 0), eight())
        assertEquals(at(2026, 3, 29, 8, 0), w.syncAt)
        assertEquals(1, w.syncAt.offset.totalSeconds / 3600)
    }
}
