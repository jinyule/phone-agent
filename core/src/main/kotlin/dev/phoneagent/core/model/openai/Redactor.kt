package dev.phoneagent.core.model.openai

import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Masks credentials in provider text before it reaches a [dev.phoneagent.core.model.ModelError]
 * message or cause, a [ProbeResult] or any diagnostic output (model-layer §2). Two layers: every
 * occurrence of the secrets a request used becomes [MASK], each as sent and in its standard JSON
 * string escaping (`"` → `\"`, `\` → `\\`: [forms]); then text shaped like a credential
 * (`Bearer …`, `sk-…`, JWTs) is masked whether or not this request used it. Error codes and
 * parameter names never match these shapes, so the diagnosis survives. A reader of raw provider
 * text masks only the exact forms ([exact]) and leaves the shapes until the text is parsed and
 * decoded: a shape matched on raw text stops at the first escape and leaves the rest of a key to
 * read once decoded. Text the provider wrote is shown as [quote] shows it: masked in place, or not
 * at all when an escape in it hides a credential. Reply content is never quoted ([Unquoted]).
 *
 * Every secret is matched exactly, however short: a length threshold would let a short real
 * credential through, and the shapes do not recognize values such as `test`. A local service that
 * needs no authentication uses `Credential.None`; a placeholder key it is given anyway is a
 * secret like any other, masked wherever it appears (and a reply that holds it is rejected).
 *
 * Content the model produced is never masked: a reply that holds a form of a secret is rejected
 * whole ([guard], [EchoGuard]).
 *
 * Every cut comes after masking, with the whole set of secrets the run has sent: callers redact
 * the full text, then cut it. A reader that must stop early reads [reach] characters past its
 * limit and drops the [openEnd] of what it read, so a cut never leaves part of a secret behind.
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
    fun guard(): EchoGuard = if (forms.isEmpty()) EchoGuard.NONE else EchoGuard(forms)

    /**
     * [value] when masking leaves it as it is, else `null`. Identifiers (listed model ids, reply
     * ids, reported models, finish reasons) are kept whole or left out, never shown rewritten.
     */
    fun verbatim(value: String): String? = value.takeIf { text(it) == it }

    /** [text] with every credential masked: [exact], then `Bearer …` and the credential shapes. */
    fun text(text: String): String {
        val masked = BEARER.replace(exact(text), "$1$MASK")
        return SHAPES.fold(masked) { acc, shape -> shape.replace(acc, MASK) }
    }

    /**
     * [text] with only the [forms] of the secrets masked, as sent and in their standard JSON
     * escaping. What a read of raw text masks before the text is parsed: a shape matched there
     * would stop at the first escape and leave the rest of a key to read once decoded.
     */
    fun exact(text: String): String {
        var masked = text
        for (form in forms) masked = masked.replace(form, MASK)
        return masked
    }

    /**
     * Text the provider wrote ([raw]: a field of an error body read decoded from JSON, an error
     * body that is not JSON, a redirect target) as a diagnosis quotes it. A secret written as sent
     * or in its standard escaping is masked in place ([exact]); when the lenient decoding
     * ([JsonEscapes.lenientDecode]) of what is left changes it and then shows a credential (a form
     * of a secret, an `sk-` key or a JWT), the text is not quoted at all ([WITHHELD]), since masking
     * it as written would miss what the escape hides and leave the rest readable; otherwise it is
     * masked as [text] masks it, so a credential shape with nothing to decode is masked in place
     * and `Bearer …` masks only the token after it.
     */
    fun quote(raw: String): String {
        val masked = exact(raw)
        val decoded = JsonEscapes.lenientDecode(masked)
        val hides = decoded != masked && (exact(decoded) != decoded || SHAPES.any { it.containsMatchIn(decoded) })
        return if (hides) WITHHELD else text(masked)
    }

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

        /** What [quote] gives for text that hides a credential behind its escapes. */
        const val WITHHELD: String = "(not shown: it holds a credential)"

        private const val MAX_DEPTH = 8

        /** Masks only credential-shaped text (no request secrets known). */
        val SHAPES_ONLY: Redactor = Redactor(emptyList())

        /**
         * `Bearer <token>`, the token masked. No word boundary before the word: an escape a provider
         * wrote as text right before it (`\nBearer`) ends in a letter and would leave none. A word
         * that ends in `bearer` (`forbearer x`) masks the word after it too, which only shows less.
         */
        private val BEARER = Regex("""(?i)(bearer\s+)[^\s"',;]+""")
        private val SHAPES =
            listOf(
                // OpenAI-style keys: sk-…, sk-proj-…
                Regex("""\bsk-[A-Za-z0-9_-]{8,}"""),
                // JWTs (ChatGPT plan access tokens): header.payload.signature
                Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*"""),
            )

        /** A credential shape still open at the end of the text (cut before [SHAPES] could match it). */
        private val OPEN_SHAPE = Regex("""(?:\bsk-[A-Za-z0-9_-]*|\beyJ[A-Za-z0-9_.-]*)\z""")
    }
}

/** Whether the last [n] characters of this text are the first [n] of [form]. */
private fun String.endsWithStartOf(
    form: String,
    n: Int,
): Boolean = form.length > n && regionMatches(length - n, form, 0, n)
