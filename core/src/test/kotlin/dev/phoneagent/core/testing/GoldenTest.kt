package dev.phoneagent.core.testing

import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.screen.ScreenFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Goldens are compared byte for byte, trailing newlines included, and tests never write them:
 * a mismatch fails and leaves the actual text under the build directory for review.
 */
class GoldenTest {
    private val path = "screens/chat-page.expected.txt"
    private val chatPage = ScreenFormatter(lexicon = Screens.LEXICON).page(Screens.CHAT, SnapshotDetail.BRIEF)

    @Test
    fun `the unchanged text matches`(
        @TempDir actual: Path,
    ) {
        Golden.assertMatches(path, chatPage, actual)
        assertTrue(Files.notExists(actual.resolve(path)), "nothing is kept for a match")
    }

    @Test
    fun `one more trailing newline is a mismatch`(
        @TempDir actual: Path,
    ) {
        val failure = runCatching { Golden.assertMatches(path, chatPage + "\n", actual) }.exceptionOrNull()
        assertTrue(failure is AssertionFailedError, "a trailing newline changes the bytes: $failure")
    }

    @Test
    fun `a dropped last character is a mismatch`(
        @TempDir actual: Path,
    ) {
        val failure = runCatching { Golden.assertMatches(path, chatPage.dropLast(1), actual) }.exceptionOrNull()
        assertTrue(failure is AssertionFailedError, "a dropped last character changes the bytes: $failure")
    }

    @Test
    fun `a mismatch keeps the actual text under build and leaves the golden alone`(
        @TempDir actual: Path,
    ) {
        val source = Path.of("src/test/resources/prompt-surface", path)
        val before = source.readText()
        val failure = runCatching { Golden.assertMatches(path, "not the page", actual) }.exceptionOrNull()
        assertTrue(failure is AssertionFailedError, "a mismatch fails: $failure")
        assertTrue(failure!!.message!!.contains(actual.resolve(path).toString()), failure.message)
        assertEquals("not the page\n", actual.resolve(path).readText(), "kept with the file's final newline")
        assertEquals(before, source.readText(), "the golden is untouched")
    }

    @Test
    fun `no test source writes goldens back`() {
        val switch = "PA_UPDATE" + "_GOLDEN"
        val offenders =
            Files.walk(Path.of("src/test/kotlin")).use { files ->
                files
                    .filter { it.toString().endsWith(".kt") && switch in it.readText() }
                    .map { it.fileName.toString() }
                    .toList()
            }
        assertEquals(emptyList<String>(), offenders, "tests only compare; accepting a golden is a separate step")
    }
}
