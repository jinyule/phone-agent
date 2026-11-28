package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place that turns the evidence on a control into a risk (policy-rules.md §3): its name,
 * the text inside it at any depth, and the OCR over it. Spending in particular is decided here; a
 * control without any text on a payment page counts as paying.
 *
 * What a control shows itself is read once per snapshot, and what is drawn at a point once per
 * point ([Said]); a control at a point is judged by joining the two, without reading either
 * again, so judging every control under every point costs time in proportion to their number
 * (policy-rules.md §8).
 */
internal class Controls(
    private val lexicon: Lexicon,
    private val pages: Pages,
    settings: SettingsPages,
    private val fields: Fields,
) {
    private val says = Says(lexicon, fields, settings)
    private val dialogs = Dialogs(lexicon, pages)

    /** What each element shows itself, read once per snapshot. */
    private val owns = PerScreen { ConcurrentHashMap<UiElement, Own>() }

    /** What [texts] say, each distinct text read once per snapshot. */
    fun said(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): Said = says.of(texts, screen)

    /** What [element] shows itself ([Evidence.shown]), read once per snapshot. */
    fun shown(
        element: UiElement,
        screen: ScreenSnapshot,
    ): List<String> = own(element, screen).texts

    /**
     * What activating [element] where [drawn] is drawn over it does, or `null` when ordinary;
     * inputs and clear buttons only edit. Every risk a reading finds counts, the most severe
     * deciding, and it [Hit.sends] when any reading sends (policy-rules.md §0): a sensitive
     * settings entry, the words on the control ([labelled]), a settings control, and a risk that
     * keeps a would-be clear button from being one ([Fields.clearing]).
     */
    fun risk(
        element: UiElement,
        drawn: Said,
        screen: ScreenSnapshot,
    ): Hit? {
        if (Elements.editable(element)) return null
        val own = own(element, screen)
        val said = own.said.then(drawn)
        val clearing = fields.clearing(element, said, screen)
        val veto = clearing?.veto
        return if (clearing != null && veto == null) {
            null
        } else {
            listOf(
                said.sensitive?.let { Hit(IntentKind.SETTINGS, "settings:$it") },
                labelled(own, said, screen),
                own.settings,
                veto,
            ).fold(null, ::firstMax)
        }
    }

    /** What touching where there is no element and only [drawn] (OCR) is does. */
    fun ofText(
        drawn: Said,
        screen: ScreenSnapshot,
    ): Hit? = drawn.classified ?: unlabeled(drawn, screen)

    private fun own(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Own = owns.of(screen).computeIfAbsent(element) { Own(it, screen) }

    /**
     * The riskiest word among the control's own label, its label as a whole ([Lexicon.ofLabel]) and
     * each text; an icon's resource id when there is no text; and with these, what a generic
     * button's dialog makes it do and a control without text on a payment page, the most severe
     * deciding. A strong word counts at any length in the control's own label: its name, or the
     * one text it shows, or on a payment page any text on it. A control that shows several texts
     * elsewhere (a list row: a name, a preview, a time) is read text by text, so a long preview is
     * not a button.
     */
    private fun labelled(
        own: Own,
        said: Said,
        screen: ScreenSnapshot,
    ): Hit? {
        val whole =
            if (pages.isPayment(
                    screen,
                )
            ) {
                said.controlled
            } else {
                (own.name ?: said.single)?.let { lexicon.ofControl(it, screen) }
            }
        val icon = if (said.isEmpty) own.icon else null
        return listOf(own.ofLabel, icon, said.classified, own.dialog, unlabeled(said, screen)).fold(whole, ::firstMax)
    }

    /** No text at all on a payment page: it may well be the pay button. */
    private fun unlabeled(
        said: Said,
        screen: ScreenSnapshot,
    ): Hit? =
        if (said.isEmpty &&
            pages.isPayment(screen)
        ) {
            Hit(IntentKind.SPEND, Words.UNLABELED_ON_PAYMENT_PAGE)
        } else {
            null
        }

    /** In the settings app, toggles, pickers and enable / force-stop buttons change the system. */
    private fun settingsControl(
        element: UiElement,
        label: String,
        screen: ScreenSnapshot,
    ): Hit? {
        if (screen.pkg != Pages.SETTINGS) return null
        val toggles = element.role in TOGGLES || ScreenIndex.of(screen).children(element).any { it.role in TOGGLES }
        val acts = toggles || Text.bare(lexicon.read(label)) in SETTINGS_ACTIONS
        return Hit(IntentKind.SETTINGS, "settings:control").takeIf { acts }
    }

    /** What [element] shows itself, and what that says about activating it ([risk]); read when built. */
    private inner class Own(
        element: UiElement,
        screen: ScreenSnapshot,
    ) {
        init {
            Work.owns.increment()
        }

        private val label = Elements.label(element, screen)

        /** Its name, its content and the OCR inside a drawn button ([Evidence.shown]). */
        val texts: List<String> = Evidence.shown(element, screen)
        val said: Said = said(texts, screen)

        /** Its name, when not blank. */
        val name: String? = element.name?.takeIf(String::isNotBlank)

        /** What its label names ([Lexicon.ofLabel]). */
        val ofLabel: Hit? = lexicon.ofLabel(label, screen)

        /** What its resource id names, counted when it shows no text at all. */
        val icon: Hit? = icon(element)

        /** What its dialog makes it do when it is a generic button ("确定"), else `null`. */
        val dialog: Hit? =
            if (lexicon.isGeneric(label)) dialogs.generic(element, screen, lexicon.read(label)) else null

        /** It changes the system in the settings app. */
        val settings: Hit? = settingsControl(element, label, screen)
    }
}

/**
 * An icon button without any text: its resource id ("btn_send") says what it does, the most
 * severe of its words deciding ("btn_send_delete" deletes, and sends).
 */
private fun icon(element: UiElement): Hit? =
    element.resourceId
        ?.let(Text::idWords)
        .orEmpty()
        .mapNotNull { word -> Words.ID_WORDS[word]?.let { Hit(it, "id:$word") } }
        .fold(null, ::firstMax)

/**
 * What a list of texts on a control says, as far as [Controls.risk] reads it: how many distinct
 * texts there are (none, one, more) and the one, whether all are clearing words, and by each
 * reading the most severe risk among them, the first among equals. [then] joins two lists, this
 * one first, as if they were read together, without reading either again.
 *
 * @property single the text, when there is exactly one.
 * @property allClear every text is a clearing word ([Fields.clearWord]); so when there is none.
 * @property sensitive the first sensitive settings entry a text names ([SettingsPages.sensitiveEntry]).
 * @property named the risks the texts name.
 */
internal class Said(
    private val count: Int,
    val single: String?,
    val allClear: Boolean,
    val sensitive: String?,
    private val named: Named,
) {
    /** There is no text. */
    val isEmpty: Boolean
        get() = count == 0

    /** The [Lexicon.ofControl] risk of the texts that are not clearing words. */
    val veto: Hit?
        get() = named.veto

    /** The [Lexicon.classify] risk of the texts. */
    val classified: Hit?
        get() = named.classified

    /** The [Lexicon.ofControl] risk of the texts. */
    val controlled: Hit?
        get() = named.controlled

    /** This list followed by [next]. */
    fun then(next: Said): Said {
        val joined =
            when {
                count == 0 -> next.count
                next.count == 0 -> count
                count == 1 && next.count == 1 && single == next.single -> 1
                else -> MANY
            }
        val one = if (joined == 1) single ?: next.single else null
        return Said(joined, one, allClear && next.allClear, sensitive ?: next.sensitive, named.then(next.named))
    }
}

/** The most severe risk texts name by each reading of [Said], the first among equals. */
internal class Named(
    val veto: Hit?,
    val classified: Hit?,
    val controlled: Hit?,
) {
    /** These texts followed by those of [next]. */
    fun then(next: Named): Named =
        Named(firstMax(veto, next.veto), firstMax(classified, next.classified), firstMax(controlled, next.controlled))
}

/** More than one distinct text ([Said]). */
private const val MANY = 2

/** No text at all. */
internal val NOTHING_SAID: Said = Said(0, null, allClear = true, null, Named(null, null, null))

/** Reads texts into [Said]s, each distinct text once per snapshot. */
private class Says(
    private val lexicon: Lexicon,
    private val fields: Fields,
    private val settings: SettingsPages,
) {
    private val ones = PerScreen { ConcurrentHashMap<String, Said>() }

    fun of(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): Said {
        Work.texts.add(texts.size.toLong())
        return texts.fold(NOTHING_SAID) { said, text -> said.then(one(text, screen)) }
    }

    private fun one(
        text: String,
        screen: ScreenSnapshot,
    ): Said =
        ones.of(screen).computeIfAbsent(text) {
            val clear = fields.clearWord(it)
            val control = lexicon.ofControl(it, screen)
            val named = Named(if (clear) null else control, lexicon.classify(it, screen), control)
            Said(1, it, clear, settings.sensitiveEntry(listOf(it), screen), named)
        }
}

/**
 * "确定" and the like do what the dialog around them says ([Elements.context]): pay on a payment
 * page or when the dialog mentions paying, else delete or reach out when it mentions that, else
 * nothing. The dialog risk of every named element and OCR line is read once per snapshot and kept
 * by window ([DialogTable]), so a generic button costs the same however large its dialog.
 */
private class Dialogs(
    lexicon: Lexicon,
    private val pages: Pages,
) {
    private val tables = PerScreen { DialogTable(it, lexicon) }

    /**
     * What generic [element] (labelled [label]) does in its dialog, or `null`; it [Hit.sends]
     * when some text of the dialog sends, whatever it does.
     */
    fun generic(
        element: UiElement,
        screen: ScreenSnapshot,
        label: String,
    ): Hit? {
        val table = tables.of(screen)
        val spend =
            table.first(element, Wanted.SPEND)
                ?: Hit(IntentKind.SPEND, "payment page").takeIf { pages.isPayment(screen) }
        val found = spend ?: table.first(element, Wanted.DELETE) ?: table.first(element, Wanted.ANY)
        val sends = table.first(element, Wanted.SENDING) != null
        return found?.let { Hit(it.kind, "${Text.bare(label)}:${it.matched}", sends) }
    }
}

/**
 * The dialog risk ([Lexicon.inContext]) of every named element and OCR line of one screen, in
 * reading order, OCR last; kept for the whole screen and for each window as the first hit of each
 * [Wanted] sort, and the first one on another element than that.
 */
private class DialogTable(
    screen: ScreenSnapshot,
    lexicon: Lexicon,
) {
    private val named: List<Pair<UiElement, Hit>> =
        screen.elements.mapNotNull { e ->
            e.name
                ?.takeIf(String::isNotBlank)
                ?.let(lexicon::inContext)
                ?.let { e to it }
        }
    private val drawn: List<Hit> = screen.ocr.orEmpty().mapNotNull { lexicon.inContext(it.text) }
    private val all: Map<Wanted, First> = firsts(named)
    private val windows: Map<Int?, Map<Wanted, First>> =
        named
            .groupBy {
                it.first.window
            }.mapValues { firsts(it.value) }
    private val none: Map<Wanted, First> = firsts(emptyList())
    private val ocr: Map<Wanted, Hit?> = Wanted.entries.associateWith { wanted -> drawn.firstOrNull(wanted.takes) }

    /**
     * The first [wanted] hit in [element]'s dialog ([Elements.around]): on another element in its
     * window (any window when its own is unknown), else drawn.
     */
    fun first(
        element: UiElement,
        wanted: Wanted,
    ): Hit? {
        val window = if (element.window == null) all else windows[element.window] ?: none
        return window.getValue(wanted).except(element) ?: ocr.getValue(wanted)
    }

    private fun firsts(hits: List<Pair<UiElement, Hit>>): Map<Wanted, First> =
        Wanted.entries.associateWith { wanted -> First(hits.filter { wanted.takes(it.second) }) }
}

/** The sorts of dialog hit a generic button reads ([Dialogs.generic]). */
private enum class Wanted(
    val takes: (Hit) -> Boolean,
) {
    SPEND({ it.kind == IntentKind.SPEND }),
    DELETE({ it.kind == IntentKind.DELETE }),
    ANY({ true }),
    SENDING(Hit::sends),
}

/** The first of [hits], and the first one on another element than that one. */
private class First(
    hits: List<Pair<UiElement, Hit>>,
) {
    private val head: Pair<UiElement, Hit>? = hits.firstOrNull()
    private val other: Hit? = head?.let { (on, _) -> hits.firstOrNull { it.first.ref != on.ref }?.second }

    /** The first hit not on [element]. */
    fun except(element: UiElement): Hit? =
        when {
            head == null -> null
            head.first.ref == element.ref -> other
            else -> head.second
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
