package dev.phoneagent.core.replay

import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecordedRequestsTest {
    private val requests = ReplaySamples.requests

    /** A third request at another level, after a compaction kept only the user's message. */
    private val third =
        RecordedRequest(
            3,
            20,
            ReplaySamples.ref,
            requests[1].request.copy(
                messages = listOf(ReplaySamples.user, Message(Role.USER, listOf(Part.Text("summary")))),
                reasoning = ReasoningLevel.LOW,
            ),
        )

    private fun lines(text: String) =
        text.lines().filter { it.isNotEmpty() }.map { Json.parseToJsonElement(it).jsonObject }

    private fun failure(text: String): String =
        assertThrows<IllegalArgumentException> {
            RecordedRequests.decode(text)
        }.message.orEmpty()

    /** A field added to `ChatRequest` with a default would otherwise be dropped from the file unnoticed. */
    @Test
    fun `a line holds every field of a request`() {
        fun names(descriptor: SerialDescriptor) =
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        assertEquals(
            names(serializer<ChatRequest>().descriptor),
            names(serializer<RecordedRequests.Settings>().descriptor) + "messages",
        )
    }

    @Test
    fun `requests read back as written`() {
        val all = requests + third
        assertEquals(all, RecordedRequests.decode(RecordedRequests.encode(all)))
    }

    @Test
    fun `a line holds only the messages after those it shares with the previous request`() {
        val second = lines(RecordedRequests.encode(requests))[1]
        assertEquals(1, second.getValue("keep").jsonPrimitive.int)
        assertEquals(2, second.getValue("append").jsonArray.size)
    }

    @Test
    fun `unchanged model and settings are null`() {
        val second = lines(RecordedRequests.encode(requests))[1]
        assertEquals(JsonNull, second["model"])
        assertEquals(JsonNull, second["settings"])
    }

    @Test
    fun `changed settings are written again`() {
        val line = lines(RecordedRequests.encode(requests + third))[2]
        assertNotEquals(JsonNull, line["settings"])
        assertEquals(1, line.getValue("keep").jsonPrimitive.int)
    }

    @Test
    fun `encoding is deterministic`() {
        assertEquals(RecordedRequests.encode(requests), RecordedRequests.encode(requests.map { it.copy() }))
    }

    @Test
    fun `requests numbered out of order are refused when written`() {
        assertThrows<IllegalArgumentException> { RecordedRequests.encode(listOf(requests[1])) }
    }

    @Test
    fun `a file without a final newline is refused`() {
        assertTrue("newline" in failure(RecordedRequests.encode(requests).dropLast(1)))
    }

    @Test
    fun `a line numbered out of order is refused`() {
        val text = RecordedRequests.encode(requests).replaceFirst("{\"n\":1,", "{\"n\":2,")
        assertTrue("line 1: n is 2" in failure(text))
    }

    @Test
    fun `a logSeq that goes back is refused`() {
        val text = RecordedRequests.encode(requests).replace("\"logSeq\":18", "\"logSeq\":4")
        assertTrue("line 2: logSeq 4 goes back" in failure(text))
    }

    @Test
    fun `a first line without the model is refused`() {
        val first = lines(RecordedRequests.encode(requests))[0]
        val text = JsonObject(first - "model").toString() + "\n"
        assertTrue("needs the model" in failure(text))
    }

    @Test
    fun `a first line without the settings is refused`() {
        val first = lines(RecordedRequests.encode(requests))[0]
        val text = JsonObject(first - "settings").toString() + "\n"
        assertTrue("needs the settings" in failure(text))
    }

    @Test
    fun `keeping more messages than the previous request had is refused`() {
        val text = RecordedRequests.encode(requests).replace("\"keep\":1", "\"keep\":2")
        assertTrue("line 2: keep 2 of 1" in failure(text))
    }

    @Test
    fun `an unknown key is refused and the line named`() {
        val text = RecordedRequests.encode(requests).replaceFirst("{\"n\":1,", "{\"n\":1,\"extra\":0,")
        assertTrue(failure(text).startsWith("requests.expected.jsonl line 1:"))
    }
}
