package dev.phoneagent.privileged.helper

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Surface
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.PrivilegedError
import dev.phoneagent.privileged.PrivilegedProtocol

/**
 * The helper's [IPrivilegedCore]. Only [allowedUid] (the app) may call it: the helper outlives
 * the app and its Binder could reach other processes. Capabilities that are not implemented yet
 * throw [PrivilegedError.notImplemented].
 *
 * [onShutdown] runs on the Binder thread and must only schedule the exit.
 */
@Suppress("TooManyFunctions") // One override per IPrivilegedCore method.
internal class PrivilegedCore(
    private val allowedUid: Int,
    private val onShutdown: () -> Unit,
) : IPrivilegedCore.Stub() {
    private val lock = Any()
    private val held = mutableListOf<HeldResource>()

    /** Something the helper must undo in [releaseAll] (a display, a mute). */
    fun interface HeldResource {
        fun release()
    }

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
            val caller = Binder.getCallingUid()
            if (caller != allowedUid) throw SecurityException("uid $caller may not call the PhoneAgent helper")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun protocolVersion(): Int = PrivilegedProtocol.VERSION

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun releaseAll() {
        val released = synchronized(lock) { held.toList().also { held.clear() } }
        released.forEach { it.release() }
        Log.i(TAG, "releaseAll: released ${released.size} resource(s)")
    }

    override fun shutdown() {
        Log.i(TAG, "shutdown requested")
        onShutdown()
    }

    /** Registers [resource] to be undone by the next [releaseAll]. */
    fun hold(resource: HeldResource) {
        synchronized(lock) { held += resource }
    }

    override fun probe(): Bundle = notImplemented("probe")

    override fun createDisplay(
        s: Surface?,
        w: Int,
        h: Int,
        dpi: Int,
    ): Int = notImplemented("createDisplay")

    override fun releaseDisplay(displayId: Int): Unit = notImplemented("releaseDisplay")

    override fun startActivity(
        displayId: Int,
        intent: Intent?,
        userId: Int,
        cold: Boolean,
    ): Int = notImplemented("startActivity")

    override fun topTask(displayId: Int): Int = notImplemented("topTask")

    override fun moveTask(
        taskId: Int,
        displayId: Int,
    ): Boolean = notImplemented("moveTask")

    override fun watchMainScreen(windowMs: Int): Int = notImplemented("watchMainScreen")

    override fun mainArrivals(sinceUptimeMs: Long): Bundle = notImplemented("mainArrivals")

    override fun injectStroke(
        displayId: Int,
        xs: IntArray?,
        ys: IntArray?,
        t: LongArray?,
    ): Boolean = notImplemented("injectStroke")

    override fun injectKey(
        displayId: Int,
        keyCode: Int,
    ): Boolean = notImplemented("injectKey")

    override fun setAudioMuted(
        pkg: String?,
        muted: Boolean,
    ): Boolean = notImplemented("setAudioMuted")

    override fun grantSelf(permission: String?): Int = notImplemented("grantSelf")

    private fun notImplemented(method: String): Nothing = throw PrivilegedError.notImplemented(method)

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
