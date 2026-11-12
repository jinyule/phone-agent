package dev.phoneagent.core.replay

import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.deriveMessages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * Lock (a) of WP08 design §6: normalizing commutes with deriving the requests. Deriving them from
 * the log and normalizing the result gives, byte for byte, what normalizing the log first and
 * deriving from it gives. So normalized fixtures say what the raw session said to the model, and
 * replay can compare normalized requests.
 */
class NormalizerCommutesTest {
    /** A committed fixture's session with ids no normalizer would choose, as a live recording has them. */
    private fun raw(name: String): String {
        val text = Normalizer.lines(Tokens.materializeSession(fixture(name).session.text)).joinToString("") { "$it\n" }
        return RAW_IDS.entries.fold(text) { acc, (concrete, raw) -> acc.replace("\"$concrete\"", "\"$raw\"") }
    }

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
    ): String {
        val normalizer = Normalizer()
        normalizer.session(raw)
        return normalizer.requests(derived(read(raw), sent))
    }

    /** Normalize then derive: the requests of the normalized log, made concrete and normalized again. */
    private fun normalizeThenDerive(
        raw: String,
        sent: List<Long>,
    ): String {
        val normalized = Normalizer().session(raw)
        val concrete = Tokens.materializeSession(normalized)
        val normalizer = Normalizer()
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

    private companion object {
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
