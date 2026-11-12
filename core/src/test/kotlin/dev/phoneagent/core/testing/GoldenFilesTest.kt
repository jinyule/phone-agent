package dev.phoneagent.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The tree-agnostic part of the goldens: other roots, exact content, the described failure. */
class GoldenFilesTest {
    @TempDir
    lateinit var root: Path

    private val accept = "cp -R a/. b/"

    private fun files(): GoldenFiles {
        Files.createDirectories(root.resolve("expected/dir"))
        root.resolve("expected/dir/one.txt").writeText("one\n")
        return GoldenFiles(root.resolve("expected"), root.resolve("actual"), accept)
    }

    @Test
    fun `content is compared as given, with no newline added`() {
        val files = files()
        files.assertMatches("dir/one.txt", "one\n")
        assertThrows<AssertionFailedError> { files.assertMatches("dir/one.txt", "one") }
    }

    @Test
    fun `a match deletes the actual file an earlier run kept`() {
        val files = files()
        assertThrows<AssertionFailedError> { files.assertMatches("dir/one.txt", "two\n") }
        assertTrue(Files.exists(root.resolve("actual/dir/one.txt")))
        files.assertMatches("dir/one.txt", "one\n")
        assertFalse(Files.exists(root.resolve("actual/dir/one.txt")))
    }

    @Test
    fun `a described mismatch says where instead of printing both contents, and carries them`() {
        val failure =
            assertThrows<AssertionFailedError> {
                files().assertMatches("dir/one.txt", "two\n") { expected, actual -> "first line: $expected vs $actual" }
            }
        val message = failure.message.orEmpty()
        assertTrue(message.contains(root.resolve("actual/dir/one.txt").toString()), message)
        assertTrue(message.contains(accept), message)
        assertTrue(message.endsWith("first line: one\n vs two\n"), message)
        assertFalse(message.contains("==>"), "the two contents are not dumped: $message")
        assertEquals("one\n", failure.expected.value)
        assertEquals("two\n", failure.actual.value)
        assertEquals("two\n", root.resolve("actual/dir/one.txt").readText())
        assertEquals("one\n", root.resolve("expected/dir/one.txt").readText(), "the golden is untouched")
    }

    @Test
    fun `an equivalent text is compared in place of the golden's bytes`() {
        val files = files()
        files.assertEquivalent("dir/one.txt", "one, as read\n", "one, as read\n")
        val failure =
            assertThrows<AssertionFailedError> { files.assertEquivalent("dir/one.txt", "one, as read\n", "one\n") }
        assertTrue(failure.message.orEmpty().contains(root.resolve("expected/dir/one.txt").toString()), failure.message)
        assertEquals("one\n", root.resolve("actual/dir/one.txt").readText())
    }

    @Test
    fun `a missing golden fails and keeps the actual file`() {
        val failure = assertThrows<AssertionFailedError> { files().assertMatches("dir/new.txt", "new\n") }
        assertTrue(failure.message.orEmpty().contains("is missing"), failure.message)
        assertEquals("new\n", root.resolve("actual/dir/new.txt").readText())
    }
}
