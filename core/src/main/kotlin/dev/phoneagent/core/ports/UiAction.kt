package dev.phoneagent.core.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What an action is aimed at: an element of the snapshot it was planned on, or a point. */
@Serializable
public sealed interface Target {
    /** An element by its ref in the snapshot named by `ScreenPort.act(on = …)`. */
    @Serializable
    @SerialName("ref")
    public data class Ref(
        /** The element handle. */
        val ref: ElementRef,
    ) : Target

    /** A point in normalized coordinates (0–999 on both axes). */
    @Serializable
    @SerialName("point")
    public data class Point(
        /** Horizontal coordinate, 0–999. */
        val x: Int,
        /** Vertical coordinate, 0–999. */
        val y: Int,
    ) : Target {
        init {
            require(ScreenCoordinates.isValid(x) && ScreenCoordinates.isValid(y)) {
                "Point ($x,$y) outside 0..${ScreenCoordinates.MAX}"
            }
        }
    }
}

/** A UI gesture or input on one surface (phase1 §3.3). Validated tool arguments become one of these. */
@Serializable
public sealed interface UiAction {
    /** Tap, or long-press when [long]. */
    @Serializable
    @SerialName("tap")
    public data class Tap(
        /** What to tap. */
        val target: Target,
        /** Long-press instead of tap. */
        val long: Boolean = false,
    ) : UiAction

    /** Enter [text]; replaces the field's content unless [append]; presses IME action when [submit]. */
    @Serializable
    @SerialName("type")
    public data class Type(
        /** Field to type into, or `null` for the focused field. */
        val target: Target?,
        /** Text to enter. */
        val text: String,
        /** Keep existing content and add [text] at the end. */
        val append: Boolean = false,
        /** Submit after typing (IME action / enter). */
        val submit: Boolean = false,
    ) : UiAction

    /** Scroll one page in [direction] (content moves so that what lies in [direction] comes into view). */
    @Serializable
    @SerialName("scroll")
    public data class Scroll(
        /** Scrollable container, or `null` for the main one. */
        val target: Target?,
        /** Where to reveal content. */
        val direction: Direction,
    ) : UiAction

    /** Raw swipe between two points. */
    @Serializable
    @SerialName("swipe")
    public data class Swipe(
        /** Start point. */
        val from: Target.Point,
        /** End point. */
        val to: Target.Point,
        /** Gesture duration, > 0. */
        val durationMs: Long,
    ) : UiAction {
        init {
            require(durationMs > 0) { "Swipe duration must be > 0, got $durationMs" }
        }
    }

    /** Press a whitelisted system key. */
    @Serializable
    @SerialName("key")
    public data class Key(
        /** The key. */
        val key: SystemKey,
    ) : UiAction

    /** Turn a wheel picker until it shows [value]. */
    @Serializable
    @SerialName("set_wheel")
    public data class SetWheel(
        /** The picker. */
        val target: Target,
        /** Desired visible value. */
        val value: String,
    ) : UiAction
}

/** Scroll direction. */
@Serializable
public enum class Direction {
    /** Reveal content above. */
    UP,

    /** Reveal content below. */
    DOWN,

    /** Reveal content to the left. */
    LEFT,

    /** Reveal content to the right. */
    RIGHT,
}

/** The only system keys the agent may press (phase1 §5 `phone_key`). */
@Serializable
public enum class SystemKey {
    /** Back. */
    BACK,

    /** Home of the surface. */
    HOME,

    /** Enter / IME action. */
    ENTER,
}

/** What `ScreenPort.launch` opens. No variant accepts an arbitrary Intent (phase1 §5 `phone_intent`). */
@Serializable
public sealed interface LaunchTarget {
    /** An installed app by package name; [cold] force-stops it first. */
    @Serializable
    @SerialName("app")
    public data class App(
        /** Android package name. */
        val packageName: String,
        /** Restart from a clean state. */
        val cold: Boolean = false,
    ) : LaunchTarget {
        init {
            require(PACKAGE_NAME.matches(packageName)) { "Invalid package name '$packageName'" }
        }
    }

    /** A deep link or web URL. The adapter decides which schemes it opens. */
    @Serializable
    @SerialName("link")
    public data class Link(
        /** Absolute URI. */
        val uri: String,
    ) : LaunchTarget {
        init {
            require(uri.contains(':') && uri.isNotBlank()) { "Link must be an absolute URI, got '$uri'" }
        }
    }

    /** A whitelisted system intent; [fields] are the kind-specific arguments from the tool schema. */
    @Serializable
    @SerialName("system")
    public data class SystemIntent(
        /** Which intent. */
        val kind: SystemIntentKind,
        /** Kind-specific arguments (e.g. `time`, `text`, `number`). */
        val fields: Map<String, String> = emptyMap(),
    ) : LaunchTarget
}

/** Intents `phone_intent` may fire. */
@Serializable
public enum class SystemIntentKind {
    /** Set an alarm. */
    ALARM,

    /** Start a timer. */
    TIMER,

    /** Insert a calendar event. */
    CALENDAR,

    /** Share text through the system share sheet. */
    SHARE,

    /** Open the dialer with a number (never places the call). */
    DIAL,

    /** Start navigation to a place. */
    NAVIGATE,

    /** Open a whitelisted settings page. */
    SETTINGS,
}

private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
