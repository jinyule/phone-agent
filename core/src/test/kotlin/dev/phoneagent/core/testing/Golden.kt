package dev.phoneagent.core.testing

import org.junit.jupiter.api.Assertions.fail
import java.io.File

/**
 * Golden files of the model-visible surface (engineering-baseline §3.1 L3), compared byte for byte.
 *
 * On a mismatch the actual text is written under `build/prompt-surface-actual/` and the test
 * fails with the path: review the diff, then copy it over the expected file. Setting the
 * environment variable `PA_UPDATE_GOLDEN=1` rewrites the expected files instead (review the
 * resulting git diff before committing). Every file ends with exactly one newline.
 */
object Golden {
    private const val EXPECTED = "src/test/resources/prompt-surface"
    private const val ACTUAL = "build/prompt-surface-actual"

    fun assertMatches(
        path: String,
        text: String,
    ) {
        val actual = text.trimEnd('\n') + "\n"
        val expectedFile = File(EXPECTED, path)
        if (System.getenv("PA_UPDATE_GOLDEN") == "1") {
            expectedFile.parentFile.mkdirs()
            expectedFile.writeText(actual)
            return
        }
        val expected = if (expectedFile.exists()) expectedFile.readText() else null
        if (expected != actual) {
            val actualFile = File(ACTUAL, path)
            actualFile.parentFile.mkdirs()
            actualFile.writeText(actual)
            fail<Unit>(
                "Golden file $EXPECTED/$path differs (or is missing). Actual text: ${actualFile.absolutePath}. " +
                    "Review it and copy it over the expected file, or rerun with PA_UPDATE_GOLDEN=1.",
            )
        }
    }
}
