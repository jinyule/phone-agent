package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** Where a screen action is aimed, as the model wrote it. */
internal sealed interface Aim {
    /** An element by ref; [snapshot] is the `screen` argument naming the snapshot the ref is from. */
    data class Ref(
        val ref: ElementRef,
        val snapshot: String?,
    ) : Aim

    /** A point in 0–999 coordinates. */
    data class Point(
        val x: Int,
        val y: Int,
    ) : Aim
}

/**
 * What a call does, decoded from its arguments by the tool vocabulary of phase1 §5. The argument
 * names this relies on are listed in policy-rules.md §7.
 */
internal sealed interface Step {
    /** Talks to the user or ends the turn; touches no app (ask_user, hand_to_user, end_not_done, phone_apps). */
    data object Talk : Step

    /** Reads the current screen (`phone_observe`, `phone_wait`). */
    data object Observe : Step

    data class Tap(
        val aim: Aim,
    ) : ScreenStep {
        override val ref: Aim.Ref? get() = aim as? Aim.Ref
    }

    data class Type(
        val aim: Aim.Ref?,
        val text: String,
        val submit: Boolean,
    ) : ScreenStep {
        override val ref: Aim.Ref? get() = aim
    }

    data class Scroll(
        val aim: Aim.Ref?,
    ) : ScreenStep {
        override val ref: Aim.Ref? get() = aim
    }

    data class Swipe(
        val from: Aim.Point,
        val to: Aim.Point,
    ) : ScreenStep {
        override val ref: Aim.Ref? get() = null
    }

    data class Key(
        val key: SystemKey,
    ) : ScreenStep {
        override val ref: Aim.Ref? get() = null
    }

    /** `phone_launch`: [target] is the `link` argument when [link], else the `app` argument (package or name). */
    data class Launch(
        val target: String,
        val link: Boolean,
    ) : Step

    data class Intent(
        val kind: SystemIntentKind,
        val fields: Map<String, String>,
    ) : Step

    /** `request_confirmation`. */
    data class Confirmation(
        val action: String,
        val target: String?,
        val content: String?,
        val amount: String?,
    ) : Step

    /** A tool the vocabulary does not know. */
    data object Unknown : Step

    /** A known tool whose arguments cannot be checked. */
    data object Malformed : Step
}

/** A gesture or input on the current screen. */
internal sealed interface ScreenStep : Step {
    /** The element ref the step names, whose snapshot must be the current one; `null` for points and none. */
    val ref: Aim.Ref?
}

/** Decodes [ToolCall]s into [Step]s. Total: never throws. */
internal object Steps {
    private val PARSERS: Map<String, (JsonObject) -> Step?> =
        mapOf(
            "phone_observe" to { _ -> Step.Observe },
            "phone_wait" to { _ -> Step.Observe },
            "phone_apps" to { _ -> Step.Talk },
            "ask_user" to { _ -> Step.Talk },
            "hand_to_user" to { _ -> Step.Talk },
            "end_not_done" to { _ -> Step.Talk },
            "phone_tap" to ::tap,
            "phone_type" to ::type,
            "phone_scroll" to { args -> Args.optionalRef(args)?.let { Step.Scroll(it.aim) } },
            "phone_swipe" to ::swipe,
            "phone_key" to ::key,
            "phone_launch" to ::launch,
            "phone_intent" to ::intent,
            "request_confirmation" to ::confirmation,
        )

    /** Tool names the vocabulary understands. */
    val KNOWN: Set<String> = PARSERS.keys

    fun parse(call: ToolCall): Step {
        val parser = PARSERS[call.name]
        return if (parser == null) Step.Unknown else parser(call.args) ?: Step.Malformed
    }

    private fun tap(args: JsonObject): Step? =
        if (args.containsKey("ref")) Args.ref(args)?.let(Step::Tap) else Args.point(args, "x", "y")?.let(Step::Tap)

    private fun type(args: JsonObject): Step? {
        val text = Args.string(args, "text")
        val target = Args.optionalRef(args)
        return if (text != null && target != null) Step.Type(target.aim, text, Args.bool(args, "submit")) else null
    }

    private fun swipe(args: JsonObject): Step? {
        val from = Args.point(args, "x1", "y1")
        val to = Args.point(args, "x2", "y2")
        return if (from != null && to != null) Step.Swipe(from, to) else null
    }

    private fun key(args: JsonObject): Step? =
        SystemKey.entries.firstOrNull { it.name == Args.string(args, "key")?.uppercase(Locale.ROOT) }?.let(Step::Key)

    private fun launch(args: JsonObject): Step? {
        val app = Args.string(args, "app")?.takeIf(String::isNotBlank)
        val link = Args.string(args, "link")?.takeIf(String::isNotBlank)
        return when {
            app != null && link == null -> Step.Launch(app, link = false)
            link != null && app == null -> Step.Launch(link, link = true)
            else -> null
        }
    }

    private fun intent(args: JsonObject): Step? {
        val kind = SystemIntentKind.entries.firstOrNull { it.name == Args.string(args, "kind")?.uppercase(Locale.ROOT) }
        val fields =
            args
                .filterKeys { it != "kind" }
                .mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.let {
                        k to
                            it.content
                    }
                }.toMap()
        return kind?.let { Step.Intent(it, fields) }
    }

    private fun confirmation(args: JsonObject): Step? {
        val action = Args.string(args, "action")?.takeIf(String::isNotBlank)
        val amount = (args["amount"] as? JsonPrimitive)?.content
        return action?.let { Step.Confirmation(it, Args.string(args, "target"), Args.string(args, "content"), amount) }
    }
}
