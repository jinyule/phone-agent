package dev.phoneagent.core.agent

import dev.phoneagent.core.testing.Golden

/**
 * Golden files of the loop's model-visible feedback (engineering-baseline §3.1 L3), under
 * `src/test/resources/prompt-surface/loop/`, compared byte for byte ([Golden.FILES]).
 *
 * A golden file holds the text followed by one newline. Tests only compare: on a mismatch, or when
 * the golden is missing, the actual content is written to `build/prompt-surface-actual/loop/<name>`
 * and the test fails. Accepting it is a separate step after reviewing the diff: [ACCEPT], run from
 * the repository root.
 */
internal object LoopGolden {
    /** The acceptance step, run from the repository root after reviewing the actual files. */
    const val ACCEPT: String = Golden.ACCEPT

    /** Compares [text] with the golden [name]; a mismatch leaves the actual file under the build directory. */
    fun assertMatches(
        name: String,
        text: String,
    ) {
        Golden.FILES.assertMatches("loop/$name", text + "\n")
    }
}
