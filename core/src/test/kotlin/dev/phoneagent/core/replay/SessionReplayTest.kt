package dev.phoneagent.core.replay

import dev.phoneagent.core.testing.GoldenFiles
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The session replay regression suite (engineering-baseline §3.2): one test per fixture directory
 * under [SnapshotMode.fixtures], in the mode `-PsessionSnapshot` chose.
 * - replay (the default, CI): run the fixture and compare everything ([Replayed.runChecks] and
 *   [Replayed.compareChecks]); mismatching files are kept under `core/build/session-actual/`;
 * - refresh: run it, require [Replayed.runChecks], write the product outputs back, then replay the
 *   rewritten fixture;
 * - record: record each [AuthoredScenarios] scenario into its directory, then replay it.
 */
class SessionReplayTest {
    private val mode = SnapshotMode.current()
    private val root = SnapshotMode.fixtures

    @TestFactory
    fun sessions(): List<DynamicTest> =
        when (mode) {
            SnapshotMode.REPLAY -> fixtures().map { dir -> dynamicTest(dir.name) { replay(dir) } }
            SnapshotMode.REFRESH -> fixtures().map { dir -> dynamicTest(dir.name) { refresh(dir) } }
            SnapshotMode.RECORD -> AuthoredScenarios.ALL.map { source -> dynamicTest(source.name) { record(source) } }
        }

    @Test
    fun `every authored scenario has its fixture and every authored fixture its scenario`() {
        assumeTrue(mode == SnapshotMode.REPLAY, "record writes them")
        val authored =
            fixtures()
                .filter {
                    ReplayFiles.scenario(Files.readString(it.resolve(ReplayFiles.SCENARIO))).recording ==
                        Recording.AUTHORED
                }.map { it.name }
        assertEquals(AuthoredScenarios.ALL.map { it.name }.sorted(), authored.sorted(), "run -PsessionSnapshot=record")
    }

    private fun fixtures(): List<Path> =
        Files.list(root).use { dirs -> dirs.filter { it.isDirectory() }.sorted().toList() }

    private fun replay(dir: Path) =
        runTest {
            val replayed = SessionReplay.run(this, ReplayFiles.load(dir))
            assertAll(dir.name, replayed.runChecks() + replayed.compareChecks(goldens(dir)))
        }

    private fun refresh(dir: Path) {
        runTest {
            val replayed = SessionReplay.run(this, ReplayFiles.load(dir))
            assertAll(
                "${dir.name}: refresh keeps the model's replies, the device facts and the input",
                replayed.runChecks(),
            )
            replayed.writeBack(dir)
        }
        replay(dir)
    }

    private fun record(source: RecordingSource) {
        val dir = root.resolve(source.name)
        runTest { SessionRecorder.record(this, source, dir, Path.of("build/session-record")) }
        replay(dir)
    }

    private fun goldens(dir: Path) = GoldenFiles(dir, Path.of("build/session-actual", dir.name), SessionReplay.ACCEPT)
}
