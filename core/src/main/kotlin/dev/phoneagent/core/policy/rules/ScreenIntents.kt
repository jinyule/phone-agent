package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiElement
import kotlin.math.abs

/** Intents of gestures and input on a known screen (policy-rules.md §2). */
internal class ScreenIntents(
    reading: Reading,
) {
    private val tuning = reading.tuning
    private val risks = reading.risks
    private val pages = reading.pages
    private val fields = reading.fields
    private val searches = reading.searches

    fun resolve(
        step: ScreenStep,
        screen: ScreenSnapshot,
    ): ActionIntent =
        when (step) {
            is Step.Tap -> tap(step, screen)
            is Step.Type -> type(step, screen)
            is Step.Scroll -> scroll(step, screen)
            is Step.Swipe -> swipe(step, screen)
            is Step.Key -> key(step.key, screen)
        }

    private fun tap(
        step: Step.Tap,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val touched = risks.tap(step.aim, step.long, screen)
        return ActionIntent(touched.kind, screen.pkg, touched.target)
    }

    /**
     * Typing: a password, code or payment-password field is INPUT with that class (handed off);
     * submitting on a payment page pays; submitting anywhere else but a search field reaches out;
     * typing in the settings app changes settings.
     */
    private fun type(
        step: Step.Type,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val field = if (step.aim == null) fields.typingTarget(screen) else Elements.byRef(screen, step.aim.ref)
        val fieldClass = if (field == null) FieldClass.PLAIN else fields.classify(field, screen)
        val searches = field != null && this.searches.isSearch(field, screen)
        val kind =
            when {
                fieldClass in Fields.HAND_OFF -> IntentKind.INPUT
                step.submit && pages.isPayment(screen) -> IntentKind.SPEND
                step.submit && !searches -> IntentKind.OUTWARD
                screen.pkg == Pages.SETTINGS -> IntentKind.SETTINGS
                else -> IntentKind.INPUT
            }
        return ActionIntent(kind, screen.pkg, field, fieldClass = fieldClass.takeIf { kind == IntentKind.INPUT })
    }

    /** Scrolling a picker or a slider in the settings app sets a value. */
    private fun scroll(
        step: Step.Scroll,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val target = step.aim?.let { Elements.byRef(screen, it.ref) }
        val sets = target != null && screen.pkg == Pages.SETTINGS && target.role in ADJUSTERS
        return ActionIntent(if (sets) IntentKind.SETTINGS else IntentKind.NAVIGATE, screen.pkg, target)
    }

    /**
     * A swipe from a pay slider pays, also one only drawn (OCR, canvas); from a slider, picker or
     * toggle in the settings app it sets a value; sideways along a list row it may delete the row
     * (mail and chat lists swipe to delete).
     */
    private fun swipe(
        step: Step.Swipe,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val start = risks.at(step.from, long = false, screen)
        val element = start.target
        val dx = abs(step.to.x - step.from.x)
        val sideways = dx >= tuning.rowSwipeMin && dx > abs(step.to.y - step.from.y)
        val kind =
            when {
                start.kind == IntentKind.SPEND -> IntentKind.SPEND
                element == null -> IntentKind.NAVIGATE
                screen.pkg == Pages.SETTINGS && element.role in SETTERS -> IntentKind.SETTINGS
                sideways && rowLike(element, screen) -> IntentKind.DELETE
                else -> IntentKind.NAVIGATE
            }
        return ActionIntent(kind, screen.pkg, element)
    }

    private fun rowLike(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        val b = element.bounds
        val inCarousel =
            screen.elements.any {
                ElementState.SCROLLABLE in it.states && Geometry.inside(b, it.bounds) &&
                    it.bounds.bottom - it.bounds.top < tuning.carouselMax
            }
        return Elements.clickable(element) && b.right - b.left >= tuning.rowMinWidth && !inCarousel
    }

    /**
     * Enter: on a payment page it pays; in a field it sends unless the field searches; on a focused
     * control it activates it; with nothing focused it sends unless every field searches.
     */
    private fun key(
        key: SystemKey,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val focused = screen.elements.firstOrNull { ElementState.FOCUSED in it.states }
        val pressed = risks.pressed(screen)
        val kind =
            when {
                key != SystemKey.ENTER -> IntentKind.NAVIGATE
                pressed != null -> pressed.kind
                pages.isPayment(screen) -> IntentKind.SPEND
                focused != null -> if (searches.isSearch(focused, screen)) IntentKind.NAVIGATE else IntentKind.OUTWARD
                else -> enterUnfocused(screen)
            }
        return ActionIntent(kind, screen.pkg, if (pressed == null) focused else pressed.target)
    }

    private fun enterUnfocused(screen: ScreenSnapshot): IntentKind {
        val fields = screen.elements.filter(Elements::editable)
        val searches = fields.isNotEmpty() && fields.all { this.searches.isSearch(it, screen) }
        return if (searches) IntentKind.NAVIGATE else IntentKind.OUTWARD
    }
}

/** Roles that set a value when scrolled. */
private val ADJUSTERS = setOf(ElementRole.PICKER, ElementRole.PROGRESS)

/** Roles that set a value when swiped from. */
private val SETTERS =
    setOf(ElementRole.PROGRESS, ElementRole.PICKER, ElementRole.SWITCH, ElementRole.CHECKBOX, ElementRole.RADIO)
