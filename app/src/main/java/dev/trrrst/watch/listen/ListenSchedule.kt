package dev.trrrst.watch.listen

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** When the background listening test (PLAN Phase 3, step 1) runs. Pure, unit-tested.
 *
 *  Casio's manual (module 3589, "Auto Time Correction") says the watch connects to the
 *  phone four times a day at around [MANUAL_TIMES]. The app also knows an "auto-sync
 *  time" from the watch's BLE settings whose meaning isn't confirmed, so the test
 *  listens around both: from [LEAD] before each time until [AFTER] after it. */
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

    /** The times to listen around: the manual's four, plus the watch's own setting if on. */
    fun times(autoSyncOn: Boolean?, hour: Int, minute: Int): List<LocalTime> =
        if (autoSyncOn == true && hour in 0..23 && minute in 0..59)
            (MANUAL_TIMES + LocalTime.of(hour, minute)).distinct().sorted()
        else MANUAL_TIMES
}
