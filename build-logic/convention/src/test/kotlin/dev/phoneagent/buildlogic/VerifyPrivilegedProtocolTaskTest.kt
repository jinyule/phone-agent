package dev.phoneagent.buildlogic

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `verifyPrivilegedProtocol` in a throwaway git repository: what the local check catches, and what
 * only the CI baseline (`-PprivilegedProtocolBaseRef=<sha>`) catches.
 */
class VerifyPrivilegedProtocolTaskTest {
    @TempDir
    lateinit var dir: File

    private val aidlPath = "src/main/aidl/dev/phoneagent/privileged/IPrivilegedCore.aidl"
    private val aidl =
        """
        package dev.phoneagent.privileged;
        interface IPrivilegedCore {
            int protocolVersion() = 1;
            int[] identity() = 2;
            void releaseAll() = 3;
            oneway void shutdown() = 4;
            boolean injectKey(int displayId, int keyCode) = 19;
        }
        """.trimIndent()
    private val changed = aidl.replace("int keyCode)", "int keyCode, int metaState)")

    private fun write(
        path: String,
        text: String,
    ) {
        File(dir, path).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun fingerprint(text: String): String =
        PrivilegedProtocolCheck.fingerprint(mapOf("dev/phoneagent/privileged/IPrivilegedCore.aidl" to text))

    private fun protocol(
        version: Int,
        text: String,
        history: String,
    ) {
        write(aidlPath, text)
        write(
            "src/main/kotlin/dev/phoneagent/privileged/PrivilegedProtocol.kt",
            "object P {\n    const val VERSION: Int = $version\n}\n",
        )
        write("protocol-history.txt", history)
    }

    private fun git(vararg args: String): String {
        val command =
            listOf("git", "-c", "user.name=test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false")
        val process = ProcessBuilder(command + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    /** Commits the working tree and returns the commit's sha. */
    private fun commit(): String {
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", "state")
        return git("rev-parse", "HEAD")
    }

    private fun verify(vararg args: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(listOf("verifyPrivilegedProtocol", "--stacktrace") + args)

    private fun BuildResult.outcome(): TaskOutcome? = task(":verifyPrivilegedProtocol")?.outcome

    @BeforeEach
    fun createProject() {
        write("settings.gradle", "rootProject.name = 'protocol-check'\n")
        write("build.gradle", "plugins { id 'phoneagent.privileged.protocol' }\n")
        git("init", "-q")
    }

    @Test
    fun rewritingAHistoryLineInPlaceFailsOnlyAgainstTheBaseRef() {
        protocol(1, aidl, "1 ${fingerprint(aidl)}\n")
        val base = commit()
        protocol(1, changed, "1 ${fingerprint(changed)}\n")

        assertEquals(TaskOutcome.SUCCESS, verify().build().outcome(), "the local check alone cannot see the rewrite")
        val failure = verify("-PprivilegedProtocolBaseRef=$base").buildAndFail()
        assertTrue(failure.output.contains("1 ${fingerprint(aidl)}"), failure.output)
    }

    @Test
    fun removingAnOldHistoryLineFailsAgainstTheBaseRef() {
        protocol(1, aidl, "1 ${fingerprint(aidl)}\n")
        val base = commit()
        protocol(2, changed, "2 ${fingerprint(changed)}\n")

        val failure = verify("-PprivilegedProtocolBaseRef=$base").buildAndFail()
        assertTrue(failure.output.contains("protocol-history.txt"), failure.output)
    }

    @Test
    fun aBumpWithAnAppendedLinePassesAgainstTheBaseRef() {
        protocol(1, aidl, "1 ${fingerprint(aidl)}\n")
        val base = commit()
        protocol(2, changed, "1 ${fingerprint(aidl)}\n2 ${fingerprint(changed)}\n")

        assertEquals(TaskOutcome.SUCCESS, verify("-PprivilegedProtocolBaseRef=$base").build().outcome())
    }

    @Test
    fun aBaseWithoutHistoryCountsAsAnEmptyPrefix() {
        write("README", "before the helper existed\n")
        val base = commit()
        protocol(1, aidl, "1 ${fingerprint(aidl)}\n")

        assertEquals(TaskOutcome.SUCCESS, verify("-PprivilegedProtocolBaseRef=$base").build().outcome())
    }

    @Test
    fun aBaseRefThatIsNotInTheCloneFailsLoudly() {
        protocol(1, aidl, "1 ${fingerprint(aidl)}\n")
        commit()

        val failure = verify("-PprivilegedProtocolBaseRef=${"0".repeat(39)}1").buildAndFail()
        assertTrue(failure.output.contains("is not a commit"), failure.output)
    }

    @Test
    fun droppingOnewayFromShutdownFailsEvenWithABump() {
        val notOneway = aidl.replace("oneway void shutdown()", "void shutdown()")
        protocol(2, notOneway, "1 ${fingerprint(aidl)}\n2 ${fingerprint(notOneway)}\n")

        val failure = verify().buildAndFail()
        assertTrue(failure.output.contains("oneway void shutdown ( ) = 4 ;"), failure.output)
    }
}
