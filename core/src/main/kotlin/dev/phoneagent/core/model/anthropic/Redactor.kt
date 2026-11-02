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
 * Every cut comes after masking: callers redact the full text, then cut it. A reader that must
 * stop early reads [reach] bytes past its limit and drops the [openEnd] of what it read, so a cut
 * never leaves part of a secret behind. Reply content that streams in pieces goes through a
 * [Window] instead, which only masks the request's own secrets.
 *
 * A copy of the OpenAI adapters' redactor: capabilities may not use each other's code (package
 * rules), so the two are kept in step by hand. [prefix]-style cuts ([reach], [openEnd]) match
 * WP03's; [Window] and [echoes] are this adapter's (reply content of Anthropic Messages).
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

    /** [text] with every credential masked. */
    fun text(text: String): String {
        var masked = text
        for (form in forms) masked = masked.replace(form, MASK)
        masked = BEARER.replace(masked, "$1$MASK")
        return SHAPES.fold(masked) { acc, shape -> shape.replace(acc, MASK) }
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

    /** Whether [text] contains one of the request secrets. */
    fun echoes(text: String): Boolean = secrets.any { it in text }

    /**
     * A [Window] over the request secrets; with [json], also over the forms they take inside JSON
     * text ([forms], and those with `/` written `\/`).
     */
    fun window(json: Boolean = false): Window = Window(if (json) forms.flatMap(::slashForms).distinct() else secrets)

    /**
     * Masks request secrets in text that arrives in pieces (reply text, tool arguments, thinking
     * previews): [push] returns what can be shown now, [finish] the rest. Together they add up to
     * the text with every secret, also one split across pieces, replaced by [MASK]; only a tail that
     * could still grow into a secret is held back meanwhile. Credential shapes are not masked here:
     * this is reply content, and only the request's own secrets must never be in it (model-layer §2).
     */
    class Window internal constructor(
        private val patterns: List<String>,
    ) {
        private val held = StringBuilder()

        /** Whether a secret has been masked so far. */
        var found: Boolean = false
            private set

        /** [piece] appended; returns the text that is now certain, masked. */
        fun push(piece: String): String {
            if (patterns.isEmpty()) return piece
            held.append(piece)
            val text = held.toString()
            val spans = patterns.flatMap { occurrences(text, it) }
            val cut = uncrossed(text.length - pending(text), spans)
            held.delete(0, cut)
            return masked(text, cut, spans)
        }

        /** The text still held back, masked; the window is empty afterwards. */
        fun finish(): String {
            val text = held.toString()
            held.clear()
            return masked(text, text.length, patterns.flatMap { occurrences(text, it) })
        }

        /** Length of the longest tail of [text] that begins a pattern without completing it. */
        private fun pending(text: String): Int =
            patterns.maxOf { pattern ->
                (minOf(pattern.length - 1, text.length) downTo 1).firstOrNull { text.endsWith(pattern.take(it)) } ?: 0
            }

        /** The first [cut] characters of [text], each run of overlapping [spans] in them replaced by [MASK]. */
        private fun masked(
            text: String,
            cut: Int,
            spans: List<IntRange>,
        ): String {
            val inside = spans.filter { it.last < cut }.sortedBy { it.first }
            if (inside.isNotEmpty()) found = true
            val out = StringBuilder()
            var at = 0
            for (span in inside) {
                if (span.first >= at) out.append(text, at, span.first).append(MASK)
                at = maxOf(at, span.last + 1)
            }
            return out.append(text, at, cut).toString()
        }
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
        /** Where [pattern] occurs in [text], overlapping occurrences included. */
        private fun occurrences(
            text: String,
            pattern: String,
        ): List<IntRange> =
            generateSequence(text.indexOf(pattern)) { at -> text.indexOf(pattern, at + 1).takeIf { it >= 0 } }
                .takeWhile { it >= 0 }
                .map { it until it + pattern.length }
                .toList()

        /** [cut] moved back until none of [spans] crosses it. */
        private fun uncrossed(
            cut: Int,
            spans: List<IntRange>,
        ): Int {
            var at = cut
            while (true) {
                val crossing = spans.firstOrNull { it.first < at && it.last >= at } ?: return at
                at = crossing.first
            }
        }

        /** [form] and, when it has a `/`, the same with `/` escaped as JSON allows. */
        private fun slashForms(form: String): List<String> = listOf(form, form.replace("/", "\\/"))

        /** What a masked credential reads as. */
        const val MASK: String = "***"

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
