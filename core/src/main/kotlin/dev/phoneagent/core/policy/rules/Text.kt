package dev.phoneagent.core.policy.rules

import java.text.Normalizer
import java.util.Locale

/** The one text normalization every list is matched with, so lists are written in plain form. */
internal object Text {
    /** An Android package name. */
    val PACKAGE: Regex = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private val WHITESPACE = Regex("\\s+")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val ID_SEPARATORS = Regex("[_.\\-]+")

    /** NFKC (full-width forms become ASCII, ￥ becomes ¥), lower case, whitespace removed. */
    fun compact(raw: String): String = nfkc(raw).filterNot(Char::isWhitespace)

    /** NFKC, lower case, whitespace runs collapsed to one space, trimmed. */
    fun spaced(raw: String): String = nfkc(raw).trim().replace(WHITESPACE, " ")

    /** [compact] without trailing punctuation or symbols ("发送！" → "发送", "ok." → "ok"). */
    fun bare(raw: String): String = compact(raw).trimEnd { !it.isLetterOrDigit() }

    /** Words of a resource id's last segment: split at `_`, `-`, `.` and camel case, lower case. */
    fun idWords(resourceId: String): List<String> =
        resourceId
            .substringAfterLast('/')
            .replace(CAMEL, "$1_$2")
            .lowercase(Locale.ROOT)
            .split(ID_SEPARATORS)
            .filter(String::isNotEmpty)

    private fun nfkc(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
