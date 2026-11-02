@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionWriteException
import dev.phoneagent.core.session.TaskLevelChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The intake records each accepted command once, whole, then the finisher records the rest. */
class IntakeTest {
    private val written = mutableListOf<SessionEvent>()

    private fun intake(record: suspend (SessionEvent) -> Unit): Intake =
        Intake(Inbox(emptyList(), emptyList()) { written += it }, record) { error("no log failure expected") }

    @Test
    fun `a record cut by cancellation completes and the finisher does not record it again`() =
        runTest {
            val intake =
                intake { event ->
                    written += event
                    delay(1_000) // e.g. the write is done, the caller is still busy
                }
            val job = launch { intake.run() }
            intake.changeLevel(SecurityLevel.CAUTIOUS)
            intake.changeLevel(SecurityLevel.AUTONOMOUS)
            runCurrent()
            intake.close()
            intake.changeLevel(SecurityLevel.STANDARD)
            job.cancelAndJoin()
            assertTrue(intake.drain())

            assertEquals(
                listOf(TaskLevelChanged(SecurityLevel.CAUTIOUS), TaskLevelChanged(SecurityLevel.AUTONOMOUS)),
                written,
                "accepted before close: recorded once each; after close: ignored",
            )
        }

    @Test
    fun `a command the log refuses stays accepted for the next drain`() =
        runTest {
            var refuse = true
            val intake =
                intake { event ->
                    if (refuse) throw SessionWriteException("disk full")
                    written += event
                }
            intake.changeLevel(SecurityLevel.CAUTIOUS)
            assertFalse(intake.drain())
            refuse = false
            assertTrue(intake.drain())
            assertEquals(listOf(TaskLevelChanged(SecurityLevel.CAUTIOUS)), written)
        }
}
