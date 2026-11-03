package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Rejects a reply whose model-produced content holds a secret its request sent (model-layer §2).
 * The content is never rewritten: rewriting call arguments would change the action, and the
 * session never puts a secret in the context, so a secret in the output means something is wrong.
 * The reply fails as [CredentialEcho.error] instead.
 *
 * Matching is exact, on the [Redactor]'s forms of each secret (as sent and JSON-escaped, however
 * short): no credential shapes, which ordinary output may contain.
 * Deltas are checked as they are shown, not held back: each [Window] keeps the end of one delta
 * channel, one character shorter than the longest form, so a secret split over fragments is
 * caught by the fragment that completes it, before that one is shown. [parts] checks what the
 * reply keeps: text, call ids, names and arguments (recovered calls too), reasoning summaries and
 * the opaque reasoning (encrypted content included), every member name and value of it, as read
 * and as the session log writes it back ([writtenBack]).
 *
 * Call arguments are checked whole ([arguments]) in three forms: as sent, written back in the
 * standard escaping when they parse, and leniently decoded ([JsonEscapes.lenientDecode]) whether
 * or not they parse, since the tool layer decodes the escapes of cut or broken arguments too.
 * Other text (answer, reasoning, markup left in the answer) is literal and is matched as sent only.
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
        if (holds(text)) throw CredentialEcho.error()
    }

    /**
     * Checks every value of [parts] that is recorded and sent back with the next request; of the
     * opaque reasoning, every member name and value, as read and as written back
     * ([writtenBack]: the form the log holds and a request rebuilt from it sends).
     */
    fun parts(parts: List<Part>) {
        for (part in parts) {
            when (part) {
                is Part.Text -> {
                    check(part.text)
                }

                is Part.ToolCall -> {
                    check(part.id.value)
                    check(part.name)
                    arguments(part.argsJson)
                }

                // Member names and values alike, written back in the standard escaping, where every string
                // that holds a secret holds a form of it; numbers as read and as the log writes them.
                is Part.Reasoning -> {
                    part.summary?.let(::check)
                    check(part.opaque.toString())
                    check(writtenBack(part.opaque))
                }

                is Part.Image, is Part.ToolResult -> {
                    // Not part of a reply.
                }
            }
        }
    }

    /** Throws [CredentialEcho.error] when the whole call arguments [raw] hold a secret ([holdsSecret]). */
    fun arguments(raw: String) {
        if (holdsSecret(raw)) throw CredentialEcho.error()
    }

    /**
     * Whether the whole call arguments [raw] hold a form of a secret: as sent, written back in the
     * standard escaping when they parse, or leniently decoded (escapes such as `\u0070` and `\/`
     * decoded even in arguments that were cut or are broken).
     */
    fun holdsSecret(raw: String): Boolean =
        holds(raw) || rewritten(raw)?.let(::holds) == true || holds(JsonEscapes.lenientDecode(raw))

    private fun holds(text: String): Boolean = forms.any { it in text }

    /** [json] written back in the standard escaping (escapes such as `\/` decoded), or `null` when it is not JSON. */
    private fun rewritten(json: String): String? =
        try {
            Json.parseToJsonElement(json).toString()
        } catch (ignoredNotJson: SerializationException) {
            // Not JSON (cut or broken): the lenient decoding in holdsSecret reads its escapes instead.
            null
        }

    companion object {
        /** A guard that matches nothing (the request sent no secret). */
        val NONE: EchoGuard = EchoGuard(emptyList())
    }
}

/**
 * [element] as kotlinx.serialization writes it back, as the session log does: numbers in the
 * encoder's own form (`1.23456e5` becomes `123456.0`), strings in the standard escaping. A number
 * it cannot write back (`1e400`) fails the reply as [ModelError.BadResponse] with fixed text
 * ([UNWRITABLE_NUMBER]): the log could not record the reply.
 */
internal fun writtenBack(element: JsonElement): String =
    try {
        Json.encodeToString(JsonElement.serializer(), element)
    } catch (unwritable: SerializationException) {
        // Its message quotes the number; the class name and stack trace are enough.
        throw ModelError.BadResponse(UNWRITABLE_NUMBER, Unquoted.bare(unwritable))
    }

/** What a reply fails with when it keeps a number no JSON encoder can write back. */
internal const val UNWRITABLE_NUMBER: String = "the reply holds a number no JSON encoder can write back"

/**
 * The failure of a reply that echoed a request secret ([EchoGuard]): a [ModelError.BadResponse]
 * with fixed text, so no part of the echo reaches the error, and with
 * [ModelError.discardsStreamed] set: the runtime does not keep what the attempt streamed (no
 * interrupted assistant message, only the attempt and this reason), and the UI takes back the
 * fragments it showed.
 */
internal object CredentialEcho {
    const val DETAIL: String = "the reply contained a credential this request sent and was discarded"

    fun error(): ModelError.BadResponse = ModelError.BadResponse(DETAIL, discardsStreamed = true)
}
