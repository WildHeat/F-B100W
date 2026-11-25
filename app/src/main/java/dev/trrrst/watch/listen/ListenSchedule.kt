package dev.trrrst.watch.listen

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** When the background listening test (PLAN Phase 3, step 1) runs. Pure, unit-tested.
 *
 *  Casio's manual (module 3589, "Auto Time Correction") says the watch connects to the
 *  phone four times a day at around [MANUAL_TIMES]; with auto time sync on, the user's
 *  watch called at about :30:30 (2026-11-23). The phone listens from [LEAD] before each
 *  time until [AFTER] after it. */
object ListenSchedule {
    val LEAD: Duration = Duration.ofMinutes(10)
    val AFTER: Duration = Duration.ofMinutes(20)
    val MANUAL_TIMES: List<LocalTime> = listOf(
        LocalTime.of(0, 30), LocalTime.of(6, 30), LocalTime.of(12, 30), LocalTime.of(18, 30),
    )

    /** One listening window: listen from [start] to [end]; the watch should sync at
     *  [syncAt] (the first of the times in [label], if windows were merged). */
    data class Window(val start: ZonedDateTime, val syncAt: ZonedDateTime, val end: ZonedDateTime, val label: String)

    /** The next window that hasn't ended yet. If it has already begun, listening starts
     *  [now]. Windows that overlap are merged into one. */
    fun next(now: ZonedDateTime, times: List<LocalTime>): Window {
        require(times.isNotEmpty()) { "no sync times" }
        val today = now.toLocalDate()
        val syncs = listOf(today.minusDays(1), today, today.plusDays(1), today.plusDays(2))
            .flatMap { d -> times.map { d.atTime(it).atZone(now.zone) } }
            .distinct()
            .sorted()
            .filter { it.plus(AFTER).isAfter(now) }
        val first = syncs.first()
        var end = first.plus(AFTER)
        val inWindow = mutableListOf(first)
        for (s in syncs.drop(1)) {
            if (s.minus(LEAD).isAfter(end)) break
            inWindow += s
            end = maxOf(end, s.plus(AFTER))
        }
        val lead = first.minus(LEAD)
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        return Window(
            if (lead.isAfter(now)) lead else now, first, end,
            inWindow.joinToString(", ") { it.format(hm) },
        )
    }

    /** "Test the wake-up" (PLAN Phase 3, Part 2): starts [WAKE_TEST_LEAD] from now, so the
     *  user has time to swipe the app away and lock the phone, and lasts [WAKE_TEST_LENGTH]. */
    val WAKE_TEST_LEAD: Duration = Duration.ofMinutes(3)
    val WAKE_TEST_LENGTH: Duration = Duration.ofMinutes(5)
    const val WAKE_TEST_LABEL = "wake-up test"

    fun wakeTest(now: ZonedDateTime): Window {
        val start = now.plus(WAKE_TEST_LEAD).withNano(0)
        return Window(start, start, start.plus(WAKE_TEST_LENGTH), WAKE_TEST_LABEL)
    }

    /** A saved "done until" further ahead than this is ignored (e.g. the phone's clock
     *  was set back), so it can never block listening for long. */
    val DONE_UNTIL_MAX_AHEAD: Duration = Duration.ofDays(1)

    /** When to plan the next window from (ms): now, but never inside a window that has
     *  already run. [doneUntil] is the end of the last window that finished (0 = none).
     *
     *  On 25 Nov the watch's 12:30 call ended that window at 12:30:40; opening the app at
     *  12:42 planned from "now", restarted the 12:30 window until 12:50, and the wake-up
     *  test set at 12:43 was ignored as "already listening". */
    fun planFrom(now: Long, notBefore: Long, doneUntil: Long): Long {
        val done = if (doneUntil - now > DONE_UNTIL_MAX_AHEAD.toMillis()) 0L else doneUntil
        return maxOf(now, notBefore, done)
    }
}
