package dev.phoneagent.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a class of one variant breaks one of [boundaries] ([ClassBoundaries.violations]).
 * [classDirs] and [classJars] are the variant's own compiled classes (AGP's project-scope
 * `CLASSES`: Kotlin and javac output), not its dependencies'.
 */
@CacheableTask
abstract class VerifyClassBoundariesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:Input
    abstract val boundaries: ListProperty<ClassBoundary>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val classes =
            ClassBoundaries.read(
                classDirs.get().map { it.asFile },
                classJars.get().map { it.asFile },
            )
        val violations = ClassBoundaries.violations(classes, boundaries.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Classes reference a package they may not (see ADR 2026-11-03-builtin-adb-app-wiring, decision 9):\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("${classes.size} classes checked\n")
    }
}
