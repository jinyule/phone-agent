package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * The shared risk vocabulary (phase1 §4, policy-rules.md §3). The only place that decides what
 * activating an element does: the screen format marks its answers ⚠ and the intent resolver uses
 * it for every tap on an element, so the mark and the verdict agree. Spending in particular is
 * decided here and nowhere else; an element without a label on a payment page counts as paying.
 */
internal class RulesLexicon : RiskLexicon {
    override fun activationRisk(
        element: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk? =
        if (Elements.editable(element) || Fields.editsField(element, screen)) {
            null
        } else {
            settingsEntry(element, screen) ?: labelled(element, screen) ?: settingsControl(element, screen)
        }

    private fun settingsEntry(
        element: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk? = Pages.sensitiveEntry(element, screen)?.let { ElementRisk(IntentKind.SETTINGS, "settings:$it") }

    private fun labelled(
        element: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk? {
        val label = Elements.label(element, screen)
        val hit = Lexicon.classify(label) ?: icon(element, label)
        return when {
            hit != null -> ElementRisk(hit.kind, hit.matched)
            Lexicon.isGeneric(label) -> generic(element, screen, label)
            label.isBlank() && Pages.isPayment(screen) -> ElementRisk(IntentKind.SPEND, Words.UNLABELED_ON_PAYMENT_PAGE)
            else -> null
        }
    }

    /** An icon button without a label: its resource id ("btn_send") says what it does. */
    private fun icon(
        element: UiElement,
        label: String,
    ): Hit? {
        val words = if (label.isBlank()) element.resourceId?.let(Text::idWords).orEmpty() else emptyList()
        return words.firstNotNullOfOrNull { word -> Words.ID_WORDS[word]?.let { Hit(it, "id:$word") } }
    }

    /**
     * "确定" and the like do what the dialog around them says: pay on a payment page or when the
     * dialog mentions paying, else delete or reach out when it mentions that, else nothing.
     */
    private fun generic(
        element: UiElement,
        screen: ScreenSnapshot,
        label: String,
    ): ElementRisk? {
        val hits = Elements.context(element, screen).mapNotNull { Lexicon.classify(it, Words.CONTEXT_MAX) }
        val spend =
            hits.firstOrNull { it.kind == IntentKind.SPEND }
                ?: Hit(IntentKind.SPEND, "payment page").takeIf { Pages.isPayment(screen) }
        val found = spend ?: hits.firstOrNull { it.kind == IntentKind.DELETE } ?: hits.firstOrNull()
        return found?.let { ElementRisk(it.kind, "${Text.bare(label)}:${it.matched}") }
    }

    /** In the settings app, toggles, pickers and enable / force-stop buttons change the system. */
    private fun settingsControl(
        element: UiElement,
        screen: ScreenSnapshot,
    ): ElementRisk? {
        val toggles = element.role in TOGGLES || screen.elements.any { it.parent == element.ref && it.role in TOGGLES }
        val acts = Text.bare(Elements.label(element, screen)) in SETTINGS_ACTIONS
        return ElementRisk(IntentKind.SETTINGS, "settings:control").takeIf {
            screen.pkg == Pages.SETTINGS &&
                (toggles || acts)
        }
    }
}

private val TOGGLES = setOf(ElementRole.SWITCH, ElementRole.CHECKBOX, ElementRole.RADIO, ElementRole.PICKER)

private val SETTINGS_ACTIONS =
    setOf(
        "强行停止",
        "停用",
        "启用",
        "开启",
        "关闭",
        "打开",
        "允许",
        "拒绝",
        "授权",
        "卸载更新",
        "forcestop",
        "disable",
        "enable",
        "turnon",
        "turnoff",
        "allow",
        "deny",
    )
