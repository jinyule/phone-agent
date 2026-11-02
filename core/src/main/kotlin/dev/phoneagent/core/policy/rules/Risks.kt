package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * What a touch activates and how risky that is.
 *
 * @property target the element activated: the riskiest one the touch may reach (the smallest
 *   among equals), or `null` when there is no element there.
 * @property risk what activating it does, or `null` when ordinary.
 * @property texts all visible text on what the touch may reach: names, content and OCR.
 * @property candidates every element the touch may reach, smallest first; empty when there is none.
 */
internal data class Assessment(
    val target: UiElement?,
    val risk: ElementRisk?,
    val texts: List<String>,
    val candidates: List<UiElement>,
) {
    /** The kind of what the touch does: [risk]'s, or NAVIGATE when ordinary. */
    val kind: IntentKind
        get() = if (risk == null) IntentKind.NAVIGATE else risk.kind
}

/**
 * Where a touch lands and what it activates (policy-rules.md §2, §3). The screen format's ⚠
 * ([RulesLexicon]), the intent resolver and the hard rules all read it, so the mark and the verdict
 * agree; [Controls] turns the evidence on each control into its risk.
 */
internal class Risks(
    private val controls: Controls,
) {
    private val byRisk: Comparator<Judged> = compareBy { severity(it.risk?.kind) }

    /** A tap aimed at [aim]. */
    fun tap(
        aim: Aim,
        screen: ScreenSnapshot,
    ): Assessment =
        when (aim) {
            is Aim.Ref -> byRef(aim, screen)
            is Aim.Point -> at(aim, screen)
        }

    private fun byRef(
        aim: Aim.Ref,
        screen: ScreenSnapshot,
    ): Assessment {
        val element = Elements.byRef(screen, aim.ref)
        return if (element == null) assess(emptyList(), null, screen) else activation(element, screen)
    }

    /**
     * A tap by ref on [element], as the actuator performs it: the click of the control that takes
     * it ([Tree.clickTarget]), else a touch at [element]'s center.
     */
    fun activation(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Assessment {
        val control = Tree.clickTarget(element, screen)
        val center = Geometry.center(element.bounds)
        return if (control == null) at(center, screen) else assess(listOf(control), center, screen)
    }

    /** A touch at [point]: every control that may take it counts ([Tree.under]), and the riskiest decides. */
    fun at(
        point: Aim.Point,
        screen: ScreenSnapshot,
    ): Assessment = assess(Tree.under(screen, point.x, point.y), point, screen)

    /** The visible text on [element], as a tap by ref at its center sees it. */
    fun texts(
        element: UiElement,
        screen: ScreenSnapshot,
    ): List<String> = Evidence.of(element, Geometry.center(element.bounds), screen)

    private fun assess(
        candidates: List<UiElement>,
        point: Aim.Point?,
        screen: ScreenSnapshot,
    ): Assessment {
        val drawn = Evidence.drawnAt(point, screen)
        val judged =
            candidates.map { element ->
                val texts = Evidence.of(element, point, screen)
                Judged(element, controls.risk(element, texts, screen), texts)
            }
        val riskiest = judged.maxWithOrNull(byRisk)
        val texts = (judged.flatMap(Judged::texts) + drawn).distinct()
        return Assessment(
            riskiest?.element,
            if (riskiest ==
                null
            ) {
                controls.ofText(drawn, screen)
            } else {
                riskiest.risk
            },
            texts,
            candidates,
        )
    }
}

/** A control a touch may reach, with its risk and its text. */
private data class Judged(
    val element: UiElement,
    val risk: ElementRisk?,
    val texts: List<String>,
)

/** The text evidence on a control. */
internal object Evidence {
    /**
     * What [element] shows: its name, the names in its content ([Elements.content]), the OCR
     * lines at [point], and, when it is a control the tree gives no text at all, the OCR lines
     * inside it (a drawn button). Inside a container that takes no click, only the point counts.
     */
    fun of(
        element: UiElement,
        point: Aim.Point?,
        screen: ScreenSnapshot,
    ): List<String> {
        val own = listOfNotNull(element.name?.takeIf(String::isNotBlank)) + Elements.content(element, screen)
        val drawnButton = own.isEmpty() && Elements.clickable(element)
        val inside =
            if (drawnButton) {
                screen.ocr.orEmpty().filter {
                    Geometry.inside(it.bounds, element.bounds)
                }
            } else {
                emptyList()
            }
        return (own + inside.map { it.text } + drawnAt(point, screen)).distinct()
    }

    /** The OCR lines at [point]; none without a point. */
    fun drawnAt(
        point: Aim.Point?,
        screen: ScreenSnapshot,
    ): List<String> =
        if (point == null) {
            emptyList()
        } else {
            screen.ocr
                .orEmpty()
                .filter { Geometry.contains(it.bounds, point.x, point.y) }
                .map { it.text }
        }
}

/**
 * The one place that turns the evidence on a control into a risk (policy-rules.md §3): its name,
 * the text inside it at any depth, and the OCR over it. Spending in particular is decided here; a
 * control without any text on a payment page counts as paying.
 */
internal class Controls(
    private val lexicon: Lexicon,
    private val pages: Pages,
    private val settings: SettingsPages,
    private val fields: Fields,
) {
    private val byHit: Comparator<Hit> = compareBy { severity(it.kind) }

    /** The risk of activating [element], whose visible text is [texts]; inputs and clear buttons only edit. */
    fun risk(
        element: UiElement,
        texts: List<String>,
        screen: ScreenSnapshot,
    ): ElementRisk? =
        if (Elements.editable(element) || fields.editsField(element, texts, screen)) {
            null
        } else {
            settingsEntry(texts, screen) ?: labelled(element, texts, screen) ?: settingsControl(element, screen)
        }

    /** The risk of touching where there is no element and only [texts] (OCR) are drawn. */
    fun ofText(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): ElementRisk? = best(texts.mapNotNull(lexicon::classify)) ?: unlabeled(texts, screen)

    private fun settingsEntry(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): ElementRisk? = settings.sensitiveEntry(texts, screen)?.let { ElementRisk(IntentKind.SETTINGS, "settings:$it") }

    /**
     * The riskiest word among the control's own label, its label as a whole and each text; an
     * icon's resource id when there is no text; a generic button by its dialog. A strong word
     * counts at any length in the control's own label: its name, or the one text it shows, or on a
     * payment page any text on it. A control that shows several texts elsewhere (a list row: a
     * name, a preview, a time) is read text by text, so a long preview is not a button.
     */
    private fun labelled(
        element: UiElement,
        texts: List<String>,
        screen: ScreenSnapshot,
    ): ElementRisk? {
        val label = Elements.label(element, screen)
        val own = listOfNotNull(element.name?.takeIf(String::isNotBlank) ?: texts.singleOrNull())
        val whole = if (pages.isPayment(screen)) texts else own
        val hits =
            whole.mapNotNull(lexicon::ofControl) +
                listOfNotNull(lexicon.classify(label), lexicon.leading(label), icon(element, texts)) +
                texts.mapNotNull(lexicon::classify)
        return best(hits) ?: if (lexicon.isGeneric(label)) generic(element, screen, label) else unlabeled(texts, screen)
    }

    private fun best(hits: List<Hit>): ElementRisk? =
        hits.maxWithOrNull(byHit)?.let { ElementRisk(it.kind, it.matched) }

    /** No text at all on a payment page: it may well be the pay button. */
    private fun unlabeled(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): ElementRisk? =
        if (texts.isEmpty() &&
            pages.isPayment(screen)
        ) {
            ElementRisk(IntentKind.SPEND, Words.UNLABELED_ON_PAYMENT_PAGE)
        } else {
            null
        }

    /** An icon button without any text: its resource id ("btn_send") says what it does. */
    private fun icon(
        element: UiElement,
        texts: List<String>,
    ): Hit? {
        val words = if (texts.isEmpty()) element.resourceId?.let(Text::idWords).orEmpty() else emptyList()
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
        val hits = Elements.context(element, screen).mapNotNull(lexicon::inContext)
        val spend =
            hits.firstOrNull { it.kind == IntentKind.SPEND }
                ?: Hit(IntentKind.SPEND, "payment page").takeIf { pages.isPayment(screen) }
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

/** Least to most severe: which of several risks counts, and how far a declared risk class raises an intent. */
private val SEVERITY: List<IntentKind> =
    listOf(
        IntentKind.READ,
        IntentKind.NAVIGATE,
        IntentKind.INPUT,
        IntentKind.SETTINGS,
        IntentKind.DELETE,
        IntentKind.OUTWARD,
        IntentKind.SPEND,
    )

/** The rank of [kind] in [SEVERITY]; no risk ranks lowest. */
internal fun severity(kind: IntentKind?): Int = SEVERITY.indexOf(kind)

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
