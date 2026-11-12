package dev.phoneagent.app.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.task.TaskProblem
import dev.phoneagent.app.task.TaskView
import dev.phoneagent.core.agent.LoopTexts
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.StepDetail
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the chat shows of a task's steps, stops and problems, and what its controls do. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
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
    fun anUncertainStepSaysItMayHaveTakenEffect() {
        val card =
            StepCard(
                "phone_tap",
                "tap send",
                listOf(StepDetail("screen", "s1"), StepDetail("ref", "e2")),
                state = StepState.UNKNOWN,
            )
        show(TaskView(chatOf(Phase.Done, card, ToolCallRecorded(TAP))))

        compose.onNodeWithText(text(R.string.step_unknown)).assertExists()
        compose.onNodeWithText(text(R.string.step_failed)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tool_phone_tap)).assertExists()
        compose.onNodeWithText("tap send").assertExists()
        compose.onNodeWithText(context.getString(R.string.detail_line, text(R.string.detail_ref), "e2")).assertExists()
        compose.onNodeWithText("s1", substring = true).assertDoesNotExist()
    }

    @Test
    fun aHandOffsOutcomeAndWhereItsAppIsAreSaid() {
        val handOver = TAP.copy(name = "hand_to_user", args = JsonObject(mapOf("kind" to JsonPrimitive("login"))))
        val card =
            StepCard(
                "hand_to_user",
                "log in",
                listOf(StepDetail("handoff", "not_taken_back")),
                state = StepState.UNKNOWN,
            )
        val lost = "${ScreenUnavailableException::class.java.name}: gone. ${LoopTexts().screenUnknown}"
        show(
            TaskView(
                chatOf(
                    Phase.Done,
                    card,
                    ToolCallRecorded(handOver),
                    HandoffStarted(prompt, HandoffKind.LOGIN, "log in", handOver.id),
                    ToolResultRecorded(
                        handOver.id,
                        handOver.name,
                        ToolResult(ToolStatus.ERROR, listOf(Part.Text(lost))),
                    ),
                ),
            ),
        )
        compose
            .onNodeWithText(
                context.getString(
                    R.string.detail_line,
                    text(R.string.detail_handoff),
                    text(R.string.handoff_not_taken_back),
                ),
            ).assertExists()
        compose.onNodeWithText(text(R.string.place_maybe_with_user)).assertExists()
    }

    @Test
    fun aWithheldScreenIsSaidToBeKeptFromTheAgent() {
        val card = StepCard("phone_observe", "look", listOf(StepDetail("screen", "s4")), state = StepState.SUCCEEDED)
        val observe = TAP.copy(name = "phone_observe", args = JsonObject(emptyMap()))
        show(
            TaskView(
                chatOf(
                    Phase.Done,
                    card,
                    ToolCallRecorded(observe),
                    ScreenJudged(
                        observe.id,
                        SnapshotId("s4"),
                        JudgmentPurpose.TOOL,
                        JudgmentOutcome.WITHHELD,
                        "refused_app",
                    ),
                ),
            ),
        )
        compose.onNodeWithText(text(R.string.step_withheld)).assertExists()
    }

    @Test
    fun aRunningTaskCanBeStopped() {
        show(TaskView(chatOf(Phase.Running(1, 1), null)))
        compose.onNodeWithTag(ChatTags.STOP).performClick()
        assertEquals(1, actions.stops)
    }

    @Test
    fun aStoppedTaskSaysHowToGoOnAndOffersANewTask() {
        show(TaskView(chatOf(Phase.Stopped(StopCause.USER), null, TaskStopped(StopCause.USER))))
        compose.onNodeWithText(text(R.string.stopped_user)).assertExists()
        compose.onNodeWithTag(ChatTags.STOP).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.chat_new_task)).performClick()
        assertEquals(1, actions.newTasks)
    }

    @Test
    fun theComposerSendsWhatWasTyped() {
        show(TaskView())
        compose.onNodeWithText(text(R.string.chat_empty)).assertExists()
        compose.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(ChatTags.INPUT).performTextInput("open settings")
        compose.onNodeWithTag(ChatTags.SEND).performClick()
        assertEquals(listOf("open settings"), actions.sent)
    }

    @Test
    fun withoutAModelTheChatLeadsToTheModelSettings() {
        show(TaskView(problem = TaskProblem.NO_MODEL))
        compose.onNodeWithText(text(R.string.task_problem_no_model)).assertExists()
        compose.onNodeWithText(text(R.string.chat_open_models)).performClick()
        assertEquals(1, actions.models)
    }

    @Test
    fun aStorageProblemOffersNoModelSettings() {
        show(TaskView(problem = TaskProblem.STORAGE))
        compose.onNodeWithText(text(R.string.task_problem_storage)).assertExists()
        compose.onNodeWithText(text(R.string.chat_open_models)).assertDoesNotExist()
    }
}
