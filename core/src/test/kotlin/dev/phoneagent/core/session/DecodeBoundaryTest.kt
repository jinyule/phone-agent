package dev.phoneagent.core.session

import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.ports.LaunchTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Ranges and invariants that the KDoc promises are enforced where a log is read: every bad line is
 * a [SessionCorruptedException] with its line number, never a later failure inside the agent.
 */
class DecodeBoundaryTest {
    private val codec = SessionCodec()
    private val header = codec.encodeHeader(SessionSamples.header)
    private val prefix: List<String> = listOf(codec.encode(SessionRecord(1, 0, SessionSamples.start)))

    private fun envelope(
        type: String,
        data: String,
        seq: Int = prefix.size + 1,
    ) = """{"seq":$seq,"time":0,"type":"$type","data":$data}"""

    private fun read(vararg lines: String) =
        codec.read(
            (listOf(header) + prefix + lines)
                .joinToString("") {
                    "$it\n"
                }.encodeToByteArray(),
        )

    private fun encodedPayload(event: SessionEvent): String =
        codec.encode(SessionRecord(prefix.size + 1L, 0, event)).substringAfter("\"data\":").removeSuffix("}")

    private val sampleHeader = SessionSamples.events.filterIsInstance<RequestHeader>().first()

    @Test
    fun `out-of-range numbers and broken invariants are corruption of their line`() {
        val headerPayload = encodedPayload(sampleHeader)
        val bad =
            mapOf(
                "retry attempt 1" to envelope("request/retry", """{"attempt":1,"delayMs":0,"error":"NETWORK"}"""),
                "negative retry delay" to envelope("request/retry", """{"attempt":2,"delayMs":-1,"error":"NETWORK"}"""),
                "turn 0 started" to envelope("turn/start", """{"turn":0}"""),
                "turn 0 ended" to envelope("turn/end", """{"turn":0,"reason":"COMPLETED","detail":null}"""),
                "step 0 started" to envelope("step/start", """{"turn":1,"step":0}"""),
                "step 0 ended" to envelope("step/end", """{"turn":1,"step":0,"outcome":"COMPLETED"}"""),
                "attempt 0 failed" to
                    envelope(
                        "assistant/attempt",
                        """{"attempt":0,"error":"NETWORK","message":"x","httpStatus":null,"retryAfterMs":null}""",
                    ),
                "attempt 0 replied" to
                    envelope(
                        "assistant/message",
                        encodedPayload(
                            AssistantMessage(
                                1,
                                SessionSamples.provider,
                                SessionSamples.model,
                                emptyList(),
                                StopReason.END_TURN,
                            ),
                        ).replace("\"attempt\":1", "\"attempt\":0"),
                    ),
                "interrupted without parts" to
                    envelope(
                        "assistant/message",
                        encodedPayload(
                            AssistantMessage(
                                1,
                                SessionSamples.provider,
                                SessionSamples.model,
                                emptyList(),
                                StopReason.END_TURN,
                            ),
                        ).replace("\"stopReason\":\"END_TURN\"", "\"stopReason\":null")
                            .replace("\"interrupted\":false", "\"interrupted\":true"),
                    ),
                "header with a zero output cap" to
                    envelope(
                        "request/header",
                        headerPayload.replace("\"maxOutputTokens\":64000", "\"maxOutputTokens\":0"),
                    ),
                "header with duplicate tools" to
                    envelope("request/header", headerPayload.replace("\"tools\":[", "\"tools\":[$tapDef,")),
            )
        val notRejected =
            bad.mapNotNull { (name, line) ->
                val error = runCatching { read(line) }.exceptionOrNull()
                if (error is SessionCorruptedException && error.line == prefix.size + 2) null else "$name: $error"
            }
        assertEquals(emptyList<String>(), notRejected, "each bad line is corruption of line ${prefix.size + 2}")
    }

    @Test
    fun `values at the edge of their range are accepted`() {
        val turn = envelope("turn/start", """{"turn":1}""")
        val fine =
            listOf(
                listOf(envelope("request/retry", """{"attempt":2,"delayMs":0,"error":"NETWORK"}""")),
                listOf(turn),
                listOf(turn, envelope("step/start", """{"turn":1,"step":1}""", seq = prefix.size + 2)),
            )
        fine.forEach { lines ->
            assertEquals(prefix.size + lines.size, read(*lines.toTypedArray()).records.size, "$lines")
        }
        assertTrue(ModelErrorKind.NETWORK.retryable)
    }

    @Test
    fun `a package name of any length in a launch is read, or is corruption of its line`() {
        // FWD-B1 sweep: package names were checked by a regex repeating a group, which overflowed the stack.
        val name = "a" + ".a".repeat(REPEATS)
        val launch =
            encodedPayload(
                SessionSamples.events.filterIsInstance<ScreenLaunched>().first { it.target is LaunchTarget.App },
            )
        val cells =
            listOf(
                Executable {
                    val read = read(envelope("screen/launch", launch.replace("com.example.chat", name)))
                    val target = (read.records.last().event as ScreenLaunched).target
                    assertEquals(name, (target as LaunchTarget.App).packageName)
                },
                Executable {
                    val bad = envelope("screen/launch", launch.replace("com.example.chat", "$name."))
                    val error = runCatching { read(bad) }.exceptionOrNull()
                    assertTrue(error is SessionCorruptedException && error.line == prefix.size + 2, "$error")
                },
            )
        assertAll(cells)
    }

    private companion object {
        /** Segments in the long package name: far more than the stack holds repetitions of a regex group. */
        const val REPEATS = 32_000

        val tapDef: String =
            Json.encodeToString(
                serializer<ToolDef>(),
                SessionSamples.events
                    .filterIsInstance<RequestHeader>()
                    .first()
                    .tools
                    .first(),
            )
    }
}
