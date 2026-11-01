package dev.phoneagent.core.model.openai

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Golden and replay tests only compare: a mismatch fails and leaves the actual output under
 * `build/wire-actual/` for review; nothing in the test tree writes fixtures back into `src/`.
 */
class WireFixturesTest {
    @Test
    fun `a mismatch fails and leaves the actual output under build, not src`() {
        val path = "golden/chat-minimal.json"
        val source = Path.of("src/test/resources/wire", path)
        val before = source.readText()
        val actual = Path.of("build/wire-actual", path)
        Files.deleteIfExists(actual)
        val failure = runCatching { Kit.assertGolden(path, JsonPrimitive("not the golden")) }.exceptionOrNull()
        assertTrue(failure is AssertionFailedError, "a mismatch fails: $failure")
        assertTrue(failure!!.message!!.contains("build/wire-actual/$path"), failure.message)
        assertEquals("\"not the golden\"\n", actual.readText(), "the actual output is kept for review")
        assertEquals(before, source.readText(), "the golden is untouched")
    }

    @Test
    fun `no test writes fixtures back`() {
        val switch = "PA_UPDATE" + "_GOLDENS"
        val writer = "wireSource" + "("
        val offenders =
            Files.walk(Path.of("src/test/kotlin")).use { files ->
                files
                    .filter { it.toString().endsWith(".kt") }
                    .filter { file -> file.readText().let { switch in it || writer in it } }
                    .map { it.fileName.toString() }
                    .toList()
            }
        assertEquals(emptyList<String>(), offenders, "tests only compare; generation is a separate step")
    }
}
