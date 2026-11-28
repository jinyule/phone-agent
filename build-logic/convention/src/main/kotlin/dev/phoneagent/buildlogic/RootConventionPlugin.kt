package dev.phoneagent.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import dev.detekt.gradle.Detekt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Root project: repository-wide formatting (Spotless + ktlint) and the detekt canary,
 * which proves the detekt configuration still rejects each rule we rely on.
 */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            check(this == rootProject) { "phoneagent.root must be applied to the root project only" }
            pluginManager.apply("base")
            configureSpotless()
            configureDetektCanary()
        }

    private fun Project.configureSpotless() {
        pluginManager.apply("com.diffplug.spotless")
        val ktlintVersion = libs.versionOf("ktlint")
        extensions.configure<SpotlessExtension> {
            kotlin {
                target(sourceTree("**/src/**/*.kt"))
                ktlint(ktlintVersion)
            }
            kotlinGradle {
                target(sourceTree("*.gradle.kts", "**/*.gradle.kts"))
                ktlint(ktlintVersion)
            }
            format("misc") {
                target(
                    sourceTree(
                        "*.md",
                        "**/*.md",
                        "**/*.yml",
                        "**/*.yaml",
                        "**/*.toml",
                        ".gitignore",
                        ".gitattributes",
                        ".editorconfig",
                    ),
                )
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }

    /**
     * Files under the root directory matching [includes]. The excludes prune whole directories
     * while the tree is walked. Spotless's `targetExclude` only filters afterwards, so the walk
     * still entered build directories that compile tasks were rewriting in parallel and failed
     * with "Could not read path".
     */
    private fun Project.sourceTree(vararg includes: String): ConfigurableFileTree =
        fileTree(rootDir) {
            include(*includes)
            exclude(PRUNED_DIRECTORIES)
        }

    private fun Project.configureDetektCanary() {
        pluginManager.apply("dev.detekt")
        val canaryClasspath =
            configurations.register("detektCanaryClasspath") {
                isCanBeConsumed = false
                isCanBeResolved = true
            }
        dependencies.add(canaryClasspath.name, libs.library("kotlinx-coroutines-core"))
        rejectNonReproducibleVersions()

        val canary =
            tasks.register<Detekt>("detektCanary") {
                description = "Runs detekt with the project config on config/detekt/canary (known-bad code)."
                setSource(files("config/detekt/canary"))
                config.setFrom(files("config/detekt/detekt.yml"))
                buildUponDefaultConfig.set(true)
                classpath.setFrom(canaryClasspath)
                jvmTarget.set(JVM_TARGET.toString())
                basePath.set(rootDir.absolutePath)
                ignoreFailures.set(true)
                reports {
                    checkstyle.required.set(true)
                    html.required.set(false)
                    sarif.required.set(false)
                    markdown.required.set(false)
                }
            }
        val verify =
            tasks.register<VerifyDetektCanaryTask>("verifyDetektCanary") {
                description = "Fails unless every rule listed in config/detekt/canary/expected-rules.txt fired."
                group = "verification"
                report.set(canary.flatMap { it.reports.checkstyle.outputLocation })
                expectedRules.set(layout.projectDirectory.file("config/detekt/canary/expected-rules.txt"))
                marker.set(layout.buildDirectory.file("detekt-canary/verified"))
            }
        tasks.named("check") { dependsOn(verify) }
    }
}

/** Generated or tool-owned directories that formatting never looks into. */
private val PRUNED_DIRECTORIES = listOf("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".git/**")
