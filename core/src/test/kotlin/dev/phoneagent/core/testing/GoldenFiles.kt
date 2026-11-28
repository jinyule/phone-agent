package dev.phoneagent.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path

/**
 * A tree of golden files under [expectedRoot], compared byte for byte: the comparison and the
 * acceptance step shared by the prompt-surface goldens ([Golden]), the loop goldens and the
 * sidecars of session fixtures. Each tree has its own [accept] step, so accepting one never
 * rewrites another.
 *
 * Tests only compare. On a mismatch, or when the golden is missing, the actual content is written
 * to the same path under [actualRoot] and the test fails with an [AssertionFailedError] that names
 * both files and [accept] and carries both contents; on a match a stale actual file of an earlier
 * run is deleted.
 *
 * @param expectedRoot where the committed goldens are, relative to the module directory.
 * @param actualRoot where mismatching content is kept for review, under the build directory.
 * @param accept the acceptance step, run from the repository root after reviewing the actual files.
 */
class GoldenFiles(
    private val expectedRoot: Path,
    private val actualRoot: Path,
    val accept: String,
) {
    /**
     * Compares [content] with the golden at [path], byte for byte; a mismatch leaves [content]
     * under [actualDir]. [describe] may say where the two differ (it gets the golden, `null` when
     * missing, and [content]); its text then replaces the two contents in the message, which a
     * long file would flood.
     */
    fun assertMatches(
        path: String,
        content: String,
        actualDir: Path = actualRoot,
        describe: (expected: String?, actual: String) -> String? = { _, _ -> null },
    ) {
        val expectedFile = expectedRoot.resolve(path)
        val expected = if (Files.exists(expectedFile)) Files.readString(expectedFile) else null
        compare(path, expected, content, actualDir, describe)
    }

    /**
     * [assertMatches] against [expected] in place of the golden's bytes: the golden as the current
     * code reads and writes it (a session log written again by the current codec), so a file of an
     * older layout still matches what it means. A mismatch is reported and kept the same way.
     */
    fun assertEquivalent(
        path: String,
        expected: String?,
        content: String,
        describe: (expected: String?, actual: String) -> String? = { _, _ -> null },
    ) {
        compare(path, expected, content, actualRoot, describe)
    }

    private fun compare(
        path: String,
        expected: String?,
        content: String,
        actualDir: Path,
        describe: (expected: String?, actual: String) -> String?,
    ) {
        val expectedFile = expectedRoot.resolve(path)
        val kept = actualDir.resolve(path)
        if (expected == content) {
            Files.deleteIfExists(kept)
            return
        }
        Files.createDirectories(kept.parent)
        Files.writeString(kept, content)
        val what = if (expected == null) "is missing" else "differs"
        val message =
            "golden $expectedFile $what; the actual file is $kept. " +
                "Review it, then accept from the repository root: $accept"
        val detail = describe(expected, content) ?: return assertEquals(expected, content, message)
        throw AssertionFailedError("$message\n$detail", expected, content)
    }
}
