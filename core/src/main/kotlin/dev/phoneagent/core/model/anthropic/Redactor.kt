package dev.phoneagent.core.model.anthropic

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Masks credentials in provider text before it reaches a [dev.phoneagent.core.model.ModelError]
 * message or cause, or any diagnostic output (model-layer §2). Two layers: every
 * occurrence of the [secrets] a request used becomes [MASK]; then text shaped like a credential
 * (`Bearer …`, `sk-…`, JWTs) is masked whether or not this request used it. Error codes and
 * parameter names never match these shapes, so the diagnosis survives.
 *
 * Callers redact before truncating, so a cut never leaves half a secret behind.
 *
 * A copy of the OpenAI adapters' redactor: capabilities may not use each other's code (package
 * rules), so the two are kept in step by hand.
 */
internal class Redactor(
    secrets: Iterable<String>,
) {
    // Longest first, so a secret that contains another is masked whole.
    private val secrets = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /** A redactor that also masks [secret]. */
    fun with(secret: String?): Redactor = if (secret.isNullOrEmpty()) this else Redactor(secrets + secret)

    /** [text] with every credential masked. */
    fun text(text: String): String {
        var masked = text
        for (secret in secrets) masked = masked.replace(secret, MASK)
        masked = BEARER.replace(masked, "$1$MASK")
        return SHAPES.fold(masked) { acc, shape -> shape.replace(acc, MASK) }
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
    }
}
