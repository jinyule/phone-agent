package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.testing.PromptSurfaces
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The texts of a session fixture as the current code writes them, shared by the three modes: a
 * replay compares them, refresh and record write them. Both sides of a comparison are decoded,
 * written again by the current codec and normalized (engineering-baseline §3.2), so a fixture of
 * an older format compares through its migrations.
 */
object FixtureTexts {
    const val SYSTEM_PROMPT: String = "system-prompt.expected.md"
    const val TOOL_SCHEMAS: String = "tool-schemas.expected.json"

    /**
     * The normalized `session.jsonl` and `requests.expected.jsonl` of one session, and the
     * sidecars of its first request ([sidecars]; none when it sent none).
     */
    class Normalized(
        val session: String,
        val requests: String,
        val sidecars: Map<String, String>,
        val normalizer: Normalizer,
    )

    /**
     * [log] and [requests] normalized with one [Normalizer] over [secrets], after the replies'
     * resolved templates are put back (N7, [templates]).
     */
    fun normalized(
        log: SessionLog,
        requests: List<RecordedRequest>,
        templates: Map<ToolCallId, RequestTemplates.Resolved> = emptyMap(),
        secrets: List<String> = emptyList(),
    ): Normalized {
        val normalizer = Normalizer(secrets)
        val session = normalizer.session(canonical(log, templates))
        val sidecars = requests.firstOrNull()?.let { sidecars(it.request) }.orEmpty()
        return Normalized(session, normalizer.requests(RecordedRequests.encode(requests)), sidecars, normalizer)
    }

    /** [log] as the current codec writes it (current version), with [templates] put back into the replies. */
    fun canonical(
        log: SessionLog,
        templates: Map<ToolCallId, RequestTemplates.Resolved> = emptyMap(),
        codec: SessionCodec = SessionCodec(),
    ): String {
        val header = codec.encodeHeader(log.header.copy(version = codec.currentVersion))
        val records = log.records.map { restored(it, templates) }.map(codec::encode)
        return (listOf(header) + records).joinToString("") { "$it\n" }
    }

    private fun restored(
        record: SessionRecord,
        templates: Map<ToolCallId, RequestTemplates.Resolved>,
    ): SessionRecord {
        val reply = record.event as? AssistantMessage ?: return record
        return record.copy(event = reply.copy(parts = RequestTemplates.restore(reply.parts, templates)))
    }

    /** The two sidecars of [request] (the session's first): system prompt and tool schemas, golden-file style. */
    fun sidecars(request: ChatRequest): Map<String, String> =
        mapOf(
            SYSTEM_PROMPT to PromptSurfaces.systemPrompt(request.system) + "\n",
            TOOL_SCHEMAS to PromptSurfaces.toolSchemas(request.tools) + "\n",
        )

    /**
     * The PolicyEngine's decisions in [records] (WP08 design §6 ②), one line each in log order:
     * every `policy/verdict` (the call, the level, the intent, the whole verdict: a Confirm's card
     * with its reason, a HandOff's kind, a Refuse's reason and hint) and every `screen/judged`
     * (policy-rules §6.4), as its type and its data the way [codec] writes them. Any change of a
     * decision changes its line.
     */
    fun decisions(
        records: List<SessionRecord>,
        codec: SessionCodec = SessionCodec(),
    ): List<String> =
        records
            .filter { it.event is PolicyVerdictRecorded || it.event is ScreenJudged }
            .map { record ->
                val line = Json.parseToJsonElement(codec.encode(record)).jsonObject
                "${line.getValue("type").jsonPrimitive.content} ${line.getValue("data")}"
            }

    /**
     * Where two JSON-lines texts first differ: the line (named by [line] from its index) and the
     * path of the first differing value in it, or that one text has more lines.
     */
    fun firstDifference(
        expected: String?,
        actual: String,
        line: (index: Int, element: JsonElement) -> String,
    ): String {
        val want = expected?.let(Normalizer::lines).orEmpty()
        val got = Normalizer.lines(actual)
        val at = want.indices.firstOrNull { it >= got.size || want[it] != got[it] }
        val counts = "the replay has ${got.size} lines, the fixture ${want.size}"
        return when {
            at != null && at < got.size -> {
                val (a, b) = Normalizer.parse(want[at]) to Normalizer.parse(got[at])
                val where = path(a, b) ?: "its text"
                "first difference: ${line(at, a)}, at $where\n  expected ${want[at]}\n  actual   ${got[at]}"
            }

            at != null -> {
                "$counts: first missing " + line(at, Normalizer.parse(want[at]))
            }

            else -> {
                "$counts: first extra " + line(want.size, Normalizer.parse(got[want.size]))
            }
        }
    }

    /** The path of the first value that differs between [a] and [b], or `null` when they are equal. */
    fun path(
        a: JsonElement,
        b: JsonElement,
        at: String = "",
    ): String? =
        when {
            a == b -> {
                null
            }

            a is JsonObject && b is JsonObject -> {
                (a.keys + b.keys).firstNotNullOfOrNull { key ->
                    val (x, y) = a[key] to b[key]
                    if (x != null && y != null) path(x, y, "$at.$key") else "$at.$key".takeIf { x != y }
                }
            }

            a is JsonArray && b is JsonArray -> {
                a.indices.firstNotNullOfOrNull { i -> if (i < b.size) path(a[i], b[i], "$at[$i]") else "$at[$i]" }
                    ?: "$at[${a.size}]"
            }

            else -> {
                at.ifEmpty { "." }
            }
        }

    /** How a line of `session.jsonl` is named in a difference: its seq and type. */
    fun recordLine(
        index: Int,
        element: JsonElement,
    ): String {
        if (index == 0) return "the header"
        val envelope = element as? JsonObject
        val seq = (envelope?.get("seq") as? JsonPrimitive)?.content
        return "record seq $seq (${envelope?.get("type")?.jsonPrimitive?.content})"
    }

    /** How a line of `requests.expected.jsonl` is named in a difference. */
    fun requestLine(
        index: Int,
        element: JsonElement,
    ): String = "request #${(element as? JsonObject)?.get("n") ?: index + 1}"

    /** The blob references in [texts], as `ReplayFiles.load` requires them in `blobs/`. */
    fun referenced(vararg texts: String): Set<BlobRef> =
        texts.flatMap { text -> BLOB_FIELD.findAll(text).map { BlobRef(it.groupValues[1]) } }.toSet()

    private val BLOB_FIELD = Regex(""""blob":"([0-9a-f]{64})"""")

    /**
     * Deletes the fixture files in [dir]. Every entry is checked before anything is deleted, links
     * not followed: at the top only the files a fixture holds ([ReplayFiles.KNOWN]), each a regular
     * file and `blobs/` a directory; in `blobs/` only regular files named as blobs
     * ([ReplayFiles.BLOB_NAME]). Anything else fails with [IllegalArgumentException], deleting
     * nothing.
     */
    private fun clear(dir: Path) {
        val entries = Files.list(dir).use { it.toList() }
        val blobs =
            entries
                .filter { it.name == ReplayFiles.BLOBS && it.isDirectory(LinkOption.NOFOLLOW_LINKS) }
                .flatMap { blobDir -> Files.list(blobDir).use { it.toList() } }
        val stray =
            entries.filterNot { entry ->
                entry.name in ReplayFiles.KNOWN &&
                    if (entry.name == ReplayFiles.BLOBS) {
                        entry.isDirectory(LinkOption.NOFOLLOW_LINKS)
                    } else {
                        entry.isRegularFile(LinkOption.NOFOLLOW_LINKS)
                    }
            } +
                blobs.filterNot {
                    it.isRegularFile(
                        LinkOption.NOFOLLOW_LINKS,
                    ) && ReplayFiles.BLOB_NAME.matches(it.name)
                }
        require(stray.isEmpty()) {
            "$dir holds ${stray.map { dir.relativize(it).toString() }.sorted()}: not a fixture directory, left alone"
        }
        blobs.forEach(Files::delete)
        entries.forEach(Files::delete)
    }

    /**
     * Writes a fixture into [dir], replacing what it held: [scenario], [texts] with their sidecars,
     * an [override] when it has facts, and the [blobs] the texts reference.
     */
    fun write(
        dir: Path,
        scenario: Scenario,
        texts: Normalized,
        override: ReplayOverride,
        blobs: Map<BlobRef, ByteArray>,
    ) {
        if (dir.isDirectory()) clear(dir)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(ReplayFiles.SCENARIO), ReplayFiles.encode(scenario))
        Files.writeString(dir.resolve(ReplayFiles.SESSION), texts.session)
        Files.writeString(dir.resolve(ReplayFiles.REQUESTS), texts.requests)
        texts.sidecars.forEach { (name, text) -> Files.writeString(dir.resolve(name), text) }
        if (override.device.isNotEmpty()) {
            Files.writeString(
                dir.resolve(ReplayFiles.OVERRIDE),
                ReplayFiles.encode(override),
            )
        }
        val used = referenced(texts.session, texts.requests)
        if (used.isNotEmpty()) Files.createDirectories(dir.resolve(ReplayFiles.BLOBS))
        for (ref in used.sortedBy { it.value }) {
            val bytes = requireNotNull(blobs[ref]) { "${dir.name}: blob ${ref.value} is referenced but not stored" }
            Files.write(dir.resolve(ReplayFiles.BLOBS).resolve(ref.value), bytes)
        }
    }

    /**
     * Replaces the fixture in [dir] with the one [staged] holds, byte for byte. [staged] must load
     * as a fixture ([ReplayFiles.load]) before [dir] is touched; [dir] is then cleared as [write]
     * clears it, and each file copied.
     */
    fun install(
        staged: Path,
        dir: Path,
    ) {
        ReplayFiles.load(staged)
        if (dir.isDirectory()) clear(dir)
        Files.createDirectories(dir)
        val paths = Files.walk(staged).use { it.toList() }
        for (from in paths.drop(1)) {
            val to = dir.resolve(staged.relativize(from).toString())
            if (from.isDirectory()) Files.createDirectory(to) else Files.copy(from, to)
        }
    }
}
