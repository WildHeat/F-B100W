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
        val times = manual + LocalTime.of(6, 45)
        val w = ListenSchedule.next(at(2026, 11, 18, 2, 0), times)
        assertEquals(at(2026, 11, 18, 6, 20), w.start)
        assertEquals(at(2026, 11, 18, 6, 30), w.syncAt)
        assertEquals(at(2026, 11, 18, 7, 5), w.end)
        assertEquals("06:30, 06:45", w.label)
    }

    @Test fun summerTimeStartKeepsWallClock() {
        // UK clocks go forward on 29 March 2026 at 01:00.
        val w = ListenSchedule.next(at(2026, 3, 28, 20, 0), eight())
        assertEquals(at(2026, 3, 29, 8, 0), w.syncAt)
        assertEquals(1, w.syncAt.offset.totalSeconds / 3600)
    }

    @Test fun wakeTestStartsInThreeMinutesForFive() {
        val w = ListenSchedule.wakeTest(ZonedDateTime.of(2026, 11, 21, 14, 0, 12, 345_000_000, london))
        assertEquals(at(2026, 11, 21, 14, 3, 12), w.start)
        assertEquals(w.start, w.syncAt)
        assertEquals(at(2026, 11, 21, 14, 8, 12), w.end)
        assertEquals("wake-up test", w.label)
    }

    @Test fun wakeTestCrossesMidnight() {
        val w = ListenSchedule.wakeTest(at(2026, 11, 21, 23, 58))
        assertEquals(at(2026, 11, 22, 0, 1), w.start)
        assertEquals(at(2026, 11, 22, 0, 6), w.end)
    }

    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private fun zoned(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(london)

    @Test fun finishedWindowIsNotRestarted() {
        // 25 Nov: the 12:30 window ended at 12:30:40 (watch called), saved as done until
        // 12:50. Opening the app at 12:42 must plan 18:20, not restart 12:30.
        val now = ms(at(2026, 11, 25, 12, 42, 55))
        val from = ListenSchedule.planFrom(now, 0L, ms(at(2026, 11, 25, 12, 50)))
        val w = ListenSchedule.next(zoned(from), manual)
        assertEquals(at(2026, 11, 25, 18, 20), w.start)
        assertEquals(at(2026, 11, 25, 18, 30), w.syncAt)
    }

    @Test fun oldDoneUntilHasNoEffect() {
        val now = ms(at(2026, 11, 25, 12, 25))
        assertEquals(now, ListenSchedule.planFrom(now, 0L, ms(at(2026, 11, 25, 6, 50))))
        assertEquals(now, ListenSchedule.planFrom(now, 0L, 0L))
    }

    @Test fun notBeforeStillWins() {
        val now = ms(at(2026, 11, 25, 12, 30, 40))
        val end = ms(at(2026, 11, 25, 12, 50))
        assertEquals(end, ListenSchedule.planFrom(now, end, 0L))
    }

    @Test fun doneUntilFarAheadIsIgnored() {
        // Clock set back by two days: a saved end that far ahead must not block listening.
        val now = ms(at(2026, 11, 25, 12, 25))
        assertEquals(now, ListenSchedule.planFrom(now, 0L, ms(at(2026, 11, 27, 12, 50))))
    }
}
