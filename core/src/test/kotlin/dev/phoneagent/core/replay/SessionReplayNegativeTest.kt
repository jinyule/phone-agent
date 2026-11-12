package dev.phoneagent.core.replay

import dev.phoneagent.core.testing.GoldenFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A replay must fail when the run and the recording part (WP08 design §10): each kind of
 * difference in its own test, on a copy of a committed fixture changed in one place, against the
 * unchanged copy as the control.
 */
class SessionReplayNegativeTest {
    @TempDir
    lateinit var temp: Path

    private fun copy(name: String): Path {
        val from = SnapshotMode.fixtures.resolve(name)
        val to = temp.resolve(name)
        Files.walk(from).use { paths ->
            paths.toList().forEach { path -> Files.copy(path, to.resolve(from.relativize(path).toString())) }
        }
        return to
    }

    /** [file] of [dir] with its only line holding [marker] passed through [change]. */
    private fun editLine(
        dir: Path,
        file: String,
        marker: String,
        change: (String) -> String,
    ) {
        val path = dir.resolve(file)
        val lines = path.readText().lines()
        val at = lines.indices.filter { marker in lines[it] }
        assertEquals(1, at.size, "one line of $file holds $marker")
        path.writeText(lines.mapIndexed { i, line -> if (i == at.single()) change(line) else line }.joinToString("\n"))
    }

    /** The messages of every check that fails when [fixture] (in [dir]) is replayed. */
    private fun failures(
        fixture: SessionFixture,
        dir: Path,
    ): List<String> {
        var messages = emptyList<String>()
        runTest {
            val replayed = SessionReplay.run(this, fixture)
            val goldens = GoldenFiles(dir, temp.resolve("actual"), SessionReplay.ACCEPT)
            messages =
                (
                    replayed.runChecks() +
                        replayed.compareChecks(
                            goldens,
                        )
                ).mapNotNull { runCatching(it).exceptionOrNull()?.message }
        }
        return messages
    }

    private fun failures(dir: Path): List<String> = failures(ReplayFiles.load(dir), dir)

    private fun List<String>.anyHas(text: String): Boolean = any { text in it }

    @Test
    fun `the unchanged copies replay clean`() {
        assertAll(AuthoredScenarios.ALL.map { { assertEquals(emptyList<String>(), failures(copy(it.name)), it.name) } })
    }

    @Test
    fun `a request one character off fails the request comparison alone`() {
        val dir = copy("observe-tap")
        editLine(dir, ReplayFiles.REQUESTS, "\"n\":1,") { it.replaceFirst("打开和张三的聊天", "打开和张三的聊夭") }
        val failed = failures(dir)
        assertEquals(1, failed.size, "$failed")
        assertTrue(
            failed.single().contains("first difference: request #1, at .append[0].parts[0].text"),
            failed.single(),
        )
    }

    /** [fixture] with its scenario passed through [change]; the files stay as committed. */
    private fun withScenario(
        fixture: SessionFixture,
        change: (Scenario) -> Scenario,
    ) = SessionFixture(
        fixture.name,
        change(fixture.scenario),
        fixture.session,
        fixture.requestFile,
        fixture.override,
        fixture.blobs,
    )

    /** The scenario run with `maxElements = 1`, at which a chat page is too big to read, and pinned so. */
    private fun tooBigToRead(scenario: Scenario) =
        scenario.copy(
            assembly = scenario.assembly.copy(tuning = mapOf("maxElements" to 1)),
            policy = scenario.policy.copy(tuning = scenario.policy.tuning + ("maxElements" to 1)),
        )

    @Test
    fun `a verdict the rules now give otherwise fails and asks for the rules version`() {
        // The rules judge differently at the same version.
        val dir = copy("confirm-send-approved")
        val failed = failures(withScenario(ReplayFiles.load(dir), ::tooBigToRead), dir)
        assertTrue(failed.anyHas("policy/verdict, one line per verdict"), "$failed")
        assertTrue(failed.anyHas("verdicts changed while RULES_VERSION is still"), "$failed")
        assertTrue(failed.none { it.startsWith("policy.") }, "the pin itself matches: $failed")
    }

    @Test
    fun `verdicts of other rules fail on the pin without asking for the rules version`() {
        val dir = copy("confirm-send-approved")
        val fixture =
            withScenario(ReplayFiles.load(dir)) { scenario ->
                tooBigToRead(
                    scenario,
                ).let { it.copy(policy = it.policy.copy(rulesVersion = it.policy.rulesVersion + 1)) }
            }
        val failed = failures(fixture, dir)
        assertTrue(failed.anyHas("policy/verdict, one line per verdict"), "$failed")
        assertTrue(failed.any { it.startsWith("policy.rulesVersion: the fixture was judged by rules") }, "$failed")
        assertTrue(failed.none { "RULES_VERSION is still" in it }, "the version moved: $failed")
    }

    @Test
    fun `a pinned tuning value the replay does not run with fails on the pin alone`() {
        val dir = copy("observe-tap")
        val fixture =
            withScenario(ReplayFiles.load(dir)) { scenario ->
                scenario.copy(policy = scenario.policy.copy(tuning = scenario.policy.tuning + ("labelMax" to 1)))
            }
        val failed = failures(fixture, dir)
        assertEquals(1, failed.size, "$failed")
        assertTrue(
            failed.single().startsWith("policy.tuning.labelMax: the fixture was judged with 1, "),
            failed.single(),
        )
    }

    @Test
    fun `a recording with one call more than the replay fails`() {
        val dir = copy("observe-tap")
        val path = dir.resolve(ReplayFiles.REQUESTS)
        val last = Normalizer.lines(path.readText()).last()
        val line = Normalizer.parse(last).jsonObject
        val messages = line.getValue("keep").jsonPrimitive.int + line.getValue("append").jsonArray.size
        val lastSeq = Normalizer.lines(dir.resolve(ReplayFiles.SESSION).readText()).size - 1
        val extra =
            JsonObject(
                line +
                    mapOf(
                        "n" to JsonPrimitive(line.getValue("n").jsonPrimitive.int + 1),
                        "logSeq" to JsonPrimitive(lastSeq),
                        "keep" to JsonPrimitive(messages),
                        "append" to JsonArray(emptyList()),
                    ),
            )
        path.writeText(path.readText() + extra + "\n")
        val failed = failures(dir)
        assertTrue(failed.anyHas("the recording made 4 requests, the replay 3"), "$failed")
        assertTrue(failed.anyHas("first missing request #4"), "$failed")
    }

    @Test
    fun `a replay with one call more than the recording fails`() {
        // The recording's turn ends after the tap, without the final reply: the replay asks once more.
        val dir = copy("observe-tap")
        val session = dir.resolve(ReplayFiles.SESSION)
        val lines = Normalizer.lines(session.readText())
        val lastStep = lines.indexOfLast { "\"type\":\"step/start\"" in it }
        val end = Normalizer.parse(lines.last()).jsonObject
        val renumbered = JsonObject(end + ("seq" to JsonPrimitive(lastStep)))
        session.writeText((lines.take(lastStep) + renumbered.toString()).joinToString("") { "$it\n" })
        val requests = dir.resolve(ReplayFiles.REQUESTS)
        requests.writeText(Normalizer.lines(requests.readText()).dropLast(1).joinToString("") { "$it\n" })
        val failed = failures(dir)
        assertTrue(failed.anyHas("prepare after the last recorded request (#2)"), "$failed")
    }

    @Test
    fun `an action with other arguments than the recorded one fails at the device`() {
        val dir = copy("confirm-send-approved")
        editLine(dir, ReplayFiles.SESSION, "\"name\":\"phone_type\",\"argsJson\"") {
            it.replaceFirst("\\\"text\\\":\\\"十点半\\\"", "\\\"text\\\":\\\"十点\\\"")
        }
        val failed = failures(dir)
        assertTrue(failed.anyHas("call-2 op 1: the recording did"), "$failed")
    }
}
