package dev.phoneagent.privileged.helper

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Surface
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.PrivilegedProtocol
import dev.phoneagent.privileged.cap.Capabilities

/**
 * The helper's [IPrivilegedCore]. Every IPrivilegedCore method (transactions
 * FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) accepts only [allowedUid], the app: the helper
 * outlives the app and its Binder could reach other processes. DUMP and SHELL_COMMAND are guarded
 * the same way; an override of `dump` or `onShellCommand` must keep that check. The framework's
 * other system transactions (INTERFACE; PING and the like are answered natively before Java) skip
 * the guard and expose no capability. Everything beyond the frozen handoff methods is delegated to
 * [capabilities], which also decides what `releaseAll` has to undo.
 *
 * [onShutdown] runs on the Binder thread and must only schedule the exit.
 */
@Suppress("TooManyFunctions") // One override per IPrivilegedCore method; each is a one-line delegation.
internal class PrivilegedCore(
    private val allowedUid: Int,
    private val capabilities: Capabilities,
    private val onShutdown: () -> Unit,
) : IPrivilegedCore.Stub() {
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION || code in GUARDED_SYSTEM_TRANSACTIONS) {
            val caller = Binder.getCallingUid()
            if (caller != allowedUid) throw SecurityException("uid $caller may not call the PhoneAgent helper")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun protocolVersion(): Int = PrivilegedProtocol.VERSION

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    /** Undoes everything [capabilities] hold for the app, see [Capabilities.releaseAll]. Never throws. */
    @Suppress("TooGenericExceptionCaught") // releaseAll is how the app and the lifecycle let go; it must not throw.
    override fun releaseAll() {
        val released =
            try {
                capabilities.releaseAll()
            } catch (e: RuntimeException) {
                Log.e(TAG, "releaseAll failed", e)
                "failed ($e)"
            }
        Log.i(TAG, "releaseAll: $released")
    }

    override fun shutdown() {
        Log.i(TAG, "shutdown requested")
        onShutdown()
    }

    override fun probe(): Bundle = capabilities.probe()

    override fun createDisplay(
        s: Surface?,
        w: Int,
        h: Int,
        dpi: Int,
    ): Int = capabilities.displays.createDisplay(s, w, h, dpi)

    override fun releaseDisplay(displayId: Int): Unit = capabilities.displays.releaseDisplay(displayId)

    override fun startActivity(
        displayId: Int,
        intent: Intent?,
        userId: Int,
        cold: Boolean,
    ): Int = capabilities.tasks.startActivity(displayId, intent, userId, cold)

    override fun topTask(displayId: Int): Int = capabilities.tasks.topTask(displayId)

    override fun moveTask(
        taskId: Int,
        displayId: Int,
    ): Boolean = capabilities.tasks.moveTask(taskId, displayId)

    override fun watchMainScreen(windowMs: Int): Int = capabilities.tasks.watchMainScreen(windowMs)

    override fun mainArrivals(sinceUptimeMs: Long): Bundle = capabilities.tasks.mainArrivals(sinceUptimeMs)

    override fun injectStroke(
        displayId: Int,
        xs: IntArray?,
        ys: IntArray?,
        t: LongArray?,
    ): Boolean = capabilities.input.injectStroke(displayId, xs, ys, t)

    override fun injectKey(
        displayId: Int,
        keyCode: Int,
    ): Boolean = capabilities.input.injectKey(displayId, keyCode)

    override fun setAudioMuted(
        pkg: String?,
        muted: Boolean,
    ): Boolean = capabilities.system.setAudioMuted(pkg, muted)

    override fun grantSelf(permission: String?): Int = capabilities.system.grantSelf(permission)

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /** `IBinder.SHELL_COMMAND_TRANSACTION`, hidden from the SDK: `_CMD`. */
        const val SHELL_COMMAND_TRANSACTION = ('_'.code shl 24) or ('C'.code shl 16) or ('M'.code shl 8) or 'D'.code

        /** System transactions that could reach helper code if `dump` or `onShellCommand` were overridden. */
        val GUARDED_SYSTEM_TRANSACTIONS = setOf(DUMP_TRANSACTION, SHELL_COMMAND_TRANSACTION)
    }
}
