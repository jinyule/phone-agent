package dev.phoneagent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A tool as the model sees it: name, static description and JSON Schema of the arguments.
 * It lives in `model` (not `tool`) because requests carry it and adapters must not depend on
 * tool implementations. `ToolRegistry` validates the schema when a tool is registered.
 */
@Serializable
public data class ToolDef(
    /** Unique name matching [NAME_PATTERN]. */
    val name: String,
    /** Static description; part of the cached prompt prefix, so it never changes within a session. */
    val description: String,
    /** JSON Schema of the arguments; an object schema. */
    val parameters: JsonObject,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "Tool name '$name' must match ${NAME_PATTERN.pattern}" }
    }

    /** Constants. */
    public companion object {
        /** Names every protocol accepts (OpenAI is the strictest: 1–64 of `[a-zA-Z0-9_-]`). */
        public val NAME_PATTERN: Regex = Regex("[a-zA-Z0-9_-]{1,64}")
    }
}
