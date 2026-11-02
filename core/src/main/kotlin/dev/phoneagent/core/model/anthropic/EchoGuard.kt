package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Rejects a reply whose model-produced content holds a secret its request sent (model-layer §2).
 * The content is never rewritten: thinking is bound to everything before it, so rewritten text or
 * `tool_use.input` would fail the next request's prefix check, and rewritten arguments change the
 * action; the session never puts a secret in the context, so a secret in the output means
 * something is wrong. The reply fails as [CredentialEcho.error] instead.
 *
 * Matching is exact, on the [Redactor]'s forms of each secret (as sent and JSON-escaped), with no
 * minimum length and no credential shapes, which ordinary output may contain. Content is checked
 * as it streams, not held back: [json] checks a block's start whole (every key and value, numbers
 * too); each string a delta carries goes through a [Window] of its own, one character shorter
 * than the longest form, so a secret split over fragments is caught by the fragment that
 * completes it, before that one is shown; and [arguments] checks the whole argument text of a
 * block once more when it ends, as written and with its escapes decoded ([JsonEscapes]), valid
 * JSON or not, so escapes such as `\/` or `\u0073` hide nothing there.
 *
 * Content of a declined attempt is checked as well, though its unknown blocks reach no exit
 * (deltas, reply, log, diagnostics, next request): stricter than the exit rule of model-layer §2,
 * which only asks for what reaches one. Known gaps (same section): a secret split across two
 * members or two blocks, and one written with escapes, spaces or another encoding in text or
 * thinking (literal text, which nothing downstream decodes), are not detected.
 *
 * The same rule and windows as the OpenAI adapters' guard (WP03), kept in step by hand.
 */
internal class EchoGuard(
    private val forms: List<String>,
) {
    private val keep = (forms.maxOfOrNull { it.length } ?: 1) - 1

    /** The end of one delta channel shown so far. */
    inner class Window {
        private var tail = ""

        /** Checks [fragment], the next piece of this channel, joined to the end of the pieces before it. */
        fun next(fragment: String) {
            val joined = tail + fragment
            check(joined)
            tail = joined.takeLast(keep)
        }
    }

    /** Throws [CredentialEcho.error] when [text] holds a form of a secret. */
    fun check(text: String) {
        if (forms.any { it in text }) throw CredentialEcho.error()
    }

    /** Checks every key and every non-null value in [value], as parsed. */
    fun json(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                value.forEach { (key, member) ->
                    check(key)
                    json(member)
                }
            }

            is JsonArray -> {
                value.forEach(::json)
            }

            is JsonPrimitive -> {
                if (value !is JsonNull) check(value.content)
            }
        }
    }

    /**
     * Checks the whole argument text [raw] of one block as written and leniently decoded: cut-off
     * or invalid JSON is still decoded downstream, and the decoded text contains every string a
     * parse would give.
     */
    fun arguments(raw: String) {
        check(raw)
        check(JsonEscapes.lenientDecode(raw))
    }
}

/**
 * The failure of a reply that echoed a request secret ([EchoGuard]): a [ModelError.BadResponse]
 * with fixed text, so no part of the echo reaches the error.
 *
 * The runtime must not keep what the attempt streamed (no interrupted assistant message, only the
 * attempt and this reason), and the UI clears the fragments it showed. WP01 adds a marker for that
 * to [ModelError] (A11, `discardsStreamed`); until this branch has it, [discardsStreamed] stands in
 * for it and recognizes the fixed text. The text is the OpenAI adapters' (WP03).
 */
internal object CredentialEcho {
    const val DETAIL: String = "the reply contained a credential this request sent and was discarded"

    fun error(): ModelError.BadResponse = ModelError.BadResponse(DETAIL)

    /** Placeholder for WP01's marker: whether [error] is this failure, whose streamed output must not be kept. */
    fun discardsStreamed(error: Throwable): Boolean =
        error is ModelError.BadResponse && error.message == "bad response: $DETAIL"
}
