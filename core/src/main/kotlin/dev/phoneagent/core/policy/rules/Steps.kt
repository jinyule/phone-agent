package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.tool.ToolCall
import kotlinx.serialization.json.JsonObject
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

    /** `phone_type`; with [append] the field ends up holding its current text followed by [text]. */
    data class Type(
        val aim: Aim.Ref?,
        val text: String,
        val submit: Boolean,
        val append: Boolean,
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

    /** `phone_launch`: [target] is the `link` argument when [link], else the `app` argument, trimmed. */
    data class Launch(
        val target: String,
        val link: Boolean,
    ) : Step

    /** `phone_intent`; [fields] are the other arguments, trimmed. */
    data class Intent(
        val kind: SystemIntentKind,
        val fields: Map<String, String>,
    ) : Step

    /** `request_confirmation`; [amount] is the `amount` argument read as money, `null` when not given. */
    data class Confirmation(
        val action: String,
        val target: String?,
        val content: String?,
        val amount: Money?,
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

/** Decodes [ToolCall]s into [Step]s by the rules the tools read their arguments with ([Args]). Total: never throws. */
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
            "phone_key" to { args -> Args.choice(args, "key", KEYS)?.let(Step::Key) },
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

    /** A ref, or both coordinates; never both. */
    private fun tap(args: JsonObject): Step? =
        when {
            !Args.has(args, "ref") -> Args.point(args, "x", "y")?.let(Step::Tap)
            Args.has(args, "x") || Args.has(args, "y") -> null
            else -> Args.ref(args)?.let(Step::Tap)
        }

    private fun type(args: JsonObject): Step? {
        val text = Args.text(args, "text")
        val target = Args.optionalRef(args)
        val submit = Args.flag(args, "submit")
        val append = Args.flag(args, "append")
        return if (text != null && target != null &&
            submit != null
        ) {
            append?.let { Step.Type(target.aim, text, submit, it) }
        } else {
            null
        }
    }

    private fun swipe(args: JsonObject): Step? {
        val from = Args.point(args, "x1", "y1")
        val to = Args.point(args, "x2", "y2")
        return if (from != null && to != null) Step.Swipe(from, to) else null
    }

    /** Exactly one of `app` and `link`, not blank. */
    private fun launch(args: JsonObject): Step? {
        val app = Args.text(args, "app")?.trim()
        val link = Args.text(args, "link")?.trim()
        return when {
            Args.has(args, "app") == Args.has(args, "link") -> null
            app != null -> app.takeIf(String::isNotEmpty)?.let { Step.Launch(it, link = false) }
            else -> link?.takeIf(String::isNotEmpty)?.let { Step.Launch(it, link = true) }
        }
    }

    /** The kind by its wire name; `page=app_details` needs a package name. */
    private fun intent(args: JsonObject): Step? {
        val kind = Args.choice(args, "kind", KINDS)
        val fields =
            args.keys
                .filter { it != "kind" }
                .mapNotNull { key -> Args.text(args, key)?.let { key to it.trim() } }
                .toMap()
        val details = kind == SystemIntentKind.SETTINGS && fields["page"] == APP_DETAILS
        return if (kind != null &&
            (!details || Text.isPackage(fields["package"].orEmpty()))
        ) {
            Step.Intent(kind, fields)
        } else {
            null
        }
    }

    /** `action` is required; an `amount` that is given must read as one amount that fits. */
    private fun confirmation(args: JsonObject): Step? {
        val action = Args.text(args, "action")?.takeIf(String::isNotBlank)
        val given = Args.has(args, "amount")
        val amount = Args.text(args, "amount")?.let(Prices::stated)?.money
        return if (action == null || (given && amount == null)) {
            null
        } else {
            Step.Confirmation(action, Args.text(args, "target"), Args.text(args, "content"), amount)
        }
    }
}

/** `phone_intent` page that opens one app's settings. */
internal const val APP_DETAILS: String = "app_details"

private val KEYS: Map<String, SystemKey> = SystemKey.entries.associateBy { it.name.lowercase(Locale.ROOT) }

private val KINDS: Map<String, SystemIntentKind> =
    SystemIntentKind.entries.associateBy {
        it.name.lowercase(Locale.ROOT)
    }
