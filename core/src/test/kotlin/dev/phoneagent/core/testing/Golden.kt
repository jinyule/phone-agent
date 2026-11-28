package dev.phoneagent.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/**
 * Golden files of the model-visible surface (engineering-baseline §3.1 L3), compared byte for byte.
 *
 * A golden file holds the text followed by one newline (the file convention; nothing of the text
 * itself is trimmed, so a text that ends in a newline is stored with two). Tests only compare: on a
 * mismatch, or when the golden is missing, the actual file content is written to
 * `build/prompt-surface-actual/<path>` and the test fails. Accepting it is a separate, explicit
 * step after a test run: review the diff, then copy it over with [ACCEPT] (the failure message
 * prints it).
 */
object Golden {
    private const val EXPECTED = "src/test/resources/prompt-surface"
    private val actualRoot: Path = Path.of("build/prompt-surface-actual")

    /** The acceptance step, run from the repository root after reviewing the actual files. */
    const val ACCEPT: String = "cp -R core/build/prompt-surface-actual/. core/src/test/resources/prompt-surface/"

    /** Compares [text] with the golden at [path]; a mismatch leaves the actual file under [actualDir]. */
    fun assertMatches(
        path: String,
        text: String,
        actualDir: Path = actualRoot,
    ) {
        val actual = text + "\n"
        val expectedFile = Path.of(EXPECTED, path)
        val expected = if (Files.exists(expectedFile)) Files.readString(expectedFile) else null
        val kept = actualDir.resolve(path)
        if (expected == actual) {
            Files.deleteIfExists(kept)
            return
        }
        Files.createDirectories(kept.parent)
        Files.writeString(kept, actual)
        val what = if (expected == null) "is missing" else "differs"
        assertEquals(
            expected,
            actual,
            "golden $EXPECTED/$path $what; the actual file is $kept. " +
                "Review it, then accept from the repository root: $ACCEPT",
        )
    }
}
