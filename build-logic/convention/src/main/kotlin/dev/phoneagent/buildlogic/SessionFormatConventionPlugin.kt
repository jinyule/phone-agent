package dev.phoneagent.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `generateSessionFormatFixture` for `:core`: writes the session-format fixture of the current
 * format version (`src/test/resources/session-format/v{N}/session.jsonl`) from the test samples.
 * The generator refuses an existing version directory, so a published fixture is never rewritten
 * (ADR 2026-11-01-core-contracts); tests only read fixtures.
 */
class SessionFormatConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.withPlugin("java") {
                val sourceSets = extensions.getByType<SourceSetContainer>()
                val fixtures =
                    layout.projectDirectory
                        .dir(FIXTURE_ROOT)
                        .asFile.absolutePath
                tasks.register<JavaExec>("generateSessionFormatFixture") {
                    group = "session format"
                    description = "Writes the fixture of a new session format version; refuses published ones."
                    classpath = sourceSets.getByName("test").runtimeClasspath
                    mainClass.set(GENERATOR)
                    args(fixtures)
                }
            }
        }

    private companion object {
        const val FIXTURE_ROOT = "src/test/resources/session-format"
        const val GENERATOR = "dev.phoneagent.core.session.FormatFixture"
    }
}
