package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A custom Chat Completions profile on OpenAI's own host gets the OpenAI preset's request shape
 * (`max_completion_tokens`, model-layer §3.1). The client routes the official host to the mock
 * server, so the request is the one the adapter really builds for that address.
 */
class OfficialEndpointTest {
    @StartStop
    private val server = Kit.server()

    private fun routedToServer(): OkHttpClient =
        HttpTimeouts()
            .client()
            .newBuilder()
            .addInterceptor { chain ->
                val url =
                    chain
                        .request()
                        .url
                        .newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .url(url)
                        .build(),
                )
            }.build()

    @Test
    fun `official OpenAI chat requests cap output with max_completion_tokens`() =
        runTest {
            server.enqueueSse(Kit.sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
            val profile = Kit.profile("https://api.openai.com/v1")
            Kit
                .adapters(Keys(), routedToServer())
                .unprobed(profile, ModelId("gpt-test"), Kit.capabilities(), Kit.blobs())
                .complete(Kit.request(maxOutputTokens = 256))
            val body = server.next().json()
            assertEquals(JsonPrimitive(256), body["max_completion_tokens"])
            assertFalse("max_tokens" in body, "the deprecated field is not sent")
            Kit.assertGolden("golden/chat-openai-official.json", body)
        }
}
