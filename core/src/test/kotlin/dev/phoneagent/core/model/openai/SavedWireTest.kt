package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire options "test and save" negotiated are what later models send (model-layer §3.1/§4.1):
 * `OpenAiAdapters.model` has no default for them, so a caller cannot silently fall back to the
 * defaults, and a field the trial saw refused never comes back once the saved options are used.
 */
class SavedWireTest {
    @StartStop
    private val server = MockWebServer()

    @Test
    fun `model takes the wire options explicitly`() {
        // The JVM name of model(...) is mangled (ModelId is a value class); defaults add a `$default` twin.
        val defaults =
            OpenAiAdapters::class.java.declaredMethods.filter {
                it.name.startsWith("model") && it.name.endsWith("\$default")
            }
        assertEquals(emptyList<String>(), defaults.map { it.name }, "model(...) must not default its wire options")
    }

    /** Probes [profile] against a refusal of [field], then sends two requests from a model rebuilt with the result. */
    private suspend fun savedAndRebuilt(
        profile: ProviderProfile,
        refusal: String,
        trial: String,
        reply: String,
    ): List<JsonObject> {
        server.enqueueError(404, "{}")
        server.enqueueError(400, refusal)
        server.enqueueSse(trial)
        val passed = Kit.adapters().probe().testAndSave(profile, Kit.MODEL, Kit.capabilities(), "pa-1")
        assertTrue(passed is ProbeResult.Passed, "the trial passes without the refused field")
        val saved = (passed as ProbeResult.Passed).wire.copy()
        repeat(3) { server.next() }
        // A fresh adapters instance, as after an app restart, built from the saved options.
        val model = Kit.adapters().model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), saved)
        return List(2) {
            server.enqueueSse(reply)
            model.complete(Kit.request())
            server.next().json()
        }
    }

    @Test
    fun `a Chat field the trial saw refused is not sent by the rebuilt model`() =
        runTest {
            val profile = Kit.profile(server.base())
            val refusal =
                """{"error":{"message":"Unrecognized request argument supplied: parallel_tool_calls",""" +
                    """"param":"parallel_tool_calls"}}"""
            val trial =
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_t","function":""" +
                        """{"name":"confirm_connection","arguments":"{\"token\":\"pa-1\"}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                )
            val reply = Kit.sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
            val sent = savedAndRebuilt(profile, refusal, trial, reply)
            assertTrue(sent.all { "parallel_tool_calls" !in it && "tools" in it }, "the refused field stays out")
            assertTrue(sent.all { "stream_options" in it }, "fields that were not refused stay")
        }

    @Test
    fun `a Responses field the trial saw refused is not sent by the rebuilt model`() =
        runTest {
            val profile = Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES)
            val refusal = """{"error":{"message":"Encrypted content is not supported.","param":"include"}}"""
            val trial =
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
                    "{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"confirm_connection\"," +
                    "\"arguments\":\"{\\\"token\\\":\\\"pa-1\\\"}\"}]}}\n\n"
            val reply =
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
                    "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n"
            val sent = savedAndRebuilt(profile, refusal, trial, reply)
            assertTrue(sent.all { "include" !in it && "input" in it }, "the refused field stays out")
        }
}
