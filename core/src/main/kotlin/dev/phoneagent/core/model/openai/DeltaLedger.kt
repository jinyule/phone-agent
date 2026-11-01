package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.WireSupport.TEXT_SEPARATOR

/**
 * What one reply has shown through `onDelta`, so the end of the stream can show what the deltas
 * missed: servers that send only a final object, text the markup preview held back, calls
 * recovered from leaked markup. After [catchUp] the text, reasoning and argument deltas add up to
 * the reply's text, reasoning summaries (blank-line separated) and call arguments, wherever the
 * reply extends what was shown; every call has been announced once.
 */
internal class DeltaLedger(
    private val onDelta: (Delta) -> Unit,
) {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val args = LinkedHashMap<ToolCallId, StringBuilder>()

    /** Whether any reasoning was shown (a new reasoning section then needs a separator). */
    val hasReasoning: Boolean get() = reasoning.isNotEmpty()

    fun text(fragment: String) {
        if (fragment.isEmpty()) return
        text.append(fragment)
        onDelta(Delta.Text(fragment))
    }

    fun reasoning(fragment: String) {
        if (fragment.isEmpty()) return
        reasoning.append(fragment)
        onDelta(Delta.Reasoning(fragment))
    }

    /** Announces call [id] once. */
    fun started(
        id: ToolCallId,
        name: String,
    ) {
        if (id in args) return
        args[id] = StringBuilder()
        onDelta(Delta.ToolCallStarted(id, name))
    }

    /** Shows argument text of an announced call; fragments of unknown calls are dropped. */
    fun args(
        id: ToolCallId,
        fragment: String,
    ) {
        val shown = args[id]
        if (shown == null || fragment.isEmpty()) return
        shown.append(fragment)
        onDelta(Delta.ToolCallArgs(id, fragment))
    }

    /** Shows whatever of [parts] the deltas have not shown yet (see the class contract). */
    fun catchUp(parts: List<Part>) {
        remainder(text, parts.filterIsInstance<Part.Text>().joinToString("") { it.text })?.let(::text)
        val summaries = parts.filterIsInstance<Part.Reasoning>().mapNotNull { it.summary }
        remainder(reasoning, summaries.joinToString(TEXT_SEPARATOR))?.let(::reasoning)
        for (call in parts.filterIsInstance<Part.ToolCall>()) {
            started(call.id, call.name)
            remainder(checkNotNull(args[call.id]), call.argsJson)?.let { args(call.id, it) }
        }
    }

    private fun remainder(
        shown: CharSequence,
        full: String,
    ): String? = if (full.length > shown.length && full.startsWith(shown)) full.substring(shown.length) else null
}
