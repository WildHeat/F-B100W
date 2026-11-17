package dev.trrrst.watch.data

import dev.trrrst.watch.SyncMessages
import dev.trrrst.watch.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogTest {
    private fun rec(i: Int, ok: Boolean = true) = SyncRecord(
        time = 1_700_000_000_000L + i, trigger = SyncTrigger.MANUAL, ok = ok,
        steps = if (ok) i * 100 else null,
        error = if (ok) null else SyncMessages.CONNECTION_LOST,
    )

    @Test fun newestFirst() {
        val log = SyncLog.add(SyncLog.add(emptyList(), rec(1)), rec(2))
        assertEquals(listOf(rec(2), rec(1)), log)
    }

    @Test fun keepsOnlyTheLastTen() {
        var log = emptyList<SyncRecord>()
        (1..13).forEach { log = SyncLog.add(log, rec(it)) }
        assertEquals(10, log.size)
        assertEquals(rec(13), log.first())
        assertEquals(rec(4), log.last())
    }

    @Test fun jsonRoundTrip() {
        val log = listOf(
            rec(3),
            rec(2, ok = false),
            SyncRecord(1_700_000_000_001L, SyncTrigger.BACKGROUND, ok = true),   // no steps
        )
        assertEquals(log, SyncLog.fromJson(SyncLog.toJson(log)))
    }

    @Test fun errorTextWithQuotesSurvives() {
        val r = SyncRecord(1L, SyncTrigger.MANUAL, false, error = "Pick \"London\", then save.\nOK")
        assertEquals(listOf(r), SyncLog.fromJson(SyncLog.toJson(listOf(r))))
    }

    @Test fun unreadableDataGivesEmptyLog() {
        assertTrue(SyncLog.fromJson(null).isEmpty())
        assertTrue(SyncLog.fromJson("").isEmpty())
        assertTrue(SyncLog.fromJson("not json").isEmpty())
    }

    @Test fun badEntriesAreSkipped() {
        val json = """[{"time":5,"trigger":"MANUAL","ok":true,"steps":42},
            {"time":6,"trigger":"SOMETHING_NEW","ok":true},
            {"trigger":"MANUAL","ok":true}]"""
        assertEquals(listOf(SyncRecord(5, SyncTrigger.MANUAL, true, steps = 42)), SyncLog.fromJson(json))
    }
}
