package dev.phoneagent.app.adb

import dev.phoneagent.core.adb.AdbCommand

/**
 * The fixed command that starts the shell-uid helper from this app's installed APK, as
 * `scripts/start-helper.sh` does over USB (phase1 §2.2): `sh` reads the one-time nonce from stdin
 * into a variable and pipes it to the helper with the builtin `echo`, so the nonce never appears on
 * a command line (other shell processes read every cmdline). `setsid` detaches the helper from the
 * adb session, and `sleep 1` keeps the session open until it has, because adbd ends the session's
 * process group when the session ends.
 */
internal object HelperLaunchCommand {
    const val MAIN_CLASS: String = "dev.phoneagent.privileged.helper.HelperMain"

    /** The command for the APK at [apkPath] (`ApplicationInfo.sourceDir`) of [packageName]. */
    fun of(
        apkPath: String,
        packageName: String,
    ): AdbCommand {
        val quote = AdbCommand::quote
        val helper =
            "CLASSPATH=${quote(apkPath)} setsid app_process /system/bin --nice-name=${quote("$packageName:helper")} " +
                "$MAIN_CLASS ${quote(packageName)}"
        return AdbCommand("read n; (echo \"\$n\" | $helper > /dev/null 2>&1 &); sleep 1")
    }

    /**
     * The command's stdin: the nonce and a newline. `read` needs the newline when stdin stays open,
     * as it does over `exec:` (no shell protocol); over `shell,v2` it is closed after this anyway.
     */
    fun stdin(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.US_ASCII)
}
