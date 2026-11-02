package dev.phoneagent.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/**
 * Golden files of the loop's model-visible feedback (engineering-baseline §3.1 L3), under
 * `src/test/resources/prompt-surface/loop/`, compared byte for byte.
 *
 * A golden file holds the text followed by one newline. Tests only compare: on a mismatch, or when
 * the golden is missing, the actual content is written to `build/prompt-surface-actual/loop/<name>`
 * and the test fails. Accepting it is a separate step after reviewing the diff: [ACCEPT], run from
 * the repository root.
 */
internal object LoopGolden {
    private const val EXPECTED = "src/test/resources/prompt-surface/loop"
    private val actualRoot: Path = Path.of("build/prompt-surface-actual/loop")

    /** The acceptance step, run from the repository root after reviewing the actual files. */
    const val ACCEPT: String = "cp -R core/build/prompt-surface-actual/. core/src/test/resources/prompt-surface/"

    /** Compares [text] with the golden [name]; a mismatch leaves the actual file under the build directory. */
    fun assertMatches(
        name: String,
        text: String,
    ) {
        val actual = text + "\n"
        val expectedFile = Path.of(EXPECTED, name)
        val expected = if (Files.exists(expectedFile)) Files.readString(expectedFile) else null
        val kept = actualRoot.resolve(name)
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
            "golden $EXPECTED/$name $what; the actual file is $kept. " +
                "Review it, then accept from the repository root: $ACCEPT",
        )
    }
}
