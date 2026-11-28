package dev.phoneagent.app.helper

import android.os.Bundle
import dev.phoneagent.privileged.handoff.AppBuild
import dev.phoneagent.privileged.handoff.HandoffBundles
import dev.phoneagent.privileged.handoff.HandoffContract
import dev.phoneagent.privileged.handoff.HandoffNonces
import dev.phoneagent.privileged.handoff.HandoffReject
import dev.phoneagent.privileged.handoff.HelperOffer
import dev.phoneagent.privileged.handoff.NonceSlots
import dev.phoneagent.privileged.handoff.NonceStore
import dev.phoneagent.privileged.handoff.Nonces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The app process dies in the middle of admitting a helper: what was saved stays, nothing after it
 * runs, and the helper never gets its reply. A new app process then starts on the same storage.
 * A resuming helper comes back with the nonce it presented; after a first handoff, the helper that
 * held the resume nonce current before that save, and was not told to stop, comes back, whether or
 * not an app process was connected to it. Both hold only until another admission is saved. The
 * other cases are the known gap of ADR 2026-11-01-privileged-handoff (decision 3): the `knownGap…`
 * tests pin that the helpers left are then refused, and are meant to flip once nonces are kept per
 * pid (plan (a) in that ADR's Alternatives).
 */
@RunWith(RobolectricTestRunner::class)
class HandoffCrashTest {
    /** The end of the app process; an Error, so no `catch (RuntimeException)` on the way stops it. */
    private class AppDied : Error("the app process died here")

    /** Storage that outlives the app process; [diesAfter] picks the save after which the process dies. */
    private class DurableStore : NonceStore {
        var slots = NonceSlots()
        var diesAfter: ((before: NonceSlots, after: NonceSlots) -> Boolean)? = null

        override fun load(): NonceSlots = slots

        override fun save(slots: NonceSlots) {
            val before = this.slots
            this.slots = slots
            if (diesAfter?.invoke(before, slots) == true) {
                diesAfter = null
                throw AppDied()
            }
        }
    }

    private val shell = HandoffContract.SHELL_UID
    private val pid = FakeHelper.PID
    private val build = AppBuild(versionCode = 10001, apkPath = "/data/app/~~a==/dev.phoneagent-b==/base.apk")
    private val store = DurableStore()

    /** A new app process on [store]. */
    private fun process() = HelperClient(HandoffNonces(store, { NOW }, { BOOT }), { build })

    private fun HelperClient.armed(): String =
        Nonces.random().also { assertTrue(arm(Nonces.sha256Hex(it), shell).getBoolean("accepted")) }

    private fun extras(
        helper: FakeHelper,
        nonce: String,
        helperPid: Int = pid,
    ): Bundle =
        HandoffBundles.offer(helper, HelperOffer(shell, helperPid, true, nonce, build.apkPath, build.versionCode))

    private fun Bundle.nextNonce(): String = (HandoffBundles.readReply(this) as HandoffBundles.Reply.Accepted).nextNonce

    private fun Bundle.reject(): HandoffReject? =
        takeUnless { it.getBoolean(HandoffContract.KEY_ACCEPTED) }
            ?.getString(HandoffContract.KEY_REASON)
            ?.let(HandoffReject::valueOf)

    /** Runs [offer] in a process that dies at [diesAfter]; true when it died before replying. */
    private fun diesDuring(
        diesAfter: (NonceSlots, NonceSlots) -> Boolean,
        offer: () -> Bundle,
    ): Boolean {
        store.diesAfter = diesAfter
        val failure = runCatching(offer).exceptionOrNull()
        store.diesAfter = null
        if (failure != null && failure !is AppDied) throw failure
        return failure != null
    }

    /** The helper that was connected (pid [pid], holding [resume]) and a new one that replaced it. */
    private fun replaced(diesAfter: (NonceSlots, NonceSlots) -> Boolean) {
        val first = process()
        val old = FakeHelper()
        val resume = first.offer(extras(old, first.armed()), shell, pid).nextNonce()
        val new = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        val newNonce = first.armed()

        assertTrue(
            "the app died",
            diesDuring(diesAfter) { first.offer(extras(new, newNonce, pid + 1), shell, pid + 1) },
        )
        assertFalse("the old helper was not told to stop", "shutdown" in old.calls)

        val restarted = process()
        assertEquals(
            "the new helper's one-time nonce is used up",
            HandoffReject.BAD_NONCE,
            restarted.offer(extras(new, newNonce, pid + 1), shell, pid + 1).reject(),
        )
        assertNull("the old helper hands off again", restarted.offer(extras(old, resume), shell, pid).reject())
        assertEquals(HelperState.Connected(old, pid), restarted.state)
    }

    /** The connected helper handing off again to a restarted app. */
    private fun resumed(diesAfter: (NonceSlots, NonceSlots) -> Boolean) {
        val helper = FakeHelper()
        val resume = process().let { it.offer(extras(helper, it.armed()), shell, pid).nextNonce() }

        val restarted = process()
        assertTrue("the app died", diesDuring(diesAfter) { restarted.offer(extras(helper, resume), shell, pid) })

        val again = process()
        assertNull("the helper hands off again", again.offer(extras(helper, resume), shell, pid).reject())
        assertEquals(HelperState.Connected(helper, pid), again.state)
    }

    /**
     * X5: a helper holds the resume nonce, its app process is gone, and the restarted one is
     * connected to nothing. A new helper's first handoff is saved and the app dies before replying.
     * The orphan still comes back: what counts is the nonce it holds, not a connection.
     */
    private fun orphaned(diesAfter: (NonceSlots, NonceSlots) -> Boolean) {
        val orphan = FakeHelper()
        val resume = process().let { it.offer(extras(orphan, it.armed()), shell, pid).nextNonce() }
        val second = process()
        assertEquals("nothing is connected to the restarted app", HelperState.NotConnected, second.state)
        val new = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        val newNonce = second.armed()

        assertTrue(
            "the app died",
            diesDuring(diesAfter) { second.offer(extras(new, newNonce, pid + 1), shell, pid + 1) },
        )

        val third = process()
        assertEquals(
            "the new helper's one-time nonce is used up",
            HandoffReject.BAD_NONCE,
            third.offer(extras(new, newNonce, pid + 1), shell, pid + 1).reject(),
        )
        assertNull("the orphan hands off again", third.offer(extras(orphan, resume), shell, pid).reject())
        assertEquals(HelperState.Connected(orphan, pid), third.state)
    }

    @Test
    fun anOrphanComesBackWhenTheAppDiesRightAfterSavingANewHelpersResumeNonce() = orphaned(NEW_RESUME_SAVED)

    @Test
    fun anOrphanComesBackWhenTheAppDiesAfterTheFirstSaveOfANewHelpersAdmission() = orphaned(FIRST_SAVE)

    @Test
    fun aReplacedHelperComesBackWhenTheAppDiesRightAfterSavingTheNewResumeNonce() = replaced(NEW_RESUME_SAVED)

    @Test
    fun aReplacedHelperComesBackWhenTheAppDiesAfterTheFirstSaveOfTheAdmission() = replaced(FIRST_SAVE)

    @Test
    fun aResumingHelperComesBackWhenTheAppDiesRightAfterSavingTheNewResumeNonce() = resumed(NEW_RESUME_SAVED)

    @Test
    fun aResumingHelperComesBackWhenTheAppDiesAfterTheFirstSaveOfTheAdmission() = resumed(FIRST_SAVE)

    @Test
    fun aResumingHelperComesBackWhenItsReplyIsLost() {
        val helper = FakeHelper()
        val resume = process().let { it.offer(extras(helper, it.armed()), shell, pid).nextNonce() }
        process().offer(extras(helper, resume), shell, pid).nextNonce() // The reply never reaches the helper.

        val again = process()
        assertNull("the helper hands off again", again.offer(extras(helper, resume), shell, pid).reject())
    }

    @Test
    fun theReplacedHelperIsToldToStopOnlyAsTheLastStepBeforeTheReply() {
        val client = process()
        val old = FakeHelper()
        client.offer(extras(old, client.armed()), shell, pid).nextNonce()
        val stoppedWhenListenersRan = mutableListOf<Boolean>()
        client.addListener { stoppedWhenListenersRan += "shutdown" in old.calls }
        val new = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))

        client.offer(extras(new, client.armed(), pid + 1), shell, pid + 1).nextNonce()

        assertEquals(listOf(false), stoppedWhenListenersRan)
        assertEquals("shutdown", old.calls.last())
    }

    /** Bounds a barrier wait so a broken test fails instead of hanging; it never paces the test. */
    private fun CountDownLatch.awaitBounded() = assertTrue("barrier timed out", await(WAIT_SECONDS, TimeUnit.SECONDS))

    /** Waits until [offering] waits for the handoff in progress, or has finished without waiting. */
    private fun awaitWaitingOrDone(offering: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (offering.isAlive && offering.state != Thread.State.BLOCKED) {
            assertTrue("the second handoff neither finished nor waited", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    /**
     * Two new helpers hand off and neither gets its reply: the second arrives after the first one's
     * save, before the connected helper was told to stop, and the app dies on the second one's save
     * (or while it waits). The connected helper was never told to stop, so it hands off again.
     */
    @Test
    fun aSecondNewHelperBeforeTheFirstRetiresTheOldOneDoesNotLockTheOldOneOut() {
        val first = process()
        val old = FakeHelper()
        val resume = first.offer(extras(old, first.armed()), shell, pid).nextNonce()
        val firstSaved = CountDownLatch(1)
        val release = CountDownLatch(1)
        first.addListener { changed ->
            if (changed is HelperState.Connected && changed.pid == pid + 1) {
                firstSaved.countDown()
                release.awaitBounded()
            }
        }
        val one = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        val oneNonce = first.armed()
        val offeringOne = thread { runCatching { first.offer(extras(one, oneNonce, pid + 1), shell, pid + 1) } }
        firstSaved.awaitBounded()
        val two = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 2))
        val twoNonce = first.armed()
        store.diesAfter = FIRST_SAVE
        val offeringTwo = thread { runCatching { first.offer(extras(two, twoNonce, pid + 2), shell, pid + 2) } }
        awaitWaitingOrDone(offeringTwo)
        store.diesAfter = null // The app process is gone from here on.

        assertFalse("the old helper was not told to stop", "shutdown" in old.calls)
        val restarted = process()
        assertNull("the old helper hands off again", restarted.offer(extras(old, resume), shell, pid).reject())
        assertEquals(HelperState.Connected(old, pid), restarted.state)

        release.countDown()
        listOf(offeringOne, offeringTwo).forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
    }

    /**
     * Known gap, the simplest case (no concurrency): the app dies after saving a helper's first
     * handoff, before the reply goes out. The helper retries with its one-time nonce, which is used
     * up, so it is refused and nothing is connected; it then releases and exits
     * (HelperLifecycleTest.aHelperRefusedWithBadNonceReleasesThenExitsWhetherResumingOrRetrying).
     * Keeping nonces per pid would flip this test on purpose.
     */
    @Test
    fun knownGapAFirstHandoffWhoseReplyIsLostIsRefusedOnRetry() {
        val helper = FakeHelper()
        val app = process()
        val nonce = app.armed()
        assertTrue("the app died", diesDuring(FIRST_SAVE) { app.offer(extras(helper, nonce), shell, pid) })

        val restarted = process()
        assertEquals(HandoffReject.BAD_NONCE, restarted.offer(extras(helper, nonce), shell, pid).reject())
        assertEquals(HelperState.NotConnected, restarted.state)
    }

    /**
     * Known gap, the order of the r4 review: the connected helper resumes to a restarted app and
     * its reply is lost; a new helper is admitted next and the app dies right after saving it,
     * before the resumed one was told to stop. Neither the resume nonce the old helper still holds
     * nor the new helper's one-time nonce is live any more: both are refused and nothing is
     * connected. Keeping nonces per pid would flip this test on purpose.
     */
    @Test
    fun knownGapALostResumeReplyAndANewHelperSavedNextLockOutBoth() {
        val old = FakeHelper()
        val held = process().let { it.offer(extras(old, it.armed()), shell, pid).nextNonce() }
        val app = process()
        app.offer(extras(old, held), shell, pid).nextNonce() // The reply never reaches the old helper.
        val new = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        val newNonce = app.armed()
        assertTrue(
            "the app died",
            diesDuring(NEW_RESUME_SAVED) { app.offer(extras(new, newNonce, pid + 1), shell, pid + 1) },
        )
        assertFalse("the old helper was not told to stop", "shutdown" in old.calls)

        val restarted = process()
        assertEquals(HandoffReject.BAD_NONCE, restarted.offer(extras(old, held), shell, pid).reject())
        assertEquals(HandoffReject.BAD_NONCE, restarted.offer(extras(new, newNonce, pid + 1), shell, pid + 1).reject())
        assertEquals(HelperState.NotConnected, restarted.state)
    }

    @Test
    fun theOlderResumeNonceStopsWorkingOnceTheHelperUsedTheNewerOne() {
        val helper = FakeHelper()
        val first = process().let { it.offer(extras(helper, it.armed()), shell, pid).nextNonce() }
        val second = process().offer(extras(helper, first), shell, pid).nextNonce()
        process().offer(extras(helper, second), shell, pid).nextNonce()

        assertEquals(HandoffReject.BAD_NONCE, process().offer(extras(helper, first), shell, pid).reject())
    }

    @Test
    fun aResumeNonceAdmitsOnlyTheHelperProcessItWasGivenTo() {
        val helper = FakeHelper()
        val resume = process().let { it.offer(extras(helper, it.armed()), shell, pid).nextNonce() }
        val other = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))

        val restarted = process()
        assertEquals(HandoffReject.BAD_NONCE, restarted.offer(extras(other, resume, pid + 1), shell, pid + 1).reject())
        assertEquals("refused before its Binder is touched", emptyList<String>(), other.calls)
        assertNull(restarted.offer(extras(helper, resume), shell, pid).reject())
    }

    private companion object {
        const val NOW = 1_000L
        const val BOOT = 7
        const val WAIT_SECONDS = 10L

        /** The first save while admitting, whatever it writes. */
        val FIRST_SAVE: (NonceSlots, NonceSlots) -> Boolean = { _, _ -> true }

        /** The save that hands out a new resume nonce. */
        val NEW_RESUME_SAVED: (NonceSlots, NonceSlots) -> Boolean =
            { before, after -> after.resume != null && after.resume != before.resume }
    }
}
