package dev.trrrst.watch.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertTrackerTest {
    private val a = "E5:33:5A:41:F8:03"
    private fun tracker() = AdvertTracker(goneAfterMs = 30_000) { "t${it / 1000}" }

    @Test fun firstAdvertIsLoggedOnce() {
        val t = tracker()
        val first = t.seen(0, a, "CASIO F-B100W", -60, true, "0201")
        assertEquals(1, first.size)
        assertTrue(first[0].contains("name=CASIO F-B100W"))
        assertTrue(t.seen(1_000, a, "CASIO F-B100W", -61, true, "0201").isEmpty())
        assertEquals(2, t.total)
    }

    @Test fun changedDataIsLogged() {
        val t = tracker()
        t.seen(0, a, null, -60, null, "0201")
        val changed = t.seen(1_000, a, null, -60, null, "0202")
        assertEquals(1, changed.size)
        assertTrue(changed[0].contains("data=0202"))
    }

    @Test fun burstEndsAfterQuietTime() {
        val t = tracker()
        t.seen(0, a, null, -60, null, "01")
        t.seen(10_000, a, null, -60, null, "01")
        assertTrue(t.tick(39_000).isEmpty())
        val ended = t.tick(40_000)
        assertEquals(listOf("$a stopped advertising: heard from t0 to t10 (10 s, 2 adverts)"), ended)
        assertEquals(ended, t.summary())
        // Heard again later: a new burst.
        assertEquals(1, t.seen(100_000, a, null, -60, null, "01").size)
    }

    @Test fun finishClosesOpenBursts() {
        val t = tracker()
        t.seen(0, a, null, -60, null, "01")
        t.seen(5_000, "11:22:33:44:55:66", null, -80, null, "02")
        assertEquals(2, t.finish().size)
        assertEquals(2, t.summary().size)
        assertTrue(t.finish().isEmpty())
    }
}
