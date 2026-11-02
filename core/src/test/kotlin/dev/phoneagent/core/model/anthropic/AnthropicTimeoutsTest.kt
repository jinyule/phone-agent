package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.anthropic.Kit.base
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** [AnthropicTimeouts]: validated on construction; idle connections are kept only when asked for. */
class AnthropicTimeoutsTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    /** Idle connections the client of [timeouts] keeps after one exchange. */
    private suspend fun idleAfterOneCall(timeouts: AnthropicTimeouts): Int {
        val client = timeouts.client()
        server.enqueueSse(Kit.TEXT_REPLY)
        Kit
            .adapters(client = client)
            .model(Kit.profile(server.base()), Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
            .complete(Kit.request())
        return client.connectionPool.idleConnectionCount()
    }

    @Test
    fun `by default no idle connection is kept`() =
        runTest {
            assertEquals(Duration.ZERO, AnthropicTimeouts().idleConnectionKeepAlive)
            assertEquals(0, idleAfterOneCall(AnthropicTimeouts()))
        }

    @Test
    fun `a keep-alive keeps the connection for the next request`() =
        runTest {
            assertEquals(1, idleAfterOneCall(AnthropicTimeouts(idleConnectionKeepAlive = 30.seconds)))
        }

    @Test
    fun `a negative or too long keep-alive is refused when configured`() {
        for (wrong in listOf((-1).nanoseconds, 5.minutes + 1.nanoseconds, Duration.INFINITE)) {
            val error = assertThrows<IllegalArgumentException> { AnthropicTimeouts(idleConnectionKeepAlive = wrong) }
            assertTrue(error.message!!.contains("idleConnectionKeepAlive"), error.message)
        }
        assertEquals(5.minutes, AnthropicTimeouts(idleConnectionKeepAlive = 5.minutes).idleConnectionKeepAlive)
    }
}
