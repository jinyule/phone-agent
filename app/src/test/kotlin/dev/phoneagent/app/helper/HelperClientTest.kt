package dev.phoneagent.app.helper

import android.os.Bundle
import android.os.RemoteException
import dev.phoneagent.privileged.PrivilegedProtocol
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class HelperClientTest {
    private class MemoryStore : NonceStore {
        var slots = NonceSlots()

        override fun load(): NonceSlots = slots

        override fun save(slots: NonceSlots) {
            this.slots = slots
        }
    }

    private val shell = HandoffContract.SHELL_UID
    private val pid = FakeHelper.PID
    private val build = AppBuild(versionCode = 10001, apkPath = "/data/app/~~a==/dev.phoneagent-b==/base.apk")
    private val store = MemoryStore()
    private val launches = mutableListOf<HelperState.NeedsLaunch>()
    private var now = 1_000L
    private val client = newClient()

    private fun newClient() = HelperClient(HandoffNonces(store, { now }, { 7 }), { build }) { launches += it }

    /** Arms a fresh nonce on [on] the way `scripts/start-helper.sh` does and returns it. */
    private fun armed(on: HelperClient = client): String =
        Nonces.random().also {
            assertTrue(on.arm(Nonces.sha256Hex(it), shell).getBoolean("accepted"))
        }

    /** What a conforming helper reports; tests change single fields with `copy`. */
    private fun claims(nonce: String) =
        HelperOffer(shell, pid, hasBinder = true, nonce, build.apkPath, build.versionCode)

    private fun extras(
        helper: FakeHelper,
        nonce: String,
        edit: HelperOffer.() -> HelperOffer = { this },
    ): Bundle = HandoffBundles.offer(helper, claims(nonce).edit())

    private fun Bundle.reject(): HandoffReject? =
        takeUnless {
            it.getBoolean(HandoffContract.KEY_ACCEPTED)
        }?.getString(HandoffContract.KEY_REASON)?.let(HandoffReject::valueOf)

    private fun Bundle.accepted(): HandoffBundles.Reply.Accepted =
        HandoffBundles.readReply(this) as HandoffBundles.Reply.Accepted

    @Test
    fun admitsAVerifiedHelper() {
        val helper = FakeHelper()
        val seen = mutableListOf<HelperState>()
        client.addListener { seen += it }

        val reply = client.offer(extras(helper, armed()), shell, pid).accepted()

        assertTrue(Nonces.isWellFormed(reply.nextNonce))
        assertEquals(HelperState.Connected(helper, pid), client.state)
        assertSame(helper, client.core)
        assertEquals(listOf(HelperState.Connected(helper, pid)), seen)
        assertEquals(listOf("identity", "protocolVersion"), helper.calls)
        assertEquals(1, helper.linked)
    }

    @Test
    fun aNonceWorksOnce() {
        val nonce = armed()
        client.offer(extras(FakeHelper(), nonce), shell, pid).accepted()
        assertEquals(HandoffReject.BAD_NONCE, client.offer(extras(FakeHelper(), nonce), shell, pid).reject())
    }

    @Test
    fun theResumeNonceAdmitsTheSameHelperInTheNextAppProcess() {
        val reply = client.offer(extras(FakeHelper(), armed()), shell, pid).accepted()
        val restarted = newClient()
        val helper = FakeHelper()
        restarted.offer(extras(helper, reply.nextNonce), shell, pid).accepted()
        assertEquals(HelperState.Connected(helper, pid), restarted.state)
    }

    @Test
    fun launchNoncesIssuedByTheAppAreAccepted() {
        client.offer(extras(FakeHelper(), client.issueLaunchNonce()), shell, pid).accepted()
    }

    @Test
    fun refusesCallersOtherThanShellWithoutTouchingTheBinder() {
        val helper = FakeHelper()
        assertEquals(
            HandoffReject.NOT_SHELL,
            client
                .offer(
                    extras(helper, armed()) {
                        copy(reportedUid = 10_123)
                    },
                    10_123,
                    pid,
                ).reject(),
        )
        assertEquals(emptyList<String>(), helper.calls)
        assertEquals(HelperState.NotConnected, client.state)
    }

    @Test
    fun refusesSelfReportsThatDifferFromTheCaller() {
        val helper = FakeHelper()
        assertEquals(
            HandoffReject.ID_MISMATCH,
            client.offer(extras(helper, armed()) { copy(reportedPid = 1) }, shell, pid).reject(),
        )
        assertEquals(emptyList<String>(), helper.calls)
    }

    @Test
    fun refusesOffersWithoutBinder() {
        val noBinder = extras(FakeHelper(), armed()).apply { remove(HandoffContract.KEY_BINDER) }
        assertEquals(HandoffReject.NO_BINDER, client.offer(noBinder, shell, pid).reject())
        assertEquals("no extras: nothing reported", HandoffReject.ID_MISMATCH, client.offer(null, shell, pid).reject())
    }

    @Test
    fun aBadNonceNeverReachesTheBinder() {
        val helper = FakeHelper()
        assertEquals(HandoffReject.BAD_NONCE, client.offer(extras(helper, Nonces.random()), shell, pid).reject())
        assertEquals(emptyList<String>(), helper.calls)
    }

    @Test
    fun aBinderFromAnotherProcessIsRefusedAndTheNonceStaysUsable() {
        val nonce = armed()
        val elsewhere = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        assertEquals(HandoffReject.BINDER_ELSEWHERE, client.offer(extras(elsewhere, nonce), shell, pid).reject())
        assertEquals(listOf("identity"), elsewhere.calls)
        client.offer(extras(FakeHelper(), nonce), shell, pid).accepted()
    }

    @Test
    fun aBinderThatFailsIdentityIsRefused() {
        for (failure in listOf(RemoteException("dead"), SecurityException("not you"))) {
            val helper = FakeHelper(failIdentity = failure)
            assertEquals(HandoffReject.BINDER_ELSEWHERE, client.offer(extras(helper, armed()), shell, pid).reject())
        }
    }

    @Test
    fun anyRuntimeExceptionFromIdentityIsARefusalAndKeepsTheNonce() {
        val nonce = armed()
        for (failure in listOf(
            IllegalStateException("bad state"),
            IllegalArgumentException("bad arg"),
            NullPointerException(),
        )) {
            val helper = FakeHelper(failIdentity = failure)
            assertEquals(
                failure.toString(),
                HandoffReject.BINDER_ELSEWHERE,
                client.offer(extras(helper, nonce), shell, pid).reject(),
            )
        }
        assertEquals(HelperState.NotConnected, client.state)
        client.offer(extras(FakeHelper(), nonce), shell, pid).accepted()
    }

    @Test
    fun aProtocolVersionCallThatFailsIsARefusalNotAStaleHelperAndKeepsTheNonce() {
        val nonce = armed()
        for (failure in listOf(RemoteException("dead"), IllegalStateException("bad state"), SecurityException("no"))) {
            val helper = FakeHelper().apply { failProtocol = failure }
            assertEquals(
                failure.toString(),
                HandoffReject.BINDER_ELSEWHERE,
                client.offer(extras(helper, nonce), shell, pid).reject(),
            )
            assertEquals("not told to release", listOf("identity", "protocolVersion"), helper.calls)
        }
        assertEquals(HelperState.NotConnected, client.state)
        assertEquals(emptyList<HelperState.NeedsLaunch>(), launches)
        client.offer(extras(FakeHelper(), nonce), shell, pid).accepted()
    }

    @Test
    fun aStaleHelperWhoseReleaseAllThrowsIsStillRejectedAndRelaunched() {
        val failures = listOf(IllegalStateException("busy"), SecurityException("not you"), RemoteException("dead"))
        for (failure in failures) {
            val old = FakeHelper(protocol = PrivilegedProtocol.VERSION + 1, failRelease = failure)
            assertEquals(
                failure.toString(),
                HandoffReject.STALE_PROTOCOL,
                client.offer(extras(old, armed()), shell, pid).reject(),
            )
            assertEquals("releaseAll", old.calls.last())
        }
        val needs = HelperState.NeedsLaunch(HelperState.LaunchReason.Stale(HandoffReject.STALE_PROTOCOL), pid)
        assertEquals(needs, client.state)
        assertEquals(List(failures.size) { needs }, launches)
    }

    @Test
    fun aHelperOfAnotherProtocolIsReleasedRejectedAndRelaunched() {
        val nonce = armed()
        val old = FakeHelper(protocol = PrivilegedProtocol.VERSION + 1)
        assertEquals(HandoffReject.STALE_PROTOCOL, client.offer(extras(old, nonce), shell, pid).reject())
        assertEquals(listOf("identity", "protocolVersion", "releaseAll"), old.calls)
        val needs = HelperState.NeedsLaunch(HelperState.LaunchReason.Stale(HandoffReject.STALE_PROTOCOL), pid)
        assertEquals(needs, client.state)
        assertEquals(listOf(needs), launches)
        assertEquals(
            "the stale helper's nonce is burnt",
            HandoffReject.BAD_NONCE,
            client.offer(extras(FakeHelper(), nonce), shell, pid).reject(),
        )
    }

    @Test
    fun aHelperFromAnotherApkIsReleasedAndRejected() {
        val moved = FakeHelper()
        assertEquals(
            HandoffReject.STALE_APK,
            client.offer(extras(moved, armed()) { copy(apkPath = "/data/app/~~old/base.apk") }, shell, pid).reject(),
        )
        assertEquals("releaseAll", moved.calls.last())
        val older = FakeHelper()
        assertEquals(
            HandoffReject.STALE_APK,
            client.offer(extras(older, armed()) { copy(versionCode = 10000) }, shell, pid).reject(),
        )
        assertEquals("releaseAll", older.calls.last())
        assertEquals(
            HelperState.NeedsLaunch(HelperState.LaunchReason.Stale(HandoffReject.STALE_APK), pid),
            client.state,
        )
    }

    @Test
    fun aStaleHelperDoesNotDisturbTheConnectedOne() {
        val current = FakeHelper()
        client.offer(extras(current, armed()), shell, pid).accepted()
        val stale = FakeHelper(protocol = 0)
        assertEquals(HandoffReject.STALE_PROTOCOL, client.offer(extras(stale, armed()), shell, pid).reject())
        assertEquals(HelperState.Connected(current, pid), client.state)
        assertEquals(emptyList<HelperState.NeedsLaunch>(), launches)
    }

    @Test
    fun aNewHelperReplacesAndShutsDownTheOldOne() {
        val first = FakeHelper()
        client.offer(extras(first, armed()), shell, pid).accepted()
        val second = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        client.offer(extras(second, armed()) { copy(reportedPid = pid + 1) }, shell, pid + 1).accepted()

        assertEquals(HelperState.Connected(second, pid + 1), client.state)
        assertEquals("shutdown", first.calls.last())
        assertEquals("the old helper is no longer followed", 0, first.linked)
        first.die()
        assertEquals(HelperState.Connected(second, pid + 1), client.state)
    }

    @Test
    fun helperDeathAsksForANewHelper() {
        val helper = FakeHelper()
        client.offer(extras(helper, armed()), shell, pid).accepted()
        val seen = mutableListOf<HelperState>()
        val handle = client.addListener { seen += it }

        helper.die()

        val needs = HelperState.NeedsLaunch(HelperState.LaunchReason.HelperDied, pid)
        assertEquals(needs, client.state)
        assertNull(client.core)
        assertEquals(listOf<HelperState>(needs), seen)
        assertEquals(listOf(needs), launches)
        handle.close()
        helper.die()
        assertEquals("closed listeners are not called", 1, seen.size)
    }

    @Test
    fun aListenerOrALaunchRequestThatThrowsStopsNeitherTheReplyNorTheOtherListeners() {
        val client =
            HelperClient(HandoffNonces(store, { now }, { 7 }), { build }) {
                launches += it
                error("the launcher broke")
            }
        val seen = mutableListOf<HelperState>()
        client.addListener { error("a broken listener") }
        client.addListener { seen += it }
        val first = FakeHelper()
        val second = FakeHelper()
        val third = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))

        client.offer(extras(first, armed(client)), shell, pid).accepted()
        first.die()
        client.offer(extras(second, armed(client)), shell, pid).accepted()
        client.offer(extras(third, armed(client)) { copy(reportedPid = pid + 1) }, shell, pid + 1).accepted()

        val died = HelperState.NeedsLaunch(HelperState.LaunchReason.HelperDied, pid)
        assertEquals(
            listOf(
                HelperState.Connected(first, pid),
                died,
                HelperState.Connected(second, pid),
                HelperState.Connected(third, pid + 1),
            ),
            seen,
        )
        assertEquals(listOf(died), launches)
        assertEquals("the replaced helper is still told to stop", "shutdown", second.calls.last())
        assertEquals(HelperState.Connected(third, pid + 1), client.state)
    }

    @Test
    fun aHelperDyingDuringTheHandoffIsRefused() {
        val helper = FakeHelper(failLink = true)
        assertEquals(HandoffReject.BINDER_ELSEWHERE, client.offer(extras(helper, armed()), shell, pid).reject())
        assertEquals(HelperState.NotConnected, client.state)
    }

    @Test
    fun aHandoffThatFailsToLinkKeepsTheConnectedHelpersResumeNonce() {
        val first = FakeHelper()
        val resume = client.offer(extras(first, armed()), shell, pid).accepted().nextNonce
        val dying = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1), failLink = true)
        assertEquals(
            HandoffReject.BINDER_ELSEWHERE,
            client.offer(extras(dying, armed()) { copy(reportedPid = pid + 1) }, shell, pid + 1).reject(),
        )
        assertEquals(HelperState.Connected(first, pid), client.state)

        val restarted = newClient()
        val again = FakeHelper()
        assertNull("the connected helper's resume nonce", restarted.offer(extras(again, resume), shell, pid).reject())
        assertEquals(HelperState.Connected(again, pid), restarted.state)
    }

    @Test
    fun aNonceThatExpiresWhileTheHelperIsQueriedIsRefused() {
        val helper = FakeHelper(onIdentity = { now += HandoffNonces.DEFAULT_ARMED_TTL_MS + 1 })
        assertEquals(HandoffReject.BAD_NONCE, client.offer(extras(helper, armed()), shell, pid).reject())
        assertEquals(HelperState.NotConnected, client.state)
        assertEquals("no longer followed", 0, helper.linked)
    }

    @Test
    fun aNonceReplacedWhileTheHelperIsQueriedIsRefused() {
        val nonce = armed()
        val helper = FakeHelper(onIdentity = { armed() })
        assertEquals(HandoffReject.BAD_NONCE, client.offer(extras(helper, nonce), shell, pid).reject())
        assertEquals(HelperState.NotConnected, client.state)
        assertEquals(0, helper.linked)
    }

    @Test
    fun aStaleHelperWhoseNonceWentAwayIsRefusedWithoutBeingReleased() {
        val old =
            FakeHelper(protocol = PrivilegedProtocol.VERSION + 1, onIdentity = {
                now +=
                    HandoffNonces.DEFAULT_ARMED_TTL_MS + 1
            })
        assertEquals(HandoffReject.BAD_NONCE, client.offer(extras(old, armed()), shell, pid).reject())
        assertEquals(listOf("identity", "protocolVersion"), old.calls)
        assertEquals(HelperState.NotConnected, client.state)
        assertEquals(emptyList<HelperState.NeedsLaunch>(), launches)
    }

    /** Bounds a barrier wait so a broken test fails instead of hanging; it never paces the test. */
    private fun CountDownLatch.awaitBounded() = assertTrue("barrier timed out", await(10, TimeUnit.SECONDS))

    @Test
    fun listenersEndOnTheCurrentStateWhenADeathRacesTheAdmission() {
        val helper = FakeHelper()
        val inFirstListener = CountDownLatch(1)
        val resume = CountDownLatch(1)
        client.addListener { changed ->
            if (changed is HelperState.Connected && inFirstListener.count > 0) {
                inFirstListener.countDown()
                resume.awaitBounded()
            }
        }
        val seen = CopyOnWriteArrayList<HelperState>()
        client.addListener { seen += it }
        val nonce = armed()

        val admitting = thread { client.offer(extras(helper, nonce), shell, pid) }
        inFirstListener.awaitBounded()
        helper.die()
        resume.countDown()
        admitting.join()

        val needs = HelperState.NeedsLaunch(HelperState.LaunchReason.HelperDied, pid)
        assertEquals(needs, client.state)
        assertEquals(listOf(HelperState.Connected(helper, pid), needs), seen.toList())
        assertEquals(listOf(needs), launches)
    }

    @Test
    fun aLaunchRequestOvertakenByANewHelperIsDropped() {
        val first = FakeHelper()
        client.offer(extras(first, armed()), shell, pid).accepted()
        val inListener = CountDownLatch(1)
        val resume = CountDownLatch(1)
        client.addListener { changed ->
            if (changed is HelperState.NeedsLaunch) {
                inListener.countDown()
                resume.awaitBounded()
            }
        }

        val dying = thread { first.die() }
        inListener.awaitBounded()
        val second = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        client.offer(extras(second, armed()) { copy(reportedPid = pid + 1) }, shell, pid + 1).accepted()
        resume.countDown()
        dying.join()

        assertEquals(HelperState.Connected(second, pid + 1), client.state)
        assertEquals("the helper is back before the launch request was delivered", emptyList<Any>(), launches)
    }

    /** Waits until [offering] waits for the handoff in progress, or has finished without waiting. */
    private fun awaitWaitingOrDone(offering: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (offering.isAlive && offering.state != Thread.State.BLOCKED) {
            assertTrue("the second handoff neither finished nor waited", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    @Test
    fun aReplacedHelperHandingOffBeforeItIsToldToStopIsRefusedAndTheNewOneStays() {
        val old = FakeHelper()
        val resume = client.offer(extras(old, armed()), shell, pid).accepted().nextNonce
        val saved = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.addListener { changed ->
            if (changed is HelperState.Connected && changed.pid == pid + 1 && saved.count > 0) {
                saved.countDown()
                release.awaitBounded()
            }
        }
        val new = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        val newNonce = armed()
        val offeringNew = thread { client.offer(extras(new, newNonce) { copy(reportedPid = pid + 1) }, shell, pid + 1) }
        saved.awaitBounded()
        var oldReply: Bundle? = null
        val offeringOld = thread { oldReply = client.offer(extras(old, resume), shell, pid) }
        awaitWaitingOrDone(offeringOld)
        release.countDown()
        offeringNew.join()
        offeringOld.join()

        assertEquals(HandoffReject.BAD_NONCE, oldReply?.reject())
        assertEquals(HelperState.Connected(new, pid + 1), client.state)
        assertFalse("the new helper keeps running", "shutdown" in new.calls)
        assertEquals("the old helper is told to stop once", 1, old.calls.count { it == "shutdown" })
    }

    @Test
    fun aReplacedHelperHandingOffAfterItWasToldToStopIsRefusedUntilTheAppRestarts() {
        val old = FakeHelper()
        val resume = client.offer(extras(old, armed()), shell, pid).accepted().nextNonce
        val new = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        client.offer(extras(new, armed()) { copy(reportedPid = pid + 1) }, shell, pid + 1).accepted()
        val saved = store.slots
        val asked = old.calls.size

        assertEquals(HandoffReject.BAD_NONCE, client.offer(extras(old, resume), shell, pid).reject())
        assertEquals(HelperState.Connected(new, pid + 1), client.state)
        assertFalse("the new helper keeps running", "shutdown" in new.calls)
        assertEquals("nothing is written", saved, store.slots)
        assertEquals("refused before its Binder is touched", asked, old.calls.size)

        // A restarted app does not know the old helper was told to stop: it may come back once.
        assertNull(newClient().offer(extras(old, resume), shell, pid).reject())
    }

    @Test
    fun aHandoffStartedFromAListenerFailsInsteadOfOverlapping() {
        client.offer(extras(FakeHelper(), armed()), shell, pid).accepted()
        val second = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 1))
        val third = FakeHelper(reportedIdentity = intArrayOf(shell, pid + 2))
        val nested = mutableListOf<Result<Bundle>>()
        client.addListener { changed ->
            if (changed is HelperState.Connected && changed.pid == pid + 1) {
                val nonce = armed()
                nested +=
                    runCatching { client.offer(extras(third, nonce) { copy(reportedPid = pid + 2) }, shell, pid + 2) }
            }
        }

        client.offer(extras(second, armed()) { copy(reportedPid = pid + 1) }, shell, pid + 1).accepted()

        val failure = nested.single().exceptionOrNull()
        assertTrue("$nested", failure is IllegalStateException)
        assertEquals(HelperState.Connected(second, pid + 1), client.state)
        assertFalse("shutdown" in second.calls)
        assertEquals(emptyList<String>(), third.calls)
    }

    @Test
    fun onlyTheShellCanArm() {
        val hash = Nonces.sha256Hex(Nonces.random())
        val notShell = client.arm(hash, 10_123)
        assertFalse(notShell.getBoolean(HandoffContract.KEY_ACCEPTED))
        assertNotNull(notShell.getString(HandoffContract.KEY_REASON))
        assertEquals(NonceSlots(), store.slots)
        val malformed = client.arm("not-a-hash", shell)
        assertFalse(malformed.getBoolean(HandoffContract.KEY_ACCEPTED))
        assertNotNull(malformed.getString(HandoffContract.KEY_REASON))
        assertTrue(client.arm(hash, shell).getBoolean(HandoffContract.KEY_ACCEPTED))
    }

    @Test
    fun armingWithAnUnknownBootIsRefusedWithTheReasonAndChangesNothing() {
        val unknown = HelperClient(HandoffNonces(store, { now }, { null }), { build })
        val reply = unknown.arm(Nonces.sha256Hex(Nonces.random()), shell)
        assertFalse(reply.getBoolean(HandoffContract.KEY_ACCEPTED))
        val reason = reply.getString(HandoffContract.KEY_REASON).orEmpty()
        assertTrue(reason, reason.contains("BOOT_COUNT"))
        assertEquals(NonceSlots(), store.slots)
        assertThrows(IllegalStateException::class.java) { unknown.issueLaunchNonce() }
        assertEquals(NonceSlots(), store.slots)
    }

    @Test
    fun statusIsForTheShellOnly() {
        assertNull(client.status(10_123))
        val none = client.status(shell)
        assertEquals("NOT_CONNECTED", none?.getString(HandoffContract.KEY_STATE))
        assertEquals(-1, none?.getInt(HandoffContract.KEY_HELPER_PID))

        val helper = FakeHelper()
        client.offer(extras(helper, armed()), shell, pid).accepted()
        assertEquals("CONNECTED", client.status(shell)?.getString(HandoffContract.KEY_STATE))
        assertEquals(pid, client.status(shell)?.getInt(HandoffContract.KEY_HELPER_PID))

        helper.die()
        assertEquals("NEEDS_LAUNCH", client.status(shell)?.getString(HandoffContract.KEY_STATE))
        assertEquals(pid, client.status(shell)?.getInt(HandoffContract.KEY_HELPER_PID))
    }
}
