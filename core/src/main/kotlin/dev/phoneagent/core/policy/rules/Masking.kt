package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.FieldClass

/**
 * Masks what a confirmation card shows (`ConfirmCard.content` is logged): bank card numbers that
 * pass the Luhn check and PRC id numbers with a valid check digit keep only their last four
 * characters; text typed into a bank-card or id field is masked whole the same way.
 */
internal object Masking {
    private const val KEEP = 4
    private const val LUHN_MAX_DIGIT = 9
    private const val LUHN_MODULUS = 10
    private const val ID_MODULUS = 11

    private val CARD = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")
    private val ID = Regex("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])")
    private val MASKABLE = Regex("[0-9A-Za-z]")
    private val ID_WEIGHTS = listOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECKS = "10X98765432"

    fun mask(
        text: String,
        fieldClass: FieldClass?,
    ): String =
        if (fieldClass == FieldClass.BANK_CARD || fieldClass == FieldClass.ID_NUMBER) {
            hide(text)
        } else {
            val cards = CARD.replace(text) { if (luhn(it.value.filter(Char::isDigit))) hide(it.value) else it.value }
            ID.replace(cards) { if (validId(it.value)) hide(it.value) else it.value }
        }

    /** Replaces every letter and digit but the last [KEEP] with `*`. */
    private fun hide(text: String): String = text.dropLast(KEEP).replace(MASKABLE, "*") + text.takeLast(KEEP)

    private fun luhn(digits: String): Boolean {
        val sum =
            digits.reversed().withIndex().sumOf { (i, c) ->
                val d = c.digitToInt()
                if (i % 2 == 1) (d * 2).let { if (it > LUHN_MAX_DIGIT) it - LUHN_MAX_DIGIT else it } else d
            }
        return sum % LUHN_MODULUS == 0
    }

    private fun validId(id: String): Boolean {
        val sum = ID_WEIGHTS.indices.sumOf { ID_WEIGHTS[it] * id[it].digitToInt() }
        return ID_CHECKS[sum % ID_MODULUS] == id.last().uppercaseChar()
    }
}
