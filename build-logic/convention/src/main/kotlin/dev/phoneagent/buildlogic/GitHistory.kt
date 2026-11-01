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
     * [GradleException] when [ref] is malformed or not a commit in this clone.
     */
    fun read(
        ref: String,
        fileName: String,
    ): String? {
        if (!REF.matches(
                ref,
            )
        ) {
            throw GradleException("privilegedProtocolBaseRef '$ref' is not a commit sha or ref name.")
        }
        if (git("rev-parse", "--verify", "--quiet", "$ref^{commit}").code != 0) {
            throw GradleException(
                "privilegedProtocolBaseRef $ref is not a commit in this clone; fetch it first " +
                    "(CI: actions/checkout with fetch-depth: 0).",
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
        val REF = Regex("""[A-Za-z0-9][A-Za-z0-9._/~^-]*""")
    }
}
