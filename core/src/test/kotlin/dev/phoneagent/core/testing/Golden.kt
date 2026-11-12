package dev.phoneagent.core.testing

import java.nio.file.Path

/**
 * Golden files of the model-visible surface (engineering-baseline §3.1 L3), compared byte for byte
 * ([GoldenFiles]).
 *
 * A golden file holds the text followed by one newline (the file convention; nothing of the text
 * itself is trimmed, so a text that ends in a newline is stored with two). Tests only compare: on a
 * mismatch, or when the golden is missing, the actual file content is written to
 * `build/prompt-surface-actual/<path>` and the test fails. Accepting it is a separate, explicit
 * step after a test run: review the diff, then copy it over with [ACCEPT] (the failure message
 * prints it).
 */
object Golden {
    /** The acceptance step, run from the repository root after reviewing the actual files. */
    const val ACCEPT: String = "cp -R core/build/prompt-surface-actual/. core/src/test/resources/prompt-surface/"

    /** The prompt-surface tree; the loop goldens are its `loop/` directory and share [ACCEPT]. */
    val FILES: GoldenFiles =
        GoldenFiles(Path.of("src/test/resources/prompt-surface"), Path.of("build/prompt-surface-actual"), ACCEPT)

    /** Compares [text] with the golden at [path]; a mismatch leaves the actual file under [actualDir]. */
    fun assertMatches(
        path: String,
        text: String,
        actualDir: Path = Path.of("build/prompt-surface-actual"),
    ) {
        FILES.assertMatches(path, text + "\n", actualDir)
    }
}
