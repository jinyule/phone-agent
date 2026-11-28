package dev.phoneagent.app.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.task.ChatEntry
import dev.phoneagent.app.task.PRODUCT_TOOLS
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.StepDetail
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** The chat's words for what core reports: every tool, detail and outcome has its label. */
@RunWith(AndroidJUnit4::class)
class ChatTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyToolOfTheProductHasALabel() {
        val unlabelled = PRODUCT_TOOLS.tools.map { it.def.name }.filter { toolText(it) == null }
        assertEquals(emptyList<String>(), unlabelled)
        assertNull(toolText("not_a_tool"))
    }

    @Test
    fun everyArgumentOfEveryToolIsLabelledOrHidden() {
        val names =
            PRODUCT_TOOLS.tools
                .flatMap { tool ->
                    tool.def.parameters["properties"]
                        ?.jsonObject
                        ?.keys
                        .orEmpty()
                }.toSet() - "reason"
        val unlabelled = names.filter { detailText(it) == null && it !in HIDDEN_DETAILS }
        assertEquals(emptyList<String>(), unlabelled)
        assertTrue("the phase-1 tools take arguments", names.size > 30)
    }

    @Test
    fun everyResultDetailOfThePhaseOneToolsIsLabelledOrHidden() {
        // The `details` keys the phase-1 tools write (core.tools: ToolResults, PhoneObserveTool,
        // PhoneWaitTool, AskUserTool, HandToUserTool).
        val keys =
            listOf(
                ToolResult.SCREEN,
                "outcome",
                "staleReason",
                "effect",
                "quality",
                "imageAttached",
                "met",
                "answer",
                HANDOFF_DETAIL,
            )
        assertEquals(emptyList<String>(), keys.filter { detailText(it) == null && it !in HIDDEN_DETAILS })
        assertEquals(setOf("answer", HANDOFF_DETAIL), keys.filter { it !in HIDDEN_DETAILS }.toSet())
    }

    @Test
    fun theScreenIdsAndTechnicalFieldsAreNotShown() {
        val details =
            listOf(
                StepDetail("screen", "s1"),
                StepDetail("ref", "e2"),
                StepDetail("effect", "CHANGED"),
                StepDetail("new", "x"),
            )
        assertEquals(listOf(StepDetail("ref", "e2"), StepDetail("new", "x")), shownDetails(details))
    }

    @Test
    fun everyOutcomeOfAHandOffHasItsOwnWords() {
        val texts = HandoffResult.entries.map { handoffValueText(it.name.lowercase(Locale.ROOT)) }
        assertTrue(texts.all { it != null })
        assertEquals(HandoffResult.entries.size, texts.toSet().size)
        assertEquals(
            listOf("done", "declined", "expired", "unavailable", "failed", "not_taken_back"),
            HandoffResult.entries.map { it.name.lowercase(Locale.ROOT) },
        )
        assertNull(handoffValueText("DONE"))
        assertEquals(R.string.handoff_not_taken_back, detailValueText(StepDetail(HANDOFF_DETAIL, "not_taken_back")))
        assertNull(detailValueText(StepDetail("kind", "done")))
    }

    @Test
    fun anUncertainStepIsNeverShownAsAFailure() {
        assertEquals(R.string.step_unknown, stateText(StepState.UNKNOWN))
        assertEquals(context.getString(R.string.step_unknown), "Result uncertain, it may have taken effect")
        val step =
            ChatEntry.Step(
                ToolCallId("call-1"),
                "phone_tap",
                card = StepCard("phone_tap", "x", state = StepState.UNKNOWN),
                status = ToolStatus.CANCELLED,
            )
        assertEquals(R.string.step_unknown, stepStateText(step))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun inChineseAnUncertainStepSaysItMayHaveTakenEffectAndAFailureNeverThatNothingRan() {
        assertEquals("结果不确定，可能已经生效", context.getString(R.string.step_unknown))
        assertFalse(context.getString(R.string.step_failed).contains("没有执行"))
        assertEquals("App 可能在你那边。", context.getString(R.string.place_maybe_with_user))
    }

    @Test
    fun aStepWithoutACardSaysOnlyWhatItsStatusAllows() {
        assertEquals(R.string.step_running, statusText(null))
        assertEquals(R.string.step_succeeded, statusText(ToolStatus.OK))
        assertEquals(R.string.step_refused, statusText(ToolStatus.REFUSED))
        for (status in ToolStatus.entries - ToolStatus.OK - ToolStatus.REFUSED) {
            assertEquals(
                status.name,
                R.string.step_unknown,
                statusText(status),
            )
        }
        assertEquals(
            R.string.step_failed,
            stepStateText(ChatEntry.Step(ToolCallId("c"), "x", card = StepCard("x", "y", state = StepState.FAILED))),
        )
        assertEquals(
            R.string.step_succeeded,
            stepStateText(ChatEntry.Step(ToolCallId("c"), "x", status = ToolStatus.OK)),
        )
    }

    @Test
    fun amountsHaveTwoDecimals() {
        assertEquals("123.45", amountDigits(Money(12_345, "CNY")))
        assertEquals("0.05", amountDigits(Money(5)))
        assertEquals("1.00", amountDigits(Money(100)))
        assertNotNull(detailText("amount"))
    }
}
