package dev.phoneagent.core.policy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Persisted shape of the policy types (they are recorded in `policy/verdict` and `approval/asked`):
 * optional fields may be absent and take their defaults, a missing required field is rejected, and
 * decoding runs the same invariants as the constructor.
 */
class PolicyFormatTest {
    private val element = """{"ref":"e1","role":"BUTTON","bounds":{"left":0,"top":0,"right":1,"bottom":1}}"""
    private val money = """{"minor":100,"currency":"CNY"}"""
    private val card =
        """{"callId":"c","reason":"SPEND","action":"pay","target":"Shop","content":"x",""" +
            """"amount":$money,"pkg":"a.b"}"""

    private class Shape<T>(
        val serializer: KSerializer<T>,
        val minimal: String,
        val full: String,
        val missingRequired: String,
    )

    private val shapes =
        listOf(
            Shape(
                serializer<ActionIntent>(),
                """{"kind":"READ"}""",
                """{"kind":"INPUT","pkg":"a.b","target":$element,"amount":$money,"fieldClass":"PASSWORD"}""",
                """{"pkg":"a.b"}""",
            ),
            Shape(
                serializer<Verdict>(),
                """{"type":"confirm","card":$card}""",
                """{"type":"confirm","card":$card}""",
                """{"type":"confirm"}""",
            ),
            Shape(
                serializer<Verdict>(),
                """{"type":"hand_off","kind":"LOGIN","hint":"h"}""",
                """{"type":"hand_off","kind":"LOGIN","hint":"h"}""",
                """{"type":"hand_off","kind":"LOGIN"}""",
            ),
            Shape(
                serializer<Verdict>(),
                """{"type":"refuse","reason":"r","hintForModel":"h"}""",
                """{"type":"refuse","reason":"r","hintForModel":"h"}""",
                """{"type":"refuse","reason":"r"}""",
            ),
        )

    @Test
    fun `optional fields default, full objects round-trip, missing required fields are rejected`() {
        shapes.forEach { check(it) }
        assertEquals(ActionIntent(IntentKind.READ), Json.decodeFromString<ActionIntent>("""{"kind":"READ"}"""))
    }

    private fun <T> check(shape: Shape<T>) {
        for (json in listOf(shape.minimal, shape.full)) {
            val value = Json.decodeFromString(shape.serializer, json)
            assertEquals(value, Json.decodeFromString(shape.serializer, Json.encodeToString(shape.serializer, value)))
        }
        assertThrows<SerializationException>(shape.missingRequired) {
            Json.decodeFromString(shape.serializer, shape.missingRequired)
        }
    }
}
