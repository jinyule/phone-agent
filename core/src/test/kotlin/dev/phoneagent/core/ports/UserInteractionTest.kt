package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.ToolCallId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

class UserInteractionTest {
    private val card =
        ConfirmCard(
            ToolCallId("c"),
            ConfirmReason.OUTWARD,
            "发送消息",
            target = "张三",
            content = "十点到",
            pkg = "com.tencent.mm",
        )

    @Test
    fun `questions need text and distinct non-blank options`() {
        assertEquals(emptyList<String>(), Question("几点到？").options)
        assertEquals(listOf("十点", "十一点"), Question("几点到？", listOf("十点", "十一点")).options)
        assertThrows<IllegalArgumentException> { Question(" ") }
        assertThrows<IllegalArgumentException> { Question("几点到？", listOf("十点", " ")) }
        assertThrows<IllegalArgumentException> { Question("几点到？", listOf("十点", "十点")) }
    }

    @Test
    fun `ask results have stable discriminators and an answer needs text`() {
        val results = listOf(AskResult.Answered("十点"), AskResult.Declined, AskResult.Expired, AskResult.Unavailable)
        val encoded = results.map { Json.encodeToString<AskResult>(it) }
        assertEquals(
            listOf(
                """{"type":"answered","text":"十点"}""",
                """{"type":"declined"}""",
                """{"type":"expired"}""",
                """{"type":"unavailable"}""",
            ),
            encoded,
        )
        assertEquals(results, encoded.map { Json.decodeFromString<AskResult>(it) })
        assertThrows<IllegalArgumentException> { AskResult.Answered(" ") }
        assertThrows<SerializationException> { Json.decodeFromString<AskResult>("""{"type":"answered"}""") }
    }

    @Test
    fun `result enums are part of the session format`() {
        assertEquals(listOf("APPROVED", "REJECTED", "EXPIRED", "UNAVAILABLE"), ConfirmResult.entries.map { it.name })
        assertEquals(
            listOf("DONE", "DECLINED", "EXPIRED", "UNAVAILABLE", "FAILED", "NOT_TAKEN_BACK"),
            HandoffResult.entries.map { it.name },
        )
        assertEquals(
            listOf("SPEND", "OUTWARD", "DELETE", "SETTINGS", "FIRST_IN_APP", "REQUESTED_BY_MODEL"),
            ConfirmReason.entries.map { it.name },
        )
    }

    @Test
    fun `confirm cards need an action and round-trip with or without optional fields`() {
        val minimal = ConfirmCard(ToolCallId("c"), ConfirmReason.FIRST_IN_APP, "打开微信")
        val paid = card.copy(reason = ConfirmReason.SPEND, amount = Money(1999, "CNY"))
        listOf(minimal, card, paid).forEach {
            assertEquals(it, Json.decodeFromString<ConfirmCard>(Json.encodeToString(it)))
        }
        assertEquals(
            minimal,
            Json.decodeFromString<ConfirmCard>("""{"callId":"c","reason":"FIRST_IN_APP","action":"打开微信"}"""),
        )
        assertThrows<IllegalArgumentException> { ConfirmCard(ToolCallId("c"), ConfirmReason.OUTWARD, " ") }
        assertThrows<SerializationException> {
            Json.decodeFromString<ConfirmCard>("""{"callId":"c","reason":"OUTWARD"}""")
        }
    }

    @Test
    fun `money is non-negative with an optional ISO currency`() {
        assertEquals(Money(0), Money(0, null))
        assertEquals("CNY", Money(1999, "CNY").currency)
        assertEquals(Money(1), Json.decodeFromString<Money>("""{"minor":1}"""))
        assertEquals(Money(100, "CNY"), Json.decodeFromString<Money>("""{"minor":100,"currency":"CNY"}"""))
        assertThrows<IllegalArgumentException> { Money(-1) }
        assertThrows<IllegalArgumentException> { Money(1, "yuan") }
        assertThrows<IllegalArgumentException> { Money(1, "cny") }
        assertThrows<IllegalArgumentException> { Json.decodeFromString<Money>("""{"minor":-1}""") }
        assertThrows<IllegalArgumentException> { Json.decodeFromString<Money>("""{"minor":1,"currency":"cny"}""") }
        assertThrows<SerializationException> { Json.decodeFromString<Money>("""{"currency":"CNY"}""") }
    }

    @Test
    fun `a waiting prompt ends with a result or with cancellation, never both`() =
        runTest {
            val reply = CompletableDeferred<ConfirmResult>()
            val interaction = FakeInteraction(reply)
            val approved = async { interaction.confirm(card) }
            reply.complete(ConfirmResult.APPROVED)
            assertEquals(ConfirmResult.APPROVED, approved.await())

            val stopped = FakeInteraction(CompletableDeferred())
            val waiting = async { stopped.confirm(card) }
            testScheduler.runCurrent()
            waiting.cancel()
            assertThrows<CancellationException> { waiting.await() }
            assertEquals(listOf(card, card), interaction.asked + stopped.asked)
            assertTrue(stopped.ask(Question("?")) is AskResult.Unavailable)
            assertEquals(HandoffResult.UNAVAILABLE, stopped.handOff(HandoffKind.LOGIN, "登录"))
        }

    /** Answers confirmations from [reply]; nobody can answer questions or take a handoff. */
    private class FakeInteraction(
        private val reply: CompletableDeferred<ConfirmResult>,
    ) : UserInteraction {
        val asked = mutableListOf<ConfirmCard>()

        override suspend fun ask(question: Question): AskResult = AskResult.Unavailable

        override suspend fun confirm(card: ConfirmCard): ConfirmResult {
            asked += card
            return reply.await()
        }

        override suspend fun handOff(
            kind: HandoffKind,
            reason: String,
        ): HandoffResult = HandoffResult.UNAVAILABLE
    }
}
