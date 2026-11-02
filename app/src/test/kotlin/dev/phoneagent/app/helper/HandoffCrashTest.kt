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

/**
 * The app process dies in the middle of admitting a helper: what was saved stays, nothing after it
 * runs, and the helper never gets its reply. A new app process then starts on the same storage.
 * Whichever helper is still running, and was not told to stop, must be able to hand off again.
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

        /** The first save while admitting, whatever it writes. */
        val FIRST_SAVE: (NonceSlots, NonceSlots) -> Boolean = { _, _ -> true }

        /** The save that hands out a new resume nonce. */
        val NEW_RESUME_SAVED: (NonceSlots, NonceSlots) -> Boolean =
            { before, after -> after.resume != null && after.resume != before.resume }
    }
}
