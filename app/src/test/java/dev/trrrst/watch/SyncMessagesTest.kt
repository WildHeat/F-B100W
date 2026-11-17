package dev.trrrst.watch

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMessagesTest {
    private fun timeout(): TimeoutCancellationException = runBlocking {
        try {
            withTimeout(1) { delay(1_000) }
            error("no timeout")
        } catch (e: TimeoutCancellationException) {
            e
        }
    }

    @Test fun lostConnectionWinsOverCause() {
        assertEquals(SyncMessages.CONNECTION_LOST, SyncMessages.forFailure(timeout(), stillConnected = false))
        assertEquals(SyncMessages.CONNECTION_LOST,
            SyncMessages.forFailure(IllegalStateException("x"), stillConnected = false))
    }

    @Test fun timeoutWhileConnectedMeansNoAnswer() {
        assertEquals(SyncMessages.NO_ANSWER, SyncMessages.forFailure(timeout(), stillConnected = true))
    }

    @Test fun otherFailuresNeverLeakExceptionText() {
        val msg = SyncMessages.forFailure(
            IllegalStateException("watch write failed: 09ea070b"), stillConnected = true
        )
        assertEquals(SyncMessages.DIDNT_FINISH, msg)
    }

    @Test fun messagesHaveNoHexAndGiveANextStep() {
        listOf(
            SyncMessages.NOT_CONNECTED, SyncMessages.CONNECTION_LOST,
            SyncMessages.NO_ANSWER, SyncMessages.DIDNT_FINISH,
        ).forEach {
            assertFalse(it, Regex("0x|[0-9][0-9a-f]{3,}", RegexOption.IGNORE_CASE).containsMatchIn(it))
            assertTrue(it, it.contains("Connect"))
        }
    }
}
