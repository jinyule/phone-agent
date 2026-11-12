package dev.phoneagent.privileged.helper

import dev.phoneagent.privileged.isPackageName

/**
 * Command line of [HelperMain]: `<package> [--orphan-grace-ms=<ms>] [--package-check-ms=<ms>]`.
 * The nonce is not an argument: it arrives on stdin.
 */
internal data class HelperArgs(
    /** Package of the app to hand the Binder to (primary user only). */
    val packageName: String,
    /** How long resources of a dead app are kept before `releaseAll` (phase1-architecture §2.2). */
    val orphanGraceMs: Long = DEFAULT_ORPHAN_GRACE_MS,
    /** How often the package is re-checked while the app is dead (phase1-architecture §2.2). */
    val packageCheckMs: Long = DEFAULT_PACKAGE_CHECK_MS,
) {
    companion object {
        const val USAGE: String =
            "usage: HelperMain <package> [--orphan-grace-ms=<1..600000>] [--package-check-ms=<1000..600000>] " +
                "(nonce on stdin)"
        const val DEFAULT_ORPHAN_GRACE_MS: Long = 10_000
        const val DEFAULT_PACKAGE_CHECK_MS: Long = 30_000
        private const val GRACE_FLAG = "--orphan-grace-ms="
        private const val CHECK_FLAG = "--package-check-ms="
        private val GRACE_RANGE = 1L..600_000L
        private val CHECK_RANGE = 1_000L..600_000L

        /** Parses [args]; throws [IllegalArgumentException] naming the offending value. */
        fun parse(args: Array<String>): HelperArgs {
            require(args.isNotEmpty()) { "missing <package>" }
            val packageName = args[0]
            require(isPackageName(packageName)) { "invalid package name '$packageName'" }
            var parsed = HelperArgs(packageName)
            for (arg in args.drop(1)) {
                parsed =
                    when {
                        arg.startsWith(GRACE_FLAG) -> parsed.copy(orphanGraceMs = millis(arg, GRACE_FLAG, GRACE_RANGE))
                        arg.startsWith(CHECK_FLAG) -> parsed.copy(packageCheckMs = millis(arg, CHECK_FLAG, CHECK_RANGE))
                        else -> throw IllegalArgumentException("unknown argument '$arg'")
                    }
            }
            return parsed
        }

        private fun millis(
            arg: String,
            flag: String,
            range: LongRange,
        ): Long {
            val text = arg.removePrefix(flag)
            val value = text.toLongOrNull()
            require(value != null && value in range) {
                "${flag.removeSuffix("=")} must be ${range.first}..${range.last}, was '$text'"
            }
            return value
        }
    }
}
