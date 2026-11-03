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
        val masked = maskBearer(exact(text))
        return SHAPES.fold(masked) { acc, shape -> shape.replace(acc, MASK) }
    }

    /**
     * [text] with every form of this request's secrets masked and nothing else: what may be masked
     * before the text is decoded, since an escape never splits a whole form.
     */
    fun exact(text: String): String = forms.fold(text) { acc, form -> acc.replace(form, MASK) }

    /**
     * Text the provider wrote ([raw]: a field of an error read decoded from JSON, a refusal's
     * category, each string of error JSON with no message field, an error body that is not JSON, a
     * redirect target) as a diagnosis quotes it. A secret written as sent or in its standard
     * escaping is masked in place ([exact]); when the lenient decoding ([JsonEscapes.lenientDecode])
     * of what is left changes it and then shows a credential (a form of a secret, an `sk-` key or a
     * JWT), the text is not quoted at all ([WITHHELD]), since masking it as written would miss what
     * the escape hides and leave the rest readable (`\u0073k-…`, also inside a decoded field);
     * otherwise it is masked as [text] masks it, so a credential shape with nothing to decode is
     * masked in place and `Bearer …` masks only the token after it. The OpenAI adapters' rule,
     * word for word (WP03).
     */
    fun quote(raw: String): String {
        val masked = exact(raw)
        val decoded = JsonEscapes.lenientDecode(masked)
        val hidden = decoded != masked && (exact(decoded) != decoded || SHAPES.any { it.containsMatchIn(decoded) })
        return if (hidden) WITHHELD else text(masked)
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

/**
 * [text] with the token after each `Bearer` masked (model-layer §2 rule 4): the word `bearer` and
 * the whitespace after it stay, and the token becomes [Redactor.MASK] together with any words
 * ending in `bearer` in a row before it, so no `Bearer` passes for the token: `Bearer Bearer x`
 * shows `Bearer ***`, `forbearer Bearer x` shows `forbearer ***`. When nothing follows the run, its
 * last word is the token. Case does not matter, and there is no word boundary before the word: an
 * escape a provider wrote as text right before it (`\nBearer`) ends in a letter and would leave
 * none. A word that ends in `bearer` (`forbearer x`) masks the word after it too, which only shows
 * less. A word is a run of characters other than whitespace (ASCII: space, tab, line feed, vertical
 * tab, form feed, carriage return), `"`, `'`, `,` and `;`. One pass, in constant stack depth
 * however long the run.
 */
private fun maskBearer(text: String): String {
    val masked = StringBuilder(text.length)
    var kept = 0
    var at = text.indexOf(SCHEME, ignoreCase = true)
    while (at >= 0) {
        val scheme = text.spaceEnd(at + SCHEME.length)
        val end = if (scheme > at + SCHEME.length) text.tokenEnd(scheme) else NO_TOKEN
        if (end != NO_TOKEN) {
            masked.append(text, kept, scheme).append(Redactor.MASK)
            kept = end
        }
        at = text.indexOf(SCHEME, if (end == NO_TOKEN) at + 1 else end, ignoreCase = true)
    }
    return masked.append(text, kept, text.length).toString()
}

/** The end of the token from [from] on, past the words ending in `bearer` before it; [NO_TOKEN] when there is none. */
private fun String.tokenEnd(from: Int): Int {
    var start = from
    var end = wordEnd(start)
    var lastOfRun = NO_TOKEN
    while (isRunWord(start, end)) {
        lastOfRun = end
        start = spaceEnd(end)
        end = wordEnd(start)
    }
    return if (end > start) end else lastOfRun
}

/** Whether the word from [start] to [end] ends in `bearer` and whitespace follows it. */
private fun String.isRunWord(
    start: Int,
    end: Int,
): Boolean =
    end - start >= SCHEME.length &&
        spaceEnd(end) > end &&
        regionMatches(end - SCHEME.length, SCHEME, 0, SCHEME.length, ignoreCase = true)

private fun String.wordEnd(from: Int): Int {
    var end = from
    while (end < length && this[end].inWord()) end++
    return end
}

private fun String.spaceEnd(from: Int): Int {
    var end = from
    while (end < length && this[end].isSpace()) end++
    return end
}

private fun Char.isSpace(): Boolean = this == ' ' || this in '\t'..'\r'

private fun Char.inWord(): Boolean = !isSpace() && this !in WORD_ENDS

private const val SCHEME = "bearer"

/** Characters other than whitespace that end a word. */
private const val WORD_ENDS = "\"',;"

/** No token after `bearer` and its whitespace: nothing to mask there. */
private const val NO_TOKEN = -1
