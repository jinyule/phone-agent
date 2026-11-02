package dev.phoneagent.app.adapters.screen

import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction

/** What every run of one [AndroidScreen] shares. */
internal class PortParts(
    val surface: SurfaceRef,
    val system: ScreenSystem,
    val analyzer: SnapshotAnalyzer,
    val observer: ScreenObserver,
    val timings: ScreenTimings,
    val injector: InputInjector?,
)

/**
 * One action against one fresh read ([baseline]) of the display: perform it through the fallback
 * chains, let the display settle, read it back at the baseline's detail, and judge the effect.
 * The outcome's diff compares [compareWith] (the screen the action was planned on, the latest one
 * when none was named, `null` when the named one was forgotten or there is none) with the screen
 * after.
 */
internal class ActionRun(
    private val parts: PortParts,
    private val host: ScreenHost,
    private val geometry: DisplayGeometry,
    private val baseline: Baseline,
    private val compareWith: ScreenSnapshot?,
) {
    private val displayId = parts.surface.displayId
    private val timings = parts.timings
    private val before = baseline.read
    private val ownPackage = parts.system.ownPackage
    private val actuator =
        Actuator(host, displayId, geometry, timings, parts.injector) { Aims.windowPackageAt(before, it) == ownPackage }

    /** Performs [action] aimed at [aim]. */
    suspend fun perform(
        action: UiAction,
        aim: Aim,
    ): ActionOutcome =
        when {
            Aims.onOwnUi(aim, ownPackage) -> {
                Outcomes.OWN_UI
            }

            action is UiAction.Type -> {
                type(action, aim)
            }

            else -> {
                ChangeWatch.watching(host.events, displayId, timings) { watch ->
                    finish(gesture(action, aim, watch), watch, timings.settleCapMs)
                }
            }
        }

    private suspend fun gesture(
        action: UiAction,
        aim: Aim,
        watch: ChangeWatch,
    ): Actuator.Result =
        when (action) {
            is UiAction.Tap -> {
                tap(action, aim, watch)
            }

            is UiAction.Scroll -> {
                actuator.scroll(container(aim), action.direction)
            }

            is UiAction.Swipe -> {
                actuator.swipe(
                    geometry.toPixel(action.from),
                    geometry.toPixel(action.to),
                    action.durationMs,
                )
            }

            is UiAction.Key -> {
                key(action.key)
            }

            is UiAction.Type, is UiAction.SetWheel -> {
                error("${action::class.simpleName} is not a gesture")
            }
        }

    /**
     * Presses [key] unless it would act on this app: ENTER on its focused field, BACK and HOME on
     * the window in front (the focused one, else the top-most).
     */
    private suspend fun key(key: SystemKey): Actuator.Result {
        // Only ENTER acts on a field; BACK and HOME skip the focus lookup.
        val field = if (key == SystemKey.ENTER) Aims.focusedEditable(host.windowsOn(displayId)) else null
        val own =
            when (key) {
                SystemKey.ENTER -> field?.packageName?.toString() == ownPackage
                SystemKey.BACK, SystemKey.HOME -> before.frontPackage == ownPackage
            }
        return if (own) Actuator.Result.Refused else actuator.key(key, field)
    }

    private suspend fun tap(
        action: UiAction.Tap,
        aim: Aim,
        watch: ChangeWatch,
    ): Actuator.Result =
        when (aim) {
            is Aim.AtElement -> actuator.tap(aim.element.target, action.long, probe(watch))
            is Aim.AtPixel -> actuator.tapAt(aim.pixel, action.long)
            Aim.Anywhere -> error("a tap always has a target")
        }

    private fun container(aim: Aim): NodeTarget? =
        when (aim) {
            is Aim.AtElement -> Aims.scrollableAround(aim.element, geometry) ?: aim.element.target
            is Aim.AtPixel -> Aims.scrollableAt(before, aim.pixel)?.target
            Aim.Anywhere -> Aims.mainScrollable(before, parts.system.ownPackage)?.target
        }

    private fun fieldFor(aim: Aim): Pair<AccessibilityNodeInfo?, String> =
        when (aim) {
            is Aim.AtElement -> {
                Aims.editableIn(aim.element.node) to "that element is not a text field; tap it first"
            }

            is Aim.AtPixel -> {
                Aims.editableAt(before, aim.pixel)?.node to "there is no text field at that point"
            }

            Aim.Anywhere -> {
                Aims.focusedEditable(host.windowsOn(displayId)) to
                    "no text field has focus; name the field to type into"
            }
        }

    private suspend fun type(
        action: UiAction.Type,
        aim: Aim,
    ): ActionOutcome {
        val (field, missing) = fieldFor(aim)
        val shown = field?.let(TextEntry::read) as? TextEntry.Shown.Text
        return when {
            field == null -> {
                Outcomes.failed(missing)
            }

            field.packageName?.toString() == ownPackage -> {
                Outcomes.OWN_UI
            }

            shown == null -> {
                Outcomes.failed("the text field disappeared")
            }

            else -> {
                ChangeWatch.watching(host.events, displayId, timings) { watch ->
                    typeInto(field, shown, action, watch)
                }
            }
        }
    }

    private suspend fun typeInto(
        field: AccessibilityNodeInfo,
        shown: TextEntry.Shown.Text,
        action: UiAction.Type,
        watch: ChangeWatch,
    ): ActionOutcome {
        val request = TextEntry.Request(action.text, action.append, shown.text)
        val entered =
            TextEntry.enter(request, field.isPassword, levels(field, watch, shown)) { settle ->
                if (settle) watch.awaitSettled(timings.readbackCapMs)
                TextEntry.read(field)
            }
        val result =
            when (entered) {
                is TextEntry.Result.NotEntered -> {
                    Actuator.Result.NotDone(entered.reason)
                }

                is TextEntry.Result.Entered -> {
                    if (action.submit) submit(field) else Actuator.Result.Done(Actuator.Via.NODE)
                }
            }
        return finish(result, watch, if (action.submit) timings.settleCapMs else timings.quietMs)
    }

    private fun submit(field: AccessibilityNodeInfo): Actuator.Result {
        field.refresh()
        val pressed = actuator.enter(field)
        return if (pressed is Actuator.Result.NotDone) {
            Actuator.Result.NotDone("the text was entered but not submitted: ${pressed.reason}")
        } else {
            pressed
        }
    }

    private fun levels(
        field: AccessibilityNodeInfo,
        watch: ChangeWatch,
        shownBefore: TextEntry.Shown,
    ): List<Pair<TextEntry.Level, TextEntry.Attempt>> =
        listOf(
            TextEntry.Level.SET_TEXT to EntryLevels.setText(field),
            TextEntry.Level.IME_CONNECTION to
                EntryLevels.imeConnection(field, host) {
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    watch.awaitSettled(timings.readbackCapMs)
                    field.refresh()
                },
            TextEntry.Level.PASTE to
                EntryLevels.paste(field, parts.system.clipboard) {
                    watch.awaitUntil(timings.pasteWaitMs) { TextEntry.read(field) != shownBefore }
                },
        )

    private fun probe(watch: ChangeWatch): ClickProbe =
        object : ClickProbe {
            override fun nowMs(): Long = parts.system.uptimeMs()

            override suspend fun changed(): Boolean = watch.awaitSettled(timings.quietMs)
        }

    /** Settles, reads back and builds the outcome of [result]. */
    private suspend fun finish(
        result: Actuator.Result,
        watch: ChangeWatch,
        settleMs: Long,
    ): ActionOutcome {
        if (result == Actuator.Result.Refused) return Outcomes.OWN_UI
        if (!(result is Actuator.Result.Done && result.settled)) {
            watch.awaitSettled(if (result is Actuator.Result.Done) settleMs else timings.quietMs)
        }
        val after = parts.observer.observe(host, geometry, baseline.detail)
        val diff = compareWith?.let { parts.analyzer.diff(it, after) }
        return when (result) {
            is Actuator.Result.Done -> {
                ActionOutcome(ActionOutcome.Status.Ok, baseline.effectOf(after), after, diff)
            }

            is Actuator.Result.NotDone -> {
                ActionOutcome(
                    ActionOutcome.Status.Failed(result.reason),
                    ActionOutcome.Effect.UNKNOWN,
                    after,
                    diff,
                )
            }

            Actuator.Result.Refused -> {
                error("refusals return before the display is read")
            }
        }
    }
}
