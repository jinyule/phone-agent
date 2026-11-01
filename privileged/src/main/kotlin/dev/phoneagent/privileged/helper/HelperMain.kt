package dev.phoneagent.privileged.helper

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import dev.phoneagent.privileged.handoff.Nonces
import java.io.IOException
import kotlin.system.exitProcess

/**
 * `app_process` entry point of the shell-uid helper (phase1-architecture §2.2):
 *
 * ```
 * read n; (echo "$n" | CLASSPATH=<base.apk> setsid app_process /system/bin \
 *     --nice-name=<package>:helper dev.phoneagent.privileged.helper.HelperMain <package> &)
 * ```
 *
 * The one-time nonce is the first line of stdin (never an argument: other shell processes can
 * read `/proc/<pid>/cmdline`). The helper refuses to start unless `CLASSPATH` is the installed APK
 * of `<package>`, then hands its Binder to that app and keeps running after the app dies.
 *
 * It releases everything and exits once the app is uninstalled or re-signed. The package is
 * re-checked before every handoff, when the app dies, when the orphan grace ends and, while the
 * app is dead, every `--package-check-ms` (default 30 s): an uninstall is noticed at most
 * max(orphan grace, package check interval) later, 30 s with the defaults.
 *
 * Exit codes: 0 rejected / shut down / app uninstalled or re-signed; 1 the first handoff never
 * reached the app; 2 bad arguments or nonce; 3 not launched from the app's installed APK;
 * 4 framework internals missing.
 */
public object HelperMain {
    private const val TAG = "PhoneAgentHelper"
    private const val EXIT_USAGE = 2
    private const val EXIT_NOT_GENUINE = 3
    private const val EXIT_PLATFORM = 4

    /** Entry point; never returns normally. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val parsed =
            try {
                HelperArgs.parse(args)
            } catch (e: IllegalArgumentException) {
                fail(EXIT_USAGE, "${e.message}; ${HelperArgs.USAGE}", e)
            }
        val nonce = readNonce() ?: fail(EXIT_USAGE, "no well-formed nonce on stdin")

        // ActivityThread internals need a main Looper on this thread.
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        val context =
            try {
                ShellContext.create()
            } catch (e: ReflectiveOperationException) {
                fail(EXIT_PLATFORM, "cannot create the shell context", e)
            }
        val packages = AppPackages(context)
        val classPath = System.getProperty("java.class.path").orEmpty()
        val pinned =
            try {
                PinnedApp.resolve(parsed.packageName, classPath, packages.read(parsed.packageName))
            } catch (e: IllegalStateException) {
                fail(EXIT_NOT_GENUINE, e.message.orEmpty(), e)
            }
        Log.i(TAG, "starting for ${pinned.packageName} uid ${pinned.uid} versionCode ${pinned.versionCode}")

        val worker = HandlerThread("phoneagent-helper").apply { start() }
        HelperRuntime(context, pinned, packages, Handler(worker.looper), nonce, parsed).start()
        Looper.loop()
    }

    /** First stdin line, if it is a well-formed nonce; stdin is closed either way. */
    private fun readNonce(): String? {
        val line =
            try {
                System.`in`
                    .bufferedReader()
                    .readLine()
                    ?.trim()
            } catch (e: IOException) {
                Log.e(TAG, "cannot read the nonce from stdin", e)
                null
            }
        try {
            System.`in`.close()
        } catch (e: IOException) {
            // stdin is only used for the nonce; a failed close changes nothing.
            Log.w(TAG, "closing stdin failed", e)
        }
        return line?.takeIf(Nonces::isWellFormed)
    }

    private fun fail(
        code: Int,
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        Log.e(TAG, message, cause)
        System.err.println("PhoneAgent helper: $message")
        exitProcess(code)
    }
}
