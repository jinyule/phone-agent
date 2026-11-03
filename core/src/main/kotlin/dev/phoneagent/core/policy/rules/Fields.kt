package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * Input fields: how sensitive they are, and which taps only edit them (policy-rules.md §5).
 *
 * [editsField] is adapted from Mika's `IrreversibleDetector` (Copyright 2026 wangzan101,
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

    /** The elements labelled to send and those labelled "搜索" on a screen, read once per snapshot. */
    private val buttons =
        PerScreen { screen ->
            val labelled = screen.elements.map { it to Elements.label(it, screen) }
            Buttons(
                sends = labelled.filter { (_, label) -> sends(label, screen) }.map { it.first },
                searches = labelled.filter { (_, label) -> searches(label) }.map { it.first },
            )
        }

    private fun sends(
        label: String,
        screen: ScreenSnapshot,
    ): Boolean = lexicon.classify(lexicon.read(label), screen)?.kind == IntentKind.OUTWARD

    private fun searches(label: String): Boolean = lexicon.whole(label) && Text.bare(label) in SEARCH

    /** Enter in this field searches rather than sends: it says so, and nothing on its row sends. */
    fun isSearch(
        field: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        val own = Text.compact(listOfNotNull(field.hint, field.name).joinToString(" "))
        val ids = field.resourceId?.let(Text::idWords).orEmpty()
        val found = buttons.of(screen)
        val onRow = { e: UiElement -> e.ref != field.ref && Geometry.sameRow(e.bounds, field.bounds) }
        return found.sends.none(onRow) &&
            ("搜索" in own || "search" in own || "search" in ids || found.searches.any(onRow))
    }

    /**
     * Tapping [element] (whose visible text is [texts]) only edits a field (§3.3): a clear button
     * ("×", "清空", an id with "clear") inside or right next to a field; or an icon the tree says
     * nothing about inside a field, when the only text drawn on it is a clearing word ("清除",
     * "×"), or when nothing is drawn on it and the field holds text. Never when any text on it
     * names another risk ("支付", "发送", "删除聊天记录"): that text decides instead.
     */
    fun editsField(
        element: UiElement,
        texts: List<String>,
        screen: ScreenSnapshot,
    ): Boolean = clearing(element, texts, screen)?.let { it.veto == null } ?: false

    /**
     * [element] as a would-be clear button ([editsField]): `null` when it is none; else the most
     * severe risk a text on it names besides clearing (its [Clearing.veto]), read at any length,
     * which then decides the tap (policy-rules.md §0: a risk once found is never dropped).
     */
    fun clearing(
        element: UiElement,
        texts: List<String>,
        screen: ScreenSnapshot,
    ): Clearing? {
        val label = Elements.label(element, screen)
        val clears = clearWord(label) || "clear" in element.resourceId?.let(Text::idWords).orEmpty()
        val icon = label.isBlank() && element.resourceId == null
        val drawnClear = texts.isNotEmpty() && texts.all(::clearWord)
        val fields = ScreenIndex.of(screen).editables.filter { it.ref != element.ref }
        val near =
            fields.any { field ->
                val inside = Geometry.inside(element.bounds, field.bounds)
                val iconClears = icon && inside && (drawnClear || (texts.isEmpty() && !field.value.isNullOrEmpty()))
                iconClears || (clears && (inside || next(element, field)))
            }
        return if (near) Clearing(vetoOf(texts, screen)) else null
    }

    /** The most severe risk among [texts] other than clearing the field, the first among equals. */
    private fun vetoOf(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): Hit? =
        texts
            .filterNot(::clearWord)
            .mapNotNull { lexicon.ofControl(it, screen) }
            .maxWithOrNull(compareBy { severity(it.kind) })

    /**
     * A [Words.FIELD_CLEAR] entry: a word ([Text.bare] form) or a symbol such as "×" ([Text.compact]
     * form); never a text longer than what is read of a label ([Lexicon.whole]).
     */
    private fun clearWord(text: String): Boolean =
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

/** Elements of one screen whose label sends, and those whose label is a search button. */
private class Buttons(
    val sends: List<UiElement>,
    val searches: List<UiElement>,
)
