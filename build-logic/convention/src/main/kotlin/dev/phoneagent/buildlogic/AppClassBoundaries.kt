package dev.phoneagent.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

private const val SELF_CHECK = "dev.phoneagent.app.debug.AdbSelfCheck"

/** The classes compiled from the debug adb self-check, `AdbSelfCheck.kt`, that use the adb client. */
private val SELF_CHECK_CLASSES = listOf(SELF_CHECK, "${SELF_CHECK}Kt")

/**
 * `:app`'s boundaries around the built-in adb client, which is setup code that must never be
 * reachable from a tool or the agent (AGENTS.md §3.4; core's PackageDependencyTest keeps core's
 * side):
 * - `core.adb` only from the launch package `app.adb` and the debug self-check;
 * - `app.adb` only from the application class (it starts automatic launches) and the self-check,
 *   so a third package that hands on what `app.adb` gives must be added here too.
 */
internal val APP_CLASS_BOUNDARIES: List<ClassBoundary> =
    listOf(
        ClassBoundary(
            target = "dev.phoneagent.core.adb",
            packages = listOf("dev.phoneagent.app.adb"),
            classes = SELF_CHECK_CLASSES,
        ),
        ClassBoundary(
            target = "dev.phoneagent.app.adb",
            packages = emptyList(),
            classes = listOf("dev.phoneagent.app.PhoneAgentApplication") + SELF_CHECK_CLASSES,
        ),
    )

/**
 * `verify<Variant>ClassBoundaries` for each variant (part of `check`): [APP_CLASS_BOUNDARIES] over
 * the variant's compiled classes.
 */
internal fun Project.verifyClassBoundaries(variant: ApplicationVariant) {
    val verify =
        tasks.register<VerifyClassBoundariesTask>(
            "verify${variant.name.replaceFirstChar(Char::uppercase)}ClassBoundaries",
        ) {
            description =
                "Fails when a ${variant.name} class references the built-in adb client from outside its packages."
            group = "verification"
            boundaries.set(APP_CLASS_BOUNDARIES)
            marker.set(layout.buildDirectory.file("class-boundaries/${variant.name}.txt"))
        }
    variant.artifacts
        .forScope(ScopedArtifacts.Scope.PROJECT)
        .use(verify)
        .toGet(ScopedArtifact.CLASSES, VerifyClassBoundariesTask::classJars, VerifyClassBoundariesTask::classDirs)
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
}
