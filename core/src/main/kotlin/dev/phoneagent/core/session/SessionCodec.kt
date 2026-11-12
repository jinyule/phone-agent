package dev.phoneagent.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Encodes and decodes `session.jsonl` (phase1 §3.9). One JSON object per line, `\n`-terminated:
 * line 1 is the [SessionHeader], every further line an [Envelope].
 *
 * Reading is strict: unknown types are skipped only when the record is ignorable
 * ([UnknownEventTypeException] otherwise), unknown keys are rejected, and any unreadable line
 * fails the whole log ([SessionCorruptedException]) except a torn last line, which is reported in
 * [SessionLog.truncatedTail]. A readable line that breaks a rule spanning several records
 * (see `SessionInvariants`) is corruption too. Records older than [currentVersion] pass through
 * the migration chain.
 *
 * @param migrations one per version step; they must form a contiguous chain up to [currentVersion].
 *   By default this build's chain ([SessionFormat.MIGRATIONS]).
 * @property currentVersion the version this codec writes and migrates to.
 */
public class SessionCodec(
    migrations: List<SessionMigration> = SessionFormat.MIGRATIONS,
    public val currentVersion: Int = SessionFormat.CURRENT_VERSION,
) {
    private val migrationFrom: Map<Int, SessionMigration> = migrations.associateBy { it.fromVersion }

    /** The oldest version this codec can read. */
    public val oldestReadableVersion: Int = migrationFrom.keys.minOrNull() ?: currentVersion

    init {
        require(currentVersion >= 1) { "currentVersion must be >= 1" }
        require(migrationFrom.size == migrations.size) { "Duplicate migrations for one version" }
        require(migrationFrom.keys.sorted() == (oldestReadableVersion until currentVersion).toList()) {
            "Migrations ${migrationFrom.keys.sorted()} must form a contiguous chain ending at ${currentVersion - 1}"
        }
    }

    /** The header line, without the newline. */
    public fun encodeHeader(header: SessionHeader): String =
        payloadJson.encodeToString(serializer<SessionHeader>(), header)

    /**
     * Parses the header line. Throws [SessionCorruptedException] when it is not a header and
     * [UnsupportedSessionVersionException] when its version is outside [oldestReadableVersion]..[currentVersion].
     */
    public fun decodeHeader(line: String): SessionHeader {
        val element = parse(line, HEADER_LINE)
        val header = corruptOn(HEADER_LINE) { payloadJson.decodeFromJsonElement(serializer<SessionHeader>(), element) }
        val supported = oldestReadableVersion..currentVersion
        if (header.version !in supported) throw UnsupportedSessionVersionException(header.version, supported)
        return header
    }

    /** One record line in the current version, without the newline. */
    public fun encode(record: SessionRecord): String {
        val type = SessionEventTypes.of(record.event)
        val envelope =
            Envelope(
                seq = record.seq,
                time = record.time,
                type = type.name,
                ignorable = type.ignorable,
                surfaceOp = record.surfaceOp,
                sources = record.sources,
                data = type.encode(payloadJson, record.event),
            )
        return envelopeJson.encodeToString(serializer<Envelope>(), envelope)
    }

    /**
     * Decodes one record [line] written in [version] (already checked against the header).
     * [lineNumber] is only used in error messages.
     */
    public fun decode(
        line: String,
        version: Int,
        lineNumber: Int,
    ): DecodedLine {
        val element = parse(line, lineNumber)
        val envelope =
            corruptOn(lineNumber) {
                envelopeJson.decodeFromJsonElement(serializer<Envelope>(), migrate(element, version))
            }
        val type = SessionEventTypes.named(envelope.type)
        return when {
            type != null -> {
                val event =
                    corruptOn(lineNumber, "seq ${envelope.seq} (${envelope.type}): ") {
                        type.decode(payloadJson, envelope.data)
                    }
                val (seq, time) = envelope.seq to envelope.time
                DecodedLine.Event(SessionRecord(seq, time, event, envelope.surfaceOp, envelope.sources))
            }

            envelope.ignorable -> {
                DecodedLine.Skipped(SkippedRecord(envelope.seq, envelope.type))
            }

            else -> {
                throw UnknownEventTypeException(envelope.seq, envelope.type)
            }
        }
    }

    /** Reads a whole log; see the class contract for what is accepted. */
    public fun read(bytes: ByteArray): SessionLog {
        val split = splitLines(bytes)
        val headerBytes = split.lines.firstOrNull() ?: throw SessionCorruptedException(HEADER_LINE, "missing header")
        val header = decodeHeader(utf8(headerBytes, HEADER_LINE))
        val reader = LogReader()
        split.lines.drop(1).forEachIndexed { index, lineBytes ->
            val lineNumber = index + 2
            reader.take(decode(utf8(lineBytes, lineNumber), header.version, lineNumber), lineNumber)
        }
        if (reader.records.isEmpty()) throw SessionCorruptedException(HEADER_LINE + 1, "missing task/start")
        val tail = split.tail?.let { TruncatedTail(line = split.lines.size + 1, bytes = it) }
        return SessionLog(header, reader.records, reader.skipped, tail)
    }

    /**
     * Collects decoded lines, checking seq order and the rules that span records with the same
     * `SessionInvariants` the store appends with (an unknown ignorable record included).
     */
    private class LogReader {
        val records = mutableListOf<SessionRecord>()
        val skipped = mutableListOf<SkippedRecord>()
        private val invariants = SessionInvariants()
        private var expectedSeq = 1L

        fun take(
            decoded: DecodedLine,
            lineNumber: Int,
        ) {
            val seq =
                when (decoded) {
                    is DecodedLine.Event -> decoded.record.seq
                    is DecodedLine.Skipped -> decoded.record.seq
                }
            if (seq != expectedSeq) throw SessionCorruptedException(lineNumber, "expected seq $expectedSeq, found $seq")
            expectedSeq++
            when (decoded) {
                is DecodedLine.Event -> {
                    accept(decoded.record, lineNumber)
                }

                is DecodedLine.Skipped -> {
                    val violation = invariants.skippedViolation(seq)
                    if (violation != null) throw SessionCorruptedException(lineNumber, "seq $seq: $violation")
                    skipped += decoded.record
                }
            }
        }

        private fun accept(
            record: SessionRecord,
            lineNumber: Int,
        ) {
            invariants.violation(record)?.let { throw SessionCorruptedException(lineNumber, "seq ${record.seq}: $it") }
            invariants.accept(record)
            records += record
        }
    }

    private fun migrate(
        element: JsonElement,
        version: Int,
    ): JsonObject {
        var envelope = element as? JsonObject ?: throw IllegalArgumentException("a record must be a JSON object")
        for (from in version until currentVersion) envelope = migrationFrom.getValue(from).migrate(envelope)
        return envelope
    }

    private fun parse(
        line: String,
        lineNumber: Int,
    ): JsonElement =
        try {
            Json.parseToJsonElement(line)
        } catch (malformed: IllegalArgumentException) {
            throw SessionCorruptedException(lineNumber, "not JSON: ${malformed.message}", malformed)
        }

    /** Runs [block], reporting a failed decode or constructor check as corruption of [lineNumber]. */
    private inline fun <T> corruptOn(
        lineNumber: Int,
        context: String = "",
        block: () -> T,
    ): T =
        try {
            block()
        } catch (invalid: IllegalArgumentException) {
            // SerializationException and failed `require`s in constructors are both IllegalArgumentException.
            throw SessionCorruptedException(lineNumber, context + (invalid.message ?: "invalid record"), invalid)
        }

    private fun utf8(
        bytes: ByteArray,
        lineNumber: Int,
    ): String {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (notUtf8: CharacterCodingException) {
            throw SessionCorruptedException(lineNumber, "not UTF-8", notUtf8)
        }
    }

    private class Split(
        val lines: List<ByteArray>,
        val tail: Int?,
    )

    private fun splitLines(bytes: ByteArray): Split {
        val lines = mutableListOf<ByteArray>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == NEWLINE) {
                lines += bytes.copyOfRange(start, i)
                start = i + 1
            }
        }
        return Split(lines, (bytes.size - start).takeIf { it > 0 })
    }
}

private const val HEADER_LINE = 1
private const val NEWLINE = '\n'.code.toByte()

/** Envelope: fields at their default (`ignorable`, `surfaceOp`, `sources`) are omitted. */
private val envelopeJson = Json { encodeDefaults = false }

/** Header and payloads: every field is written, so a later change of a default cannot alter old records. */
private val payloadJson = Json { encodeDefaults = true }

/** One decoded record line. */
public sealed interface DecodedLine {
    /** A known record. */
    public data class Event(
        /** The record. */
        val record: SessionRecord,
    ) : DecodedLine

    /** An ignorable record of unknown type. */
    public data class Skipped(
        /** Where and what. */
        val record: SkippedRecord,
    ) : DecodedLine
}
