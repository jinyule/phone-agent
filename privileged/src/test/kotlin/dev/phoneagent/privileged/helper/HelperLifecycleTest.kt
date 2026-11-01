package dev.phoneagent.privileged.helper

import dev.phoneagent.privileged.helper.HelperLifecycle.Effect
import dev.phoneagent.privileged.helper.HelperLifecycle.Event
import dev.phoneagent.privileged.helper.HelperLifecycle.Outcome
import dev.phoneagent.privileged.helper.HelperLifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HelperLifecycleTest {
    private val launch = "a".repeat(64)
    private val resume1 = "b".repeat(64)
    private val resume2 = "c".repeat(64)
    private val grace = 10_000L
    private val delays = listOf(0L, 1_000L, 3_000L)

    private fun lifecycle() = HelperLifecycle(launch, grace, delays)

    /** Started and admitted as session 1 with [resume1]. */
    private fun attached(): HelperLifecycle =
        lifecycle().apply {
            handle(Event.Start)
            handle(Event.Delivered(Outcome.Accepted(1, resume1)))
        }

    /** Session 1 died and its grace expired with the app not running. */
    private fun idle(): HelperLifecycle =
        attached().apply {
            handle(Event.AppDied(1, appGenuine = true))
            handle(Event.GraceExpired(1, appRunning = false))
        }

    private val exitOk = listOf(Effect.ReleaseAll, Effect.Exit(HelperLifecycle.EXIT_OK, ""))

    private fun List<Effect>.withoutWhy() = map { if (it is Effect.Exit) it.copy(why = "") else it }

    @Test
    fun startDeliversTheLaunchNonce() {
        val l = lifecycle()
        assertEquals(listOf(Effect.Deliver(launch)), l.handle(Event.Start))
        assertEquals(State.Delivering(launch, 0, first = true), l.state)
        assertEquals("a second start is ignored", emptyList<Effect>(), l.handle(Event.Start))
    }

    @Test
    fun acceptanceAttachesWithTheResumeNonce() {
        val l = attached()
        assertEquals(State.Attached(1, resume1), l.state)
    }

    @Test
    fun rejectionReleasesAndExits() {
        val l = lifecycle()
        l.handle(Event.Start)
        assertEquals(exitOk, l.handle(Event.Delivered(Outcome.Rejected("STALE_APK"))).withoutWhy())
        assertEquals(State.Exited, l.state)
        assertEquals(emptyList<Effect>(), l.handle(Event.AppStarted))
    }

    @Test
    fun firstDeliveryRetriesThenExitsUndelivered() {
        val l = lifecycle()
        l.handle(Event.Start)
        assertEquals(listOf(Effect.ScheduleRetry(1, 1_000)), l.handle(Event.Delivered(Outcome.Failed("busy"))))
        assertEquals(State.Backoff(launch, 1, first = true), l.state)
        assertEquals("a stale retry is ignored", emptyList<Effect>(), l.handle(Event.RetryDue(2)))
        assertEquals(listOf(Effect.Deliver(launch)), l.handle(Event.RetryDue(1)))
        assertEquals(listOf(Effect.ScheduleRetry(2, 3_000)), l.handle(Event.Delivered(Outcome.Failed("busy"))))
        assertEquals(listOf(Effect.Deliver(launch)), l.handle(Event.RetryDue(2)))
        assertEquals(
            listOf(Effect.ReleaseAll, Effect.Exit(HelperLifecycle.EXIT_UNDELIVERED, "")),
            l.handle(Event.Delivered(Outcome.Failed("busy"))).withoutWhy(),
        )
        assertEquals(State.Exited, l.state)
    }

    @Test
    fun appDeathStartsTheGraceTimer() {
        val l = attached()
        assertEquals(listOf(Effect.ScheduleGrace(1, grace)), l.handle(Event.AppDied(1, appGenuine = true)))
        assertEquals(State.Orphaned(1, resume1), l.state)
    }

    @Test
    fun deathOfAnotherSessionIsIgnored() {
        val l = attached()
        assertEquals(emptyList<Effect>(), l.handle(Event.AppDied(7, appGenuine = true)))
        assertEquals(State.Attached(1, resume1), l.state)
    }

    @Test
    fun deathOfAnUninstalledOrResignedAppExits() {
        val l = attached()
        assertEquals(exitOk, l.handle(Event.AppDied(1, appGenuine = false)).withoutWhy())
        assertEquals(State.Exited, l.state)
    }

    @Test
    fun graceExpiryReleasesAndWaits() {
        val l = attached()
        l.handle(Event.AppDied(1, appGenuine = true))
        assertEquals(listOf(Effect.ReleaseAll), l.handle(Event.GraceExpired(1, appRunning = false)))
        assertEquals(State.Idle(resume1), l.state)
    }

    @Test
    fun graceExpiryWithTheAppRunningReleasesThenHandsOffAgain() {
        val l = attached()
        l.handle(Event.AppDied(1, appGenuine = true))
        assertEquals(
            listOf(Effect.ReleaseAll, Effect.Deliver(resume1)),
            l.handle(Event.GraceExpired(1, appRunning = true)),
        )
        assertEquals(State.Delivering(resume1, 0, first = false), l.state)
    }

    @Test
    fun appReturningWithinGraceReleasesBeforeTheNextHandoff() {
        val l = attached()
        l.handle(Event.AppDied(1, appGenuine = true))
        assertEquals(listOf(Effect.ReleaseAll, Effect.Deliver(resume1)), l.handle(Event.AppStarted))
    }

    @Test
    fun aLateGraceTimerNeverReleasesTheNextSession() {
        val l = attached()
        l.handle(Event.AppDied(1, appGenuine = true))
        l.handle(Event.AppStarted)
        l.handle(Event.Delivered(Outcome.Accepted(2, resume2)))
        assertEquals(emptyList<Effect>(), l.handle(Event.GraceExpired(1, appRunning = true)))
        assertEquals(State.Attached(2, resume2), l.state)
    }

    @Test
    fun aLateGraceTimerDoesNotCutTheNextGraceShort() {
        val l = attached()
        l.handle(Event.AppDied(1, appGenuine = true))
        l.handle(Event.AppStarted)
        l.handle(Event.Delivered(Outcome.Accepted(2, resume2)))
        assertEquals(listOf(Effect.ScheduleGrace(2, grace)), l.handle(Event.AppDied(2, appGenuine = true)))
        assertEquals(
            "session 1's timer fires while session 2 is orphaned",
            emptyList<Effect>(),
            l.handle(Event.GraceExpired(1, appRunning = false)),
        )
        assertEquals(State.Orphaned(2, resume2), l.state)
        assertEquals(listOf(Effect.ReleaseAll), l.handle(Event.GraceExpired(2, appRunning = false)))
        assertEquals(State.Idle(resume2), l.state)
    }

    @Test
    fun appStartingAfterGraceHandsOffWithTheResumeNonce() {
        val l = idle()
        assertEquals(listOf(Effect.Deliver(resume1)), l.handle(Event.AppStarted))
        assertEquals(emptyList<Effect>(), l.handle(Event.Delivered(Outcome.Accepted(2, resume2))))
        assertEquals(State.Attached(2, resume2), l.state)
    }

    @Test
    fun failedRedeliveryGoesBackToWaitingInsteadOfExiting() {
        val l = idle()
        l.handle(Event.AppStarted)
        l.handle(Event.Delivered(Outcome.Failed("busy")))
        l.handle(Event.RetryDue(1))
        l.handle(Event.Delivered(Outcome.Failed("busy")))
        l.handle(Event.RetryDue(2))
        assertEquals(emptyList<Effect>(), l.handle(Event.Delivered(Outcome.Failed("busy"))))
        assertEquals(State.Idle(resume1), l.state)
        assertEquals(listOf(Effect.Deliver(resume1)), l.handle(Event.AppStarted))
    }

    @Test
    fun appStartedIsIgnoredWhileConnectedOrDelivering() {
        val created = lifecycle()
        assertEquals(emptyList<Effect>(), created.handle(Event.AppStarted))
        val delivering = lifecycle().apply { handle(Event.Start) }
        assertEquals(emptyList<Effect>(), delivering.handle(Event.AppStarted))
        val backoff =
            lifecycle().apply {
                handle(Event.Start)
                handle(Event.Delivered(Outcome.Failed("busy")))
            }
        assertEquals(emptyList<Effect>(), backoff.handle(Event.AppStarted))
        assertEquals(emptyList<Effect>(), attached().handle(Event.AppStarted))
    }

    @Test
    fun outOfStateEventsAreIgnored() {
        val l = attached()
        assertEquals(emptyList<Effect>(), l.handle(Event.Delivered(Outcome.Accepted(9, resume2))))
        assertEquals(emptyList<Effect>(), l.handle(Event.RetryDue(1)))
        assertEquals(emptyList<Effect>(), l.handle(Event.GraceExpired(1, appRunning = false)))
        assertEquals(State.Attached(1, resume1), l.state)
    }

    @Test
    fun shutdownReleasesAndExitsFromAnyState() {
        for (l in listOf(lifecycle(), lifecycle().apply { handle(Event.Start) }, attached(), idle())) {
            assertEquals(exitOk, l.handle(Event.ShutdownRequested).withoutWhy())
            assertEquals(State.Exited, l.state)
            assertEquals(emptyList<Effect>(), l.handle(Event.ShutdownRequested))
        }
    }

    @Test
    fun configurationIsValidated() {
        assertThrows(IllegalArgumentException::class.java) { HelperLifecycle(launch, 0, delays) }
        assertThrows(IllegalArgumentException::class.java) { HelperLifecycle(launch, grace, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { HelperLifecycle(launch, grace, listOf(0, -1)) }
    }

    @Test
    fun logsNeverContainNonces() {
        val states =
            listOf(
                State.Created(launch),
                State.Delivering(launch, 0, true),
                State.Backoff(launch, 1, true),
                State.Attached(1, launch),
                State.Orphaned(1, launch),
                State.Idle(launch),
            )
        val texts =
            states.map { it.toString() } + Outcome.Accepted(1, launch).toString() + Effect.Deliver(launch).toString()
        texts.forEach { assertFalse(it, it.contains(launch)) }
    }
}
