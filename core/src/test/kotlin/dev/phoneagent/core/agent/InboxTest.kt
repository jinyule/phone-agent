package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.LogRecords.log
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The dual-queue inbox (D12). */
class InboxTest {
    private val recorded = mutableListOf<InboxSpliced>()

    private fun entry(
        kind: InboxEntryKind,
        text: String,
    ) = InboxEntry(kind, listOf(Part.Text(text)))

    private val followup = entry(InboxEntryKind.FOLLOWUP, "next")
    private val steer = entry(InboxEntryKind.STEER, "faster")
    private val inject = entry(InboxEntryKind.INJECT, "battery low")

    private fun inbox(vararg splices: InboxSpliced): Inbox = Inbox.restore(log(*splices)) { recorded += it }

    @Test
    fun `injected context alone does not start a turn, a steer or a followup does`() =
        runTest {
            val inbox = inbox()
            assertFalse(inbox.hasTurnWork())
            inbox.add(InboxQueue.NEXT_STEP, inject)
            assertFalse(inbox.hasTurnWork())
            assertFalse(inbox.hasSteer())
            inbox.add(InboxQueue.NEXT_STEP, steer)
            assertTrue(inbox.hasSteer())
            assertTrue(inbox.hasTurnWork())
            assertEquals(listOf(inject, steer), inbox.take(turnStart = false))
            inbox.add(InboxQueue.NEXT_TURN, followup)
            assertTrue(inbox.hasTurnWork())
            assertFalse(inbox.hasSteer())
        }

    @Test
    fun `a step takes every step entry and one followup at a turn start`() =
        runTest {
            val second = entry(InboxEntryKind.FOLLOWUP, "after that")
            val inbox = inbox()
            inbox.add(InboxQueue.NEXT_TURN, followup)
            inbox.add(InboxQueue.NEXT_TURN, second)
            inbox.add(InboxQueue.NEXT_STEP, inject)
            assertEquals(
                emptyList<InboxEntry>(),
                inbox.take(turnStart = false).filter {
                    it.kind ==
                        InboxEntryKind.FOLLOWUP
                },
            )
            assertEquals(listOf(followup), inbox.take(turnStart = true))
            assertEquals(emptyList<InboxEntry>(), inbox.take(turnStart = false), "nothing recorded for an empty take")
            assertEquals(listOf(second), inbox.take(turnStart = true))
            assertEquals(
                listOf(
                    InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(followup)),
                    InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(second)),
                    InboxSpliced(InboxQueue.NEXT_STEP, 0, listOf(inject)),
                    InboxSpliced(InboxQueue.NEXT_STEP, 1),
                    InboxSpliced(InboxQueue.NEXT_TURN, 1),
                    InboxSpliced(InboxQueue.NEXT_TURN, 1),
                ),
                recorded,
            )
        }

    @Test
    fun `the log's splices rebuild the queues`() =
        runTest {
            val inbox =
                inbox(
                    InboxSpliced(InboxQueue.NEXT_TURN, 0, listOf(followup)),
                    InboxSpliced(InboxQueue.NEXT_STEP, 0, listOf(steer, inject)),
                    InboxSpliced(InboxQueue.NEXT_STEP, 1),
                    InboxSpliced(InboxQueue.NEXT_TURN, removed = 5),
                )
            assertFalse(inbox.hasSteer(), "the steer was taken")
            assertFalse(inbox.hasTurnWork(), "removing more than queued empties the queue")
            assertEquals(listOf(inject), inbox.take(turnStart = true))
        }

    @Test
    fun `clearing records only the queues that held something`() =
        runTest {
            val inbox = inbox()
            inbox.clear()
            assertEquals(emptyList<InboxSpliced>(), recorded)
            inbox.add(InboxQueue.NEXT_STEP, inject)
            inbox.clear()
            assertEquals(InboxSpliced(InboxQueue.NEXT_STEP, removed = 1), recorded.last())
            assertFalse(inbox.hasTurnWork())
        }

    @Test
    fun `a steer cancels the registered request and blocks the next one`() =
        runTest {
            val inbox = inbox()
            val request = Job()
            assertTrue(inbox.register(request))
            inbox.add(InboxQueue.NEXT_STEP, inject)
            assertFalse(request.isCancelled, "injected context waits for the step")
            inbox.add(InboxQueue.NEXT_STEP, steer)
            assertTrue(request.isCancelled)
            assertFalse(inbox.register(Job()), "a queued steer: do not send")
            inbox.unregister(request)
            inbox.take(turnStart = false)

            val other = Job()
            assertTrue(inbox.register(other))
            inbox.unregister(Job())
            inbox.add(InboxQueue.NEXT_STEP, steer)
            assertTrue(other.isCancelled, "unregistering another job leaves this one registered")
        }
}
