package dev.phoneagent.core.replay

import dev.phoneagent.core.session.SessionCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * What normalization does to a numeric or id field (engineering-baseline §3.2). Every such field of
 * the session log and of `requests.expected.jsonl` has exactly one class, listed in
 * `replay/normalized-fields.txt`; `NormalizedFieldsTest` fails when a field is missing there.
 */
enum class FieldClass(
    val label: String,
    /** The id kind a token names (`{{call:3}}`), for the id classes. */
    val token: String? = null,
) {
    /** A wall-clock time: set to 0. */
    TIME("time"),

    /** An absolute deadline: kept relative to its record's time (`expiresAt - time`). */
    EXPIRY("expiry"),

    /** How long something took or will wait, with jitter: set to 0. */
    DURATION("duration"),

    /** A seq or a reference to one: kept (deterministic). */
    SEQ("seq"),

    /** Any other number (counts, sizes, coordinates, amounts, versions): kept. */
    VALUE("value"),

    /** An id that is the same in replay (device- or content-given, model-visible or both): kept. */
    KEPT("kept"),

    /** A task id: a `{{task:n}}` token by first occurrence. */
    TASK("task", "task"),

    /** A session id: a `{{session:n}}` token. */
    SESSION("session", "session"),

    /** A tool call id: a `{{call:n}}` token. */
    CALL("call", "call"),

    /** A prompt id: a `{{prompt:n}}` token. */
    PROMPT("prompt", "prompt"),
    ;

    companion object {
        /** The class labelled [label]. */
        fun named(label: String): FieldClass =
            requireNotNull(entries.firstOrNull { it.label == label }) { "unknown field class '$label'" }
    }
}

/**
 * The committed field table `replay/normalized-fields.txt`: one line per field, `<scope> <path>
 * <leaf type> <class>`. Scopes are `header` (line 1 of `session.jsonl`), `envelope` (a record line
 * without its `data`), an event type such as `tool/call` (that record's `data`), and `requests` (a
 * line of `requests.expected.jsonl`).
 */
object NormalizedFields {
    /** Where the table is, relative to `:core`. */
    const val FILE: String = "src/test/resources/replay/normalized-fields.txt"

    /** One line of the table. */
    data class Entry(
        val scope: String,
        val path: FieldPath,
        val leaf: String,
        val fieldClass: FieldClass,
    )

    /** The table as committed. */
    val entries: List<Entry> by lazy { parse(load()) }

    /** The rewriter of each scope. */
    val byScope: Map<String, FieldRewriter<FieldClass>> by lazy {
        entries.groupBy { it.scope }.mapValues { (_, lines) -> FieldRewriter(lines.map { it.path to it.fieldClass }) }
    }

    /** The lines of [text] that are not blank or comments, parsed; fails naming the bad line. */
    fun parse(text: String): List<Entry> =
        text.lines().mapIndexedNotNull { index, line ->
            val content = line.substringBefore('#').trim()
            if (content.isEmpty()) return@mapIndexedNotNull null
            val parts = content.split(Regex("\\s+"))
            require(
                parts.size == 4,
            ) { "normalized-fields.txt line ${index + 1}: expected 4 columns, got ${parts.size}" }
            Entry(parts[0], FieldPath(parts[1]), parts[2], FieldClass.named(parts[3]))
        }

    private fun load(): String =
        requireNotNull(NormalizedFields::class.java.getResource("/replay/normalized-fields.txt")) {
            "replay/normalized-fields.txt is missing from the test resources"
        }.readText()
}

/**
 * The normalizer of engineering-baseline §3.2, over the text of a session log and of its requests
 * file. One instance normalizes one fixture: ids get their tokens by first occurrence in the log,
 * and the requests file reuses the log's tokens (a request only holds ids the log already has).
 *
 * Rules (each field's class is in [NormalizedFields]):
 * - N1 times are 0; N2 deadlines are kept relative to their record's time; N3 durations are 0;
 * - N4 task, session, call and prompt ids become `{{task:n}}`, `{{session:n}}`, `{{call:n}}`,
 *   `{{prompt:n}}`; snapshot ids, element refs, blob refs, provider and model ids are kept;
 * - N5 each of [secrets] becomes `{{credential:n}}` (as written, JSON-escaped once and twice);
 * - seqs and every other value are kept; nothing else is touched. Model-visible text and JSON
 *   (`argsJson`, tool arguments, opaque reasoning, provider metadata) are never rewritten.
 *
 * Input must be canonical (the codec's own encoding, [canonical]); the output keeps its layout.
 */
class Normalizer(
    private val secrets: List<String> = emptyList(),
) {
    private val tokens = mutableMapOf<String, MutableMap<String, Int>>()
    private val hits = mutableMapOf<Int, Int>()

    /** How many replacements each key needed so far, by placeholder number. */
    val credentialHits: Map<Int, Int> get() = hits

    /** The normalized text of a canonical `session.jsonl`. */
    fun session(text: String): String =
        lines(text)
            .mapIndexed { index, line ->
                val element = parse(redact(line))
                val normalized = if (index == 0) apply("header", element, 0) else record(element)
                encode(normalized)
            }.joinToString("") { "$it\n" }

    /**
     * The normalized text of a `requests.expected.jsonl` written by [RecordedRequests.encode], after
     * [session] of the same fixture. A call id the log did not hold fails with
     * [IllegalArgumentException].
     */
    fun requests(text: String): String =
        lines(text).joinToString("") { line -> encode(apply("requests", parse(redact(line)), 0, strict = true)) + "\n" }

    private fun record(element: JsonElement): JsonElement {
        val envelope = element as? JsonObject ?: throw IllegalArgumentException("a record line must be a JSON object")
        val time = (envelope["time"] as? JsonPrimitive)?.longOrNull ?: 0
        val outer = apply("envelope", envelope, time) as JsonObject
        val type = (envelope["type"] as? JsonPrimitive)?.content
        val data = envelope["data"] ?: return outer
        return JsonObject(outer + ("data" to (type?.let { apply(it, data, time) } ?: data)))
    }

    private fun apply(
        scope: String,
        element: JsonElement,
        time: Long,
        strict: Boolean = false,
    ): JsonElement {
        val rewriter = NormalizedFields.byScope[scope] ?: return element
        return rewriter.rewrite(element) { fieldClass, path, value ->
            when (fieldClass) {
                FieldClass.TIME, FieldClass.DURATION -> {
                    JsonPrimitive(0)
                }

                FieldClass.EXPIRY -> {
                    JsonPrimitive(value.long - time)
                }

                FieldClass.SEQ, FieldClass.VALUE, FieldClass.KEPT -> {
                    value
                }

                FieldClass.TASK, FieldClass.SESSION, FieldClass.CALL, FieldClass.PROMPT -> {
                    JsonPrimitive(token(checkNotNull(fieldClass.token), value.content, strict, "$scope $path"))
                }
            }
        }
    }

    private fun token(
        kind: String,
        raw: String,
        strict: Boolean,
        where: String,
    ): String {
        val seen = tokens.getOrPut(kind) { linkedMapOf() }
        val number =
            seen[raw] ?: run {
                require(!strict) { "$where: $kind id not in the session log" }
                (seen.size + 1).also { seen[raw] = it }
            }
        return Tokens.of(kind, number)
    }

    private fun redact(line: String): String {
        if (secrets.isEmpty()) return line
        val redacted = Credentials.redact(line, secrets)
        redacted.hits.forEach { (n, count) -> hits.merge(n, count, Int::plus) }
        return redacted.text
    }

    companion object {
        /** [bytes] of a session log decoded and written again by the current codec (migrated, current version). */
        fun canonical(
            bytes: ByteArray,
            codec: SessionCodec = SessionCodec(),
        ): String {
            val log = codec.read(bytes)
            val header = codec.encodeHeader(log.header.copy(version = codec.currentVersion))
            return (listOf(header) + log.records.map(codec::encode)).joinToString("") { "$it\n" }
        }

        internal fun lines(text: String): List<String> {
            require(text.isEmpty() || text.endsWith("\n")) { "a JSON-lines text must end with a newline" }
            return text.lineSequence().filter { it.isNotEmpty() }.toList()
        }

        internal fun parse(line: String): JsonElement = Json.parseToJsonElement(line)

        internal fun encode(element: JsonElement): String = element.toString()
    }
}

/**
 * The id tokens of normalized fixtures and the concrete ids they stand for while a fixture runs:
 * `{{call:3}}` is `call-3`, a valid id of every kind (a session id must be a path segment).
 */
object Tokens {
    private val TOKEN = Regex("""\{\{([a-z]+):([1-9][0-9]*)}}""")

    /** The token of the [number]-th id of [kind]. */
    fun of(
        kind: String,
        number: Int,
    ): String = "{{$kind:$number}}"

    /** The concrete id [token] stands for; fails with [IllegalArgumentException] when it is no token. */
    fun concrete(token: String): String {
        val match = requireNotNull(TOKEN.matchEntire(token)) { "'$token' is not an id token" }
        val (kind, number) = match.destructured
        return "$kind-$number"
    }

    /** A normalized `session.jsonl` with its tokens replaced by concrete ids, ready to decode. */
    fun materializeSession(text: String): String =
        Normalizer
            .lines(text)
            .mapIndexed { index, line ->
                val element = Normalizer.parse(line)
                Normalizer.encode(if (index == 0) materialize("header", element) else materializeRecord(element))
            }.joinToString("") { "$it\n" }

    /** A normalized `requests.expected.jsonl` with its tokens replaced by concrete ids. */
    fun materializeRequests(text: String): String =
        Normalizer.lines(text).joinToString("") {
            Normalizer.encode(materialize("requests", Normalizer.parse(it))) +
                "\n"
        }

    private fun materializeRecord(element: JsonElement): JsonElement {
        val envelope = element as? JsonObject ?: throw IllegalArgumentException("a record line must be a JSON object")
        val type = (envelope["type"] as? JsonPrimitive)?.content
        val data = envelope["data"]
        return if (type == null ||
            data == null
        ) {
            envelope
        } else {
            JsonObject(envelope + ("data" to materialize(type, data)))
        }
    }

    private fun materialize(
        scope: String,
        element: JsonElement,
    ): JsonElement {
        val rewriter = NormalizedFields.byScope[scope] ?: return element
        return rewriter.rewrite(element) { fieldClass, path, value ->
            val kind = fieldClass.token ?: return@rewrite value
            val match = TOKEN.matchEntire(value.content)
            require(match != null && match.groupValues[1] == kind) {
                "$scope $path: expected a {{$kind:n}} token, the fixture is not normalized"
            }
            JsonPrimitive(concrete(value.content))
        }
    }
}
