package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.WireSupport.TEXT_SEPARATOR

/**
 * What one reply has shown through `onDelta`, so the end of the stream can show what the deltas
 * missed: servers that send only a final object, text the markup preview held back, calls
 * recovered from leaked markup. The adapters only show what the reply will keep, so after
 * [catchUp] the text, reasoning and argument deltas add up to the reply's text, reasoning
 * summaries (blank-line separated) and call arguments, and every call has been announced once.
 * Only a provider whose final output contradicts its own deltas breaks that; deltas cannot be
 * taken back, so [catchUp] reports it and the reply records it under [PREVIEW_DIVERGED].
 *
 * Everything is checked with [guard] before it is shown: a delta that completes a request secret
 * fails the reply ([CredentialEcho]) and is not shown.
 */
internal class DeltaLedger(
    private val onDelta: (Delta) -> Unit,
    private val guard: EchoGuard,
) {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val args = LinkedHashMap<ToolCallId, StringBuilder>()
    private val names = HashMap<ToolCallId, String>()
    private val textWindow = guard.Window()
    private val reasoningWindow = guard.Window()
    private val argsWindows = HashMap<ToolCallId, EchoGuard.Window>()

    /** Whether any reasoning was shown (a new reasoning section then needs a separator). */
    val hasReasoning: Boolean get() = reasoning.isNotEmpty()

    fun text(fragment: String) {
        if (fragment.isEmpty()) return
        textWindow.next(fragment)
        text.append(fragment)
        onDelta(Delta.Text(fragment))
    }

    fun reasoning(fragment: String) {
        if (fragment.isEmpty()) return
        reasoningWindow.next(fragment)
        reasoning.append(fragment)
        onDelta(Delta.Reasoning(fragment))
    }

    /** Announces call [id] once. */
    fun started(
        id: ToolCallId,
        name: String,
    ) {
        if (id in args) return
        guard.check(id.value)
        guard.check(name)
        args[id] = StringBuilder()
        names[id] = name
        argsWindows[id] = guard.Window()
        onDelta(Delta.ToolCallStarted(id, name))
    }

    /** Shows argument text of an announced call; fragments of unknown calls are dropped. */
    fun args(
        id: ToolCallId,
        fragment: String,
    ) {
        val shown = args[id]
        if (shown == null || fragment.isEmpty()) return
        argsWindows.getValue(id).next(fragment)
        shown.append(fragment)
        onDelta(Delta.ToolCallArgs(id, fragment))
    }

    /**
     * Shows whatever of [parts] the deltas have not shown yet (see the class contract). Returns the
     * channels where [parts] do not extend what was shown, in this order: `text`, `reasoning`,
     * `calls` (an announced call is missing from [parts] or has another name there), `args`;
     * empty when the deltas add up.
     */
    fun catchUp(parts: List<Part>): List<String> {
        val diverged = mutableListOf<String>()
        val calls = parts.filterIsInstance<Part.ToolCall>().associate { it.id to it.name }
        if (!extend(text, parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, ::text)) diverged += "text"
        val summaries = parts.filterIsInstance<Part.Reasoning>().mapNotNull { it.summary }
        if (!extend(reasoning, summaries.joinToString(TEXT_SEPARATOR), ::reasoning)) diverged += "reasoning"
        if (names.any { (id, name) -> calls[id] != name }) diverged += "calls"
        for (call in parts.filterIsInstance<Part.ToolCall>()) {
            started(call.id, call.name)
            if (!extend(checkNotNull(args[call.id]), call.argsJson) { args(call.id, it) }) diverged += "args"
        }
        return diverged.distinct()
    }

    /** Shows the rest of [full] when it extends [shown]; `false` when it does not. */
    private fun extend(
        shown: CharSequence,
        full: String,
        show: (String) -> Unit,
    ): Boolean {
        val extends = full.startsWith(shown)
        if (extends && full.length > shown.length) show(full.substring(shown.length))
        return extends
    }
}

/** `providerMetadata` key listing the delta channels the final reply contradicts ([DeltaLedger.catchUp]). */
internal const val PREVIEW_DIVERGED = "preview_diverged"
