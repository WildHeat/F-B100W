package dev.trrrst.watch.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBurstsTest {
    @Test fun staysUnderAndroidsScanStartLimit() {
        val starts = ScanBursts.maxStartsWithin(ScanBursts.ANDROID_LIMIT_WINDOW_MS)
        assertTrue("$starts scan starts per 30 s", starts < ScanBursts.ANDROID_LIMIT_STARTS)
    }

    @Test fun countsStartsInAWindow() {
        assertEquals(4, ScanBursts.maxStartsWithin(30_000, 5_000, 3_000))   // 0, 8, 16, 24 s
        assertEquals(5, ScanBursts.maxStartsWithin(32_000, 5_000, 3_000))   // ... and 32 s
        assertEquals(6, ScanBursts.maxStartsWithin(30_000, 4_000, 2_000))   // too many
    }
}
