package dev.phoneagent.core.model

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The [ChatModel] contract every adapter test inherits (WP03, WP04), next to its own tests:
 * - the subject's model declares a strict subset of the reasoning levels, so that some level
 *   clamps to another and the two checks below can fail ([Subject]);
 * - [ChatModel.prepare] gives the same [RequestSettings] for the same input, one mapping version
 *   and one credential kind per instance, and the level clamped like [clampTo];
 * - the wire maps the clamped level: preparing the effective level gives the same wire;
 * - [ChatModel.complete] changes neither the settings already given out, the ones it sends
 *   included (in place included), nor the ones [ChatModel.prepare] gives afterwards;
 * - settings already given out, and later ones, do not follow a change of the live settings the
 *   model was built from ([Subject.mutateLiveSettings]);
 * - [ChatModel.complete] accepts a request built from those settings and sends it once;
 * - a request with another mapping version or credential kind fails with
 *   [ModelError.InvalidRequest] before anything is sent.
 *
 * A subclass returns a fresh [Subject] from [subject]: the adapter wired to a transport that
 * answers every request with a valid final reply, and counts the requests it sent.
 *
 * Passing these checks does not show that the wire bytes are right: each adapter still needs its
 * own protocol mapping goldens (WP03, WP04).
 */
abstract class ChatModelContract {
    /**
     * The model under test, the number of requests its transport has sent so far, and a hook that
     * changes the live settings the model was built from (the user editing the provider profile),
     * or `null` when the adapter reads none.
     *
     * The model's `info.capabilities.reasoningLevels` is a strict subset of [ReasoningLevel]: not
     * every level, and not `null` (unknown), since then no level clamps and mapping the level asked
     * for instead of the clamped one goes unnoticed. Build the adapter under test with such
     * capabilities even when the real model's are unknown; the contract fails otherwise.
     */
    class Subject(
        val model: ChatModel,
        val sentRequests: () -> Int,
        val mutateLiveSettings: (() -> Unit)? = null,
    )

    /** A fresh subject; called once per check. */
    protected abstract fun subject(): Subject

    /** The neutral request the checks send with [settings]; override when the adapter needs more. */
    protected open fun request(settings: RequestSettings): ChatRequest =
        ChatRequest(
            system = listOf(PromptBlock("contract", "Reply with ok.")),
            tools = emptyList(),
            messages = listOf(Message(Role.USER, listOf(Part.Text("ok?")))),
            wire = settings.wire,
            maxOutputTokens = OUTPUT_CAP,
            reasoning = settings.reasoning,
        )

    @Test
    fun `the subject declares reasoning levels that make some level clamp`() {
        val supported =
            subject()
                .model.info.capabilities.reasoningLevels
        assertTrue(
            LEVELS.any { it != null && it.clampTo(supported) != it },
            "the subject's model must declare a strict, non-null subset of reasoning levels, got $supported",
        )
    }

    @Test
    fun `prepare gives the same settings for the same input, with one mapping version and credential kind`() {
        val model = subject().model
        LEVELS.forEach { level -> assertEquals(model.prepare(level), model.prepare(level), "level $level") }
        val wires = LEVELS.map { model.prepare(it).wire }
        assertEquals(1, wires.map { it.mappingVersion }.toSet().size, "mapping versions $wires")
        assertEquals(1, wires.map { it.credential }.toSet().size, "credential kinds $wires")
    }

    @Test
    fun `prepare clamps the level to the model's reasoning levels`() {
        val model = subject().model
        val supported = model.info.capabilities.reasoningLevels
        LEVELS.forEach { level ->
            assertEquals(level?.clampTo(supported), model.prepare(level).reasoning, "level $level")
        }
    }

    @Test
    fun `prepare maps the clamped level, so preparing the effective level gives the same wire`() {
        val model = subject().model
        LEVELS.forEach { level ->
            val settings = model.prepare(level)
            assertEquals(model.prepare(settings.reasoning).wire, settings.wire, "level $level")
        }
    }

    @Test
    fun `complete changes neither the settings given out nor the ones prepared afterwards`() =
        runTest {
            val subject = subject()
            val given = LEVELS.map { subject.model.prepare(it) }
            val frozen = given.map { it.copied() }
            LEVELS.zip(given).forEach { (level, settings) ->
                subject.model.complete(request(settings))
                assertEquals(
                    frozen,
                    given,
                    "settings given out, the ones sent included, after complete at level $level",
                )
                assertEquals(
                    frozen,
                    LEVELS.map { subject.model.prepare(it) },
                    "settings prepared after complete at level $level",
                )
            }
        }

    @Test
    fun `settings share nothing with the live settings the model was built from`() {
        val subject = subject()
        val mutate = subject.mutateLiveSettings
        assumeTrue(mutate != null, "the adapter reads no live settings")
        val given = LEVELS.map { subject.model.prepare(it) }
        val frozen = given.map { it.copied() }
        mutate?.invoke()
        assertEquals(frozen, given, "settings given out before the change")
        assertEquals(frozen, LEVELS.map { subject.model.prepare(it) }, "settings given out after the change")
    }

    @Test
    fun `complete accepts a request built from what prepare gave`() =
        runTest {
            val subject = subject()
            LEVELS.forEach { level ->
                val before = subject.sentRequests()
                subject.model.complete(request(subject.model.prepare(level)))
                assertEquals(before + 1, subject.sentRequests(), "level $level: one request sent")
            }
        }

    @Test
    fun `complete refuses another mapping version or credential kind before sending anything`() =
        runTest {
            val subject = subject()
            val settings = subject.model.prepare(ReasoningLevel.MEDIUM)
            val own = settings.wire
            val foreign =
                listOf(own.copy(mappingVersion = own.mappingVersion + 1)) +
                    CredentialKind.entries.filter { it != own.credential }.map { own.copy(credential = it) }
            foreign.forEach { wire ->
                assertThrows<ModelError.InvalidRequest>("$wire") {
                    subject.model.complete(request(settings.copy(wire = wire)))
                }
            }
            assertEquals(0, subject.sentRequests(), "nothing was sent")
        }

    private companion object {
        val LEVELS: List<ReasoningLevel?> = listOf(null) + ReasoningLevel.entries
        const val OUTPUT_CAP = 16

        /** These settings with their own copies of the wire's maps, the only mutable parts. */
        fun RequestSettings.copied(): RequestSettings =
            copy(wire = wire.copy(headers = wire.headers.toMap(), options = wire.options.toMap()))
    }
}
