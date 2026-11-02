package dev.phoneagent.privileged

/**
 * Contract version between the app and the shell-uid helper.
 *
 * The helper keeps running old code after an app update, so the app compares this value
 * (and its own versionCode) during handoff and restarts a mismatched helper
 * (docs/design/engineering-baseline.md §6). Bump it with every IPrivilegedCore change and append
 * the new AIDL fingerprint to `privileged/protocol-history.txt`; `:privileged:verifyPrivilegedProtocol`
 * fails the build otherwise.
 */
public object PrivilegedProtocol {
    /** Current protocol version; strictly increasing. */
    public const val VERSION: Int = 3
}

/**
 * How the helper reports protocol-level errors. A method the running helper does not implement
 * throws [UnsupportedOperationException] whose message starts with [NOT_IMPLEMENTED]; Binder
 * carries that exception type and message to the caller (`android.os.ServiceSpecificException`,
 * which would carry an int code, is not in the public SDK).
 */
public object PrivilegedError {
    /** Message prefix of the "not implemented" [UnsupportedOperationException]. */
    public const val NOT_IMPLEMENTED: String = "NOT_IMPLEMENTED"

    /** The exception a helper throws for a method it does not implement. */
    public fun notImplemented(method: String): UnsupportedOperationException =
        UnsupportedOperationException(
            "$NOT_IMPLEMENTED: $method is not implemented by helper protocol ${PrivilegedProtocol.VERSION}",
        )

    /** Whether [error], caught from an `IPrivilegedCore` call, means the helper does not implement the method. */
    public fun isNotImplemented(error: Throwable): Boolean =
        error is UnsupportedOperationException && error.message.orEmpty().startsWith(NOT_IMPLEMENTED)
}
