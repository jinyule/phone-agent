package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAiAdaptersTest {
    @StartStop
    private val server = Kit.server()

    @Test
    fun `the public entry point serves both protocols`() =
        runTest {
            val adapters =
                OpenAiAdapters(Keys(), HttpTimeouts(connectMs = 1_000, readMs = 5_000, writeMs = 1_000), Kit.clock())
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":""" +
                        """[{"index":0,"function":{"name":"get_weather","arguments":"{}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            )
            val chat = adapters.unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())
            assertTrue(chat is ChatCompletionsModel)
            assertEquals(ModelProtocol.OPENAI_CHAT_COMPLETIONS, chat.info.protocol)
            val id =
                chat
                    .complete(Kit.request())
                    .toolCalls
                    .single()
                    .id.value
            assertTrue(Regex("call_[0-9a-f]{32}").matches(id), id)
            val responses =
                adapters.unprobed(
                    Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES),
                    Kit.MODEL,
                    Kit.capabilities(),
                    Kit.blobs(),
                )
            assertTrue(responses is ResponsesModel)
        }

    @Test
    fun `anthropic profiles and bad headers are refused`() =
        runTest {
            val adapters = Kit.adapters()
            assertThrows<IllegalArgumentException> {
                adapters.unprobed(
                    Kit.profile("https://api.anthropic.com/v1", protocol = ModelProtocol.ANTHROPIC_MESSAGES),
                    Kit.MODEL,
                    Kit.capabilities(),
                    Kit.blobs(),
                )
            }
            val badHeader = Kit.profile(server.base(), headers = mapOf("X-Note" to "line\nbreak"))
            for (protocol in listOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES)) {
                val error =
                    assertThrows<IllegalArgumentException> {
                        adapters.unprobed(
                            badHeader.copy(protocol = protocol),
                            Kit.MODEL,
                            Kit.capabilities(),
                            Kit.blobs(),
                        )
                    }
                assertTrue(error.message!!.contains("header value"))
            }
            assertThrows<IllegalArgumentException> { adapters.probe().listModels(badHeader) }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `defaults need only a credential source`() {
        val model =
            OpenAiAdapters(
                Keys(),
            ).unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())
        assertEquals(Kit.MODEL, model.info.model)
        assertEquals(HttpTimeouts(15_000, 300_000, 60_000), HttpTimeouts())
    }

    @Test
    fun `timeouts must be positive`() {
        assertThrows<IllegalArgumentException> { HttpTimeouts(connectMs = 0) }
        assertThrows<IllegalArgumentException> { HttpTimeouts(readMs = -1) }
        assertThrows<IllegalArgumentException> { HttpTimeouts(writeMs = 0) }
        val client = HttpTimeouts(connectMs = 1, readMs = 2, writeMs = 3).client()
        assertEquals(
            listOf(1, 2, 3),
            listOf(client.connectTimeoutMillis, client.readTimeoutMillis, client.writeTimeoutMillis),
        )
        val resends = listOf(client.retryOnConnectionFailure, client.followRedirects, client.followSslRedirects)
        assertEquals(listOf(false, false, false), resends, "OkHttp never resends on its own")
        assertTrue(RetryAfterStash in client.networkInterceptors)
    }

    @Test
    fun `retry-after is stashed from OkHttp and restored for the mapper`() {
        val sent = headersOf("Retry-After", "0", "Retry-After", "1", "X-Other", "y")
        val stashed =
            sent
                .newBuilder()
                .removeAll(
                    "Retry-After",
                ).add(RetryAfterStash.NAME, "0")
                .add(RetryAfterStash.NAME, "1")
                .build()
        assertEquals(listOf("0", "1"), RetryAfterStash.restore(stashed).values("Retry-After"))
        assertEquals(null, RetryAfterStash.restore(stashed)[RetryAfterStash.NAME])
        val plain = headersOf("X-Other", "y")
        assertTrue(RetryAfterStash.restore(plain) === plain, "headers without a stash are returned as they are")
    }
}
