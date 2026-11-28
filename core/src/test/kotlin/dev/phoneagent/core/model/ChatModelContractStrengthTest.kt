package dev.phoneagent.core.model

import dev.phoneagent.core.model.FakeChatModel.Flaw
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import java.lang.reflect.InvocationTargetException

/**
 * [ChatModelContract] fails an adapter that breaks it: each [Flaw] of [FakeChatModel], and a model
 * whose reasoning levels make no level clamp, fails the check written for it, while the fake
 * without a flaw passes every check.
 */
class ChatModelContractStrengthTest {
    @Test
    fun `the fake without a flaw passes every check`() {
        assertEquals(emptyList<String>(), failedChecks { FakeChatModel(it) })
    }

    @Test
    fun `settings complete changes in place, in what it sent, or for later requests fail the contract`() {
        listOf(Flaw.CHANGES_HEADERS_IN_PLACE, Flaw.CHANGES_SENT_SETTINGS, Flaw.CHANGES_LATER_SETTINGS).forEach { flaw ->
            assertEquals(listOf(LIFECYCLE), failedChecks { FakeChatModel(it, flaw = flaw) }, "$flaw")
        }
    }

    @Test
    fun `settings that follow the live profile fail the contract`() {
        assertEquals(listOf(LIVE_SETTINGS), failedChecks { FakeChatModel(it, flaw = Flaw.SHARES_LIVE_HEADERS) })
    }

    @Test
    fun `mapping the level asked for instead of the clamped one fails the contract`() {
        assertEquals(listOf(MAPS_CLAMPED), failedChecks { FakeChatModel(it, flaw = Flaw.MAPS_BEFORE_CLAMPING) })
    }

    @Test
    fun `a model that declares every level, or none, fails the contract, flaw or not`() {
        listOf(ReasoningLevel.entries, null).forEach { levels ->
            assertEquals(listOf(SUBJECT_CLAMPS), failedChecks { FakeChatModel(it, levels) }, "levels $levels")
            val failed = failedChecks { FakeChatModel(it, levels, Flaw.MAPS_BEFORE_CLAMPING) }
            assertTrue(SUBJECT_CLAMPS in failed, "levels $levels, mapping before clamping: $failed")
        }
    }

    private companion object {
        const val SUBJECT_CLAMPS = "the subject declares reasoning levels that make some level clamp"
        const val MAPS_CLAMPED = "prepare maps the clamped level, so preparing the effective level gives the same wire"
        const val LIFECYCLE = "complete changes neither the settings given out nor the ones prepared afterwards"
        const val LIVE_SETTINGS = "settings share nothing with the live settings the model was built from"

        /**
         * Runs every check of [ChatModelContract] on subjects around the fake [build] makes and
         * names the ones that fail; a check skipped by an assumption does not fail.
         */
        fun failedChecks(build: (MutableMap<String, String>) -> FakeChatModel): List<String> {
            val contract =
                object : ChatModelContract() {
                    override fun subject(): Subject = FakeChatModel.subject(build)
                }
            return ChatModelContract::class.java.methods
                .filter { it.isAnnotationPresent(Test::class.java) }
                .mapNotNull { check ->
                    val thrown = runCatching { check.invoke(contract) }.exceptionOrNull()
                    when (val failure = (thrown as? InvocationTargetException)?.targetException ?: thrown) {
                        null, is TestAbortedException -> null
                        is AssertionError -> check.name
                        else -> throw failure
                    }
                }.sorted()
        }
    }
}
