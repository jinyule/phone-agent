package dev.phoneagent.core.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Registry of every [SessionEvent] type: its on-disk name (the `@SerialName`) and whether
 * records of that type are ignorable by readers that do not know it. Types that the model view,
 * the turn and run structure (`session/resumed` included), the task settings or the execution
 * rules (`policy/verdict`, which `tool/started` points at; `screen/judged`, which decides what
 * later results may show) depend on are not ignorable; diagnostics, prompts, device facts and
 * side requests are.
 */
public object SessionEventTypes {
    private val all: List<EventType<*>> =
        listOf(
            type<TaskStarted>(ignorable = false),
            type<TaskLevelChanged>(ignorable = false),
            type<TaskStopped>(ignorable = false),
            type<TurnStarted>(ignorable = false),
            type<TurnEnded>(ignorable = false),
            type<StepStarted>(ignorable = false),
            type<StepEnded>(ignorable = false),
            type<InboxSpliced>(ignorable = false),
            type<SessionResumed>(ignorable = false),
            type<UserMessage>(ignorable = false),
            type<RequestHeader>(ignorable = false),
            type<RequestRetry>(ignorable = true),
            type<AssistantMessage>(ignorable = false),
            type<AssistantAttempt>(ignorable = true),
            type<ReasoningDropped>(ignorable = false),
            type<SideRequest>(ignorable = true),
            type<SideResponse>(ignorable = true),
            type<ToolCallRecorded>(ignorable = false),
            type<ToolStarted>(ignorable = false),
            type<ToolExecuted>(ignorable = false),
            type<ToolResultRecorded>(ignorable = false),
            type<PolicyVerdictRecorded>(ignorable = false),
            type<ApprovalAsked>(ignorable = true),
            type<ApprovalDecided>(ignorable = true),
            type<QuestionAsked>(ignorable = true),
            type<QuestionAnswered>(ignorable = true),
            type<HandoffStarted>(ignorable = true),
            type<HandoffEnded>(ignorable = true),
            type<PromptWithdrawn>(ignorable = true),
            type<CompactionStarted>(ignorable = true),
            type<CompactionSummary>(ignorable = false),
            type<CompactionPrune>(ignorable = false),
            type<CompactionEnded>(ignorable = true),
            type<ScreenObserved>(ignorable = true),
            type<ScreenActed>(ignorable = true),
            type<ScreenLaunched>(ignorable = true),
            type<ScreenFrame>(ignorable = true),
            type<ScreenJudged>(ignorable = false),
        )
    private val byName: Map<String, EventType<*>> = all.associateBy { it.name }
    private val byClass: Map<KClass<*>, EventType<*>> = all.associateBy { it.kclass }

    /** Every known type name. */
    public val names: Set<String> = byName.keys

    /** The type name of [event]. */
    public fun typeOf(event: SessionEvent): String = of(event).name

    /** Whether records of the known type [name] are written as ignorable; `null` for unknown names. */
    public fun isIgnorable(name: String): Boolean? = byName[name]?.ignorable

    internal fun of(event: SessionEvent): EventType<*> = byClass.getValue(event::class)

    internal fun named(name: String): EventType<*>? = byName[name]

    // serializer<T>() rather than the generated T.serializer(): detekt analyzes without the
    // serialization compiler plugin and cannot see generated members.
    private inline fun <reified T : SessionEvent> type(ignorable: Boolean): EventType<T> =
        EventType(T::class, serializer<T>(), ignorable)

    internal class EventType<T : SessionEvent>(
        val kclass: KClass<T>,
        private val serializer: KSerializer<T>,
        val ignorable: Boolean,
    ) {
        val name: String = serializer.descriptor.serialName

        fun encode(
            json: Json,
            event: SessionEvent,
        ): JsonElement = json.encodeToJsonElement(serializer, kclass.java.cast(event))

        fun decode(
            json: Json,
            data: JsonElement,
        ): SessionEvent = json.decodeFromJsonElement(serializer, data)
    }
}
