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
import org.junit.jupiter.api.Assertions.assertTrue
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
            // Assertions show no listed value: under a regression they would hold the key.
            val probe = Kit.adapters(Keys(PLACEHOLDER)).probe()
            val listing = listing("$PLACEHOLDER:8b", "llama3.2", "${PLACEHOLDER}2.5-coder")
            val shown = listOf(ListedModel(ModelId("llama3.2")))
            server.enqueue(listing)
            assertTrue(probe.listModels(profile) == shown, "only the id without the key is listed")
            val candidates = listOf("$PLACEHOLDER:8b" to true, "llama3.2" to true, "***:8b" to false)
            for ((n, pair) in candidates.withIndex()) {
                val (candidate, listed) = pair
                server.enqueue(listing)
                server.enqueueSse(trialCall("pa-1"))
                val result = probe.testAndSave(profile, ModelId(candidate), Kit.capabilities(), token = "pa-1")
                result as ProbeResult.Passed
                assertTrue(result.models == shown, "candidate #$n: only the id without the key is listed")
                assertEquals(listed, result.candidateListed, "candidate #$n is compared with the ids as listed")
            }
        }

    private companion object {
        const val BELL = 7
        const val PLACEHOLDER = "qwen"
    }
}
