package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builders for policy tests: screens from a few elements, calls from plain maps. */
internal object Fx {
    const val APP = "com.example.chat"
    const val OWN = "dev.phoneagent"

    /** The name this app is shown under. */
    const val OWN_LABEL = "PhoneAgent"

    val CLICK = setOf(ElementState.CLICKABLE)

    /** The policy under test, built as the app builds it. */
    fun rules(): PolicyRules = PolicyRules(setOf(OWN), setOf(OWN_LABEL))

    fun box(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Bounds = Bounds(left, top, right, bottom)

    /** A clickable button; set hint, value, secret, parent, window or resource id with `copy`. */
    fun el(
        ref: String,
        name: String? = null,
        role: ElementRole = ElementRole.BUTTON,
        bounds: Bounds = box(0, 400, 100, 450),
        states: Set<ElementState> = CLICK,
    ): UiElement =
        UiElement(
            ref = ElementRef(ref),
            role = role,
            name = name,
            value = null,
            hint = null,
            bounds = bounds,
            states = states,
            secret = false,
            parent = null,
            window = null,
            resourceId = null,
        )

    fun text(
        ref: String,
        name: String,
        bounds: Bounds = box(0, 300, 500, 340),
        window: Int? = null,
        parent: String? = null,
    ): UiElement =
        el(ref, name, ElementRole.TEXT, bounds, emptySet()).copy(window = window, parent = parent?.let(::ElementRef))

    /** An editable input; set name, value or resource id with `copy`. */
    fun field(
        ref: String,
        hint: String? = null,
        secret: Boolean = false,
        focused: Boolean = false,
        bounds: Bounds = box(0, 900, 800, 960),
    ): UiElement {
        val states = setOf(ElementState.EDITABLE, ElementState.CLICKABLE)
        return el(ref, null, ElementRole.INPUT, bounds, if (focused) states + ElementState.FOCUSED else states)
            .copy(hint = hint, secret = secret)
    }

    /** A snapshot of the agent surface; set the activity with `copy`. */
    fun screen(
        vararg elements: UiElement,
        pkg: String? = APP,
        flags: Set<ScreenFlag> = emptySet(),
        ocr: List<OcrLine>? = null,
        id: String = "s1",
    ): ScreenSnapshot =
        ScreenSnapshot(
            id = SnapshotId(id),
            surface = SurfaceRef(2, SurfaceKind.AGENT),
            pkg = pkg,
            activity = null,
            elements = elements.toList(),
            ocr = ocr,
            quality = TreeQuality.GOOD,
            flags = flags,
            fingerprint = "fp-$id",
        )

    fun ocr(
        text: String,
        bounds: Bounds = box(0, 600, 999, 650),
    ): OcrLine = OcrLine(text, bounds)

    fun call(
        name: String,
        vararg args: Pair<String, Any?>,
        reason: String = "给张三发消息",
        id: String = "c1",
    ): ToolCall =
        ToolCall(
            id = ToolCallId(id),
            taskId = TaskId("t1"),
            sessionId = SessionId("s1"),
            name = name,
            args = JsonObject(args.associate { (k, v) -> k to json(v) }),
            reason = reason,
        )

    private fun json(value: Any?): kotlinx.serialization.json.JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is kotlinx.serialization.json.JsonElement -> value
            else -> error("unsupported $value")
        }

    fun tap(
        ref: String,
        screen: String = "s1",
    ): ToolCall = call("phone_tap", "screen" to screen, "ref" to ref)

    /** A policy context; set the approved card with `copy`. */
    fun ctx(
        level: SecurityLevel = SecurityLevel.STANDARD,
        screen: ScreenSnapshot? = null,
        unattended: Boolean = false,
        operated: Set<String> = emptySet(),
        outwardConfirmed: Set<String> = emptySet(),
    ): PolicyContext =
        PolicyContext(
            taskId = TaskId("t1"),
            level = level,
            unattended = unattended,
            screen = screen,
            operatedPackages = operated,
            outwardConfirmedPackages = outwardConfirmed,
        )
}
