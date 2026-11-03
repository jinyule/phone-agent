package dev.phoneagent.core.model.anthropic

import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Masks credentials in provider text before it reaches a [dev.phoneagent.core.model.ModelError]
 * message or cause, or any diagnostic output (model-layer §2). Two layers: every occurrence of the
 * secrets a request used becomes [MASK], each as sent and in its standard JSON string escaping
 * (`"` → `\"`, `\` → `\\`: [forms]); then text shaped like a credential (`Bearer …`, `sk-…`, JWTs)
 * is masked whether or not this request used it. Error codes and parameter names never match these
 * shapes, so the diagnosis survives.
 *
 * Shapes are matched on decoded text, or on text with nothing to decode: matched on text not
 * decoded yet, a shape stops at the first escape inside it (`\u0064`, `\/`) and leaves the rest of
 * the key readable. Text not decoded yet gets [exact] (whole forms, which no escape splits); [text]
 * goes on decoded text, and [quote] decodes before it decides.
 *
 * Every cut comes after masking: callers redact the full text, then cut it. A reader that must
 * stop early reads [reach] bytes past its limit and drops the [openEnd] of what it read: the start
 * of a form of this request's secrets, or of a credential shape written plainly. A secret written
 * with other escapes and cut inside is not recognized there, and its first part stays
 * (model-layer §2, known gaps).
 *
 * Content the model produced is never masked: a reply that holds a form of a secret is rejected
 * whole ([guard], [EchoGuard]).
 *
 * A copy of the OpenAI adapters' redactor: capabilities may not use each other's code (package
 * rules), so the two are kept in step by hand; [reach] and [openEnd] match WP03's.
 */
internal class Redactor(
    secrets: Iterable<String>,
) {
    private val secrets = secrets.filter { it.isNotEmpty() }.distinct()

    /** Each secret as sent and JSON-escaped; longest first, so a form that contains another is masked whole. */
    private val forms =
        this.secrets
            .flatMap { listOf(it, JsonPrimitive(it).toString().removeSurrounding("\"")) }
            .distinct()
            .sortedByDescending { it.length }

    /** How many UTF-8 bytes past a cut a reader must see so that every secret starting before it is whole. */
    val reach: Int = (forms.maxOfOrNull { it.encodeToByteArray().size } ?: 1) - 1

    /** A redactor that also masks [secret]. */
    fun with(secret: String?): Redactor = if (secret.isNullOrEmpty()) this else Redactor(secrets + secret)

    /** A fresh [EchoGuard] (one per reply) for the same secrets. */
    fun guard(): EchoGuard = EchoGuard(forms)

    /** [text] with every credential masked; on decoded text only (see the class comment). */
    fun text(text: String): String {
        val masked = BEARER.replace(exact(text), "$1$MASK")
        return SHAPES.fold(masked) { acc, shape -> shape.replace(acc, MASK) }
    }

    /**
     * [text] with every form of this request's secrets masked and nothing else: what may be masked
     * before the text is decoded, since an escape never splits a whole form.
     */
    fun exact(text: String): String = forms.fold(text) { acc, form -> acc.replace(form, MASK) }

    /**
     * [raw] provider text that was not read as JSON fields (an error body that is not JSON or not a
     * JSON object, the whole of one without a message, a redirect target) masked as [text] masks
     * it; or not quoted at all ([WITHHELD]) when it has escapes its lenient decoding
     * ([JsonEscapes.lenientDecode]) changes and the decoded text holds a form of this request's
     * secrets or a credential shape (`sk-…`, JWT), since masking it as written would miss what an
     * escape hides and leave the rest readable. Text with nothing to decode is masked in place,
     * which then covers all of it. A `Bearer` word never withholds: [text] masks the token after
     * it. The rule both adapters keep (WP03).
     */
    fun quote(raw: String): String {
        val decoded = JsonEscapes.lenientDecode(raw)
        val hidden = decoded != raw && (exact(decoded) != decoded || SHAPES.any { it.containsMatchIn(decoded) })
        return if (hidden) WITHHELD else text(raw)
    }

    /**
     * A field the provider wrote, read decoded from JSON (an error's message, a refusal's category):
     * this request's secrets in it masked ([exact]), then [quote]d. So a key the field writes plainly
     * is masked and the field still says what went wrong, and so is a credential shape when the
     * field has no escape left to decode; a credential shape or a secret behind escapes the field's
     * own text writes (`\u0073k-…` inside the string, left by the JSON decoding) makes it
     * [WITHHELD]. No shape is masked before [quote] decodes the field.
     */
    fun field(decoded: String): String = quote(exact(decoded))

    /**
     * The length of the end of [masked] (what [text] returned for text a read limit cut off) that
     * may be the start of a credential that went on: the longest end that begins a [forms] entry, or a
     * `sk-` key or JWT the cut left too short for its shape. The reader drops it.
     */
    fun openEnd(masked: String): Int {
        val secret =
            (minOf(reach, masked.length) downTo 1).firstOrNull { n -> forms.any { masked.endsWithStartOf(it, n) } }
        val shape = OPEN_SHAPE.find(masked)?.let { masked.length - it.range.first }
        return maxOf(secret ?: 0, shape ?: 0)
    }

    /** Whether the last [n] characters of this text are the first [n] of [form]. */
    private fun String.endsWithStartOf(
        form: String,
        n: Int,
    ): Boolean = form.length > n && regionMatches(length - n, form, 0, n)

    /**
     * [error] itself when no message it prints (causes and suppressed exceptions included) needs
     * masking; otherwise a stand-in that keeps the class names and stack traces of the first
     * [MAX_DEPTH] causes but carries only redacted messages and no suppressed exceptions.
     */
    fun cause(error: Throwable): Throwable = if (reachable(error).all(::clean)) error else Scrubbed(error, this, 0)

    private fun clean(error: Throwable): Boolean = error.message.let { it == null || text(it) == it }

    /** Every exception [error] prints, each once (chains may loop). */
    private fun reachable(error: Throwable): Set<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque(listOf(error))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next)) {
                next.cause?.let(pending::addLast)
                pending.addAll(next.suppressed)
            }
        }
        return seen
    }

    /** A redacted copy of one exception in a cause chain. */
    private class Scrubbed(
        original: Throwable,
        redactor: Redactor,
        depth: Int,
    ) : Exception(
            "${original::class.java.name}: ${redactor.text(original.message.orEmpty())}",
            original.cause?.takeIf { it !== original && depth + 1 < MAX_DEPTH }?.let {
                Scrubbed(
                    it,
                    redactor,
                    depth + 1,
                )
            },
        ) {
        init {
            stackTrace = original.stackTrace
        }

        override fun toString(): String = message.orEmpty()
    }

    companion object {
        /** What a masked credential reads as. */
        const val MASK: String = "***"

        /** What [quote] gives for provider text that hides a credential behind its escapes. */
        const val WITHHELD: String = "(not shown: it holds a credential)"

        private const val MAX_DEPTH = 8

        /** Masks only credential-shaped text (no request secrets known). */
        val SHAPES_ONLY: Redactor = Redactor(emptyList())

        private val BEARER = Regex("""(?i)(\bbearer\s+)[^\s"',;]+""")
        private val SHAPES =
            listOf(
                // API keys: sk-ant-api03-… (Anthropic), sk-…, sk-proj-… (OpenAI style)
                Regex("""\bsk-[A-Za-z0-9_-]{8,}"""),
                // JWTs (ChatGPT plan access tokens): header.payload.signature
                Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*"""),
            )

        /** A credential shape still open at the end of the text (cut before [SHAPES] could match it). */
        private val OPEN_SHAPE = Regex("""(?:\bsk-[A-Za-z0-9_-]*|\beyJ[A-Za-z0-9_.-]*)\z""")
    }
}
