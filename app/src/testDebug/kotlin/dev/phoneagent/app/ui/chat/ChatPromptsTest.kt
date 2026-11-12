package dev.phoneagent.app.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.task.TaskView
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.QuestionAsked
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The prompts the task waits for: a confirmation, a question, a hand-off, and their answers. */
@RunWith(AndroidJUnit4::class)
class ChatPromptsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prompt = PromptId("prompt-1")
    private val actions = RecordingChatActions()

    private fun text(id: Int): String = context.getString(id)

    private fun show(view: TaskView) {
        compose.setContent { MaterialTheme { ChatScreen(view, actions, actions::openModels) } }
    }

    @Test
    fun aConfirmationIsAllowedOrNot() {
        val card =
            ConfirmCard(
                ToolCallId("call-1"),
                ConfirmReason.SPEND,
                "pay",
                target = "Cafe",
                amount = Money(12_345, "CNY"),
            )
        show(
            TaskView(
                chatOf(
                    Phase.Waiting(WaitReason.USER, prompt),
                    null,
                    ApprovalAsked(prompt, card, expiresAt = Long.MAX_VALUE),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.confirm_spend)).assertExists()
        compose
            .onNodeWithText(
                context.getString(R.string.detail_line, text(R.string.detail_target), "Cafe"),
            ).assertExists()
        compose
            .onNodeWithText(
                context.getString(R.string.detail_line, text(R.string.detail_amount), "CNY 123.45"),
            ).assertExists()
        compose.onNodeWithText(text(R.string.confirm_approve)).performClick()
        compose.onNodeWithText(text(R.string.confirm_reject)).performClick()
        assertEquals(listOf(prompt to UserReply.Approve, prompt to UserReply.Reject), actions.answers)
    }

    @Test
    fun aQuestionIsAnsweredByAnOptionTheComposerOrSkipped() {
        val asked = QuestionAsked(prompt, ToolCallId("call-1"), Question("how many?", listOf("two", "four")))
        show(TaskView(chatOf(Phase.Waiting(WaitReason.USER, prompt), null, asked)))

        compose.onNodeWithText("how many?").assertExists()
        compose.onNodeWithText("four").performClick()
        compose.onNodeWithText(text(R.string.ask_skip)).performClick()
        compose.onNodeWithText(text(R.string.chat_answer_hint)).assertExists()
        assertEquals(listOf(prompt to UserReply.Answer("four"), prompt to UserReply.Decline), actions.answers)
    }

    @Test
    fun aHandOffIsDoneOrGivenBack() {
        show(
            TaskView(
                chatOf(
                    Phase.Waiting(WaitReason.HANDOFF, prompt),
                    null,
                    HandoffStarted(prompt, HandoffKind.PAYMENT, "pay the bill"),
                ),
            ),
        )
        compose.onNodeWithText(text(R.string.handoff_kind_payment)).assertExists()
        compose.onNodeWithText("pay the bill").assertExists()
        compose.onNodeWithText(text(R.string.handoff_did_it)).performClick()
        compose.onNodeWithText(text(R.string.handoff_give_back)).performClick()
        assertEquals(listOf(prompt to UserReply.HandoffDone, prompt to UserReply.Decline), actions.answers)
    }
}
