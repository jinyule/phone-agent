package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.LogRecords.log
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.SCREEN_JUDGMENT_TOOL
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The runtime's judgment of a new screen (policy-rules §6.4). */
class ScreenGateTest {
    private val tap =
        ToolCall(
            ToolCallId("c4"),
            SessionSamples.task,
            SessionSamples.session,
            "phone_tap",
            buildJsonObject { put("ref", "e2") },
            reason = "打开聊天",
        )
    private val screen = SessionSamples.snapshot
    private val records = log(TaskStarted(SecurityLevel.CAUTIOUS, unattended = true))

    /** Resolves every call to a READ in its screen's package and remembers what it was asked. */
    private class Resolver : IntentResolver {
        val asked = mutableListOf<Triple<ToolCall, RiskClass, ScreenSnapshot?>>()

        override fun resolve(
            call: ToolCall,
            risk: RiskClass,
            screen: ScreenSnapshot?,
        ): ActionIntent {
            asked += Triple(call, risk, screen)
            return ActionIntent(IntentKind.READ, pkg = screen?.pkg)
        }
    }

    private fun judged(verdict: Verdict): ScreenJudgment =
        ScreenGate(Resolver(), ScriptedPolicy(verdict)).judge(tap, screen, records)

    @Test
    fun `a new screen is judged as phone_observe of the call that read it, as a READ, on the log's context`() {
        val resolver = Resolver()
        val policy = ScriptedPolicy(Verdict.Allow)

        val judgment = ScreenGate(resolver, policy).judge(tap, screen, records)

        assertEquals(ScreenJudgment.Shown, judgment)
        val observe = tap.copy(name = "phone_observe", args = JsonObject(emptyMap()))
        assertEquals("phone_observe", SCREEN_JUDGMENT_TOOL)
        assertEquals(listOf(Triple(observe, RiskClass.READ, screen)), resolver.asked)
        assertEquals(listOf(ActionIntent(IntentKind.READ, pkg = screen.pkg)), policy.intents)
        val context = policy.contexts.single()
        assertEquals(policyContextOf(records, tap.taskId, screen, approved = null), context)
        assertEquals(SecurityLevel.CAUTIOUS, context.level)
        assertTrue(context.unattended)
        assertEquals(screen, context.screen)
        assertNull(context.approved)
    }

    @Test
    fun `a refused screen is withheld with the rule that refused it and its hint`() {
        assertEquals(
            ScreenJudgment.Withheld("hard.finance_app", "这是金融类 App，按安全规则不能操作。"),
            judged(Verdict.Refuse("hard.finance_app", "这是金融类 App，按安全规则不能操作。")),
        )
    }

    @Test
    fun `a confirm withholds the screen too, recorded as such, with no hint`() {
        assertEquals(ScreenJudgment.Withheld("confirm", hint = null), judged(Verdict.Confirm(PolicySamples.sendCard)))
    }

    @Test
    fun `a hand-off withholds the screen too, recorded as such, with no hint`() {
        assertEquals(ScreenJudgment.Withheld("hand_off", hint = null), judged(PolicySamples.handOff))
    }

    @Test
    fun `a withheld copy keeps the id, surface, pkg and detail, and nothing of what the screen shows`() {
        val full = screen.copy(flags = setOf(ScreenFlag.SECURE, ScreenFlag.KEYBOARD), detail = SnapshotDetail.FULL)
        assertTrue(full.elements.isNotEmpty() && !full.ocr.isNullOrEmpty() && full.activity != null)

        val copy = full.withheld()

        assertEquals(full.id, copy.id)
        assertEquals(full.surface, copy.surface)
        assertEquals(full.pkg, copy.pkg)
        assertEquals(SnapshotDetail.FULL, copy.detail)
        assertNull(copy.activity)
        assertEquals(emptyList<Any>(), copy.elements)
        assertNull(copy.ocr)
        assertEquals(TreeQuality.EMPTY, copy.quality)
        assertEquals(setOf(ScreenFlag.WITHHELD), copy.flags)
        assertEquals("withheld", copy.fingerprint)
        assertNotEquals(full.fingerprint, copy.fingerprint)
        assertEquals(copy, screen.copy(fingerprint = "other").withheld().copy(detail = SnapshotDetail.FULL))
    }
}
