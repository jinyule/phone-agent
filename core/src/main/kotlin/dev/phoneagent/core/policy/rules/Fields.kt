package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Input fields: how sensitive they are, and which taps only edit them (policy-rules.md §5).
 *
 * [clearing] is adapted from Mika's `IrreversibleDetector` (Copyright 2026 wangzan101,
 * Apache-2.0). Modified by PhoneAgent contributors: an icon without text counts as a clear button
 * only inside a field that holds text or under a clearing word, any other text on it (OCR too)
 * decides instead, and the distance comes from [PolicyTuning]. See NOTICE.
 */
internal class Fields(
    private val tuning: PolicyTuning,
    private val lexicon: Lexicon,
    private val pages: Pages,
) {
    /**
     * How sensitive [field] is, from its hint, name and resource id (never from what is typed in
     * it). A secret field is a password, or a payment password on a payment-password page.
     */
    fun classify(
        field: UiElement,
        screen: ScreenSnapshot,
    ): FieldClass {
        val text = Text.compact(listOfNotNull(field.hint, field.name).joinToString(" "))
        val parts = field.resourceId?.let(Text::idWords).orEmpty()
        // Words and adjacent pairs, so that both "pay_pwd" and "smsCode" read as one word.
        val ids = parts + parts.zipWithNext { a, b -> a + b }
        val named =
            CLASSES
                .firstOrNull { (_, words, idWords) -> words.any { it in text } || ids.any { it in idWords } }
                ?.first
        return named ?: if (field.secret) secretClass(screen) else FieldClass.PLAIN
    }

    private fun secretClass(screen: ScreenSnapshot): FieldClass =
        if (pages.isPaymentPassword(screen)) FieldClass.PAYMENT_PASSWORD else FieldClass.PASSWORD

    /** [field] says it searches: "搜索" or "search" in its hint or name, or "search" in its resource id. */
    fun saysSearch(field: UiElement): Boolean {
        val own = Text.compact(listOfNotNull(field.hint, field.name).joinToString(" "))
        return "搜索" in own || "search" in own || "search" in field.resourceId?.let(Text::idWords).orEmpty()
    }

    /** [element] is a search button: its label, read whole ([Lexicon.whole]), is "搜索" or "search". */
    fun searchButton(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        val label = Elements.label(element, screen)
        return lexicon.whole(label) && Text.bare(label) in SEARCH
    }

    /**
     * [element] as a would-be clear button (§3.3), when its visible text says [said]: `null` when
     * it is none; else the most severe risk a text on it names besides clearing (its
     * [Clearing.veto]), read at any length, which then decides the tap (policy-rules.md §0: a risk
     * once found is never dropped). A clear button is one ("×", "清空", an id with "clear") inside
     * or right next to a field; or an icon the tree says nothing about inside a field, when the
     * only text drawn on it is a clearing word ("清除", "×"), or when nothing is drawn on it and the
     * field holds text. Where it sits among the fields is read once per element and snapshot.
     */
    fun clearing(
        element: UiElement,
        said: Said,
        screen: ScreenSnapshot,
    ): Clearing? {
        val at = places.of(screen).computeIfAbsent(element) { placeOf(it, screen) }
        val drawnClear = !said.isEmpty && said.allClear
        val near =
            (at.icon && ((drawnClear && at.inField) || (said.isEmpty && at.inFilledField))) || (at.clears && at.byField)
        return if (near) Clearing(said.veto) else null
    }

    private val places = PerScreen { ConcurrentHashMap<UiElement, Place>() }

    private fun placeOf(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Place {
        val label = Elements.label(element, screen)
        val fields = ScreenIndex.of(screen).editables.filter { it.ref != element.ref }
        Work.fields.add(fields.size.toLong())
        val inside = fields.filter { Geometry.inside(element.bounds, it.bounds) }
        return Place(
            clears = clearWord(label) || "clear" in element.resourceId?.let(Text::idWords).orEmpty(),
            icon = label.isBlank() && element.resourceId == null,
            inField = inside.isNotEmpty(),
            inFilledField = inside.any { !it.value.isNullOrEmpty() },
            byField = inside.isNotEmpty() || fields.any { next(element, it) },
        )
    }

    /**
     * A [Words.FIELD_CLEAR] entry: a word ([Text.bare] form) or a symbol such as "×" ([Text.compact]
     * form); never a text longer than what is read of a label ([Lexicon.whole]).
     */
    fun clearWord(text: String): Boolean =
        lexicon.whole(text) && (Text.bare(text) in Words.FIELD_CLEAR || Text.compact(text) in Words.FIELD_CLEAR)

    private fun next(
        element: UiElement,
        field: UiElement,
    ): Boolean =
        Geometry.sameRow(element.bounds, field.bounds) &&
            element.bounds.left >= field.bounds.left - tuning.clearNear &&
            element.bounds.right <= field.bounds.right + tuning.clearNear

    /** The field `phone_type` without a ref types into: the focused editable, else the only editable. */
    fun typingTarget(screen: ScreenSnapshot): UiElement? {
        val fields = ScreenIndex.of(screen).editables
        return fields.firstOrNull { ElementState.FOCUSED in it.states } ?: fields.singleOrNull()
    }

    /** Constants. */
    companion object {
        /** Field classes that mean the user types it themselves. */
        val HAND_OFF: Set<FieldClass> =
            setOf(FieldClass.PASSWORD, FieldClass.VERIFICATION_CODE, FieldClass.PAYMENT_PASSWORD)
    }
}

/** Words in hint, name or resource id ([Text.compact] / [Text.idWords] form), checked in this order. */
private val CLASSES: List<Triple<FieldClass, List<String>, Set<String>>> =
    listOf(
        Triple(
            FieldClass.PAYMENT_PASSWORD,
            listOf("支付密码", "交易密码", "付款密码", "支付口令", "paymentpassword", "paypassword", "paymentpin"),
            setOf("paypwd", "paypassword"),
        ),
        Triple(
            FieldClass.VERIFICATION_CODE,
            listOf("验证码", "校验码", "动态码", "短信码", "动态密码", "verificationcode", "verifycode", "smscode", "otp", "captcha"),
            setOf("otp", "captcha", "vcode", "smscode", "verifycode", "authcode"),
        ),
        Triple(
            FieldClass.PASSWORD,
            listOf("密码", "口令", "password", "passcode", "passwd"),
            setOf("password", "passwd", "pwd", "pin", "passcode"),
        ),
        Triple(
            FieldClass.BANK_CARD,
            listOf("银行卡", "卡号", "cardnumber", "cardno"),
            setOf("cardno", "cardnumber", "bankcard"),
        ),
        Triple(
            FieldClass.ID_NUMBER,
            listOf("身份证", "证件号", "idnumber", "idcard", "nationalid"),
            setOf("idcard", "idno", "idnumber"),
        ),
    )

private val SEARCH = setOf("搜索", "search")

/** A would-be clear button ([Fields.clearing]); [veto] is the risk named on it besides clearing, if any. */
internal class Clearing(
    val veto: Hit?,
)

/**
 * Where an element sits among the fields of its screen ([Fields.clearing]).
 *
 * @property clears its label is a clearing word or its resource id says "clear".
 * @property icon it has no label and no resource id.
 * @property inField it lies inside a field; [inFilledField] inside one that holds text.
 * @property byField it lies inside a field or right next to one.
 */
private class Place(
    val clears: Boolean,
    val icon: Boolean,
    val inField: Boolean,
    val inFilledField: Boolean,
    val byField: Boolean,
)

/**
 * Which fields search rather than send when submitted (policy-rules.md §2): the field says so
 * ([Fields.saysSearch]) or a search button sits on its row, and no control on its row sends or
 * does something more severe (OUTWARD or above: sending, changing settings, deleting, paying;
 * whatever else such a control does, it may also send). A control on the row whose label is too
 * long to read whole ([Lexicon.whole]) counts too: what is not read cannot show that nothing on
 * the row sends (§0).
 */
internal class Searches(
    private val lexicon: Lexicon,
    private val fields: Fields,
    private val risks: Risks,
) {
    /** Fields that search, and controls that may send or are search buttons; each read once per snapshot. */
    private val searching = PerScreen { ConcurrentHashMap<UiElement, Boolean>() }
    private val reaching = PerScreen { ConcurrentHashMap<UiElement, Boolean>() }
    private val buttons = PerScreen { ConcurrentHashMap<UiElement, Boolean>() }

    /** Enter in [field] searches; its row is looked at once per snapshot ([Work.row]). */
    fun isSearch(
        field: UiElement,
        screen: ScreenSnapshot,
    ): Boolean = searching.of(screen).computeIfAbsent(field) { look(it, screen) }

    private fun look(
        field: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        Work.row.add(screen.elements.size.toLong())
        val row = screen.elements.filter { it.ref != field.ref && Geometry.sameRow(it.bounds, field.bounds) }
        val button = { e: UiElement -> buttons.of(screen).computeIfAbsent(e) { fields.searchButton(it, screen) } }
        return row.none { mayReach(it, screen) } && (fields.saysSearch(field) || row.any(button))
    }

    /**
     * [element]'s label is not read whole, or activating it sends or does something more severe
     * (whatever else it does, it may also send), as a tap by ref sees it ([Risks.activation]).
     */
    private fun mayReach(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean =
        reaching.of(screen).computeIfAbsent(element) {
            !lexicon.whole(Elements.label(it, screen)) ||
                severity(risks.activation(it, screen).kind) >= severity(IntentKind.OUTWARD)
        }
}
