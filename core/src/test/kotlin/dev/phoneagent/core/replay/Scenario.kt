package dev.phoneagent.core.replay

import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.SurfaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `scenario.json` of a session fixture: what the recording was, the model it talked to, the
 * product settings it ran with (only those that differ from the defaults), the policy rules it was
 * judged by, and the device facts that are not in the log. Read strictly ([ReplayFiles.scenario]):
 * an unknown or missing key fails the load.
 *
 * @property format layout version of this file, [FORMAT].
 * @property recording how the session was produced.
 * @property description what the scenario shows, for reviewers.
 * @property model the main model as the recording saw it; replay clamps reasoning levels with its
 *   capabilities.
 * @property task the task's start settings (`task/start`).
 * @property policy the rules the verdicts came from; replay requires the current ones to be the same.
 * @property assembly product settings that differ from the defaults.
 * @property device the device side.
 */
@Serializable
data class Scenario(
    val format: Int,
    val recording: Recording,
    val description: String,
    val model: ModelInfo,
    val task: ScenarioTask,
    val policy: PolicyPin,
    val assembly: Assembly = Assembly(),
    val device: Device,
) {
    init {
        require(format == FORMAT) { "scenario.json format $format; this build reads format $FORMAT" }
        require(description.isNotBlank()) { "scenario.json: description must not be blank" }
    }

    companion object {
        /** The layout version this build reads and writes. */
        const val FORMAT: Int = 1
    }
}

/** How a fixture was produced. */
@Serializable
enum class Recording {
    /** Written by a scripted source on the JVM (`AuthoredScenarios`). */
    @SerialName("authored")
    AUTHORED,

    /** Recorded against a real model and a real device (WP11). */
    @SerialName("live")
    LIVE,
}

/** The settings of `task/start`. */
@Serializable
data class ScenarioTask(
    val level: SecurityLevel,
    val unattended: Boolean,
)

/**
 * The policy rules a fixture's verdicts came from. Replay requires both to be what it runs with
 * ([PolicyPins.current]): a fixture judged by other rules is stale and is refreshed, which
 * rewrites this pin. Until the run's rules are in the log (WP09), they are pinned here.
 *
 * @property rulesVersion `PolicyTuning.RULES_VERSION` at recording time, >= 1.
 * @property tuning every `PolicyTuning` field, by name ([PolicyPins.values]), so a changed
 *   default shows even when `RULES_VERSION` was not bumped with it.
 */
@Serializable
data class PolicyPin(
    val rulesVersion: Int,
    val tuning: Map<String, Int>,
) {
    init {
        require(rulesVersion >= 1) { "scenario.json: policy.rulesVersion must be >= 1, got $rulesVersion" }
    }
}

/**
 * Product settings of the recording that differ from the defaults. The others (screen format,
 * tools and loop configs) are the defaults; a scenario that needs one of them set adds a field.
 *
 * @property vision whether the screen components were built for a model that sees images.
 * @property ownPackages the app's package names, as `PolicyRules` takes them.
 * @property ownLabels the app's labels, as `PolicyRules` takes them.
 * @property tuning the `PolicyTuning` fields set for this scenario, by name; the others keep their
 *   defaults ([PolicyPins.tuning]).
 * @property maxOutputTokens the main requests' output cap (`RequestPlan.maxOutputTokens`), `null`
 *   for the adapter's default.
 * @property reasoning the reasoning level the main requests ask for (`RequestPlan.reasoning`),
 *   `null` for the provider's default.
 */
@Serializable
data class Assembly(
    val vision: Boolean = false,
    val ownPackages: Set<String> = setOf("dev.phoneagent"),
    val ownLabels: Set<String> = setOf("PhoneAgent"),
    val tuning: Map<String, Int> = emptyMap(),
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningLevel? = null,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "scenario.json: assembly.maxOutputTokens must be > 0, got $maxOutputTokens"
        }
    }
}

/**
 * The device side of a recording (engineering-baseline §3.2: device-dependent values live here, not
 * in the log).
 *
 * @property surface the display the screen port drove; replay serves it as `ScreenPort.surface`.
 * @property widthPx display width in pixels, when known.
 * @property heightPx display height in pixels, when known.
 * @property densityDpi display density, when known.
 * @property build the ROM build (fingerprint), when known.
 * @property sdk the Android API level, when known.
 * @property packages version names of the apps the session touched, by package.
 * @property apps what `AppCatalog.apps()` returned.
 */
@Serializable
data class Device(
    val surface: SurfaceRef,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    val build: String? = null,
    val sdk: Int? = null,
    val packages: Map<String, String> = emptyMap(),
    val apps: List<AppEntry> = emptyList(),
) {
    init {
        for ((name, value) in listOf("widthPx" to widthPx, "heightPx" to heightPx, "densityDpi" to densityDpi)) {
            require(value == null || value > 0) { "scenario.json: device.$name must be > 0, got $value" }
        }
    }
}
