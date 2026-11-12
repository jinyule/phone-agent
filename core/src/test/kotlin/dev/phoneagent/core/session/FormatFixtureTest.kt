package dev.phoneagent.core.session

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** S1: fixtures come from the generator only, and it never overwrites a published version. */
class FormatFixtureTest {
    @Test
    fun `the generator writes a new version directory with the fixture text`(
        @TempDir root: Path,
    ) {
        val written = FormatFixture.write(root)
        assertEquals(root.resolve("v${SessionFormat.CURRENT_VERSION}").resolve(FormatFixture.FILE), written)
        assertEquals(FormatFixture.text(), Files.readString(written))
    }

    @Test
    fun `the fixture of a later version carries that version in its directory and its header`(
        @TempDir root: Path,
    ) {
        val next = SessionFormat.CURRENT_VERSION + 1
        val later =
            SessionCodec(
                migrations =
                    SessionFormat.MIGRATIONS +
                        object : SessionMigration {
                            override val fromVersion = SessionFormat.CURRENT_VERSION

                            override fun migrate(envelope: JsonObject) = envelope
                        },
                currentVersion = next,
            )
        val written = FormatFixture.write(root, later)
        assertEquals(root.resolve("v$next").resolve(FormatFixture.FILE), written)
        val bytes = Files.readAllBytes(written)
        assertEquals(next, later.decodeHeader(bytes.decodeToString().lineSequence().first()).version)
        val read = later.read(bytes)
        assertEquals(next, read.header.version)
        assertEquals(SessionSamples.records, read.records)
    }

    @Test
    fun `the generator refuses a version directory that exists, even an empty one`(
        @TempDir root: Path,
    ) {
        val current = root.resolve("v${SessionFormat.CURRENT_VERSION}")
        Files.createDirectory(current)
        val error = assertThrows<IllegalStateException> { FormatFixture.main(arrayOf(root.toString())) }
        assertTrue("never rewritten" in error.message.orEmpty(), error.message)
        assertFalse(Files.exists(current.resolve(FormatFixture.FILE)))
    }

    @Test
    fun `tests only read fixtures`() {
        val writers =
            File("src/test/kotlin")
                .walk()
                .filter { it.isFile && it.name !in GENERATOR_FILES }
                .filter { file ->
                    val text = file.readText()
                    "session-format" in text && WRITE_CALLS.any { it in text }
                }.map { it.name }
                .toList()
        assertEquals(emptyList<String>(), writers)
    }

    private companion object {
        val WRITE_CALLS = listOf("writeText(", "writeBytes(", "Files.write", "getenv(")
        val GENERATOR_FILES = setOf("FormatFixture.kt", "FormatFixtureTest.kt")
    }
}
