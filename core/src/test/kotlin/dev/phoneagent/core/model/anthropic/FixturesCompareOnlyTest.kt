package dev.phoneagent.core.model.anthropic

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Goldens and stream fixtures are only compared: a mismatch fails and leaves the actual output
 * under `build/wire-actual/` for review; no test writes fixtures back into `src/`.
 */
class FixturesCompareOnlyTest {
    @Test
    fun `a mismatch fails and leaves the actual output under build, not src`() {
        val path = "anthropic/golden/minimal.json"
        val source = Path.of("src/test/resources/wire", path)
        val before = source.readText()
        val actual = Path.of("build/wire-actual", path)
        Files.deleteIfExists(actual)
        val failure = runCatching { Kit.assertGolden(path, JsonPrimitive("not the golden")) }.exceptionOrNull()
        assertTrue(failure is AssertionFailedError, "a mismatch fails: $failure")
        assertTrue(failure!!.message!!.contains("build/wire-actual/$path"), failure.message)
        assertEquals("\"not the golden\"\n", actual.readText(), "the actual output is kept for review")
        assertEquals(before, source.readText(), "the golden is untouched")
        Files.delete(actual)
    }

    @Test
    fun `only the kit writes files, and only under build`() {
        // Assembled at run time, so this file does not match itself.
        val writes = listOf("write" + "String(", "write" + "Text(", "Files." + "write(", "write" + "Bytes(")
        val writers =
            Files.walk(Path.of("src/test/kotlin/dev/phoneagent/core/model/anthropic")).use { files ->
                files
                    .filter { it.toString().endsWith(".kt") }
                    .filter { file -> file.readText().let { text -> writes.any { it in text } } }
                    .map { it.fileName.toString() }
                    .toList()
            }
        assertEquals(listOf("AnthropicKit.kt"), writers)
        val kit = Path.of("src/test/kotlin/dev/phoneagent/core/model/anthropic/AnthropicKit.kt").readText()
        assertTrue(kit.contains("BUILD_DIR = \"build\""), "the kit writes under build/ only")
        assertTrue(kit.contains("Path.of(BUILD_DIR, relative)"), "every write goes through keepUnderBuild")
    }
}
