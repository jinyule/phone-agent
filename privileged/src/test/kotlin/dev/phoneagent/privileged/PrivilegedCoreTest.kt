package dev.phoneagent.privileged

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import dev.phoneagent.privileged.helper.PrivilegedCore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
class PrivilegedCoreTest {
    private val appUid = 10_214
    private var shutdowns = 0
    private val core = PrivilegedCore(appUid) { shutdowns++ }

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
    fun releaseAllUndoesEachHeldResourceOnce() {
        var released = 0
        core.hold { released++ }
        core.hold { released++ }
        core.releaseAll()
        core.releaseAll()
        assertEquals(2, released)
    }

    @Test
    fun releaseAllGoesOnPastAFailureAndKeepsTheFailedResourceForTheNextCall() {
        val released = mutableListOf<String>()
        var displayFails = true
        core.hold {
            check(!displayFails) { "display busy" }
            released += "display"
        }
        core.hold { released += "mute" }
        core.releaseAll()
        assertEquals("the failure does not stop the other release", listOf("mute"), released)
        displayFails = false
        core.releaseAll()
        assertEquals(listOf("mute", "display"), released)
        core.releaseAll()
        assertEquals("released ones are gone", listOf("mute", "display"), released)
    }

    @Test
    fun shutdownIsHandedToTheLifecycle() {
        core.shutdown()
        assertEquals(1, shutdowns)
    }

    @Test
    fun capabilitiesAreExplicitlyNotImplemented() {
        val calls: List<() -> Any?> =
            listOf(
                { core.probe() },
                { core.createDisplay(null, 1080, 2340, 440) },
                { core.releaseDisplay(2) },
                { core.startActivity(2, null, 0, false) },
                { core.topTask(2) },
                { core.moveTask(1, 2) },
                { core.watchMainScreen(3_000) },
                { core.mainArrivals(0) },
                { core.injectStroke(2, intArrayOf(1), intArrayOf(1), longArrayOf(0)) },
                { core.injectKey(2, 4) },
                { core.setAudioMuted("com.example", true) },
                { core.grantSelf("android.permission.WRITE_SECURE_SETTINGS") },
            )
        calls.forEach { call ->
            val error = assertThrows(UnsupportedOperationException::class.java) { call() }
            assertTrue(error.message, PrivilegedError.isNotImplemented(error))
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
