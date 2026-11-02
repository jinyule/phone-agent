package dev.phoneagent.core.model.openai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Masks credentials in provider text before it reaches a [dev.phoneagent.core.model.ModelError]
 * message or cause, a [ProbeResult] or any diagnostic output (model-layer §2). Two layers: every
 * occurrence of the secrets a request used becomes [MASK], each as sent and in its standard JSON
 * string escaping (`"` → `\"`, `\` → `\\`: [forms]); then text shaped like a credential
 * (`Bearer …`, `sk-…`, JWTs) is masked whether or not this request used it. Error codes and
 * parameter names never match these shapes, so the diagnosis survives. JSON that may escape a
 * secret in other ways (`\/`, `\u0073`) is masked after it is written back in the standard
 * escaping ([json]).
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

    /** [text] with every credential masked. */
    fun text(text: String): String {
        var masked = text
        for (form in forms) masked = masked.replace(form, MASK)
        masked = BEARER.replace(masked, "$1$MASK")
        return SHAPES.fold(masked) { acc, shape -> shape.replace(acc, MASK) }
    }

    /**
     * [json] (text that should be JSON, such as call arguments) with every credential masked as
     * [text] masks it, after JSON that parses is written back in the standard escaping. Standard
     * escaping goes character by character, so a string (or key) that holds a secret then holds
     * its escaped form: masking the strings one by one would mask nothing more.
     */
    fun json(json: String): String {
        val parsed =
            try {
                Json.parseToJsonElement(json)
            } catch (ignoredNotJson: SerializationException) {
                // Not JSON: no escapes to decode, the text is all there is to mask.
                null
            }
        return text(parsed?.toString() ?: json)
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

        private const val MAX_DEPTH = 8

        /** Masks only credential-shaped text (no request secrets known). */
        val SHAPES_ONLY: Redactor = Redactor(emptyList())

        private val BEARER = Regex("""(?i)(\bbearer\s+)[^\s"',;]+""")
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
