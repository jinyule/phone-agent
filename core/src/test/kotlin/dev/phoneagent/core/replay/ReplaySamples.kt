package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.Usage
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.WireSetting
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionHeader
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * A small whole session for the replay tests: one turn in which the model observes the screen
 * (snapshot and frame) and then answers. Two requests; ids and times as a recording would give
 * them (not normalized).
 */
object ReplaySamples {
    val provider = ProviderId("anthropic")
    val model = ModelId("claude-opus-5-5")
    val call = ToolCallId("toolu_01")
    val surface = SurfaceRef(7, SurfaceKind.AGENT)
    val frameBytes: ByteArray = "frame bytes of a test screen".toByteArray()
    val blob = BlobRef(ReplayFiles.sha256(frameBytes))

    val info =
        ModelInfo(
            provider,
            model,
            ModelProtocol.ANTHROPIC_MESSAGES,
            ModelCapabilities(
                tools = true,
                vision = true,
                contextTokens = 200_000,
                reasoningLevels = listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH),
            ),
        )
    val ref = ModelRef.of(info)

    val wire =
        WireProfile(
            mappingVersion = 1,
            credential = CredentialKind.API_KEY,
            reasoningEffort = WireSetting("output_config.effort", "high"),
        )

    val snapshot =
        ScreenSnapshot(
            id = SnapshotId("s1"),
            surface = surface,
            pkg = "com.example.chat",
            activity = ".Main",
            elements =
                listOf(
                    UiElement(
                        ElementRef("e2"),
                        ElementRole.BUTTON,
                        name = "张三",
                        bounds = Bounds(0, 100, 999, 200),
                        states = setOf(ElementState.CLICKABLE),
                    ),
                ),
            ocr = null,
            quality = TreeQuality.GOOD,
            flags = emptySet(),
            fingerprint = "fp-recorded",
        )

    val system = listOf(PromptBlock("identity", "You operate a phone."))
    val tools = listOf(ToolDef("phone_observe", "Read the screen.", JsonObject(emptyMap())))
    val user = Message(Role.USER, listOf(Part.Text("看看屏幕上有谁")))
    val observeCall = Part.ToolCall(call, "phone_observe", "{}")
    val firstReply = listOf(Part.Text("我看一下"), observeCall)
    val resultParts = listOf(Part.Text("e2 张三"), Part.Image(blob, "image/png", 2, 2))
    val answer = listOf(Part.Text("屏幕上有张三"))

    val header =
        SessionHeader(version = 1, sessionId = SessionId("2026-11-03.a"), taskId = TaskId("t-9"), createdAt = 1_000)

    val events: List<SessionEvent> =
        listOf(
            TaskStarted(SecurityLevel.STANDARD, unattended = false),
            TurnStarted(1),
            UserMessage(UserMessageKind.USER, user.parts),
            StepStarted(1, 1),
            RequestHeader(
                provider,
                model,
                ModelProtocol.ANTHROPIC_MESSAGES,
                wire,
                system,
                tools,
                CacheLayout.NONE,
                ContextEditPolicy.ClientSide,
                1024,
                ReasoningLevel.HIGH,
            ),
            AssistantMessage(1, provider, model, firstReply, StopReason.TOOL_USE, Usage(100, 10)),
            ToolCallRecorded(
                ToolCall(call, header.taskId, header.sessionId, "phone_observe", JsonObject(emptyMap()), "看屏幕"),
            ),
            PolicyVerdictRecorded(call, SecurityLevel.STANDARD, ActionIntent(IntentKind.READ), Verdict.Allow),
            ToolStarted(call, 8),
            ScreenObserved(call, snapshot),
            ScreenFrame(blob, "image/png", 2, 2, som = true, snapshotId = snapshot.id),
            ToolExecuted(call, ToolStatus.OK),
            ToolResultRecorded(call, "phone_observe", ToolResult(ToolStatus.OK, resultParts)),
            StepEnded(1, 1, StepOutcome.COMPLETED),
            StepStarted(1, 2),
            AssistantMessage(1, provider, model, answer, StopReason.END_TURN, Usage(200, 8)),
            StepEnded(1, 2, StepOutcome.COMPLETED),
            TurnEnded(1, TurnEndReason.COMPLETED),
        )

    val records: List<SessionRecord> =
        events.mapIndexed { index, event ->
            val seq = index + 1L
            val sources =
                when {
                    event is ToolCallRecorded -> listOf(6L)
                    event is ToolResultRecorded -> listOf(7L)
                    else -> emptyList()
                }
            SessionRecord(seq, 1_000 + seq * 37, event, sources = sources)
        }

    private val firstRequest =
        ChatRequest(
            system,
            tools,
            listOf(user),
            wire,
            CacheLayout.NONE,
            ContextEditPolicy.ClientSide,
            1024,
            ReasoningLevel.HIGH,
        )

    val requests: List<RecordedRequest> =
        listOf(
            RecordedRequest(1, 5, ref, firstRequest),
            RecordedRequest(
                2,
                15,
                ref,
                firstRequest.copy(
                    messages =
                        listOf(
                            user,
                            Message(Role.ASSISTANT, firstReply),
                            Message(Role.TOOL, listOf(Part.ToolResult(call, resultParts, isError = false))),
                        ),
                ),
            ),
        )

    val scenario =
        Scenario(
            format = Scenario.FORMAT,
            recording = Recording.AUTHORED,
            description = "Observe the screen, then say who is on it.",
            model = info,
            task = ScenarioTask(SecurityLevel.STANDARD, unattended = false),
            policy = PolicyPins.current(PolicyTuning()),
            device =
                Device(
                    surface,
                    widthPx = 1080,
                    heightPx = 2400,
                    apps = listOf(AppEntry("com.example.chat", "Chat", launchable = true)),
                ),
        )

    /** The raw session text of [records]. */
    fun sessionText(records: List<SessionRecord> = this.records): String {
        val codec = SessionCodec()
        return (listOf(codec.encodeHeader(header)) + records.map(codec::encode)).joinToString("") { "$it\n" }
    }

    /** The committed files of a fixture of this session: normalized session and requests. */
    class Committed(
        val session: String,
        val requests: String,
    )

    /** [records] and [requests] normalized as record mode writes them. */
    fun normalized(
        records: List<SessionRecord> = this.records,
        requests: List<RecordedRequest> = this.requests,
    ): Committed {
        val normalizer = Normalizer()
        val session = normalizer.session(sessionText(records))
        return Committed(session, normalizer.requests(RecordedRequests.encode(requests)))
    }

    /** Writes a whole fixture of this session into [dir] (created) and returns [dir]. */
    fun writeFixture(dir: Path): Path {
        Files.createDirectories(dir.resolve(ReplayFiles.BLOBS))
        val files = normalized()
        Files.writeString(dir.resolve(ReplayFiles.SCENARIO), ReplayFiles.encode(scenario))
        Files.writeString(dir.resolve(ReplayFiles.SESSION), files.session)
        Files.writeString(dir.resolve(ReplayFiles.REQUESTS), files.requests)
        Files.write(dir.resolve(ReplayFiles.BLOBS).resolve(blob.value), frameBytes)
        return dir
    }
}
