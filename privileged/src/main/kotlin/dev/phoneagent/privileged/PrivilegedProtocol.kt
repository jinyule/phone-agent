package dev.phoneagent.privileged

/**
 * Contract version between the app and the shell-uid helper.
 *
 * The helper keeps running old code after an app update, so the app compares this value
 * (and its own versionCode) during handoff and restarts a mismatched helper
 * (docs/design/engineering-baseline.md §6). Bump it with every IPrivilegedCore change.
 */
public object PrivilegedProtocol {
    /** Current protocol version; strictly increasing. */
    public const val VERSION: Int = 1
}
