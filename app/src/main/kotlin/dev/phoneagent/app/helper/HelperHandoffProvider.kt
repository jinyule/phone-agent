package dev.phoneagent.app.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import dev.phoneagent.privileged.handoff.HandoffContract

/**
 * Where the shell-uid helper hands its Binder over (`call(attach)`), and where the shell arms a
 * nonce (`call(arm)`) or reads the connection state (`call(status)`).
 *
 * Exported for the shell; the manifest additionally requires INTERACT_ACROSS_USERS_FULL, which
 * shell holds and ordinary apps cannot. Every method still checks the calling uid itself, and
 * `attach` goes through all of [HelperClient]'s checks.
 */
class HelperHandoffProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val context = context ?: return null
        val uid = Binder.getCallingUid()
        val client = HelperClient.of(context)
        return when (method) {
            HandoffContract.METHOD_ATTACH -> {
                client.offer(extras, uid, Binder.getCallingPid())
            }

            HandoffContract.METHOD_ARM -> {
                client.arm(arg, uid)
            }

            HandoffContract.METHOD_STATUS -> {
                client.status(uid)
            }

            else -> {
                Log.w(TAG, "unknown method '$method' from uid $uid")
                null
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "PhoneAgentHelperClient"
    }
}
