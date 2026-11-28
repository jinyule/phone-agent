package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import kotlin.system.exitProcess

/**
 * Hostile inputs for [BoundsTest]. Each case reads pathological texts and screens through every
 * public part of the policy: the formatter's lexicon on every element, and the resolver and the
 * engine on taps by ref and by point, typing, Enter, swipes, scrolls and observing, at every
 * level, with the re-check of every card. Screens are sized at the default limits of
 * [PolicyTuning] or far beyond them ([Case.readable]). Run as `main(case)` in a JVM of its own,
 * so a case that does not finish can be killed; exits 0 when the case is done, and 1 as soon as
 * the steps counted reading one readable screen ([Work]) exceed their bounds ([exceeded]).
 */
internal object BoundsProbe {
    /** Characters of a hostile text that is not part of a screen (call arguments, a label read alone). */
    private const val LONG = 200_000

    /** The default limits of [PolicyTuning], written out so that the probe also runs on rules without them. */
    const val MAX_ELEMENTS = 5_000
    const val MAX_SCREEN_TEXT = 100_000
    const val MAX_DEPTH = 100

    /** Characters of the text of one level in [deepLabels], at most. */
    private const val LEVEL_TEXT = 5
    private const val LEAF = "第一行文字很长很长很长第二行文字也很长很长"
    private val WHOLE = box(0, 400, 999, 450)
    private val SCREEN = box(0, 0, 999, 999)

    /** Controls of each kind in the small screen of a case that is cubic without the bounds, read first. */
    private const val SMALL = 200

    /** Controls of each kind in the full screen of such a case: three kinds, within [MAX_ELEMENTS]. */
    private const val FULL = 1_600

    /** Points [drive] touches besides the elements' centers: a tap and the start of a swipe. */
    private val TOUCHED = listOf(Aim.Point(500, 425), Aim.Point(100, 425))

    /**
     * The bound on all steps counted reading a readable screen of `n` elements and OCR lines for
     * the 11 calls of [drive] (policy-rules.md §8): the bounds of [exceeded] add up to at most
     * `11·E² + 26·E·M + 42·E + 17·M + 4`, which is at most `13·n² + 42·n + 4`, so at most `14·n²`
     * once `n` ≥ 43.
     */
    private fun total(n: Long): Long = 13 * n * n + 42 * n + 4

    /** One case: texts read alone, and screens that are all within the limits or all beyond them. */
    class Case(
        val texts: List<String>,
        val screens: List<ScreenSnapshot>,
        val readable: Boolean,
    )

    val CASES: Map<String, () -> Case> =
        mapOf(
            "brackets" to ::brackets,
            "spaces and statuses" to ::spacesAndStatuses,
            "nested controls" to ::nestedControls,
            "stacked controls" to ::stackedControls,
            "fields on one row" to ::fieldsOnOneRow,
            "total labels on one row" to ::totalLabels,
            "dialog buttons" to ::dialogButtons,
            "deep labels" to ::deepLabels,
            "touches over candidates" to ::touchesOverCandidates,
            "drawn over everything" to ::drawnOverEverything,
            "beyond the limits" to ::beyondTheLimits,
        )

    @JvmStatic
    fun main(args: Array<String>) {
        val case = CASES.getValue(args.single())()
        texts(case.texts)
        for (s in case.screens) {
            Work.reset()
            val steps = drive(s)
            val counts = Work.counts()
            val over = if (case.readable) exceeded(s, steps, counts) else emptyList()
            println("${s.elements.size} elements, ${s.ocr.orEmpty().size} OCR lines: $counts")
            if (over.isNotEmpty()) {
                println("over the bounds: $over")
                exitProcess(1)
            }
        }
        exitProcess(0)
    }

    /**
     * The counts of [Work] after reading [s] for [steps] calls ([drive]) that exceed their bounds
     * (policy-rules.md §8), with `E` elements, `M` OCR lines, `F` fields and parents `D` deep:
     *
     * - `touches` ≤ `E + 2`: one per point and kind of touch, at the elements' centers and [TOUCHED];
     * - `under` ≤ `6·E·touches`: five passes and each element marked at most once, per touch;
     * - `points` ≤ `E + 2`: what is drawn at a point is collected once;
     * - `owns` ≤ `E`: what an element shows itself is read once;
     * - `judged` ≤ `E·touches + E`: every control under a touch, and each control clicked by ref once;
     * - `ocr` ≤ `M·(owns + points)`: the OCR lines, once for each point and inside each drawn button;
     * - `texts` ≤ `E·(D + 1) + M·(owns + points)`: each element's name, its content (every element
     *   is in the content of at most `D` ancestors) and the OCR inside it, and what is drawn at each point;
     * - `content` ≤ `E·D`: each element is looked at from each of its ancestors at most once;
     * - `dialog` ≤ `2·(E + M)`: every text, once for generic buttons and once for app management;
     * - `row`, `fields` ≤ `E·F`: each field's row and each element's place among the fields, once;
     * - `gathered` ≤ `steps·(2·E + (2·E + 1)·M)`: what each step's touch shows, whose candidates'
     *   contents do not overlap, each with the OCR at the point;
     * - and their sum ≤ `13·n² + 42·n + 4` with `n = E + M` ([total]).
     */
    fun exceeded(
        s: ScreenSnapshot,
        steps: Int,
        counts: Map<String, Long>,
    ): List<String> {
        val e = s.elements.size.toLong()
        val m =
            s.ocr
                .orEmpty()
                .size
                .toLong()
        val f = s.elements.count(Elements::editable).toLong()
        val d = ScreenIndex.of(s).depth.toLong()
        val n = e + m
        val count = { name: String -> counts.getValue(name) }
        val drawn = m * (count("owns") + count("points"))
        val bounds =
            mapOf(
                "touches" to e + TOUCHED.size,
                "under" to 6 * e * count("touches"),
                "points" to e + TOUCHED.size,
                "owns" to e,
                "judged" to e * count("touches") + e,
                "ocr" to drawn,
                "texts" to e * (d + 1) + drawn,
                "content" to e * d,
                "dialog" to 2 * n,
                "row" to e * f,
                "fields" to e * f,
                "gathered" to steps * (2 * e + (2 * e + 1) * m),
            )
        val sum = counts.values.sum()
        return bounds
            .filter { (name, bound) ->
                count(name) > bound
            }.map { (name, bound) -> "$name ${count(name)} > $bound" } +
            listOfNotNull("total $sum > ${total(n)}".takeIf { sum > total(n) })
    }

    /** Unclosed brackets: one note scan per opening bracket was quadratic. */
    private fun brackets(): Case {
        val name = "(".repeat(MAX_SCREEN_TEXT - 20)
        return Case(
            listOf("(".repeat(LONG), "《".repeat(LONG), "(支付".repeat(LONG / 3), "(" + "a)".repeat(LONG / 2)),
            listOf(screen(el("e1", name), text("t1", name.take(10)))),
            readable = true,
        )
    }

    /** Long runs of spaces before a non-digit, and a strong word repeated as a status. */
    private fun spacesAndStatuses(): Case {
        val half = MAX_SCREEN_TEXT / 2
        return Case(
            listOf(
                " ".repeat(LONG) + "x",
                "¥" + " ".repeat(LONG) + "x",
                "待支付".repeat(LONG / 3),
                "支付".repeat(LONG / 2),
                "支付" + "！".repeat(LONG),
                "1,".repeat(LONG / 2) + "a",
                "us$ ".repeat(LONG / 4),
            ),
            listOf(screen(el("e1", " ".repeat(half - 1) + "x"), el("e2", "待支付".repeat(half / 3 - 1)))),
            readable = true,
        )
    }

    /**
     * Clickable elements nested in each other with the same bounds: comparing every pair and
     * walking every ancestor chain with a linear lookup was cubic. Chains as deep as allowed, as
     * many as allowed; named, and unnamed with named text at the bottom.
     */
    private fun nestedControls(): Case {
        val chains = MAX_ELEMENTS / (MAX_DEPTH + 1)
        val named = (0 until chains).flatMap { c -> chain("n", c * (MAX_DEPTH + 1), MAX_DEPTH) { "支付确认$it" } }
        val unnamed =
            (0 until chains).flatMap { c ->
                chain("u", c * (MAX_DEPTH + 1), MAX_DEPTH) { if (it == MAX_DEPTH) "确认支付" else null }
            }
        return Case(
            emptyList(),
            listOf(screen(*named.toTypedArray()), screen(*unnamed.toTypedArray())),
            readable = true,
        )
    }

    /** As many unrelated controls as allowed under one point: every one is a candidate. */
    private fun stackedControls(): Case {
        val stacked = (1 until MAX_ELEMENTS).map { el("e$it", if (it % 2 == 0) "发送" else null, bounds = WHOLE) }
        val s = screen(*stacked.toTypedArray(), ocr = listOf(Fx.ocr("确定", WHOLE)))
        return Case(emptyList(), listOf(s), readable = true)
    }

    /** Fields and send or search buttons all on one row: every field checked against every button. */
    private fun fieldsOnOneRow(): Case {
        val fields = (1..MAX_ELEMENTS / 2).map { field("f$it", hint = "搜索", bounds = WHOLE) }
        val buttons = (1 until MAX_ELEMENTS / 2).map { el("b$it", if (it % 2 == 0) "搜索" else "发", bounds = WHOLE) }
        return Case(emptyList(), listOf(screen(*(fields + buttons).toTypedArray())), readable = true)
    }

    /** Total labels without amounts, all on one row: each one read the whole row. */
    private fun totalLabels(): Case {
        val labels = (1 until MAX_ELEMENTS).map { text("t$it", "合计", WHOLE) }
        val s = screen(*labels.toTypedArray(), el("e1", "立即支付", bounds = WHOLE))
        return Case(emptyList(), listOf(s), readable = true)
    }

    /** Generic buttons in one dialog: each one read the whole dialog. */
    private fun dialogButtons(): Case {
        val buttons = (1 until MAX_ELEMENTS).map { el("e$it", "确定", bounds = WHOLE).copy(window = 1) }
        val s = screen(*buttons.toTypedArray(), text("t1", "确认删除这条消息吗", window = 1))
        return Case(emptyList(), listOf(s), readable = true)
    }

    /**
     * Unnamed elements nested in each other above all the text the screen may hold, each with a
     * text of its own: the label of each one is a different text about as long as the screen's.
     * Once as containers, once as controls; a field on the same row.
     */
    private fun deepLabels(): Case {
        val levels = MAX_DEPTH - 1
        val leaves = minOf(MAX_ELEMENTS - 2 * levels - 1, (MAX_SCREEN_TEXT - levels * LEVEL_TEXT) / LEAF.length)
        val screens =
            listOf(false, true).map { clickable ->
                val boxes = chain("c", 0, levels - 1, clickable) { null }
                val own = (1..levels).map { text("o$it", "第${it}层", WHOLE, parent = "c$it") }
                val texts = (1..leaves).map { text("t$it", LEAF, WHOLE, parent = "c$levels") }
                screen(*(boxes + own + texts + field("f1", bounds = WHOLE)).toTypedArray())
            }
        return Case(emptyList(), screens, readable = true)
    }

    /**
     * Controls that cover the whole screen, generic and unrelated, so every one is a candidate
     * under every point; elements that take no touch, each at a point of its own; OCR lines away
     * from every point. Reading the OCR lines, the controls' own words and their dialog again
     * for each control under each point was cubic. A small screen first, then a full one.
     */
    private fun touchesOverCandidates(): Case =
        Case(
            emptyList(),
            listOf(SMALL, FULL).map { n ->
                val controls = (1..n).map { el("b$it", "确定", bounds = SCREEN) }
                screen(*(controls + images(n)).toTypedArray(), ocr = List(n) { Fx.ocr("删除记录", box(0, 0, 9, 9)) })
            },
            readable = true,
        )

    /**
     * Controls without names that cover the whole screen, each a drawn button showing every OCR
     * line; OCR lines, all different, that cover the whole screen and so every point; elements
     * that take no touch, each at a point of its own. A small screen first, then a full one.
     */
    private fun drawnOverEverything(): Case =
        Case(
            emptyList(),
            listOf(SMALL, FULL).map { n ->
                val controls = (1..n).map { el("b$it", bounds = SCREEN) }
                screen(*(controls + images(n)).toTypedArray(), ocr = List(n) { Fx.ocr("第${it}行", SCREEN) })
            },
            readable = true,
        )

    /** [n] images that take no touch, each at a point of its own on a grid over the screen. */
    private fun images(n: Int): List<UiElement> {
        val side = generateSequence(1) { it + 1 }.first { it * it >= n }
        val step = SCREEN.right / side
        return (0 until n).map {
            val left = it % side * step
            val top = it / side * step
            el("i${it + 1}", role = ElementRole.IMAGE, bounds = box(left, top, left + 2, top + 2), states = emptySet())
        }
    }

    /** Screens beyond each limit are not read: each one must be refused in time in proportion to it. */
    private fun beyondTheLimits(): Case {
        val many = (1..MAX_ELEMENTS * 20).map { el("e$it", "支付", bounds = WHOLE) }
        val ring =
            (1..MAX_ELEMENTS).map { el("e$it", bounds = WHOLE).copy(parent = ElementRef("e${it % MAX_ELEMENTS + 1}")) }
        return Case(
            emptyList(),
            listOf(
                screen(*many.toTypedArray()),
                screen(el("e1", "(".repeat(MAX_SCREEN_TEXT * 50))),
                screen(*chain("d", 0, MAX_ELEMENTS - 2) { "确认支付" }.toTypedArray()),
                screen(*ring.toTypedArray()),
            ),
            readable = false,
        )
    }

    /** Reads each text as a label, a dialog text and an amount, and as the arguments of calls without a screen. */
    private fun texts(texts: List<String>) {
        val lexicon = Reading(PolicyTuning()).lexicon
        val policy = Fx.rules()
        for (text in texts) {
            lexicon.classify(text)
            lexicon.inContext(text)
            lexicon.ofControl(text)
            lexicon.isGeneric(text)
            lexicon.isEscape(text)
            Phrases.strong(text, complete = true)
            Phrases.leading(text, complete = true)
            Prices.scan(text)
            Text.isPackage(text)
            val ask =
                call(
                    "request_confirmation",
                    "action" to text,
                    "target" to text,
                    "content" to text,
                    "amount" to text,
                )
            val type = call("phone_type", "text" to text, "submit" to true)
            for (c in listOf(ask, call("request_confirmation", "action" to text), type)) {
                policy.engine.evaluate(c, policy.resolver.resolve(c, RiskClass.WRITE, null), Fx.ctx())
            }
        }
    }

    /**
     * Every public read of [s]: the lexicon on every element and every kind of call at every
     * level. Returns how many different calls it makes.
     */
    private fun drive(s: ScreenSnapshot): Int {
        val policy = Fx.rules()
        s.elements.forEach { policy.lexicon.activationRisk(it, s) }
        val refs =
            listOfNotNull(s.elements.firstOrNull(), s.elements.getOrNull(s.elements.size / 2), s.elements.lastOrNull())
        val calls =
            refs.flatMap {
                listOf(
                    Fx.tap(it.ref.value),
                    call("phone_scroll", "screen" to "s1", "ref" to it.ref.value),
                )
            } +
                listOf(
                    call("phone_tap", "x" to TOUCHED[0].x, "y" to TOUCHED[0].y),
                    call("phone_type", "text" to "你好", "submit" to true),
                    call("phone_key", "key" to "enter"),
                    call(
                        "phone_swipe",
                        "x1" to TOUCHED[1].x,
                        "y1" to TOUCHED[1].y,
                        "x2" to 900,
                        "y2" to TOUCHED[1].y,
                        "durationMs" to 300,
                    ),
                    call("phone_observe"),
                )
        for (c in calls) {
            for (level in SecurityLevel.entries) {
                val intent = policy.resolver.resolve(c, RiskClass.WRITE, s)
                val ctx = Fx.ctx(level, s)
                val verdict = policy.engine.evaluate(c, intent, ctx)
                if (verdict is Verdict.Confirm) policy.engine.evaluate(c, intent, ctx.copy(approved = verdict.card))
            }
        }
        return calls.size
    }

    /**
     * Elements `[prefix][from + 1]` … `[prefix][from + depth + 1]`, each the parent of the next, all
     * with the same bounds; [name] gets the level, 0 at the top.
     */
    private fun chain(
        prefix: String,
        from: Int,
        depth: Int,
        clickable: Boolean = true,
        name: (Int) -> String?,
    ): List<UiElement> =
        (0..depth).map { i ->
            val parent = if (i == 0) null else ElementRef("$prefix${from + i}")
            val e = el("$prefix${from + i + 1}", name(i), bounds = WHOLE).copy(parent = parent)
            if (clickable) e else e.copy(role = ElementRole.CONTAINER, states = emptySet())
        }
}
