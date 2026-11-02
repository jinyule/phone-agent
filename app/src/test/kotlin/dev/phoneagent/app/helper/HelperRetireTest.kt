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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/** What [HelperClient] does with a helper it replaced: it keeps refusing it until that helper dies. */
@RunWith(RobolectricTestRunner::class)
class HelperRetireTest {
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
    private val client = HelperClient(HandoffNonces(store, { NOW }, { BOOT }), { build })

    private fun helper(helperPid: Int) = FakeHelper(reportedIdentity = intArrayOf(shell, helperPid))

    private fun armed(): String =
        Nonces.random().also { assertTrue(client.arm(Nonces.sha256Hex(it), shell).getBoolean("accepted")) }

    private fun offer(
        helper: FakeHelper,
        nonce: String,
        helperPid: Int,
    ): Bundle =
        client.offer(
            HandoffBundles.offer(helper, HelperOffer(shell, helperPid, true, nonce, build.apkPath, build.versionCode)),
            shell,
            helperPid,
        )

    private fun Bundle.accepted(): HandoffBundles.Reply.Accepted =
        HandoffBundles.readReply(this) as HandoffBundles.Reply.Accepted

    private fun Bundle.rejected(): String? = (HandoffBundles.readReply(this) as? HandoffBundles.Reply.Rejected)?.reason

    /**
     * Admits a helper, replaces it with a newer one and returns a weak handle on the first one and
     * the resume nonce it holds. Nothing in the test keeps the first helper reachable afterwards.
     */
    private fun replacedHelper(): Pair<WeakReference<FakeHelper>, String> {
        val old = helper(pid)
        val resume = offer(old, armed(), pid).accepted().nextNonce
        offer(helper(pid + 1), armed(), pid + 1).accepted()
        assertEquals("shutdown", old.calls.last())
        return WeakReference(old) to resume
    }

    /** Collects garbage until a garbage collection has cleared a weakly reachable object. */
    private fun collectGarbage() {
        val probe = WeakReference(Any())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (probe.get() != null) {
            assertTrue("no garbage collection cleared a weak reference", System.nanoTime() < deadline)
            System.gc()
        }
    }

    /**
     * On a device, the same remote Binder arrives as the same BinderProxy object only while
     * something holds that proxy. So the client has to hold a replaced helper's Binder itself: once
     * it is collected, the helper's next handoff arrives as a proxy the client has never seen.
     */
    @Test
    fun aReplacedHelperIsHeldUntilItDiesSoItStaysRefused() {
        val (old, resume) = replacedHelper()

        collectGarbage()

        val binder = old.get()
        assertNotNull("the client let go of a replaced helper that is still running", binder)
        assertEquals(HandoffReject.BAD_NONCE.name, offer(checkNotNull(binder), resume, pid).rejected())
    }

    @Test
    fun aReplacedHelperIsForgottenOnceItDies() {
        val old = helper(pid)
        offer(old, armed(), pid).accepted()
        val new = helper(pid + 1)
        offer(new, armed(), pid + 1).accepted()
        assertEquals(1, client.retiredHelpers)

        old.die()

        assertEquals(0, client.retiredHelpers)
        assertEquals(HelperState.Connected(new, pid + 1), client.state)
    }

    @Test
    fun aReplacedHelperThatIsAlreadyDeadIsNotKept() {
        val old = helper(pid)
        offer(old, armed(), pid).accepted()
        old.failLink = true

        offer(helper(pid + 1), armed(), pid + 1).accepted()

        assertEquals(0, client.retiredHelpers)
        assertEquals("it is still told to stop, in case it is not dead", "shutdown", old.calls.last())
    }

    private companion object {
        const val NOW = 1_000L
        const val BOOT = 7
        const val WAIT_SECONDS = 10L
    }
}
