package dev.phoneagent.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails unless `protocol-history.txt` records the current VERSION with the current AIDL fingerprint. */
@CacheableTask
abstract class VerifyPrivilegedProtocolTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aidlDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val protocolSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val history: RegularFileProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val root = aidlDir.get().asFile
        val files =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "aidl" }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        if (files.isEmpty()) throw GradleException("no .aidl files under $root")
        val recorded =
            try {
                evaluate(files)
            } catch (e: IllegalArgumentException) {
                throw GradleException(e.message ?: "invalid privileged protocol files", e)
            }
        marker.get().asFile.writeText(recorded + "\n")
    }

    /** The verified `<version> <fingerprint>` line; throws [GradleException] saying what to fix. */
    private fun evaluate(files: Map<String, String>): String {
        val version = PrivilegedProtocolCheck.parseVersion(protocolSource.get().asFile.readText())
        val fingerprint = PrivilegedProtocolCheck.fingerprint(files)
        val entries = PrivilegedProtocolCheck.parseHistory(history.get().asFile.readText())
        PrivilegedProtocolCheck.verify(entries, version, fingerprint)?.let { throw GradleException(it) }
        return "$version $fingerprint"
    }
}
