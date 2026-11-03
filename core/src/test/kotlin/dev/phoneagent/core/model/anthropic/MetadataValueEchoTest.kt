package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * A number or boolean the server writes into a metadata field (`context_management`,
 * `stop_details`, `input_transformations`, `usage.iterations`) leaves that field out whole when
 * masking would change it, as a string does: a request secret such as `123456` or `true` reaches
 * neither the reply (its `toString` and its JSON) nor the session event recording it, streamed or
 * in a whole JSON reply.
 */
class MetadataValueEchoTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("cases")
    fun `a metadata number or boolean that is the key leaves its field out whole`(
        secret: String,
        transport: String,
    ) = runTest {
        if (transport == WHOLE) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(whole(secret))
                    .build(),
            )
        } else {
            server.enqueueSse(stream(secret))
        }
        val reply = Kit.model(server, credentials = Keys(secret)).complete(Kit.request())
        val event =
            AssistantMessage(
                attempt = 1,
                provider = Kit.PROVIDER,
                model = Kit.MODEL,
                parts = reply.parts,
                stopReason = reply.stopReason,
                usage = reply.usage,
                providerMetadata = reply.providerMetadata,
            )
        val exits =
            mapOf(
                "toString" to reply.toString(),
                "JSON" to Json.encodeToString(ChatResponse.serializer(), reply),
                "session event" to SessionCodec().encode(SessionRecord(seq = 1, time = 0, event = event)),
            )
        exits.forEach { (exit, text) -> assertFalse(secret in text, "the key is in the reply's $exit: $text") }
        assertEquals(KEPT, reply.providerMetadata?.keys, "every field holding the key is left out")
        assertEquals(listOf<Part>(Part.Text("ok")), reply.parts)
    }

    private companion object {
        const val WHOLE = "whole JSON"

        /** What stays of the metadata: the fields no value of the server's changes under masking. */
        val KEPT = setOf("id", "requested_model", "served_model", "stop_reason")

        /** Placeholder secrets that are JSON literals, written as such into the metadata fields. */
        val SECRETS = listOf("123456", "true")

        @JvmStatic
        fun cases(): List<Arguments> =
            SECRETS.flatMap { secret -> listOf(Arguments.of(secret, "SSE"), Arguments.of(secret, WHOLE)) }

        /** The message-level members holding [value]; `stop_details` comes with the stop reason. */
        fun extras(value: String): String =
            """"usage":{"input_tokens":1,"output_tokens":1,"iterations":[{"type":"message","value":$value}]},""" +
                """"context_management":{"applied_edits":[{"type":"x","cleared":$value}]},""" +
                """"input_transformations":[{"type":"x","value":$value}]"""

        fun stopDetails(value: String): String = """"stop_details":{"type":"x","value":$value}"""

        fun stream(value: String): String =
            Kit.sse(
                "message_start" to
                    """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5",""" +
                    """"content":[],${extras(value)}}}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn",${stopDetails(value)}},""" +
                    """"usage":{"output_tokens":1}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )

        fun whole(value: String): String =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
                """"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",${stopDetails(value)},""" +
                """${extras(value)}}"""
    }
}
