package dev.phoneagent.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * detekt for every Kotlin module, plus reproducible dependency resolution.
 *
 * `check` runs the type-resolution tasks (`detektMain`, `detektTest`): rules such as
 * `SuspendFunSwallowedCancellation` and `ElseCaseInsteadOfExhaustiveWhen` are silently
 * skipped by the plain `detekt` task.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("dev.detekt")
            val rootDir = isolated.rootProject.projectDirectory
            extensions.configure<DetektExtension> {
                toolVersion.set(libs.versionOf("detekt"))
                config.setFrom(rootDir.file("config/detekt/detekt.yml"))
                buildUponDefaultConfig.set(true)
                basePath.set(rootDir)
                parallel.set(true)
            }
            tasks.named("check") {
                dependsOn("detektMain", "detektTest")
            }
            rejectNonReproducibleVersions()
        }
}
