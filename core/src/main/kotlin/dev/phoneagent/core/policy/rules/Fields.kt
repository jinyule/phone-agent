package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/** Input fields: how sensitive they are, and which taps only edit them (policy-rules.md §5). */
internal object Fields {
    /** A clear button may sit this far (0–999) left or right of its field. */
    private const val CLEAR_NEAR = 80

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
                listOf(
                    "验证码",
                    "校验码",
                    "动态码",
                    "短信码",
                    "动态密码",
                    "verificationcode",
                    "verifycode",
                    "smscode",
                    "otp",
                    "captcha",
                ),
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

    /** Field classes that mean the user types it themselves. */
    val HAND_OFF: Set<FieldClass> =
        setOf(FieldClass.PASSWORD, FieldClass.VERIFICATION_CODE, FieldClass.PAYMENT_PASSWORD)

    /**
     * How sensitive [field] is, from its hint, name and resource id (never from what is typed in
     * it). A secret field is a password, or a payment password on a payment page.
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
                .firstOrNull { (_, words, idWords) ->
                    words.any { it in text } || ids.any { it in idWords }
                }?.first
        return named ?: if (field.secret) secretClass(screen) else FieldClass.PLAIN
    }

    private fun secretClass(screen: ScreenSnapshot): FieldClass =
        if (Pages.isPaymentPassword(screen)) FieldClass.PAYMENT_PASSWORD else FieldClass.PASSWORD

    /** Enter in this field searches rather than sends: it says so, and nothing on its row sends. */
    fun isSearch(
        field: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        val own = Text.compact(listOfNotNull(field.hint, field.name).joinToString(" "))
        val ids = field.resourceId?.let(Text::idWords).orEmpty()
        val row = screen.elements.filter { it.ref != field.ref && Geometry.sameRow(it.bounds, field.bounds) }
        val sendsOnRow = row.any { Lexicon.classify(Elements.label(it, screen))?.kind == IntentKind.OUTWARD }
        val searchesOnRow = row.any { Text.bare(Elements.label(it, screen)) in SEARCH }
        return !sendsOnRow && ("搜索" in own || "search" in own || "search" in ids || searchesOnRow)
    }

    /**
     * Tapping [element] only edits a field: a clear button ("×", "清空") or an unlabeled icon inside a
     * field, or a clear button right next to one. Deleting typed text is not a delete (§3.3).
     */
    fun editsField(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean {
        val label = Text.bare(Elements.label(element, screen))
        val clears =
            label in Words.FIELD_CLEAR ||
                element.resourceId
                    ?.let(Text::idWords)
                    .orEmpty()
                    .contains("clear")
        val unlabeled = label.isEmpty() && element.resourceId == null
        val fields = screen.elements.filter { it.ref != element.ref && Elements.editable(it) }
        return fields.any { field ->
            ((clears || unlabeled) && Geometry.inside(element.bounds, field.bounds)) ||
                (clears && next(element, field))
        }
    }

    private fun next(
        element: UiElement,
        field: UiElement,
    ): Boolean =
        Geometry.sameRow(element.bounds, field.bounds) &&
            element.bounds.left >= field.bounds.left - CLEAR_NEAR &&
            element.bounds.right <= field.bounds.right + CLEAR_NEAR

    /** The field [phone_type] without a ref types into: the focused editable, else the only editable. */
    fun typingTarget(screen: ScreenSnapshot): UiElement? {
        val fields = screen.elements.filter(Elements::editable)
        return fields.firstOrNull { ElementState.FOCUSED in it.states } ?: fields.singleOrNull()
    }
}
