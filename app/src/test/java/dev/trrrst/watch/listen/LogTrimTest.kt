package dev.trrrst.watch.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTrimTest {
    @Test fun shortTextIsKept() {
        assertEquals("a\nb\n", LogTrim.trim("a\nb\n", 100))
    }

    @Test fun keepsWholeLinesFromTheEnd() {
        val text = "line one\nline two\nline three\n"
        assertEquals("(older lines removed)\nline three\n", LogTrim.trim(text, 14))
    }

    @Test fun cutOnLineBoundaryKeepsThatLine() {
        val text = "aaaa\nbbbb\n"
        assertEquals("(older lines removed)\nbbbb\n", LogTrim.trim(text, 5))
    }
}
