package dev.phoneagent.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Debug-only entry for configuring models from a computer, so emulators and eval runs need no UI:
 *
 * ```
 * adb shell content write --uri content://dev.phoneagent.debug.model/config < model.json
 * printf '%s' "$JSON" | adb shell content write --uri content://dev.phoneagent.debug.model/config
 * adb shell content read --uri content://dev.phoneagent.debug.model/status
 * adb shell content read --uri content://dev.phoneagent.debug.model/keystore-selfcheck
 * ```
 *
 * The request (see [DebugModelRequest]) arrives on stdin, so its key is in no command line: other
 * shell-uid processes can read those (the helper nonce rule of phase1 §2.2). `printf` is a shell
 * builtin, so `$JSON` reaches no argv either. The request goes through the UI's "test and save"
 * ([DebugModelConfig]), and `status` reports how it went without repeating it. `status` says
 * `receiving` from the moment `config` is opened, so a read after `content write` returns never
 * sees the previous request's outcome.
 *
 * Exported because the shell cannot open an unexported provider (that needs root); DUMP in the
 * manifest keeps ordinary apps out, and every call also checks that the caller is uid 2000. Only
 * in debug builds: the release unit tests check that no `dev.phoneagent.app.debug` component or
 * class ships.
 */
class DebugModelConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID) {
            Log.w(TAG, "refused ${uri.lastPathSegment} from uid $uid")
            throw SecurityException("only the shell may configure models")
        }
        val context = checkNotNull(context) { "provider not attached" }
        return when (uri.lastPathSegment) {
            CONFIG -> {
                requireMode(mode, write = true)
                val config = DebugModelConfig.of(context)
                config.receiving()
                sink { config.received(it) }
            }

            STATUS -> {
                requireMode(mode, write = false)
                source { DebugModelConfig.of(context).status() }
            }

            SELF_CHECK -> {
                requireMode(mode, write = false)
                source { KeystoreSelfCheck.run() }
            }

            else -> {
                throw FileNotFoundException("unknown path; use $CONFIG, $STATUS or $SELF_CHECK")
            }
        }
    }

    private fun requireMode(
        mode: String,
        write: Boolean,
    ) {
        if (('w' in mode) != write || (write && 'r' in mode)) {
            throw FileNotFoundException(if (write) "config is write-only" else "this path is read-only")
        }
    }

    /**
     * A pipe the caller writes into. Once it is closed, [consume] gets what was written (up to one
     * byte more than [DebugModelConfig.MAX_REQUEST_BYTES]), or `null` if it could not be read.
     */
    private fun sink(consume: (ByteArray?) -> Unit): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "debug-model-config") {
            val bytes =
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(read).use {
                        readAtMost(it, DebugModelConfig.MAX_REQUEST_BYTES + 1)
                    }
                } catch (e: IOException) {
                    // The writer went away: the status says the request was unreadable.
                    Log.w(TAG, "could not read the request (${e.javaClass.name})")
                    null
                }
            consume(bytes)
        }
        return write
    }

    /** Up to [limit] bytes of [input] (`InputStream.readNBytes` needs API 33). */
    private fun readAtMost(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** A pipe the caller reads [text] from. */
    private fun source(text: () -> String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "debug-model-status") {
            try {
                ParcelFileDescriptor
                    .AutoCloseOutputStream(
                        write,
                    ).use { it.write((text() + "\n").toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                // The reader went away.
                Log.w(TAG, "could not write the reply (${e.javaClass.name})")
            }
        }
        return read
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

    internal companion object {
        const val SHELL_UID = 2000
        const val CONFIG = "config"
        const val STATUS = "status"
        const val SELF_CHECK = "keystore-selfcheck"
        private const val BUFFER_BYTES = 8 * 1024
        private const val TAG = "PhoneAgentDebugModel"
    }
}
