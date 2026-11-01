package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/** What an [Aim] hits on a screen and how risky hitting it is. */
internal object Targets {
    fun element(
        aim: Aim,
        screen: ScreenSnapshot,
    ): UiElement? =
        when (aim) {
            is Aim.Ref -> Elements.byRef(screen, aim.ref)
            is Aim.Point -> Elements.at(screen, aim.x, aim.y)
        }

    fun label(
        target: UiElement?,
        screen: ScreenSnapshot,
    ): String = if (target == null) "" else Elements.label(target, screen)

    /**
     * The risk of activating [target] (what [aim] hits): the lexicon's answer. Where there is no
     * element (canvas, a ref that is not there) or only an unlabeled control (WebView without
     * accessibility), the OCR text at the point decides, and on a payment page it counts as paying.
     */
    fun risk(
        aim: Aim,
        target: UiElement?,
        screen: ScreenSnapshot,
        lexicon: RulesLexicon,
    ): ElementRisk? =
        if (target ==
            null
        ) {
            unlabeled(aim, screen)
        } else {
            lexicon.activationRisk(target, screen) ?: drawn(aim, target, screen)
        }

    private fun drawn(
        aim: Aim,
        target: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk? {
        val plain = label(target, screen).isBlank() && !Elements.editable(target) && !Fields.editsField(target, screen)
        return if (plain) unlabeled(aim, screen) else null
    }

    private fun unlabeled(
        aim: Aim,
        screen: ScreenSnapshot,
    ): ElementRisk? {
        val point = aim as? Aim.Point
        val line =
            if (point ==
                null
            ) {
                null
            } else {
                screen.ocr.orEmpty().firstOrNull { Geometry.contains(it.bounds, point.x, point.y) }
            }
        val hit = if (line == null) null else Lexicon.classify(line.text)
        return when {
            hit != null -> ElementRisk(hit.kind, hit.matched)
            Pages.isPayment(screen) -> ElementRisk(IntentKind.SPEND, Words.UNLABELED_ON_PAYMENT_PAGE)
            else -> null
        }
    }
}
