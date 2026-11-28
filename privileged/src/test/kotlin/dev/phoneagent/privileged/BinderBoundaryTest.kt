package dev.phoneagent.privileged

import android.content.Intent
import dev.phoneagent.privileged.cap.FakeCapabilities
import dev.phoneagent.privileged.cap.NoCapabilities
import dev.phoneagent.privileged.helper.PrivilegedCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

/** What the app sees, through the generated Proxy, when a capability fails in a way nobody planned for. */
@RunWith(RobolectricTestRunner::class)
class BinderBoundaryTest {
    private val caps = FakeCapabilities()
    private val wire = OverTheWire(PrivilegedCore(APP_UID, caps) {})
    private val core = IPrivilegedCore.Stub.asInterface(wire)

    init {
        ShadowBinder.setCallingUid(APP_UID)
    }

    @After
    fun resetCaller() {
        ShadowBinder.reset()
    }

    /** Calls whose zero means success (a display id, OK, GRANTED) or whose null means "nothing". */
    private val calls: List<Pair<String, () -> Any?>> =
        listOf(
            "createDisplay" to { core.createDisplay(null, 1_080, 2_340, 440) },
            "startActivity" to { core.startActivity(2, Intent(Intent.ACTION_MAIN), 0, false) },
            "topTask" to { core.topTask(2) },
            "grantSelf" to { core.grantSelf("android.permission.WRITE_SECURE_SETTINGS") },
            "setAudioMuted" to { core.setAudioMuted("com.example", true) },
            "probe" to { core.probe() },
        )

    private fun assertArrivesAsFailure(cause: Exception) {
        caps.unexpected = cause
        for ((name, call) in calls) {
            val error = assertThrows(name, IllegalStateException::class.java) { call() }
            val message = error.message.orEmpty()
            assertTrue("$name names the cause: $message", message.contains(cause.javaClass.simpleName))
        }
        assertEquals("nothing reached the native boundary", emptyList<RuntimeException>(), wire.uncaught)
    }

    @Test
    fun anExceptionTheWireCannotCarryArrivesAsIllegalState() {
        assertArrivesAsFailure(ClassCastException("java.lang.String cannot be cast to java.lang.Integer"))
    }

    @Test
    fun aCheckedExceptionThatEscapesACapabilityArrivesAsIllegalState() {
        assertArrivesAsFailure(java.io.IOException("pipe closed"))
    }

    @Test
    fun exceptionsTheWireCarriesArriveUnchanged() {
        caps.unexpected = IllegalArgumentException("bad size")
        val error = assertThrows(IllegalArgumentException::class.java) { core.createDisplay(null, 1, 1, 1) }
        assertEquals("bad size", error.message)

        val none = IPrivilegedCore.Stub.asInterface(OverTheWire(PrivilegedCore(APP_UID, NoCapabilities("gone")) {}))
        val missing = assertThrows(UnsupportedOperationException::class.java) { none.probe() }
        assertTrue(missing.message, PrivilegedError.isNotImplemented(missing))
    }

    @Test
    fun otherCallersStillGetSecurityException() {
        ShadowBinder.setCallingUid(OTHER_UID)
        assertThrows(SecurityException::class.java) { core.topTask(2) }
        assertEquals("the guard runs before any capability", emptyList<String>(), caps.calls)
    }

    private companion object {
        const val APP_UID = 10_214
        const val OTHER_UID = 10_999
    }
}
