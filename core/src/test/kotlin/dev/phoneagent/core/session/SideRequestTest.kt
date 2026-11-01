package dev.phoneagent.core.session

import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.TEST_WIRE
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** B2: side requests (vision, light) are recorded in full but are not part of the main conversation. */
class SideRequestTest {
    private val codec = SessionCodec()

    private val sideRequest =
        ChatRequest(
            system = listOf(PromptBlock("light", "Answer yes or no.")),
            tools = emptyList(),
            messages =
                listOf(
                    Message(Role.USER, listOf(Part.Text("Is this a login page?"), SessionSamples.image)),
                ),
            wire = TEST_WIRE,
            cache = CacheLayout.NONE,
            contextEdits = ContextEditPolicy.ClientSide,
            maxOutputTokens = 16,
        )

    private val side =
        SideRequest(
            role = ModelRole.LIGHT,
            provider = SessionSamples.provider,
            model = SessionSamples.model,
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            request = sideRequest,
        )

    @Test
    fun `request headers and replies belong to the main conversation only`() {
        assertFalse("role" in serializer<RequestHeader>().descriptor.elementNames, "a header is always the main role")
        assertEquals(true, SessionEventTypes.isIgnorable("side/request"))
        assertEquals(true, SessionEventTypes.isIgnorable("side/response"))
    }

    @Test
    fun `a side request is rebuilt exactly from its record`() {
        val line = codec.encode(SessionRecord(5, 0, side, sources = listOf(3)))
        val decoded = (codec.decode(line, version = 1, lineNumber = 6) as DecodedLine.Event).record
        assertEquals(sideRequest, (decoded.event as SideRequest).request)
        assertEquals(line, codec.encode(decoded), "byte for byte")
    }

    @Test
    fun `a side response answers exactly one earlier side request`() =
        runTest {
            val files = SessionFiles(InMemoryStoragePort(), StoragePath("s"))
            val store =
                SessionStore.create(
                    files,
                    SessionSamples.session,
                    SessionSamples.task,
                    SessionSamples.start,
                    Clock { 0 },
                )
            val user = store.append(UserMessage(UserMessageKind.USER, listOf(Part.Text("hi")))).seq
            val request = store.append(side, sources = listOf(user)).seq
            val reply = SideResponse(SideOutcome.Completed(ChatResponse(listOf(Part.Text("yes")), StopReason.END_TURN)))
            val rejected =
                mapOf(
                    "no source" to suspend { store.append(reply) },
                    "source is not a side/request" to suspend { store.append(reply, sources = listOf(user)) },
                    "two sources" to suspend { store.append(reply, sources = listOf(user, request)) },
                )
            rejected.forEach { (name, append) -> assertThrows<IllegalArgumentException>(name) { append() } }
            store.append(reply, sources = listOf(request))
            assertThrows<IllegalArgumentException>("answered twice") {
                store.append(
                    SideResponse(SideOutcome.Failed(ModelErrorKind.NETWORK, "reset")),
                    sources = listOf(request),
                )
            }
            store.flush()
            val read = SessionStore.read(files)
            assertEquals(listOf(request), read.records.last().sources)
        }

    @Test
    fun `failed side requests keep the error facts and are validated`() {
        val failed = SideOutcome.Failed(ModelErrorKind.RATE_LIMITED, "slow down", httpStatus = 429, retryAfterMs = 1500)
        val line = codec.encode(SessionRecord(2, 0, SideResponse(failed), sources = listOf(1)))
        assertEquals(SideResponse(failed), (codec.decode(line, 1, 3) as DecodedLine.Event).record.event)
        assertEquals(null, SideOutcome.Failed(ModelErrorKind.NETWORK, "x").httpStatus)
        assertThrows<IllegalArgumentException> { SideOutcome.Failed(ModelErrorKind.NETWORK, "x", retryAfterMs = -1) }
    }
}
