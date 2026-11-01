package dev.phoneagent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ExcludeFromCoverageUsageTest {
    @Test
    fun `every exclusion in core states a reason`() {
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory, "run from the :core project directory, got ${File(".").absolutePath}")
        val violations =
            sources
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> violationsIn(file.readText()).map { "${file.path}: $it" } }
                .toList()
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `checker accepts a literal reason`() {
        assertEquals(emptyList<String>(), violationsIn("""@ExcludeFromCoverage(reason = "Binder stub")"""))
        assertEquals(emptyList<String>(), violationsIn("""@ExcludeFromCoverage("Binder stub")"""))
    }

    @Test
    fun `checker rejects blank or non-literal reasons`() {
        assertEquals(1, violationsIn("""@ExcludeFromCoverage(reason = "  ")""").size)
        assertEquals(1, violationsIn("""@ExcludeFromCoverage(reason = REASON)""").size)
        assertEquals(1, violationsIn("""@ExcludeFromCoverage""").size)
    }

    private fun violationsIn(source: String): List<String> =
        USAGE
            .findAll(source)
            .filterNot { it.value.startsWith("annotation class") }
            .mapNotNull { usage ->
                val reason = LITERAL_REASON.matchAt(source, usage.range.first)?.groupValues?.get(1)
                when {
                    reason == null -> "reason must be a string literal: ${source.lineAt(usage.range.first)}"
                    reason.isBlank() -> "reason must not be blank: ${source.lineAt(usage.range.first)}"
                    else -> null
                }
            }.toList()

    private fun String.lineAt(index: Int): String = substring(lastIndexOf('\n', index) + 1).substringBefore('\n').trim()

    private companion object {
        val USAGE = Regex("""annotation class ExcludeFromCoverage|@ExcludeFromCoverage\b""")
        val LITERAL_REASON = Regex("""@ExcludeFromCoverage\s*\(\s*(?:reason\s*=\s*)?"([^"]*)"\s*\)""")
    }
}
