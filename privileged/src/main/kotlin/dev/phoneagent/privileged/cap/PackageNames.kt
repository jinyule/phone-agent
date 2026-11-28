package dev.phoneagent.privileged.cap

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Checks a package name before it reaches a command line (`appops`, `am force-stop`): a Java
 * package name of at least two segments, each starting with a letter, installed for the helper's
 * user. A uid, a `u0aNN` app id or an option such as `--user` is never a package name, and a
 * command would read it as something else.
 */
internal object PackageNames {
    private val FORMAT = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    /** Whether [name] has the shape of a package name; says nothing about whether it is installed. */
    fun wellFormed(name: String): Boolean = FORMAT.matches(name)

    /** Why [name] is not an installed package the helper may name in a command, or null when it is. */
    fun problem(
        context: Context,
        name: String?,
    ): String? =
        when {
            name == null -> "no package name"
            !wellFormed(name) -> "'$name' is not a package name"
            !installed(context, name) -> "$name is not installed"
            else -> null
        }

    private fun installed(
        context: Context,
        name: String,
    ): Boolean {
        try {
            context.packageManager.getPackageInfo(name, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "$name is not installed", e)
            return false
        }
        return true
    }

    private const val TAG = "PhoneAgentHelper"
}
