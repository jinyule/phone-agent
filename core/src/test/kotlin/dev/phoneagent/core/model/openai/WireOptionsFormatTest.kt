package dev.phoneagent.core.model.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [WireOptions] as the app stores it with a profile (`model-settings.json`). */
class WireOptionsFormatTest {
    private val json = Json { encodeDefaults = true }

    /** Every field of the options set to something other than its default. */
    private val everyFieldChanged =
        WireOptions(
            chat =
                ChatQuirks(
                    parallelToolCalls = false,
                    streamUsage = false,
                    maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
                    reasoningEffort = ReasoningEffortStyle.OPENROUTER,
                    leakedCallFormats = setOf(LeakedCallFormat.DSML, LeakedCallFormat.TOOL_CALL_TAG),
                    recoverLeakedCalls = setOf(LeakedCallFormat.TOOL_CALL_TAG),
                ),
            responses = ResponsesQuirks(includeEncryptedReasoning = false),
        )

    @Test
    fun `options with every field changed come back equal`() {
        val stored = json.encodeToString(WireOptions.serializer(), everyFieldChanged)
        assertEquals(everyFieldChanged, json.decodeFromString(WireOptions.serializer(), stored))
    }

    @Test
    fun `the fixture changes every field the format has`() {
        // A field added later and left at its default above would pass the round trip unstored.
        val changed = leaves(json.encodeToJsonElement(everyFieldChanged))
        val defaults = leaves(json.encodeToJsonElement(WireOptions()))
        assertEquals(defaults.keys, changed.keys)
        assertEquals(emptyList<String>(), defaults.keys.filter { changed[it] == defaults[it] })
    }

    @Test
    fun `the stored field names are fixed`() {
        assertEquals(
            """{"chat":{"parallelToolCalls":false,"streamUsage":false,"maxTokensField":"MAX_COMPLETION_TOKENS",""" +
                """"reasoningEffort":"OPENROUTER","leakedCallFormats":["DSML","TOOL_CALL_TAG"],""" +
                """"recoverLeakedCalls":["TOOL_CALL_TAG"]},"responses":{"includeEncryptedReasoning":false}}""",
            json.encodeToString(WireOptions.serializer(), everyFieldChanged),
        )
    }

    /** The non-object values of [element] by their dotted path. */
    private fun leaves(
        element: JsonElement,
        path: String = "",
    ): Map<String, JsonElement> =
        if (element is JsonObject) {
            element.entries.fold(emptyMap()) { acc, (key, value) -> acc + leaves(value, "$path.$key") }
        } else {
            mapOf(path to element)
        }
}
