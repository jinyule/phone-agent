package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiElement
import kotlin.math.abs

/** Intents of gestures and input on a known screen (policy-rules.md §2). */
internal class ScreenIntents(
    private val lexicon: RulesLexicon,
) {
    fun resolve(
        step: ScreenStep,
        screen: ScreenSnapshot,
    ): ActionIntent =
        when (step) {
            is Step.Tap -> {
                tap(step.aim, screen)
            }

            is Step.Type -> {
                type(step, screen)
            }

            is Step.Scroll -> {
                ActionIntent(
                    IntentKind.NAVIGATE,
                    screen.pkg,
                    step.aim?.let { Targets.element(it, screen) },
                )
            }

            is Step.Swipe -> {
                swipe(step, screen)
            }

            is Step.Key -> {
                key(step.key, screen)
            }
        }

    private fun tap(
        aim: Aim,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val target = Targets.element(aim, screen)
        val risk = Targets.risk(aim, target, screen, lexicon)
        val kind = if (risk == null) IntentKind.NAVIGATE else risk.kind
        val amount = if (kind == IntentKind.SPEND) Amounts.forSpend(Targets.label(target, screen), screen) else null
        return ActionIntent(kind, screen.pkg, target, amount)
    }

    /**
     * Typing: a password, code or payment-password field is INPUT with that class (handed off);
     * submitting anywhere but a search field reaches out; typing in the settings app changes
     * settings.
     */
    private fun type(
        step: Step.Type,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val field = if (step.aim == null) Fields.typingTarget(screen) else Targets.element(step.aim, screen)
        val fieldClass = if (field == null) FieldClass.PLAIN else Fields.classify(field, screen)
        val searches = field?.let { Fields.isSearch(it, screen) } == true
        val kind =
            when {
                fieldClass in Fields.HAND_OFF -> IntentKind.INPUT
                step.submit && !searches -> IntentKind.OUTWARD
                screen.pkg == Pages.SETTINGS -> IntentKind.SETTINGS
                else -> IntentKind.INPUT
            }
        return ActionIntent(kind, screen.pkg, field, fieldClass = fieldClass.takeIf { kind == IntentKind.INPUT })
    }

    /** A long horizontal swipe from a list row deletes it in many apps; a swipe on a pay slider pays. */
    private fun swipe(
        step: Step.Swipe,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val start = Elements.at(screen, step.from.x, step.from.y)
        val dx = abs(step.to.x - step.from.x)
        val long = dx >= SWIPE_DELETE_MIN && dx > abs(step.to.y - step.from.y)
        val kind =
            when {
                start == null -> IntentKind.NAVIGATE
                lexicon.activationRisk(start, screen)?.kind == IntentKind.SPEND -> IntentKind.SPEND
                long && rowLike(start, screen) -> IntentKind.DELETE
                else -> IntentKind.NAVIGATE
            }
        return ActionIntent(kind, screen.pkg, start)
    }

    private fun rowLike(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        val b = element.bounds
        val inCarousel =
            screen.elements.any {
                ElementState.SCROLLABLE in it.states && Geometry.inside(b, it.bounds) &&
                    it.bounds.bottom - it.bounds.top < CAROUSEL_MAX
            }
        return ElementState.CLICKABLE in element.states && b.right - b.left >= ROW_MIN_WIDTH && !inCarousel
    }

    /** Enter on a non-search field sends; when nothing is focused it sends unless every field searches. */
    private fun key(
        key: SystemKey,
        screen: ScreenSnapshot,
    ): ActionIntent {
        val focused = screen.elements.firstOrNull { ElementState.FOCUSED in it.states }
        val kind =
            when {
                key != SystemKey.ENTER -> IntentKind.NAVIGATE

                focused == null -> enterUnfocused(screen)

                Elements.editable(
                    focused,
                ) -> if (Fields.isSearch(focused, screen)) IntentKind.NAVIGATE else IntentKind.OUTWARD

                else -> activated(focused, screen)
            }
        return ActionIntent(kind, screen.pkg, focused)
    }

    private fun activated(
        element: UiElement,
        screen: ScreenSnapshot,
    ): IntentKind {
        val risk = lexicon.activationRisk(element, screen)
        return if (risk == null) IntentKind.NAVIGATE else risk.kind
    }

    private fun enterUnfocused(screen: ScreenSnapshot): IntentKind {
        val fields = screen.elements.filter(Elements::editable)
        return if (fields.isNotEmpty() &&
            fields.all { Fields.isSearch(it, screen) }
        ) {
            IntentKind.NAVIGATE
        } else {
            IntentKind.OUTWARD
        }
    }

    private companion object {
        /** A horizontal swipe at least this long (0–999) on a row counts as swipe-to-delete. */
        const val SWIPE_DELETE_MIN = 400

        /** Rows are at least this wide (0–999). */
        const val ROW_MIN_WIDTH = 600

        /** Scrollable areas lower than this (0–999) are carousels: swiping them pages. */
        const val CAROUSEL_MAX = 400
    }
}
