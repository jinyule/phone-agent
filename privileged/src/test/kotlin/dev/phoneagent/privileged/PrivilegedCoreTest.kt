package dev.phoneagent.privileged

import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import dev.phoneagent.privileged.cap.FakeCapabilities
import dev.phoneagent.privileged.cap.GrantSpec
import dev.phoneagent.privileged.cap.NoCapabilities
import dev.phoneagent.privileged.helper.PrivilegedCore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
class PrivilegedCoreTest {
    private val appUid = 10_214
    private val writeSecureSettings = GrantSpec.WRITE_SECURE_SETTINGS
    private var shutdowns = 0
    private val caps = FakeCapabilities()
    private val core = PrivilegedCore(appUid, caps) { shutdowns++ }

    @After
    fun resetCaller() {
        ShadowBinder.reset()
    }

    /** Calls protocolVersion() through onTransact as [callerUid]. */
    private fun transactProtocolVersion(callerUid: Int): Int {
        ShadowBinder.setCallingUid(callerUid)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IPrivilegedCore.DESCRIPTOR)
            core.transact(IPrivilegedCore.Stub.TRANSACTION_protocolVersion, data, reply, 0)
            reply.setDataPosition(0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun theInterfaceDescriptorNeverChanges() {
        // Every transaction checks this token; another descriptor makes even ids 1-4 unreachable.
        assertEquals("dev.phoneagent.privileged.IPrivilegedCore", IPrivilegedCore.DESCRIPTOR)
    }

    @Test
    fun frozenTransactionCodesNeverMove() {
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 1, IPrivilegedCore.Stub.TRANSACTION_protocolVersion)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 2, IPrivilegedCore.Stub.TRANSACTION_identity)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 3, IPrivilegedCore.Stub.TRANSACTION_releaseAll)
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 4, IPrivilegedCore.Stub.TRANSACTION_shutdown)
    }

    @Test
    fun theAppUidMayCall() {
        assertEquals(PrivilegedProtocol.VERSION, transactProtocolVersion(appUid))
    }

    @Test
    fun otherUidsAreRefused() {
        assertThrows(SecurityException::class.java) { transactProtocolVersion(10_999) }
        assertThrows(SecurityException::class.java) { transactProtocolVersion(Process.SHELL_UID) }
    }

    /** Sends the framework transaction [code] with an empty request as [callerUid]. */
    private fun transactSystem(
        code: Int,
        callerUid: Int,
    ): Parcel {
        ShadowBinder.setCallingUid(callerUid)
        val reply = Parcel.obtain()
        val data = Parcel.obtain()
        try {
            assertTrue(core.transact(code, data, reply, 0))
        } finally {
            data.recycle()
        }
        reply.setDataPosition(0)
        return reply
    }

    @Test
    fun dumpAndShellCommandAreGuardedLikeTheMethods() {
        for (code in listOf(IBinder.DUMP_TRANSACTION, SHELL_COMMAND_TRANSACTION)) {
            for (caller in listOf(10_999, Process.SHELL_UID)) {
                val reply = transactSystem(code, caller)
                assertThrows("code $code from $caller", SecurityException::class.java) { reply.readException() }
                reply.recycle()
            }
        }
    }

    @Test
    fun theInterfaceDescriptorStaysReadableByAnyone() {
        val reply = transactSystem(IBinder.INTERFACE_TRANSACTION, 10_999)
        assertEquals(IPrivilegedCore.DESCRIPTOR, reply.readString())
        reply.recycle()
    }

    @Test
    fun identityIsThisProcess() {
        assertArrayEquals(intArrayOf(Process.myUid(), Process.myPid()), core.identity())
    }

    @Test
    fun releaseAllIsHandedToTheCapabilities() {
        core.releaseAll()
        core.releaseAll()
        assertEquals(2, caps.releases)
    }

    @Test
    fun shutdownIsHandedToTheLifecycle() {
        core.shutdown()
        assertEquals(1, shutdowns)
    }

    @Test
    fun everyCapabilityIsDelegatedWithItsArguments() {
        val intent = Intent("android.settings.SETTINGS")
        assertEquals(caps.displayIdResult, core.createDisplay(null, 1080, 2340, 440))
        core.releaseDisplay(2)
        assertEquals(caps.startResult, core.startActivity(2, intent, 0, true))
        assertEquals(caps.topTaskResult, core.topTask(2))
        assertEquals(caps.moveResult, core.moveTask(1, 2))
        assertEquals(caps.topTaskResult, core.watchMainScreen(3_000))
        assertSame(caps.arrivalsResult, core.mainArrivals(99))
        assertEquals(caps.strokeResult, core.injectStroke(2, intArrayOf(1), intArrayOf(2), longArrayOf(0)))
        assertEquals(caps.keyResult, core.injectKey(2, 4))
        assertEquals(caps.muteResult, core.setAudioMuted("com.example", true))
        assertEquals(caps.grantResult, core.grantSelf(writeSecureSettings))
        assertSame(caps.probeResult, core.probe())
        assertEquals(
            listOf(
                "createDisplay(true, 1080, 2340, 440)",
                "releaseDisplay(2)",
                "startActivity(2, android.settings.SETTINGS, 0, true)",
                "topTask(2)",
                "moveTask(1, 2)",
                "watchMainScreen(3000)",
                "mainArrivals(99)",
                "injectStroke(2, [1], [2], [0])",
                "injectKey(2, 4)",
                "setAudioMuted(com.example, true)",
                "grantSelf($writeSecureSettings)",
                "probe()",
            ),
            caps.calls,
        )
    }

    @Test
    fun aHelperWithoutCapabilitiesReportsFailureInsteadOfPretending() {
        val none = PrivilegedCore(appUid, NoCapabilities("no ActivityTaskManager")) { shutdowns++ }
        assertEquals(DisplayResult.FAILED, none.createDisplay(null, 1080, 2340, 440))
        assertEquals(StartResult.ERROR, none.startActivity(2, Intent(), 0, false))
        assertEquals(-1, none.topTask(2))
        assertEquals(-1, none.watchMainScreen(1_000))
        assertEquals(false, none.moveTask(1, 2))
        assertEquals(false, none.injectKey(2, 4))
        assertEquals(false, none.injectStroke(2, intArrayOf(1, 1), intArrayOf(1, 1), longArrayOf(0, 1)))
        assertEquals(false, none.setAudioMuted("com.example", true))
        assertEquals(GrantResult.FAILED, none.grantSelf(writeSecureSettings))
        listOf<() -> Any?>({ none.probe() }, { none.mainArrivals(0) }).forEach { call ->
            val error = assertThrows(UnsupportedOperationException::class.java) { call() }
            assertTrue(error.message, PrivilegedError.isNotImplemented(error))
            val message = error.message.orEmpty()
            assertTrue("the reason is reported: $message", message.contains("ActivityTaskManager"))
        }
    }

    @Test
    fun notImplementedIsRecognizedOnlyByItsToken() {
        assertTrue(PrivilegedError.isNotImplemented(PrivilegedError.notImplemented("x")))
        assertEquals(false, PrivilegedError.isNotImplemented(UnsupportedOperationException("other")))
        assertEquals(false, PrivilegedError.isNotImplemented(IllegalStateException(PrivilegedError.NOT_IMPLEMENTED)))
    }

    private companion object {
        /** `IBinder.SHELL_COMMAND_TRANSACTION`, hidden from the SDK: `_CMD`. */
        const val SHELL_COMMAND_TRANSACTION = ('_'.code shl 24) or ('C'.code shl 16) or ('M'.code shl 8) or 'D'.code
    }
}
