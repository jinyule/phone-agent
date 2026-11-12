package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionCorruptedException
import dev.phoneagent.core.session.SessionLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * One session fixture directory (engineering-baseline §3.2), loaded and checked by
 * [ReplayFiles.load]. Ids are concrete here (`call-1`), as the replayed session will spell them.
 *
 * @property name the directory name, the scenario's name.
 * @property scenario `scenario.json`.
 * @property session `session.jsonl`.
 * @property requestFile `requests.expected.jsonl`.
 * @property override `replay.override.json` with concrete call ids (empty when the file is absent).
 * @property blobs the files of `blobs/`, by reference; each is referenced and matches its hash.
 */
class SessionFixture(
    val name: String,
    val scenario: Scenario,
    val session: CommittedFile<SessionLog>,
    val requestFile: CommittedFile<List<RecordedRequest>>,
    val override: ReplayOverride,
    val blobs: Map<BlobRef, ByteArray>,
) {
    /** `session.jsonl` decoded, with concrete ids. */
    val log: SessionLog get() = session.value

    /** `requests.expected.jsonl` decoded, with concrete ids. */
    val requests: List<RecordedRequest> get() = requestFile.value
}

/**
 * A JSON-lines file of a fixture.
 *
 * @property text the file as committed: normalized, `{{fromRequest:…}}` templates in place.
 * @property value what it decodes to once its id tokens are replaced by concrete ids.
 */
class CommittedFile<T>(
    val text: String,
    val value: T,
)

/**
 * Reading and writing the files of a session fixture. Every reader is strict: a key the layout
 * does not have, a missing key, a value out of range, a file that is not normalized, or text
 * shaped like a credential fails the load with [IllegalArgumentException] naming the file.
 */
object ReplayFiles {
    const val SCENARIO: String = "scenario.json"
    const val SESSION: String = "session.jsonl"
    const val REQUESTS: String = "requests.expected.jsonl"
    const val OVERRIDE: String = "replay.override.json"
    const val BLOBS: String = "blobs"

    /** The files a fixture directory may hold; anything else is a stray file. */
    val KNOWN: Set<String> = setOf(SCENARIO, SESSION, REQUESTS, OVERRIDE, BLOBS)

    /** Unknown keys fail (the default); defaults are written out, so a written file says everything. */
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    private val BLOB_NAME = Regex("[0-9a-f]{64}")
    private val BLOB_FIELD = Regex(""""blob":"([0-9a-f]{64})"""")

    /** [text] of a `scenario.json`. */
    fun scenario(text: String): Scenario = decode(SCENARIO, serializer(), text)

    /** [text] of a `replay.override.json`, with call tokens as written. */
    fun override(text: String): ReplayOverride = decode(OVERRIDE, serializer(), text)

    /** The file text of [scenario]. */
    fun encode(scenario: Scenario): String = json.encodeToString(serializer<Scenario>(), scenario) + "\n"

    /** The file text of [override]. */
    fun encode(override: ReplayOverride): String = json.encodeToString(serializer<ReplayOverride>(), override) + "\n"

    /**
     * The fixture in [dir], checked: no stray files; no text shaped like a credential in any file
     * but the blobs (every mode runs this, so CI checks it on each run); `session.jsonl` and
     * `requests.expected.jsonl` normalized (normalizing them again changes nothing) and decodable
     * with their ids made concrete, the log whole (no skipped records, no torn tail) and every
     * request sent within it; `blobs/` holding exactly the referenced blobs, each matching its hash.
     */
    fun load(
        dir: Path,
        codec: SessionCodec = SessionCodec(),
    ): SessionFixture {
        require(dir.isDirectory()) { "$dir is not a fixture directory" }
        val stray =
            Files.list(dir).use { files ->
                files
                    .map { it.name }
                    .filter { it !in KNOWN }
                    .sorted()
                    .toList()
            }
        require(stray.isEmpty()) { "${dir.name}: stray files $stray (a fixture holds $KNOWN)" }
        val texts = listOf(SCENARIO, SESSION, REQUESTS, OVERRIDE).filter { dir.resolve(it).isRegularFile() }
        for (file in texts) Credentials.checkShapes("${dir.name}/$file", dir.resolve(file).readText())
        for (file in listOf(SCENARIO, SESSION, REQUESTS)) require(file in texts) { "${dir.name}: $file is missing" }

        val sessionText = dir.resolve(SESSION).readText()
        val requestsText = dir.resolve(REQUESTS).readText()
        checkNormalized(dir.name, sessionText, requestsText)
        val log = log(dir.name, sessionText, codec)
        val requests = within(dir.name, RecordedRequests.decode(Tokens.materializeRequests(requestsText)), log)
        val override = if (OVERRIDE in texts) concrete(override(dir.resolve(OVERRIDE).readText())) else ReplayOverride()
        val blobs = blobs(dir, referenced(sessionText) + referenced(requestsText))
        return SessionFixture(
            dir.name,
            scenario(dir.resolve(SCENARIO).readText()),
            CommittedFile(sessionText, log),
            CommittedFile(requestsText, requests),
            override,
            blobs,
        )
    }

    private fun <T> decode(
        file: String,
        serializer: KSerializer<T>,
        text: String,
    ): T =
        try {
            json.decodeFromString(serializer, text)
        } catch (invalid: IllegalArgumentException) {
            // SerializationException (unknown or missing key, bad value) and failed `require`s alike.
            throw IllegalArgumentException("$file: ${invalid.message}", invalid)
        }

    private fun checkNormalized(
        name: String,
        sessionText: String,
        requestsText: String,
    ) {
        val normalizer = Normalizer()
        require(normalizer.session(sessionText) == sessionText) {
            "$name/$SESSION is not normalized (record or refresh writes it)"
        }
        val requests =
            try {
                normalizer.requests(requestsText)
            } catch (foreign: IllegalArgumentException) {
                // An id the normalized log does not hold: the file was not normalized with it.
                throw IllegalArgumentException("$name/$REQUESTS is not normalized: ${foreign.message}", foreign)
            }
        require(requests == requestsText) { "$name/$REQUESTS is not normalized (record or refresh writes it)" }
    }

    private fun log(
        name: String,
        sessionText: String,
        codec: SessionCodec,
    ): SessionLog {
        val log =
            try {
                codec.read(Tokens.materializeSession(sessionText).toByteArray())
            } catch (corrupted: SessionCorruptedException) {
                throw IllegalArgumentException("$name/$SESSION: ${corrupted.message}", corrupted)
            }
        require(log.skipped.isEmpty()) { "$name/$SESSION: records of unknown types ${log.skipped.map { it.seq }}" }
        require(log.truncatedTail == null) { "$name/$SESSION: the last line is torn" }
        return log
    }

    private fun within(
        name: String,
        requests: List<RecordedRequest>,
        log: SessionLog,
    ): List<RecordedRequest> {
        val late = requests.filter { it.logSeq > log.lastSeq }.map { it.n }
        require(late.isEmpty()) { "$name/$REQUESTS: requests $late were sent after the last record (${log.lastSeq})" }
        return requests
    }

    private fun concrete(override: ReplayOverride): ReplayOverride =
        override.renamed { token ->
            require(token.startsWith("{{call:")) { "$OVERRIDE: call '$token' is not a {{call:n}} token" }
            Tokens.concrete(token)
        }

    private fun referenced(text: String): Set<String> = BLOB_FIELD.findAll(text).map { it.groupValues[1] }.toSet()

    private fun blobs(
        dir: Path,
        referenced: Set<String>,
    ): Map<BlobRef, ByteArray> {
        val root = dir.resolve(BLOBS)
        val files =
            if (root.isDirectory()) Files.list(root).use { list -> list.toList().sortedBy { it.name } } else emptyList()
        val blobs =
            files.associate { file ->
                require(file.isRegularFile() && BLOB_NAME.matches(file.name)) {
                    "${dir.name}/$BLOBS: '${file.name}' is not a blob file"
                }
                val bytes = file.readBytes()
                require(
                    sha256(bytes) == file.name,
                ) { "${dir.name}/$BLOBS/${file.name}: content does not match its name" }
                file.name to bytes
            }
        val missing = referenced - blobs.keys
        require(missing.isEmpty()) { "${dir.name}: referenced blobs missing from $BLOBS/: ${missing.sorted()}" }
        val unused = blobs.keys - referenced
        require(unused.isEmpty()) { "${dir.name}: blobs nothing references: ${unused.sorted()}" }
        return blobs.mapKeys { BlobRef(it.key) }
    }

    /** The lowercase hex SHA-256 of [bytes], as a [BlobRef] spells it. */
    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
