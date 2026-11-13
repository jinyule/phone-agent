package dev.phoneagent.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * A fixture written over a directory replaces only what a fixture holds: every entry of the
 * directory and of its `blobs/` is checked, links not followed, before anything is deleted, and
 * anything else leaves the directory as it was ([FixtureTexts.install], as record mode and
 * refresh write). An install takes only a staged fixture of fixture files, and replaces the
 * directory whole or not at all: a failed copy or move leaves it, and everything beside it, byte
 * for byte as it was.
 */
class FixtureInstallTest {
    @TempDir
    lateinit var temp: Path

    private val dir: Path get() = temp.resolve("fixture")
    private val blobs: Path get() = dir.resolve(ReplayFiles.BLOBS)
    private val outside: Path get() = temp.resolve("outside.txt")
    private val blobName = "ab".repeat(32)

    private fun staged(): Path = ReplaySamples.writeFixture(temp.resolve("staged"))

    /** A fixture in [dir] that differs from [staged]: an override and a second blob. */
    private fun older() {
        ReplaySamples.writeFixture(dir)
        Files.writeString(blobs.resolve(blobName), "an older blob")
        Files.writeString(dir.resolve(ReplayFiles.OVERRIDE), "{}\n")
        outside.writeText("not part of any fixture")
    }

    /** The names in [temp]: what an install leaves beside the fixture. */
    private fun beside(): Set<String> = Files.list(temp).use { list -> list.toList().map { it.name }.toSet() }

    /** Moves as the install does, but the [failing] ones (counted from 1) throw. */
    private fun moves(vararg failing: Int): (Path, Path) -> Unit {
        var moves = 0
        return { from, to ->
            moves++
            if (moves in failing) throw IOException("move $moves failed (injected)")
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Over [older], runs [install] and expects [T]; everything under [temp] stays as it was. */
    private inline fun <reified T : Throwable> leftAsItWas(install: () -> Unit): T {
        older()
        val before = tree()
        val failure = assertThrows<T> { install() }
        assertEquals(before, tree(), "the fixture and everything beside it are as they were")
        return failure
    }

    /** Installs [staged] laid out by [layout] and expects a refusal naming [entry], [dir] untouched. */
    private fun stagedRefused(
        entry: String,
        layout: (Path) -> Unit,
    ) {
        val staged = staged()
        layout(staged)
        val failure = leftAsItWas<IllegalArgumentException> { FixtureTexts.install(staged, dir) }
        assertTrue("$staged holds [$entry]" in failure.message.orEmpty(), failure.message)
        assertTrue("not installed" in failure.message.orEmpty(), failure.message)
    }

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
    fun `a fixture replaces a fixture, blobs and all, leaving nothing beside it`() {
        val staged = staged()
        older()
        FixtureTexts.install(staged, dir)
        assertEquals(tree(staged), tree(dir))
        assertEquals(setOf("staged", "fixture", "outside.txt"), beside())
    }

    @Test
    fun `a fixture is installed where there was none, leaving nothing beside it`() {
        val staged = staged()
        FixtureTexts.install(staged, dir)
        assertEquals(tree(staged), tree(dir))
        assertEquals(setOf("staged", "fixture"), beside())
    }

    @Test
    fun `a dangling link in the staged fixture is refused`() {
        stagedRefused(FixtureTexts.SYSTEM_PROMPT) {
            Files.createSymbolicLink(it.resolve(FixtureTexts.SYSTEM_PROMPT), temp.resolve("missing.md"))
        }
    }

    @Test
    fun `a link in the staged fixture to a file outside it is refused, not followed`() {
        stagedRefused(
            FixtureTexts.SYSTEM_PROMPT,
        ) { Files.createSymbolicLink(it.resolve(FixtureTexts.SYSTEM_PROMPT), outside) }
    }

    @Test
    fun `a link in the staged blobs to a file holding the blob is refused, not followed`() {
        stagedRefused("${ReplayFiles.BLOBS}/${ReplaySamples.blob.value}") { staged ->
            val elsewhere = Files.write(temp.resolve("elsewhere.bin"), ReplaySamples.frameBytes)
            val blob = staged.resolve(ReplayFiles.BLOBS).resolve(ReplaySamples.blob.value)
            Files.delete(blob)
            Files.createSymbolicLink(blob, elsewhere)
        }
    }

    @Test
    fun `a directory in the staged fixture where a file goes is refused`() {
        stagedRefused(FixtureTexts.TOOL_SCHEMAS) {
            Files.createDirectory(it.resolve(FixtureTexts.TOOL_SCHEMAS)).resolve("inner.json").writeText("{}")
        }
    }

    @Test
    fun `a copy that fails partway leaves the fixture as it was`() {
        val staged = staged()
        var copies = 0
        val failure =
            leftAsItWas<IOException> {
                FixtureTexts.install(staged, dir, copy = { from, to ->
                    copies++
                    if (copies == 2) throw IOException("no space left on device (injected)")
                    Files.copy(from, to)
                })
            }
        assertEquals("no space left on device (injected)", failure.message)
    }

    @Test
    fun `a copy that fails partway where there was no fixture leaves none`() {
        val staged = staged()
        val before = tree()
        assertThrows<IOException> {
            FixtureTexts.install(staged, dir, copy = { _, _ -> throw IOException("injected") })
        }
        assertEquals(before, tree())
    }

    @Test
    fun `a copy that differs from its source is not installed`() {
        val staged = staged()
        val failure =
            leftAsItWas<IllegalStateException> {
                FixtureTexts.install(staged, dir, copy = { from, to ->
                    Files.copy(from, to)
                    if (from.name == ReplayFiles.SESSION) Files.writeString(to, "torn", StandardOpenOption.APPEND)
                })
            }
        assertTrue("the copy of ${staged.resolve(ReplayFiles.SESSION)}" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a copy that is a link to its source is not installed`() {
        val staged = staged()
        val failure =
            leftAsItWas<IllegalStateException> {
                FixtureTexts.install(staged, dir, copy = { from, to ->
                    if (from.name == ReplayFiles.SESSION) Files.createSymbolicLink(to, from) else Files.copy(from, to)
                })
            }
        assertTrue("the copy of ${staged.resolve(ReplayFiles.SESSION)}" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a failed move aside leaves the fixture as it was`() {
        val staged = staged()
        val failure = leftAsItWas<IOException> { FixtureTexts.install(staged, dir, move = moves(1)) }
        assertEquals("move 1 failed (injected)", failure.message)
    }

    @Test
    fun `a failed move into place puts the old fixture back`() {
        val staged = staged()
        val failure = leftAsItWas<IOException> { FixtureTexts.install(staged, dir, move = moves(2)) }
        assertEquals("move 2 failed (injected)", failure.message)
        assertEquals(0, failure.suppressed.size)
    }

    @Test
    fun `when putting the old fixture back fails too, it is kept beside, and the failure says where`() {
        val staged = staged()
        older()
        val old = tree(dir)
        val failure = assertThrows<IOException> { FixtureTexts.install(staged, dir, move = moves(2, 3)) }
        assertEquals("move 2 failed (injected)", failure.message)
        assertFalse(dir.exists(LinkOption.NOFOLLOW_LINKS))
        val kept = failure.suppressed.single()
        assertEquals("move 3 failed (injected)", kept.cause?.message)
        val left = Path.of(kept.message.orEmpty().substringAfter(" is left in "))
        assertTrue(left.parent.name.startsWith(".fixture.installing-"), kept.message)
        assertEquals(old, tree(left))
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
