package dev.phoneagent.app.helper

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.Surface
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.PrivilegedError
import dev.phoneagent.privileged.PrivilegedProtocol
import dev.phoneagent.privileged.handoff.HandoffContract

/**
 * In-process stand-in for the helper's Binder. Records the calls the app makes and lets a test
 * kill it ([die]) the way a dead remote process would.
 */
class FakeHelper(
    var reportedIdentity: IntArray = intArrayOf(HandoffContract.SHELL_UID, PID),
    var protocol: Int = PrivilegedProtocol.VERSION,
    var failIdentity: Exception? = null,
    var failLink: Boolean = false,
    /** Runs inside [identity], while the app is admitting this helper. */
    var onIdentity: () -> Unit = {},
    var failRelease: Exception? = null,
) : IPrivilegedCore.Stub() {
    val calls = mutableListOf<String>()
    private val recipients = mutableListOf<IBinder.DeathRecipient>()

    val linked: Int get() = recipients.size

    fun die() {
        recipients.toList().forEach { it.binderDied() }
    }

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        if (failLink) throw RemoteException("already dead")
        recipients += recipient
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean = recipients.remove(recipient)

    override fun protocolVersion(): Int {
        calls += "protocolVersion"
        return protocol
    }

    override fun identity(): IntArray {
        calls += "identity"
        onIdentity()
        failIdentity?.let { throw it }
        return reportedIdentity
    }

    override fun releaseAll() {
        calls += "releaseAll"
        failRelease?.let { throw it }
    }

    override fun shutdown() {
        calls += "shutdown"
    }

    override fun probe(): Bundle = throw PrivilegedError.notImplemented("probe")

    override fun createDisplay(
        s: Surface?,
        w: Int,
        h: Int,
        dpi: Int,
    ): Int = throw PrivilegedError.notImplemented("createDisplay")

    override fun releaseDisplay(displayId: Int): Unit = throw PrivilegedError.notImplemented("releaseDisplay")

    override fun startActivity(
        displayId: Int,
        intent: Intent?,
        userId: Int,
        cold: Boolean,
    ): Int = throw PrivilegedError.notImplemented("startActivity")

    override fun topTask(displayId: Int): Int = throw PrivilegedError.notImplemented("topTask")

    override fun moveTask(
        taskId: Int,
        displayId: Int,
    ): Boolean = throw PrivilegedError.notImplemented("moveTask")

    override fun watchMainScreen(windowMs: Int): Int = throw PrivilegedError.notImplemented("watchMainScreen")

    override fun mainArrivals(sinceUptimeMs: Long): Bundle = throw PrivilegedError.notImplemented("mainArrivals")

    override fun injectStroke(
        displayId: Int,
        xs: IntArray?,
        ys: IntArray?,
        t: LongArray?,
    ): Boolean = throw PrivilegedError.notImplemented("injectStroke")

    override fun injectKey(
        displayId: Int,
        keyCode: Int,
    ): Boolean = throw PrivilegedError.notImplemented("injectKey")

    override fun setAudioMuted(
        pkg: String?,
        muted: Boolean,
    ): Boolean = throw PrivilegedError.notImplemented("setAudioMuted")

    override fun grantSelf(permission: String?): Int = throw PrivilegedError.notImplemented("grantSelf")

    companion object {
        const val PID = 4242
    }
}
