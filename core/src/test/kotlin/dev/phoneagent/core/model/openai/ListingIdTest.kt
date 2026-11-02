package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The model ids a listing yields: only usable ones, each verbatim, one bad entry costing only
 * itself. An id holding a secret the request sent is left out rather than shown rewritten;
 * whether the candidate is listed is still decided on the ids as listed.
 */
class ListingIdTest {
    @StartStop
    private val server = MockWebServer()

    private val profile get() = Kit.profile(server.base())

    private fun listing(vararg ids: String): MockResponse =
        MockResponse
            .Builder()
            .body(ids.joinToString(",", """{"object":"list","data":[""", "]}") { """{"id":${JsonPrimitive(it)}}""" })
            .build()

    private fun trialCall(token: String) =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_t","function":""" +
                """{"name":"confirm_connection","arguments":${JsonPrimitive("{\"token\":\"$token\"}")}}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        )

    @Test
    fun `an id that is not a usable model id skips only its own entry`() =
        runTest {
            server.enqueue(listing("before", "bell" + Char(BELL) + "id", "line\nbreak", "after"))
            assertEquals(
                listOf(ListedModel(ModelId("before")), ListedModel(ModelId("after"))),
                Kit.adapters().probe().listModels(profile),
            )
        }

    @Test
    fun `test and save goes on to the trial call past a bad entry`() =
        runTest {
            server.enqueue(listing("before", "bell" + Char(BELL) + "id", Kit.MODEL.value))
            server.enqueueSse(trialCall("pa-1"))
            val result = Kit.adapters().probe().testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            result as ProbeResult.Passed
            assertEquals(listOf(ModelId("before"), Kit.MODEL), result.models?.map { it.id })
            assertEquals(true, result.candidateListed)
            assertEquals(2, server.requestCount, "the trial call was made")
        }

    @Test
    fun `a placeholder key inside model names leaves those entries out, never rewritten`() =
        runTest {
            // A local service given a placeholder key that its model names happen to contain.
            val probe = Kit.adapters(Keys(PLACEHOLDER)).probe()
            val listing = listing("$PLACEHOLDER-7b", "other", "$PLACEHOLDER:latest")
            server.enqueue(listing)
            assertEquals(listOf(ListedModel(ModelId("other"))), probe.listModels(profile))
            for ((candidate, listed) in listOf("$PLACEHOLDER-7b" to true, "other" to true, "***-7b" to false)) {
                server.enqueue(listing)
                server.enqueueSse(trialCall("pa-1"))
                val result = probe.testAndSave(profile, ModelId(candidate), Kit.capabilities(), token = "pa-1")
                result as ProbeResult.Passed
                assertEquals(listOf(ModelId("other")), result.models?.map { it.id })
                assertEquals(listed, result.candidateListed, candidate)
            }
        }

    private companion object {
        const val BELL = 7
        const val PLACEHOLDER = "tinkey"
    }
}
