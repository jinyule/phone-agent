package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.testing.FakeChatModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Settings validated where they are loaded, and small loop values. */
class LoopConfigTest {
    @Test
    fun `bad limits fail on construction`() {
        val bad =
            listOf(
                { LoopConfig(maxStepsPerTurn = 0) } to "maxStepsPerTurn must be >= 1, got 0",
                { LoopConfig(maxContinuationsPerTurn = -1) } to "maxContinuationsPerTurn must be >= 0, got -1",
                { LoopConfig(maxReconfirmations = -1) } to "maxReconfirmations must be >= 0, got -1",
                { LoopConfig(confirmTimeoutMs = 0) } to "confirmTimeoutMs must be > 0, got 0",
                { LoopConfig(questionTimeoutMs = 0) } to "questionTimeoutMs must be > 0, got 0",
                { LoopConfig(handoffTimeoutMs = 0) } to "handoffTimeoutMs must be > 0, got 0",
            )
        for ((build, message) in bad) {
            assertEquals(message, assertThrows<IllegalArgumentException> { build() }.message)
        }
        val edge = LoopConfig(maxStepsPerTurn = 1, maxContinuationsPerTurn = 0, maxReconfirmations = 0)
        assertEquals(1, edge.maxStepsPerTurn)
        assertEquals(LoopConfig.DEFAULT_MAX_RECONFIRMATIONS, LoopConfig().maxReconfirmations)
        assertEquals(1, LoopConfig.DEFAULT_MAX_RECONFIRMATIONS)
    }

    @Test
    fun `a retry never waits a negative time`() {
        assertThrows<IllegalArgumentException> { Recovery.Retry(-1) }
        assertEquals(0, Recovery.Retry(0).delayMs)
    }

    @Test
    fun `an interrupted prefix keeps only the delivered text`() {
        val streaming = Streaming(attempt = 2, info = FakeChatModel.INFO)
        assertNull(streaming.interrupted(), "nothing delivered")
        streaming.add(Delta.Text("Hel"))
        streaming.add(Delta.Reasoning("thinking"))
        streaming.add(Delta.Text("lo"))
        val prefix = streaming.interrupted()!!
        assertEquals("Hello", streaming.delivered)
        assertEquals(
            listOf(
                dev.phoneagent.core.model.Part
                    .Text("Hello"),
            ),
            prefix.parts,
        )
        assertEquals(2, prefix.attempt)
        assertEquals(true, prefix.interrupted)
        assertNull(prefix.stopReason)
    }
}
