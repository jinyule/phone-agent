package dev.phoneagent.core.policy.rules

import java.util.Locale

/** What `phone_launch` and `phone_intent` may open (policy-rules.md §2, §7). */
internal object Launches {
    /**
     * Link schemes `phone_launch` opens: web, map, mail and store addresses. The same list as
     * `LaunchTarget.Link.SCHEMES` of the screen tools (WP06), kept here until that is merged.
     */
    val SCHEMES: Set<String> = setOf("https", "http", "geo", "mailto", "market")

    /** `phone_intent kind=settings page=…` values that open harmless pages, exactly as written. */
    private val SAFE_SETTINGS_PAGES =
        setOf("wifi", "bluetooth", "display", "sound", "battery", "date", "locale", "location", "storage", APP_DETAILS)

    /**
     * [uri] has a scheme of [SCHEMES] (any case), something after it and no whitespace, as the
     * tool checks it. Checked character by character, so input of any length is safe.
     */
    fun linkAllowed(uri: String): Boolean {
        val colon = uri.indexOf(':')
        val scheme = if (colon > 0) uri.substring(0, colon).lowercase(Locale.ROOT) else ""
        return scheme in SCHEMES && colon < uri.length - 1 && uri.none(Char::isWhitespace)
    }

    /** `phone_intent kind=settings` opens a page on the safe list. */
    fun safeSettingsPage(fields: Map<String, String>): Boolean = fields["page"] in SAFE_SETTINGS_PAGES
}
