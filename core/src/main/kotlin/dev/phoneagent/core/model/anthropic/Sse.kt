package dev.phoneagent.core.model.anthropic

/** One server-sent event: its `event:` name (or `null`) and its `data:` lines joined with `\n`. */
internal data class SseEvent(
    val event: String?,
    val data: String,
)

/**
 * Incremental parser for `text/event-stream` (WHATWG HTML, "Server-sent events"). Feed it the
 * stream's lines in order, without line terminators; it returns an event when a blank line ends
 * one. Comment lines (`:` …, used as keep-alives) are skipped; `id`, `retry` and unknown fields are
 * ignored because adapters never reconnect. Not thread-safe; one instance per stream.
 *
 * A copy of the OpenAI adapters' parser (capabilities may not share code); keep them in step.
 */
internal class SseParser {
    private val data = StringBuilder()
    private var hasData = false
    private var event: String? = null
    private var first = true

    /** Consumes one line; returns the event it completes, if any. */
    fun line(raw: String): SseEvent? {
        val line = if (first && raw.startsWith(BOM)) raw.substring(1) else raw
        first = false
        return when {
            line.isEmpty() -> dispatch()
            line.startsWith(':') -> null
            else -> field(line)
        }
    }

    /** Applies a `field: value` line; never completes an event. */
    private fun field(line: String): SseEvent? {
        val colon = line.indexOf(':')
        val name = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        // `id`, `retry` and unknown fields need no handling: adapters never reconnect.
        when (name) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }

            "event" -> {
                event = value
            }
        }
        return null
    }

    /**
     * End of stream. Unlike the spec, a pending event whose closing blank line never came is still
     * returned: several gateways end the body right after the last `data:` line.
     */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val result = if (hasData) SseEvent(event, data.toString()) else null
        data.setLength(0)
        hasData = false
        event = null
        return result
    }

    private companion object {
        const val BOM = "\uFEFF"
    }
}
