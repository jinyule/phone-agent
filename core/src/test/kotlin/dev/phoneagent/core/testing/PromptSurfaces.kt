package dev.phoneagent.core.testing

import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ToolDef
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * How the system prompt and the tool schemas are written out for review: the prompt-surface
 * goldens and the sidecars of session fixtures (engineering-baseline §3.2) use the same text, so
 * one change of the prompt shows the same way in both.
 */
object PromptSurfaces {
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** [blocks] in order, each under a `<!-- block: id -->` line, separated by a blank line. */
    fun systemPrompt(blocks: List<PromptBlock>): String =
        blocks.joinToString("\n\n") { "<!-- block: ${it.id} -->\n${it.text}" }

    /** [defs] as pretty-printed JSON, two-space indented. */
    fun toolSchemas(defs: List<ToolDef>): String = json.encodeToString(serializer<List<ToolDef>>(), defs)
}
