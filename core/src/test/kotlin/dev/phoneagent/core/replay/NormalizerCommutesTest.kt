package dev.phoneagent.core.replay

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.CompactionPrune
import dev.phoneagent.core.session.CompactionSummary
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.SideRequest
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.deriveMessages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * Lock (a) of WP08 design §6: normalizing commutes with deriving the requests. Deriving them from
 * the log and normalizing the result gives, byte for byte, what normalizing the log first and
 * deriving from it gives. So normalized fixtures say what the raw session said to the model, and
 * replay can compare normalized requests. Checked on every committed session at its requests, and
 * on [SessionSamples] (compaction, reasoning drops, images, side requests, every screen event) at
 * every point, also with a key in its wire headers (N5).
 */
class NormalizerCommutesTest {
    /** A committed fixture's session with ids no normalizer would choose, as a live recording has them. */
    private fun raw(name: String): String =
        rawIds(Normalizer.lines(Tokens.materializeSession(fixture(name).session.text)).joinToString("") { "$it\n" })

    /** [records] of [SessionSamples] written by the current codec, with ids no normalizer would choose. */
    private fun raw(records: List<SessionRecord>): String {
        val codec = SessionCodec()
        val header = codec.encodeHeader(SessionSamples.header.copy(version = codec.currentVersion))
        return rawIds((listOf(header) + records.map(codec::encode)).joinToString("") { "$it\n" })
    }

    private fun rawIds(text: String): String =
        RAW_IDS.entries.fold(text) { acc, (concrete, raw) -> acc.replace("\"$concrete\"", "\"$raw\"") }

    /** A request at every point of [records] from its first `request/header` on. */
    private fun everyPoint(records: List<SessionRecord>): List<Long> =
        records.dropWhile { it.event !is RequestHeader }.map { it.seq }

    private fun fixture(name: String) = ReplayFiles.load(SnapshotMode.fixtures.resolve(name))

    /**
     * The request the log rebuilds at each of [sent] (the seqs the requests were sent at), as
     * `ReplayChecker` rebuilds it, written as `requests.expected.jsonl` holds them.
     */
    private fun derived(
        log: SessionLog,
        sent: List<Long>,
    ): String {
        val requests =
            sent.mapIndexed { n, seq ->
                val prefix = log.records.filter { it.seq <= seq }
                val header = prefix.last { it.event is RequestHeader }.event as RequestHeader
                val model = ModelRef(header.provider, header.model, header.protocol)
                RecordedRequest(n + 1, seq, model, header.toRequest(deriveMessages(prefix)))
            }
        return RecordedRequests.encode(requests)
    }

    private fun read(text: String): SessionLog = SessionCodec().read(text.toByteArray())

    /** Derive then normalize: the requests of the raw log, normalized after the raw log. */
    private fun deriveThenNormalize(
        raw: String,
        sent: List<Long>,
        secrets: List<String> = emptyList(),
    ): String {
        val normalizer = Normalizer(secrets)
        normalizer.session(raw)
        return normalizer.requests(derived(read(raw), sent))
    }

    /** Normalize then derive: the requests of the normalized log, made concrete and normalized again. */
    private fun normalizeThenDerive(
        raw: String,
        sent: List<Long>,
        secrets: List<String> = emptyList(),
    ): String {
        val normalized = Normalizer(secrets).session(raw)
        val concrete = Tokens.materializeSession(normalized)
        val normalizer = Normalizer(secrets)
        normalizer.session(concrete)
        return normalizer.requests(derived(read(concrete), sent))
    }

    private fun sent(name: String): List<Long> = fixture(name).requests.map { it.logSeq }

    @Test
    fun `the raw sessions really have other ids`() {
        assertAll(
            AuthoredScenarios.ALL.map { source ->
                {
                    val raw = raw(source.name)
                    assertNotEquals(Tokens.materializeSession(Normalizer().session(raw)), raw, source.name)
                }
            },
        )
    }

    @Test
    fun `normalizing and deriving commute on every committed session`() {
        assertAll(
            AuthoredScenarios.ALL.map { source ->
                {
                    val (raw, sent) = raw(source.name) to sent(source.name)
                    assertEquals(deriveThenNormalize(raw, sent), normalizeThenDerive(raw, sent), source.name)
                }
            },
        )
    }

    @Test
    fun `the sample session has the shapes no committed fixture has`() {
        val events = SessionSamples.records.map { it.event }
        val messages = events.filterIsInstance<UserMessage>().flatMap { it.parts }
        assertAll(
            { assertTrue(events.any { it is CompactionSummary }, "compaction/summary") },
            { assertTrue(events.any { it is CompactionPrune }, "compaction/prune") },
            { assertTrue(events.any { it is ReasoningDropped }, "context/reasoning-dropped") },
            { assertTrue(messages.any { it is Part.Image }, "an image the user sent") },
            { assertTrue(events.any { it is SideRequest }, "side/request") },
            {
                assertNotEquals(
                    Tokens.materializeSession(Normalizer().session(raw(SessionSamples.records))),
                    raw(SessionSamples.records),
                )
            },
        )
    }

    @Test
    fun `normalizing and deriving commute on the sample session, at every point`() {
        val raw = raw(SessionSamples.records)
        val sent = everyPoint(SessionSamples.records)
        assertEquals(deriveThenNormalize(raw, sent), normalizeThenDerive(raw, sent))
    }

    @Test
    fun `a key in the wire headers becomes its placeholder in either order (N5)`() {
        val records =
            SessionSamples.records.map { record ->
                val header = record.event as? RequestHeader ?: return@map record
                record.copy(
                    event =
                        header.copy(
                            wire =
                                header.wire.copy(
                                    headers =
                                        header.wire.headers + (KEY_HEADER to KEY),
                                ),
                        ),
                )
            }
        val raw = raw(records)
        val sent = everyPoint(records)
        val requests = deriveThenNormalize(raw, sent, listOf(KEY))
        val session = Normalizer(listOf(KEY)).session(raw)
        assertAll(
            { assertTrue(KEY in raw, "the raw log holds the key") },
            { assertEquals(requests, normalizeThenDerive(raw, sent, listOf(KEY))) },
            { assertFalse(KEY in requests, "the key is not in the requests") },
            { assertFalse(KEY in session, "the key is not in the session") },
            {
                assertTrue(
                    "\"$KEY_HEADER\":\"${Credentials.placeholder(1)}\"" in requests,
                    "the placeholder is in the requests",
                )
            },
            {
                assertTrue(
                    "\"$KEY_HEADER\":\"${Credentials.placeholder(1)}\"" in session,
                    "the placeholder is in the session",
                )
            },
        )
    }

    private companion object {
        /** A key in a wire header; low entropy, so no scanner takes it for a real one. */
        const val KEY = "fake-key-fake-key-fake-key"

        /**
         * A header whose name `WireProfile` lets through (no credential word in it), carrying a key
         * as a gateway's tenant header or a misconfigured profile would.
         */
        const val KEY_HEADER = "x-gateway-tenant"

        /** Concrete fixture ids and the live-looking ids put in their place, out of first-occurrence order. */
        val RAW_IDS: Map<String, String> =
            mapOf(
                "call-1" to "toolu_01Zq",
                "call-2" to "call_A7",
                "call-3" to "0003-tap",
                "task-1" to "task-7f3a",
                "session-1" to "s-2026-11-13",
                "prompt-1" to "prompt-29",
            )
    }
}
