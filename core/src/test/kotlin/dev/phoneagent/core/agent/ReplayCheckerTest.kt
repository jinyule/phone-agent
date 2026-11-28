@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.deriveMessages
import dev.phoneagent.core.testing.FakeChatModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** The strict replay check of the test harness fails fast and names what differs. */
class ReplayCheckerTest {
    @Test
    fun `a request the log does not rebuild names each differing field with both values`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel().answers("好了"))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            val records = SessionStore.read(harness.files).records
            val header = records.last { it.event is RequestHeader }.event as RequestHeader
            val rebuilt = header.toRequest(deriveMessages(records))
            val sent = rebuilt.copy(system = rebuilt.system + PromptBlock("extra", "未记录的系统提示"))

            val failure =
                assertThrows<AssertionError> {
                    ReplayChecker(harness.files, strict = true).wrap(FakeChatModel()).complete(sent) {}
                }
            val message = failure.message!!
            assertTrue(message.startsWith("request #1 is not rebuilt from the log:\n  system:"), message)
            assertTrue("未记录的系统提示" in message, message)
            assertTrue("messages" !in message, "only the fields that differ: $message")
        }

    @Test
    fun `a failed check in a loop coroutine fails the test at once instead of leaving it waiting`() {
        val failure =
            assertThrows<AssertionError> {
                runTest(timeout = 30.seconds) {
                    val harness = LoopHarness(this)
                    harness.loopScope.launch { throw AssertionError("request #1 differs") }
                    awaitCancellation()
                }
            }
        assertEquals("request #1 differs", failure.message)
    }
}
