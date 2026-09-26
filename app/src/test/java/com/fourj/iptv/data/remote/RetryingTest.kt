package com.fourj.iptv.data.remote

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RetryingTest {

    @Test
    fun `succeeds without retrying when the first attempt works`() = runTest {
        var calls = 0
        val result = retrying(label = "test") {
            calls++
            "ok"
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun `recovers from a transient failure`() = runTest {
        var calls = 0
        val result = retrying(label = "test") {
            calls++
            if (calls < 3) throw IOException("connection reset")
            "ok"
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(3, calls)
    }

    @Test
    fun `recovers from a truncated json body`() = runTest {
        // The exact failure seen against a real provider: a large channel list arrives cut off.
        var calls = 0
        val result = retrying(label = "get_live_streams") {
            calls++
            if (calls == 1) {
                throw SerializationException("Expected end of the array ']', but had 'EOF'")
            }
            listOf("channel")
        }
        assertEquals(listOf("channel"), result.getOrNull())
    }

    @Test
    fun `gives up after the attempt limit`() = runTest {
        var calls = 0
        val result = retrying(label = "test") {
            calls++
            throw IOException("still broken")
        }
        assertTrue(result.isFailure)
        assertEquals(3, calls)
    }

    @Test
    fun `does not retry a permanent failure`() = runTest {
        // A rejected password fails identically forever; three attempts would just be slower.
        var calls = 0
        val result = retrying(label = "test") {
            calls++
            throw IllegalStateException("bad credentials")
        }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun `transient failures are classified as retryable`() {
        assertTrue(IOException("reset").isTransientNetworkFailure())
        assertTrue(SocketTimeoutException().isTransientNetworkFailure())
        assertTrue(SerializationException("truncated").isTransientNetworkFailure())
    }

    @Test
    fun `permanent failures are not retried`() {
        assertFalse(IllegalStateException("bad credentials").isTransientNetworkFailure())
        assertFalse(IllegalArgumentException().isTransientNetworkFailure())
    }
}

class UserMessageTest {

    @Test
    fun `a truncated response does not leak json internals at the user`() {
        // The raw message is a wall of serializer text; it must never reach a television.
        val raw = SerializationException(
            "Expected end of the array ']', but had 'EOF' instead at path: \$ JSON input: [{\"num\":1",
        )
        val message = raw.toUserMessage()
        assertFalse(message.contains("path:"))
        assertFalse(message.contains("JSON input"))
        assertFalse(message.contains("EOF"))
        assertTrue(message.contains("cut off"))
    }

    @Test
    fun `timeouts and host failures read as advice`() {
        assertEquals(
            "The provider took too long to respond.",
            SocketTimeoutException().toUserMessage(),
        )
        assertEquals(
            "That server address could not be found.",
            java.net.UnknownHostException("nope").toUserMessage(),
        )
        assertTrue(
            java.net.ConnectException().toUserMessage().contains("Check the address and port"),
        )
    }

    @Test
    fun `a truncated response does not blame the wrong kind of list`() {
        // This message is reached from a live category, a film shelf and a series shelf alike, so it
        // must not name one of them. It did say "channel list", which left a viewer whose *films*
        // were cut off being told to look at their channels.
        val message = SerializationException("missing field").toUserMessage()
        assertFalse(
            "message names a content type it cannot know: $message",
            message.contains("channel", ignoreCase = true),
        )
    }

    @Test
    fun `a real serialization failure still maps to something readable`() {
        val message = SerializationException("missing field").toUserMessage()
        assertTrue(message.isNotBlank())
        assertFalse(message.contains("kotlinx.serialization"))
    }
}
