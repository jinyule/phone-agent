package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import java.util.concurrent.ConcurrentHashMap

/**
 * What a touch activates and how risky that is.
 *
 * @property target the element activated: the riskiest one the touch may reach (the smallest
 *   among equals), or `null` when there is no element there.
 * @property risk what activating it does, or `null` when ordinary.
 * @property reached every element the touch may reach with its own risk, smallest first; empty
 *   when there is none.
 * @property sends some reading of some element the touch may reach (of the text drawn there
 *   when there is none) sends, whatever [risk] is ([Hit.sends]): its card names the recipient and
 *   the draft (policy-rules.md §6.3).
 * @param texts computes [texts] when first asked for.
 */
internal class Assessment(
    val target: UiElement?,
    val risk: ElementRisk?,
    val reached: List<Reached>,
    val sends: Boolean,
    texts: () -> List<String>,
) {
    /** All visible text on what the touch may reach: names, content and OCR. */
    val texts: List<String> by lazy(texts)

    /** The kind of what the touch does: [risk]'s, or NAVIGATE when ordinary. */
    val kind: IntentKind
        get() = if (risk == null) IntentKind.NAVIGATE else risk.kind

    /** The elements of [reached]. */
    val candidates: List<UiElement>
        get() = reached.map(Reached::element)
}

/**
 * Where a touch lands and what it activates (policy-rules.md §2, §3). The screen format's ⚠
 * ([RulesLexicon]), the intent resolver and the hard rules all read it, so the mark and the verdict
 * agree; [Controls] turns the evidence on each control into its risk. What is drawn at a point is
 * collected once per point and snapshot, and each control there is judged from it and from what
 * the control shows itself, read once per snapshot (policy-rules.md §8).
 */
internal class Risks(
    private val controls: Controls,
) {
    private val byRisk: Comparator<Reached> = compareBy { severity(it.risk?.kind) }

    /** Clicks of a control, by the control and the OCR lines at the point; read once per snapshot. */
    private val clicks = PerScreen { ConcurrentHashMap<Pair<UiElement, List<String>>, Assessment>() }

    /** Touches at a point, short and long; read once per snapshot. */
    private val touches = PerScreen { ConcurrentHashMap<Pair<Aim.Point, Boolean>, Assessment>() }

    /** What is drawn at a point; collected once per snapshot. */
    private val drawn = PerScreen { ConcurrentHashMap<Aim.Point, Drawn>() }

    /** A tap aimed at [aim]; a long press when [long]. */
    fun tap(
        aim: Aim,
        long: Boolean,
        screen: ScreenSnapshot,
    ): Assessment =
        when (aim) {
            is Aim.Ref -> byRef(aim, long, screen)
            is Aim.Point -> at(aim, long, screen)
        }

    private fun byRef(
        aim: Aim.Ref,
        long: Boolean,
        screen: ScreenSnapshot,
    ): Assessment {
        val element = Elements.byRef(screen, aim.ref)
        return if (element == null) assess(emptyList(), null, screen) else press(element, long, screen)
    }

    /** A short tap by ref on [element] ([press]). */
    fun activation(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Assessment = press(element, long = false, screen)

    /**
     * A tap by ref on [element], as the actuator performs it: the click (the long click when
     * [long]) of the control that takes it ([Tree.clickTarget]), else a touch of the same kind at
     * [element]'s center. The click depends on the point only through the OCR lines there, so
     * every element of one control is read once.
     */
    private fun press(
        element: UiElement,
        long: Boolean,
        screen: ScreenSnapshot,
    ): Assessment {
        val control = Tree.clickTarget(element, long, screen)
        val center = Geometry.center(element.bounds)
        return if (control == null) {
            at(center, long, screen)
        } else {
            val key = control to drawnAt(center, screen).texts
            clicks.of(screen).computeIfAbsent(key) { assess(listOf(control), center, screen) }
        }
    }

    /**
     * A touch at [point], held when [long]: every control that may take it counts ([Tree.under]),
     * and the riskiest decides.
     */
    fun at(
        point: Aim.Point,
        long: Boolean,
        screen: ScreenSnapshot,
    ): Assessment =
        touches.of(screen).computeIfAbsent(point to long) {
            assess(Tree.under(screen, point.x, point.y, long), point, screen)
        }

    /**
     * What Enter on [screen] presses: the focused control, as a tap by ref on it ([activation]);
     * `null` when nothing is focused or the focused element is an input.
     */
    fun pressed(screen: ScreenSnapshot): Assessment? {
        val focused = screen.elements.firstOrNull { ElementState.FOCUSED in it.states }
        return if (focused == null || Elements.editable(focused)) null else activation(focused, screen)
    }

    /** The visible text on [element], as a tap by ref at its center sees it ([Evidence.of]). */
    fun texts(
        element: UiElement,
        screen: ScreenSnapshot,
    ): List<String> =
        (controls.shown(element, screen) + drawnAt(Geometry.center(element.bounds), screen).texts).distinct()

    /** The OCR lines at [point] ([Evidence.drawnAt]) and what they say, collected once per point. */
    private fun drawnAt(
        point: Aim.Point,
        screen: ScreenSnapshot,
    ): Drawn =
        drawn.of(screen).computeIfAbsent(point) {
            Work.points.increment()
            val texts = Evidence.drawnAt(it, screen)
            Drawn(texts, controls.said(texts, screen))
        }

    private fun assess(
        candidates: List<UiElement>,
        point: Aim.Point?,
        screen: ScreenSnapshot,
    ): Assessment {
        val drawn = if (point == null) NOTHING_DRAWN else drawnAt(point, screen)
        Work.judged.add(candidates.size.toLong())
        val judged = candidates.map { Reached(it, controls.risk(it, drawn.said, screen)) }
        val riskiest = judged.maxWithOrNull(byRisk)
        val hit = if (riskiest == null) controls.ofText(drawn.said, screen) else riskiest.hit
        val sends = judged.any(Reached::sends) || hit?.sends == true
        return Assessment(riskiest?.element, hit?.let(::asRisk), judged, sends) {
            val shown = candidates.flatMap { controls.shown(it, screen) + drawn.texts } + drawn.texts
            Work.gathered.add(shown.size.toLong())
            shown.distinct()
        }
    }
}

/** A control a touch may reach, with what activating it does ([Controls.risk]). */
internal data class Reached(
    val element: UiElement,
    val hit: Hit?,
) {
    /** Its risk, or `null` when ordinary. */
    val risk: ElementRisk? = hit?.let(::asRisk)

    /** Some reading of it sends ([Hit.sends]). */
    val sends: Boolean = hit?.sends == true
}

/** [hit] as the risk of activating an element. */
private fun asRisk(hit: Hit): ElementRisk = ElementRisk(hit.kind, hit.matched)

/** The OCR lines at a point and what they say. */
private class Drawn(
    val texts: List<String>,
    val said: Said,
)

/** Nothing drawn: a touch without a point. */
private val NOTHING_DRAWN = Drawn(emptyList(), NOTHING_SAID)

/** The text evidence on a control. */
internal object Evidence {
    /**
     * What [element] shows: what it shows itself ([shown]) and the OCR lines at [point]. Inside a
     * container that takes no click, only the point counts.
     */
    fun of(
        element: UiElement,
        point: Aim.Point?,
        screen: ScreenSnapshot,
    ): List<String> = (shown(element, screen) + if (point == null) emptyList() else drawnAt(point, screen)).distinct()

    /**
     * What [element] shows itself: its name, the names in its content ([Elements.content]), and,
     * when it is a control the tree gives no text at all, the OCR lines inside it (a drawn button).
     */
    fun shown(
        element: UiElement,
        screen: ScreenSnapshot,
    ): List<String> {
        val own = listOfNotNull(element.name?.takeIf(String::isNotBlank)) + Elements.content(element, screen)
        val drawnButton = own.isEmpty() && Elements.clickable(element)
        val inside =
            if (drawnButton) {
                val lines = screen.ocr.orEmpty()
                Work.ocr.add(lines.size.toLong())
                lines.filter { Geometry.inside(it.bounds, element.bounds) }.map { it.text }
            } else {
                emptyList()
            }
        return (own + inside).distinct()
    }

    /** The OCR lines at [point]. */
    fun drawnAt(
        point: Aim.Point,
        screen: ScreenSnapshot,
    ): List<String> {
        val lines = screen.ocr.orEmpty()
        Work.ocr.add(lines.size.toLong())
        return lines.filter { Geometry.contains(it.bounds, point.x, point.y) }.map { it.text }
    }
}

/**
 * Least to most severe: which of several risks counts, and how far a declared risk class raises an
 * intent. Each kind is asked about at every level and in every case the one before it is (the
 * confirmation matrix, policy-rules.md §6.1 第 4 步): sending asks at 谨慎 and once per app at
 * 标准, changing settings and deleting at 谨慎 and 标准, spending at every level; so the most
 * severe of several risks found gives the strictest verdict.
 */
private val SEVERITY: List<IntentKind> =
    listOf(
        IntentKind.READ,
        IntentKind.NAVIGATE,
        IntentKind.INPUT,
        IntentKind.OUTWARD,
        IntentKind.SETTINGS,
        IntentKind.DELETE,
        IntentKind.SPEND,
    )

/** The rank of [kind] in [SEVERITY]; no risk ranks lowest. */
internal fun severity(kind: IntentKind?): Int = SEVERITY.indexOf(kind)

/**
 * The more severe of [first] and [next], [first] among equals; it [Hit.sends] when either does,
 * so what the other one says about sending is kept.
 */
internal fun firstMax(
    first: Hit?,
    next: Hit?,
): Hit? =
    when {
        first == null -> {
            next
        }

        next == null -> {
            first
        }

        else -> {
            val max = if (severity(next.kind) > severity(first.kind)) next else first
            if (max.sends || !(first.sends || next.sends)) max else max.copy(sends = true)
        }
    }
