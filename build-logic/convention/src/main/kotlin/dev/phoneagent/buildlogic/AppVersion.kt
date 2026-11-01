package dev.phoneagent.buildlogic

/**
 * App version parsed from `phoneagent.versionName` in `gradle.properties`.
 *
 * Accepted form: `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-(alpha|beta|rc).N`.
 * [versionCode] is `MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + s`, where `s` is
 * alpha 0–29, beta 30–59, rc 60–89 and final 99, so every later release gets a
 * strictly larger code (docs/design/engineering-baseline.md §6).
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stage: Stage,
    val stageNumber: Int,
) {
    /** Pre-release stage; [offset] is added to the patch slot, [maxNumber] bounds `N`. */
    enum class Stage(
        val label: String?,
        val offset: Int,
        val maxNumber: Int,
    ) {
        ALPHA("alpha", 0, 29),
        BETA("beta", 30, 29),
        RC("rc", 60, 29),
        FINAL(null, 99, 0),
    }

    val versionName: String
        get() =
            buildString {
                append("$major.$minor.$patch")
                if (stage != Stage.FINAL) append("-${stage.label}.$stageNumber")
            }

    val versionCode: Int
        get() = major * 1_000_000 + minor * 10_000 + patch * 100 + stage.offset + stageNumber

    companion object {
        private const val MAX_MAJOR = 2099
        private const val MAX_MINOR_PATCH = 99
        private val PATTERN = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.(0|[1-9]\d*))?""")

        /** Parses [name]; throws [IllegalArgumentException] naming the offending value. */
        fun parse(name: String): AppVersion {
            val match =
                requireNotNull(PATTERN.matchEntire(name)) {
                    "Invalid versionName '$name': expected MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-(alpha|beta|rc).N"
                }
            val (major, minor, patch, label, number) = match.destructured
            val stage = if (label.isEmpty()) Stage.FINAL else Stage.entries.single { it.label == label }
            val version =
                AppVersion(
                    major = major.toInt(),
                    minor = minor.toInt(),
                    patch = patch.toInt(),
                    stage = stage,
                    stageNumber = if (number.isEmpty()) 0 else number.toInt(),
                )
            require(version.major <= MAX_MAJOR) { "Invalid versionName '$name': MAJOR must be <= $MAX_MAJOR" }
            require(version.minor <= MAX_MINOR_PATCH) { "Invalid versionName '$name': MINOR must be <= 99" }
            require(version.patch <= MAX_MINOR_PATCH) { "Invalid versionName '$name': PATCH must be <= 99" }
            require(version.stageNumber <= stage.maxNumber) {
                "Invalid versionName '$name': ${stage.label}.N must be <= ${stage.maxNumber}"
            }
            require(version.versionCode >= 1) { "Invalid versionName '$name': versionCode must be >= 1" }
            return version
        }
    }
}
