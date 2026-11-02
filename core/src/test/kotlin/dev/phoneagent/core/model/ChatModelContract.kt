package dev.phoneagent.core.model

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The [ChatModel] contract every adapter test inherits (WP03, WP04), next to its own tests:
 * - [ChatModel.prepare] gives the same [RequestSettings] for the same input, one mapping version
 *   and one credential kind per instance, and the level clamped like [clampTo];
 * - [ChatModel.complete] accepts a request built from those settings and sends it once;
 * - a request with another mapping version or credential kind fails with
 *   [ModelError.InvalidRequest] before anything is sent.
 *
 * A subclass returns a fresh [Subject] from [subject]: the adapter wired to a transport that
 * answers every request with a valid final reply, and counts the requests it sent.
 */
abstract class ChatModelContract {
    /** The model under test and the number of requests its transport has sent so far. */
    class Subject(
        val model: ChatModel,
        val sentRequests: () -> Int,
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
    }
}
