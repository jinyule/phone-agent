package dev.phoneagent.core.testing

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

/** A scripted [ScreenPort]: records what tools send, answers from the configured lambdas. */
class FakeScreen(
    var current: ScreenSnapshot = Screens.CHAT,
) : ScreenPort {
    override val surface: SurfaceRef = Screens.AGENT

    val acts = mutableListOf<Pair<UiAction, SnapshotId?>>()
    val launches = mutableListOf<LaunchTarget>()
    val snapshotOptions = mutableListOf<SnapshotOptions>()
    val frames = mutableListOf<Boolean>()

    /** Thrown by every call when set. */
    var unavailable: ScreenUnavailableException? = null
    var onAct: (
        UiAction,
        SnapshotId?,
    ) -> ActionOutcome = { _, _ -> ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.UNKNOWN) }
    var onLaunch: (
        LaunchTarget,
    ) -> ActionOutcome = { ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.NEW_APP, current) }
    var onSnapshot: suspend () -> ScreenSnapshot = { current }
    var onAwaitChange: suspend (Long) -> Boolean = { false }
    var frame: Frame? = Frame(byteArrayOf(1, 2, 3), "image/jpeg", 720, 1280, som = true)

    /** Thrown by [frame] only, when set. */
    var frameError: ScreenUnavailableException? = null

    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot {
        unavailable?.let { throw it }
        snapshotOptions += options
        return onSnapshot()
    }

    override suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome {
        unavailable?.let { throw it }
        acts += action to on
        return onAct(action, on)
    }

    override suspend fun launch(target: LaunchTarget): ActionOutcome {
        unavailable?.let { throw it }
        launches += target
        return onLaunch(target)
    }

    override suspend fun frame(
        maxEdge: Int,
        som: Boolean,
    ): Frame? {
        unavailable?.let { throw it }
        frameError?.let { throw it }
        frames += som
        return frame
    }

    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        unavailable?.let { throw it }
        return onAwaitChange(timeoutMs)
    }
}

/** A scripted [UserInteraction] that records prompts. */
class FakeInteraction(
    var askResult: AskResult = AskResult.Answered("十点"),
    var handoffResult: HandoffResult = HandoffResult.DONE,
) : UserInteraction {
    val asked = mutableListOf<Question>()
    val handoffs = mutableListOf<Pair<HandoffKind, String>>()
    val confirmed = mutableListOf<ConfirmCard>()

    override suspend fun ask(question: Question): AskResult {
        asked += question
        return askResult
    }

    override suspend fun confirm(card: ConfirmCard): ConfirmResult {
        confirmed += card
        return ConfirmResult.APPROVED
    }

    override suspend fun handOff(
        kind: HandoffKind,
        reason: String,
    ): HandoffResult {
        handoffs += kind to reason
        return handoffResult
    }
}

/** A fixed app list. */
class FakeCatalog(
    private val entries: List<AppEntry>,
) : AppCatalog {
    override suspend fun apps(): List<AppEntry> = entries
}

/** A [ToolContext] over fakes; blobs are kept in [blobs] keyed by their sha256. */
class FakeContext(
    override val screen: ScreenPort? = FakeScreen(),
    override val apps: AppCatalog? = null,
    override val interaction: UserInteraction = FakeInteraction(),
) : ToolContext {
    override val taskId = TaskId("t1")
    override val sessionId = SessionId("session-1")
    override val clock = Clock { 0 }
    val blobs = LinkedHashMap<BlobRef, ByteArray>()

    override suspend fun storeBlob(bytes: ByteArray): BlobRef {
        val hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return BlobRef(hex).also { blobs[it] = bytes }
    }
}

/** A call of [name] with [argsJson] (without `reason`). */
fun call(
    name: String,
    argsJson: String = "{}",
    reason: String = "测试这一步",
): ToolCall =
    ToolCall(
        ToolCallId("c1"),
        TaskId("t1"),
        SessionId("session-1"),
        name,
        Json.decodeFromString<JsonObject>(argsJson),
        reason,
    )

/** The model-visible text of [result]: text parts joined, images as a placeholder line. */
fun ToolResult.text(): String =
    content.joinToString("\n") { part ->
        when (part) {
            is Part.Text -> part.text
            is Part.Image -> "[图片 ${part.mime} ${part.width}x${part.height}]"
            is Part.ToolCall, is Part.ToolResult, is Part.Reasoning -> error("unexpected part $part")
        }
    }

/** The [key] entry of the result's details as text, or `null` when absent. */
fun ToolResult.detail(key: String): String? = (details?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
