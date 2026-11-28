package dev.phoneagent.buildlogic

import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/** Reads one file as it was at an earlier commit, with the `git` on PATH. */
internal class GitHistory(
    private val exec: ExecOperations,
    /** Directory of the file; paths are relative to it. */
    private val directory: File,
) {
    /**
     * Text of [fileName] at commit [ref], or null when that commit has no such file. Throws
     * [GradleException] when [ref] is not a full commit sha, or not a commit in this clone. Only a
     * full sha always names the same content, which the task's up-to-date check and cache rely on.
     */
    fun read(
        ref: String,
        fileName: String,
    ): String? {
        if (!FULL_SHA.matches(ref)) {
            throw GradleException(
                "privilegedProtocolBaseRef '$ref' is not a full commit sha (40 or 64 lowercase hex characters). " +
                    "A branch, tag or short sha can move while the task stays up to date; pass `git rev-parse <ref>`.",
            )
        }
        if (git("rev-parse", "--verify", "--quiet", "$ref^{commit}").code != 0) {
            throw GradleException(
                "privilegedProtocolBaseRef $ref is not a commit in this clone; fetch it first " +
                    "(`git fetch origin $ref`). A full clone may still lack it: a push's `before` that a " +
                    "force-push removed from every branch. CI fetches that by sha (scripts/ci/protocol-base-ref.sh).",
            )
        }
        val path = "$ref:./$fileName"
        if (git("cat-file", "-e", path).code != 0) return null
        val shown = git("show", path)
        if (shown.code != 0) throw GradleException("git show $path failed with ${shown.code}: ${shown.text}")
        return shown.text
    }

    private class Run(
        val code: Int,
        val text: String,
    )

    private fun git(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val result =
            exec.exec {
                commandLine(listOf("git", "-C", directory.path) + args)
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        return Run(result.exitValue, out.toString(Charsets.UTF_8))
    }

    private companion object {
        /** SHA-1 or SHA-256 object name, as `git rev-parse` prints it. */
        val FULL_SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}
