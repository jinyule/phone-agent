package dev.phoneagent.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Fails unless transactions 1-4 are unchanged and `protocol-history.txt` records the current VERSION
 * with the current AIDL fingerprint. With [baseRef] (CI only, a full commit sha) the history must
 * also start with the history of that commit.
 */
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

    /**
     * Full sha of the commit whose history must stay a prefix (`-PprivilegedProtocolBaseRef`);
     * unset locally. A full sha names fixed content, so it is a sound up-to-date and cache key;
     * anything that can move (branch, tag, short sha) fails the task.
     */
    @get:Input
    @get:Optional
    abstract val baseRef: Property<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

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
        val historyFile = history.get().asFile
        val entries = PrivilegedProtocolCheck.parseHistory(historyFile.readText())
        val problem =
            PrivilegedProtocolCheck.checkFrozen(files)
                ?: PrivilegedProtocolCheck.verify(entries, version, fingerprint)
                ?: baseRef.orNull?.let { ref ->
                    val baseline = GitHistory(exec, historyFile.parentFile).read(ref, historyFile.name).orEmpty()
                    PrivilegedProtocolCheck.verifyAppendOnly(PrivilegedProtocolCheck.parseHistory(baseline), entries)
                }
        problem?.let { throw GradleException(it) }
        return "$version $fingerprint"
    }
}
