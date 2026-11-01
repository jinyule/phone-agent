package dev.phoneagent.privileged.helper

/**
 * Command line of [HelperMain]: `<package> [--orphan-grace-ms=<ms>]`. The nonce is not an argument:
 * it arrives on stdin.
 */
internal data class HelperArgs(
    /** Package of the app to hand the Binder to (primary user only). */
    val packageName: String,
    /** How long resources of a dead app are kept before `releaseAll` (phase1-architecture §2.2). */
    val orphanGraceMs: Long,
) {
    companion object {
        const val USAGE: String = "usage: HelperMain <package> [--orphan-grace-ms=<1..600000>] (nonce on stdin)"
        const val DEFAULT_ORPHAN_GRACE_MS: Long = 10_000
        private const val MAX_ORPHAN_GRACE_MS: Long = 600_000
        private const val GRACE_FLAG = "--orphan-grace-ms="
        private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

        /** Parses [args]; throws [IllegalArgumentException] naming the offending value. */
        fun parse(args: Array<String>): HelperArgs {
            require(args.isNotEmpty()) { "missing <package>" }
            val packageName = args[0]
            require(PACKAGE_NAME.matches(packageName)) { "invalid package name '$packageName'" }
            var grace = DEFAULT_ORPHAN_GRACE_MS
            for (arg in args.drop(1)) {
                require(arg.startsWith(GRACE_FLAG)) { "unknown argument '$arg'" }
                val value = arg.removePrefix(GRACE_FLAG).toLongOrNull()
                require(value != null && value in 1..MAX_ORPHAN_GRACE_MS) {
                    "--orphan-grace-ms must be 1..$MAX_ORPHAN_GRACE_MS, was '${arg.removePrefix(GRACE_FLAG)}'"
                }
                grace = value
            }
            return HelperArgs(packageName, grace)
        }
    }
}
