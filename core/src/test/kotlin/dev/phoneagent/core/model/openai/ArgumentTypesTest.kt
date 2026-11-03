package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/**
 * Call `arguments` that are not a string are never silently replaced: absent or `null` is `{}`;
 * an object is kept serialized (some gateways send one); any other JSON value (number, boolean,
 * array) is kept as its JSON text for the tool layer's own check to refuse. Each goes through the
 * echo check like string arguments.
 */
class ArgumentTypesTest {
    @StartStop
    private val server = Kit.server()

    private fun model(
        protocol: ModelProtocol,
        secret: String = Kit.FAKE_KEY,
    ): ChatModel {
        val profile = Kit.profile(server.base(), protocol = protocol)
        return Kit
            .adapters(
                Keys(secret),
            ).model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), WireOptions.defaultsFor(profile))
    }

    /** A reply with one `get_weather` call whose `arguments` member is [arguments] (`null`: no member). */
    private fun enqueueCall(
        protocol: ModelProtocol,
        arguments: String?,
    ) {
        val member = arguments?.let { ""","arguments":$it""" }.orEmpty()
        if (protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS) {
            val call = """{"index":0,"id":"call_1","function":{"name":"get_weather"$member}}"""
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":[$call]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            )
        } else {
            val item = """{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_weather"$member}"""
            val completed = """{"type":"response.completed","response":{"status":"completed","output":[$item]}}"""
            server.enqueueSse("data: $completed\n\n")
        }
    }

    @ParameterizedTest(name = "{0}: arguments {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "OPENAI_CHAT_COMPLETIONS | absent | {}",
            "OPENAI_CHAT_COMPLETIONS | null | {}",
            "OPENAI_CHAT_COMPLETIONS | {\"city\":\"Paris\"} | {\"city\":\"Paris\"}",
            "OPENAI_CHAT_COMPLETIONS | 42 | 42",
            "OPENAI_CHAT_COMPLETIONS | true | true",
            "OPENAI_CHAT_COMPLETIONS | [\"Paris\"] | [\"Paris\"]",
            "OPENAI_RESPONSES | absent | {}",
            "OPENAI_RESPONSES | null | {}",
            "OPENAI_RESPONSES | {\"city\":\"Paris\"} | {\"city\":\"Paris\"}",
            "OPENAI_RESPONSES | 42 | 42",
            "OPENAI_RESPONSES | false | false",
            "OPENAI_RESPONSES | [\"Paris\"] | [\"Paris\"]",
        ],
    )
    fun `arguments that are not a string are kept as their JSON`(
        protocol: ModelProtocol,
        arguments: String,
        expected: String,
    ) = runTest {
        enqueueCall(protocol, arguments.takeIf { it != "absent" })
        val reply = model(protocol).send(Kit.request())
        assertEquals(listOf(expected), reply.toolCalls.map { it.argsJson })
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ModelProtocol::class, names = ["OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES"])
    fun `a number that is the key is an echo`(protocol: ModelProtocol) =
        runTest {
            enqueueCall(protocol, NUMBER)
            // Any type: a failure message must not print an error before it is checked.
            val error = assertThrows<Throwable> { model(protocol, NUMBER).send(Kit.request()) }
            assertFalse(error.stackTraceToString().contains(NUMBER), "the error holds the key (value not shown)")
            assertTrue(
                error is ModelError.BadResponse && CredentialEcho.discardsStreamed(error),
                "the marked echo rejection",
            )
        }

    private companion object {
        /** A placeholder secret made of digits, as a PIN-like key would be. */
        const val NUMBER = "7359018264"
    }
}
