package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Rejects a reply whose model-produced content holds a secret its request sent (model-layer §2).
 * The content is never rewritten: rewriting call arguments would change the action, and the
 * session never puts a secret in the context, so a secret in the output means something is wrong.
 * The reply fails as [CredentialEcho.error] instead.
 *
 * Matching is exact, on the [Redactor]'s forms of each secret (as sent and JSON-escaped; secrets
 * too short to match are placeholders): no credential shapes, which ordinary output may contain.
 * Deltas are checked as they are shown, not held back: each [Window] keeps the end of one delta
 * channel, one character shorter than the longest form, so a secret split over fragments is
 * caught by the fragment that completes it, before that one is shown. [parts] checks what the
 * reply keeps: text, call ids, names and arguments (recovered calls too), reasoning summaries and
 * the opaque reasoning (encrypted content included).
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
            if (forms.isEmpty()) return
            val joined = tail + fragment
            check(joined)
            tail = joined.takeLast(keep)
        }
    }

    /** Throws [CredentialEcho.error] when [text] holds a form of a secret. */
    fun check(text: String) {
        if (forms.any { it in text }) throw CredentialEcho.error()
    }

    /** Checks every value of [parts] that is recorded and sent back with the next request. */
    fun parts(parts: List<Part>) {
        for (part in parts) {
            when (part) {
                is Part.Text -> {
                    check(part.text)
                }

                is Part.ToolCall -> {
                    check(part.id.value)
                    check(part.name)
                    check(part.argsJson)
                    rewritten(part.argsJson)?.let(::check)
                }

                // Written back in the standard escaping, where every string that holds a secret holds a form of it.
                is Part.Reasoning -> {
                    part.summary?.let(::check)
                    check(part.opaque.toString())
                }

                is Part.Image, is Part.ToolResult -> {
                    // Not part of a reply.
                }
            }
        }
    }

    /** [json] written back in the standard escaping (escapes such as `\/` decoded), or `null` when it is not JSON. */
    private fun rewritten(json: String): String? =
        try {
            Json.parseToJsonElement(json).toString()
        } catch (ignoredNotJson: SerializationException) {
            // Arguments that are not JSON have no escapes to decode; the raw check covered them.
            null
        }

    companion object {
        /** A guard that matches nothing (no secret long enough to match). */
        val NONE: EchoGuard = EchoGuard(emptyList())
    }
}

/**
 * The failure of a reply that echoed a request secret ([EchoGuard]): a [ModelError.BadResponse]
 * with fixed text, so no part of the echo reaches the error.
 *
 * The runtime must not keep what the attempt streamed (no interrupted assistant message, only the
 * attempt and this reason), and the UI clears the fragments it showed. WP01 adds a marker for that
 * to [ModelError] (A11); until it lands, [discardsStreamed] stands in for it and recognizes the
 * fixed text.
 */
internal object CredentialEcho {
    const val DETAIL: String = "the reply contained a credential this request sent and was discarded"

    fun error(): ModelError.BadResponse = ModelError.BadResponse(DETAIL)

    /** Placeholder for WP01's marker: whether [error] is this failure, whose streamed output must not be kept. */
    fun discardsStreamed(error: Throwable): Boolean =
        error is ModelError.BadResponse && error.message == "bad response: $DETAIL"
}
