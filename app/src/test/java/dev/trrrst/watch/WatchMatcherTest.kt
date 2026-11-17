package dev.trrrst.watch

import dev.trrrst.watch.WatchMatcher.ScanPick
import dev.trrrst.watch.WatchMatcher.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMatcherTest {
    private val mine = SavedWatch("D5:33:BC:22:F8:03", "CASIO F-B100W", "D5:33:BC:22:F8:03")

    // ---- after connecting: is this my watch?

    @Test fun noSavedWatchAcceptsAndSaves() {
        assertEquals(Verdict.SAVE_AND_SYNC, WatchMatcher.onConnected(null, "E5:33:8D:36:F8:03"))
    }

    @Test fun savedWatchSyncs() {
        assertEquals(Verdict.SYNC, WatchMatcher.onConnected(mine, "D5:33:BC:22:F8:03"))
    }

    @Test fun addressCaseDoesNotMatter() {
        assertEquals(Verdict.SYNC, WatchMatcher.onConnected(mine, "d5:33:bc:22:f8:03"))
    }

    @Test fun differentWatchAsks() {
        // e.g. the user's own watch after a reset (address changed E5:33:8D… → D5:33:BC…)
        assertEquals(Verdict.ASK, WatchMatcher.onConnected(mine, "E5:33:8D:36:F8:03"))
    }

    // ---- during the search

    @Test fun noSavedWatchConnectsToAny() {
        assertEquals(ScanPick.CONNECT_NOW, WatchMatcher.onScanResult(null, "E5:33:8D:36:F8:03"))
    }

    @Test fun savedWatchConnectsAtOnce() {
        assertEquals(ScanPick.CONNECT_NOW, WatchMatcher.onScanResult(mine.address, "d5:33:bc:22:f8:03"))
    }

    @Test fun otherWatchIsOnlyABackup() {
        assertEquals(ScanPick.BACKUP, WatchMatcher.onScanResult(mine.address, "E5:33:8D:36:F8:03"))
    }

    @Test fun sameAddress() {
        assertTrue(WatchMatcher.sameAddress(" D5:33:BC:22:F8:03", "d5:33:bc:22:f8:03 "))
        assertFalse(WatchMatcher.sameAddress("D5:33:BC:22:F8:03", "D5:33:BC:22:F8:04"))
    }
}
