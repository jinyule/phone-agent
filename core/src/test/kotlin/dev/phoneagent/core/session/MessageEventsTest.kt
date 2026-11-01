package dev.phoneagent.core.session

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** B5 and B6: the events behind dropping reasoning and the interrupted-prefix rule. */
class MessageEventsTest {
    private val p = SessionSamples

    @Test
    fun `an interrupted reply holds what the user saw, so it is never empty`() {
        assertThrows<IllegalArgumentException> {
            AssistantMessage(1, p.provider, p.model, emptyList(), null, interrupted = true)
        }
        assertEquals(
            emptyList<Part>(),
            AssistantMessage(1, p.provider, p.model, emptyList(), StopReason.END_TURN).parts,
            "a complete reply may be empty; the loop judges it",
        )
    }

    @Test
    fun `reasoning drops point back into the log and are not ignorable`() =
        runTest {
            assertEquals(false, SessionEventTypes.isIgnorable("context/reasoning-dropped"))
            assertThrows<IllegalArgumentException> { ReasoningDropped(upToSeq = 0) }
            val store =
                SessionStore.create(
                    SessionFiles(InMemoryStoragePort(), StoragePath("s")),
                    p.session,
                    p.task,
                    p.start,
                    Clock { 0 },
                )
            val reply =
                store
                    .append(
                        AssistantMessage(1, p.provider, p.model, listOf(p.reasoning), StopReason.END_TURN),
                    ).seq
            assertThrows<IllegalArgumentException>("must point to an earlier record") {
                store.append(ReasoningDropped(upToSeq = reply + 1))
            }
            assertEquals(ReasoningDropped(reply), store.append(ReasoningDropped(upToSeq = reply)).event)
        }
}
