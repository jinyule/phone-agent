package dev.phoneagent.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * How the recorder runs gitleaks and reads its outcome, against fake executables (a shell script
 * that keeps its arguments and exits as told), so it does not depend on gitleaks being installed:
 * leaks, a clean scan and a failure of gitleaks itself each give their own result.
 */
class GitleaksTest {
    @TempDir
    lateinit var temp: Path

    private val fixture: Path get() = Files.createDirectories(temp.resolve("fixture"))
    private val args: Path get() = temp.resolve("args.txt")

    /** A fake gitleaks that keeps its arguments, prints [output] and exits with [exit] after [sleepS] seconds. */
    private fun fake(
        exit: Int,
        output: String = "",
        sleepS: Int = 0,
    ): Path {
        val script = temp.resolve("gitleaks")
        script.writeText(
            "#!/bin/sh\nprintf '%s\\n' \"$@\" > '$args'\nprintf '$output'\nsleep $sleepS\nexit $exit\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }

    @Test
    fun `a scan that finds nothing is clean`() {
        assertEquals(Gitleaks.Scan.Clean, Gitleaks.scan(fixture, fake(exit = 0)))
    }

    @Test
    fun `leaks give their report`() {
        val scan = Gitleaks.scan(fixture, fake(exit = Gitleaks.LEAKS_EXIT, output = "File: session.jsonl\\nLine: 3\\n"))
        assertEquals(Gitleaks.Scan.Found("File: session.jsonl\nLine: 3\n"), scan)
    }

    @Test
    fun `a failure of gitleaks itself fails and says so, not that it found leaks`() {
        val failure =
            assertThrows<IllegalStateException> {
                Gitleaks.scan(fixture, fake(exit = 1, output = "unable to load gitleaks config"))
            }
        val message = failure.message.orEmpty()
        assertTrue(message.startsWith("gitleaks failed (exit 1)"), message)
        assertTrue("unable to load gitleaks config" in message, message)
        assertTrue("not scanned" in message, message)
    }

    @Test
    fun `gitleaks is asked for a located, redacted report and its own exit code for leaks`() {
        Gitleaks.scan(fixture, fake(exit = 0))
        val given = args.readLines()
        assertEquals("dir", given.first())
        assertEquals(fixture.toString(), given.last())
        val exitCode = given.indexOf("--exit-code")
        assertTrue(exitCode >= 0, "$given")
        assertEquals(Gitleaks.LEAKS_EXIT.toString(), given[exitCode + 1])
        listOf(
            "--verbose",
            "--redact",
            "--no-color",
            "--no-banner",
        ).forEach { assertTrue(it in given, "$it in $given") }
    }

    @Test
    fun `the repository's config is given`() {
        Gitleaks.scan(fixture, fake(exit = 0))
        val given = args.readLines()
        val config = given.indexOf("--config")
        assertTrue(config >= 0, "$given")
        assertEquals(".gitleaks.toml", Path.of(given[config + 1]).fileName.toString())
        assertTrue(Files.isRegularFile(Path.of(given[config + 1])), given[config + 1])
    }

    @Test
    fun `the exit code for leaks is none gitleaks uses for itself`() {
        assertFalse(Gitleaks.LEAKS_EXIT in setOf(0, 1), "gitleaks exits 0 when clean and 1 on its own errors")
    }

    @Test
    fun `output that is not valid UTF-8 is read, not thrown on`() {
        // gitleaks cuts a finding's context at a byte count, which can split a character.
        val scan = Gitleaks.scan(fixture, fake(exit = Gitleaks.LEAKS_EXIT, output = "Finding: \\345\\274 token"))
        assertTrue((scan as Gitleaks.Scan.Found).report.endsWith(" token"), scan.report)
    }

    @Test
    fun `colour codes are taken out of the report`() {
        // --no-color covers the findings, not gitleaks' own log lines.
        val failure =
            assertThrows<IllegalStateException> {
                Gitleaks.scan(fixture, fake(exit = 1, output = "\\033[90m3:29AM\\033[0m FTL unable to load"))
            }
        assertTrue(failure.message.orEmpty().endsWith("\n3:29AM FTL unable to load"), failure.message)
    }

    @Test
    fun `a gitleaks that does not finish in time fails`() {
        val failure =
            assertThrows<IllegalStateException> { Gitleaks.scan(fixture, fake(exit = 0, sleepS = 5), timeoutS = 1) }
        assertTrue("did not finish" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `without gitleaks nothing is scanned`() {
        assertEquals(Gitleaks.Scan.NotInstalled, Gitleaks.scan(fixture, executable = null))
    }
}
