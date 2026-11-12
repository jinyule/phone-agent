package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.ports.ScreenUnavailableException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReplayFilesTest {
    @TempDir
    lateinit var temp: Path

    private val scenarioText = ReplayFiles.encode(ReplaySamples.scenario)

    private fun scenarioWith(transform: (JsonObject) -> JsonObject): String =
        transform(Json.parseToJsonElement(scenarioText).jsonObject).toString()

    private fun scenarioFailure(text: String): String =
        assertThrows<IllegalArgumentException> {
            ReplayFiles.scenario(text)
        }.message.orEmpty()

    private fun fixture(): Path = ReplaySamples.writeFixture(temp.resolve("observe"))

    private fun loadFailure(dir: Path): String =
        assertThrows<IllegalArgumentException> {
            ReplayFiles.load(dir)
        }.message.orEmpty()

    private val sk = "sk-" + "test0not0real0"

    // scenario.json

    @Test
    fun `a scenario reads back as written`() {
        assertEquals(ReplaySamples.scenario, ReplayFiles.scenario(scenarioText))
    }

    @Test
    fun `a written scenario spells out its defaults`() {
        assertTrue("\"assembly\"" in scenarioText && "\"tuning\"" in scenarioText, scenarioText)
    }

    @Test
    fun `an unknown key in a scenario fails and names the file`() {
        val failure = scenarioFailure(scenarioWith { JsonObject(it + ("extra" to JsonPrimitive(1))) })
        assertTrue(failure.startsWith("scenario.json:") && "extra" in failure, failure)
    }

    @Test
    fun `a scenario without its model fails`() {
        assertTrue("model" in scenarioFailure(scenarioWith { JsonObject(it - "model") }))
    }

    @Test
    fun `a scenario of another format fails`() {
        assertTrue("format 2" in scenarioFailure(scenarioWith { JsonObject(it + ("format" to JsonPrimitive(2))) }))
    }

    @Test
    fun `a blank description fails`() {
        assertThrows<IllegalArgumentException> { ReplaySamples.scenario.copy(description = " ") }
    }

    @Test
    fun `a rules version below 1 fails`() {
        assertThrows<IllegalArgumentException> { PolicyPin(rulesVersion = 0, PolicyPins.values(PolicyTuning())) }
    }

    @Test
    fun `a display size that is not positive fails`() {
        assertThrows<IllegalArgumentException> { ReplaySamples.scenario.device.copy(widthPx = 0) }
    }

    // replay.override.json

    private val awaited = DeviceFact("{{call:1}}", 2, DevicePort.AWAIT_CHANGE, changed = true, delayMs = 300)

    @Test
    fun `an override reads back as written`() {
        val override = ReplayOverride(device = listOf(awaited, DeviceFact(null, 1, DevicePort.SNAPSHOT, hangs = true)))
        assertEquals(override, ReplayFiles.override(ReplayFiles.encode(override)))
    }

    @Test
    fun `an unknown key in an override fails and names the file`() {
        val text = """{"format":1,"device":[{"call":null,"op":1,"port":"SNAPSHOT","hangs":true,"why":"x"}]}"""
        assertTrue(
            assertThrows<IllegalArgumentException> {
                ReplayFiles.override(text)
            }.message.orEmpty().startsWith("replay.override.json:"),
        )
    }

    @Test
    fun `two facts for the same port call fail`() {
        assertThrows<IllegalArgumentException> {
            ReplayOverride(
                device = listOf(awaited, awaited.copy(changed = false)),
            )
        }
    }

    @Test
    fun `an op below 1 fails`() {
        assertThrows<IllegalArgumentException> { awaited.copy(op = 0) }
    }

    @Test
    fun `a negative delay fails`() {
        assertThrows<IllegalArgumentException> { awaited.copy(delayMs = -1) }
    }

    @Test
    fun `two outcomes in one fact fail`() {
        assertThrows<IllegalArgumentException> { awaited.copy(hangs = true) }
    }

    @Test
    fun `a wait without its outcome fails`() {
        assertThrows<IllegalArgumentException> { awaited.copy(changed = null) }
    }

    @Test
    fun `a frame cannot report a change`() {
        assertThrows<IllegalArgumentException> { DeviceFact("{{call:1}}", 1, DevicePort.FRAME, changed = true) }
    }

    @Test
    fun `an action cannot hang`() {
        assertThrows<IllegalArgumentException> { DeviceFact("{{call:1}}", 1, DevicePort.ACT, hangs = true) }
    }

    @Test
    fun `a fact that says nothing fails`() {
        assertThrows<IllegalArgumentException> { DeviceFact("{{call:1}}", 1, DevicePort.SNAPSHOT) }
    }

    @Test
    fun `an exception replay cannot build fails`() {
        assertThrows<IllegalArgumentException> { Thrown("java.lang.OutOfMemoryError", "x") }
    }

    @Test
    fun `a known exception is built with its message`() {
        val built = ThrownTypes.build(ScreenUnavailableException::class.java.name, "display released")
        assertEquals(ScreenUnavailableException::class.java to "display released", built.javaClass to built.message)
    }

    @Test
    fun `only port calls with an outcome are unlogged`() {
        assertEquals(
            listOf(true, false),
            listOf(awaited.unlogged, DeviceFact("{{call:1}}", 1, DevicePort.SNAPSHOT, delayMs = 5).unlogged),
        )
    }

    // A fixture directory

    @Test
    fun `a fixture loads with concrete ids`() {
        val loaded = ReplayFiles.load(fixture())
        assertEquals("observe", loaded.name)
        assertEquals(ReplaySamples.scenario, loaded.scenario)
        assertEquals("session-1", loaded.log.header.sessionId.value)
        assertEquals(
            ToolCallId("call-1"),
            (
                loaded.requests[1]
                    .request.messages[1]
                    .parts[1] as Part.ToolCall
            ).id,
        )
        assertEquals(ReplaySamples.records.size, loaded.log.records.size)
        assertArrayEquals(ReplaySamples.frameBytes, loaded.blobs.getValue(ReplaySamples.blob))
        assertEquals(ReplayOverride(), loaded.override)
    }

    @Test
    fun `a fixture keeps its session and requests files as committed, tokens included`() {
        val committed = ReplaySamples.normalized()
        val loaded = ReplayFiles.load(fixture())
        assertEquals(
            listOf(committed.session, committed.requests),
            listOf(loaded.session.text, loaded.requestFile.text),
        )
        assertTrue("{{call:1}}" in loaded.session.text && "{{call:1}}" in loaded.requestFile.text)
    }

    @Test
    fun `an override's call tokens become concrete ids`() {
        val dir = fixture()
        Files.writeString(
            dir.resolve(ReplayFiles.OVERRIDE),
            ReplayFiles.encode(ReplayOverride(device = listOf(awaited))),
        )
        assertEquals(
            "call-1",
            ReplayFiles
                .load(dir)
                .override.device
                .single()
                .call,
        )
    }

    @Test
    fun `an override call that is not a token fails`() {
        val dir = fixture()
        Files.writeString(
            dir.resolve(ReplayFiles.OVERRIDE),
            ReplayFiles.encode(ReplayOverride(device = listOf(awaited.copy(call = "call-1")))),
        )
        assertTrue("not a {{call:n}} token" in loadFailure(dir))
    }

    @Test
    fun `a stray file fails the load`() {
        val dir = fixture()
        Files.writeString(dir.resolve("notes.txt"), "x")
        assertTrue("stray files [notes.txt]" in loadFailure(dir))
    }

    @Test
    fun `a missing requests file fails the load`() {
        val dir = fixture()
        Files.delete(dir.resolve(ReplayFiles.REQUESTS))
        assertTrue("requests.expected.jsonl is missing" in loadFailure(dir))
    }

    @Test
    fun `text shaped like a key in the scenario fails the load`() {
        val dir = fixture()
        Files.writeString(
            dir.resolve(ReplayFiles.SCENARIO),
            ReplayFiles.encode(ReplaySamples.scenario.copy(description = "uses $sk")),
        )
        assertTrue("observe/scenario.json line" in loadFailure(dir))
    }

    @Test
    fun `text shaped like a key in the session fails the load`() {
        val dir = fixture()
        val session = Files.readString(dir.resolve(ReplayFiles.SESSION)).replace("看看屏幕上有谁", sk)
        Files.writeString(dir.resolve(ReplayFiles.SESSION), session)
        assertTrue("observe/session.jsonl line" in loadFailure(dir))
    }

    @Test
    fun `text shaped like a key in the override fails the load`() {
        val dir = fixture()
        Files.writeString(dir.resolve(ReplayFiles.OVERRIDE), "{\"format\":1,\"device\":[],\"note\":\"$sk\"}\n")
        assertTrue("observe/replay.override.json line" in loadFailure(dir))
    }

    @Test
    fun `a session that is not normalized fails the load`() {
        val dir = fixture()
        Files.writeString(dir.resolve(ReplayFiles.SESSION), ReplaySamples.sessionText())
        assertTrue("session.jsonl is not normalized" in loadFailure(dir))
    }

    @Test
    fun `a requests file that is not normalized fails the load`() {
        val dir = fixture()
        Files.writeString(dir.resolve(ReplayFiles.REQUESTS), RecordedRequests.encode(ReplaySamples.requests))
        assertTrue("requests.expected.jsonl is not normalized" in loadFailure(dir))
    }

    @Test
    fun `a session the codec refuses fails the load and names the file`() {
        val dir = fixture()
        val lines = Files.readString(dir.resolve(ReplayFiles.SESSION)).lines().filter { it.isNotEmpty() }
        Files.writeString(
            dir.resolve(ReplayFiles.SESSION),
            (lines.take(1) + lines.drop(2)).joinToString("") { "$it\n" },
        )
        assertTrue(loadFailure(dir).startsWith("observe/session.jsonl:"))
    }

    @Test
    fun `a request sent after the last record fails the load`() {
        val dir = fixture()
        val late = ReplaySamples.requests.mapIndexed { i, r -> if (i == 1) r.copy(logSeq = 99) else r }
        Files.writeString(dir.resolve(ReplayFiles.REQUESTS), ReplaySamples.normalized(requests = late).requests)
        assertTrue("requests [2] were sent after the last record" in loadFailure(dir))
    }

    @Test
    fun `a missing blob fails the load`() {
        val dir = fixture()
        Files.delete(dir.resolve(ReplayFiles.BLOBS).resolve(ReplaySamples.blob.value))
        assertTrue("referenced blobs missing" in loadFailure(dir))
    }

    @Test
    fun `a blob nothing references fails the load`() {
        val dir = fixture()
        val extra = "another frame".toByteArray()
        Files.write(dir.resolve(ReplayFiles.BLOBS).resolve(ReplayFiles.sha256(extra)), extra)
        assertTrue("blobs nothing references" in loadFailure(dir))
    }

    @Test
    fun `a blob whose content does not match its name fails the load`() {
        val dir = fixture()
        Files.write(dir.resolve(ReplayFiles.BLOBS).resolve(ReplaySamples.blob.value), "edited".toByteArray())
        assertTrue("content does not match its name" in loadFailure(dir))
    }

    @Test
    fun `a file in blobs that is not named by a hash fails the load`() {
        val dir = fixture()
        Files.write(dir.resolve(ReplayFiles.BLOBS).resolve("frame.png"), ReplaySamples.frameBytes)
        assertTrue("'frame.png' is not a blob file" in loadFailure(dir))
    }
}
