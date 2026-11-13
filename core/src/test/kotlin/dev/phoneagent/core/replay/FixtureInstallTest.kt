package dev.phoneagent.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.writeText

/**
 * A fixture written over a directory replaces only what a fixture holds: every entry of the
 * directory and of its `blobs/` is checked, links not followed, before anything is deleted, and
 * anything else leaves the directory as it was ([FixtureTexts.install], as record mode and
 * refresh write).
 */
class FixtureInstallTest {
    @TempDir
    lateinit var temp: Path

    private val dir: Path get() = temp.resolve("fixture")
    private val blobs: Path get() = dir.resolve(ReplayFiles.BLOBS)
    private val outside: Path get() = temp.resolve("outside.txt")
    private val blobName = "ab".repeat(32)

    private fun staged(): Path = ReplaySamples.writeFixture(temp.resolve("staged"))

    /** Every path under [root]: a file's bytes, a link's target, `dir` for a directory. Links are not followed. */
    private fun tree(root: Path = temp): Map<String, String> =
        Files.walk(root).use { it.toList() }.associate { path ->
            root.relativize(path).toString() to
                when {
                    path.isSymbolicLink() -> "link to ${Files.readSymbolicLink(path)}"
                    path.isDirectory() -> "dir"
                    else -> Base64.getEncoder().encodeToString(Files.readAllBytes(path))
                }
        }

    /** Installs over [dir] laid out by [layout] (on a whole fixture) and expects a refusal naming [entry]. */
    private fun refused(
        entry: String,
        layout: () -> Unit,
    ) {
        val staged = staged()
        ReplaySamples.writeFixture(dir)
        outside.writeText("not part of any fixture")
        layout()
        val before = tree()
        val failure = assertThrows<IllegalArgumentException> { FixtureTexts.install(staged, dir) }
        assertTrue("[$entry]" in failure.message.orEmpty(), failure.message)
        assertTrue("left alone" in failure.message.orEmpty(), failure.message)
        assertEquals(before, tree(), "nothing is deleted")
    }

    @Test
    fun `a fixture replaces a fixture, blobs and all`() {
        val staged = staged()
        ReplaySamples.writeFixture(dir)
        Files.writeString(blobs.resolve(blobName), "an older blob")
        Files.writeString(dir.resolve(ReplayFiles.OVERRIDE), "{}\n")
        FixtureTexts.install(staged, dir)
        assertEquals(tree(staged), tree(dir))
    }

    @Test
    fun `a file at the top that a fixture does not hold is refused`() {
        refused("notes.txt") { dir.resolve("notes.txt").writeText("my notes") }
    }

    @Test
    fun `a file in blobs not named as a blob is refused`() {
        refused("blobs/notes.txt") { blobs.resolve("notes.txt").writeText("my notes") }
    }

    @Test
    fun `a directory in blobs is refused, with the files in it`() {
        refused("blobs/keep.txt, blobs/sub") {
            blobs.resolve("keep.txt").writeText("keep")
            Files.createDirectories(blobs.resolve("sub")).resolve("inner.txt").writeText("inner")
        }
    }

    @Test
    fun `a directory in blobs named as a blob is refused`() {
        refused("blobs/$blobName") {
            Files.createDirectories(blobs.resolve(blobName)).resolve("inner.txt").writeText("inner")
        }
    }

    @Test
    fun `a link in blobs named as a blob is refused, not followed`() {
        refused("blobs/$blobName") { Files.createSymbolicLink(blobs.resolve(blobName), outside) }
    }

    @Test
    fun `a link at the top named as a fixture file is refused, not followed`() {
        refused(ReplayFiles.OVERRIDE) { Files.createSymbolicLink(dir.resolve(ReplayFiles.OVERRIDE), outside) }
    }

    @Test
    fun `blobs as a link to a directory is refused, not followed`() {
        refused(ReplayFiles.BLOBS) {
            val elsewhere = Files.createDirectories(temp.resolve("elsewhere"))
            Files.move(blobs, elsewhere.resolve(ReplayFiles.BLOBS))
            Files.createSymbolicLink(blobs, elsewhere.resolve(ReplayFiles.BLOBS))
        }
    }

    @Test
    fun `blobs as a file is refused`() {
        refused(ReplayFiles.BLOBS) {
            Files.delete(blobs.resolve(ReplaySamples.blob.value))
            Files.delete(blobs)
            blobs.writeText("not a directory")
        }
    }
}
