package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.LaunchTarget
import java.util.Locale

/** What `phone_launch` and `phone_intent` may open (policy-rules.md §2, §7). */
internal object Launches {
    /** `phone_intent kind=settings page=…` values that open harmless pages, exactly as written. */
    private val SAFE_SETTINGS_PAGES =
        setOf("wifi", "bluetooth", "display", "sound", "battery", "date", "locale", "location", "storage", APP_DETAILS)

    /**
     * [uri] has a scheme of [LaunchTarget.Link.SCHEMES] (any case), the whitelist the launch tool and
     * the adapters share, something after it and no whitespace, as the tool checks it. Checked
     * character by character, so input of any length is safe.
     */
    fun linkAllowed(uri: String): Boolean {
        val colon = uri.indexOf(':')
        val scheme = if (colon > 0) uri.substring(0, colon).lowercase(Locale.ROOT) else ""
        return scheme in LaunchTarget.Link.SCHEMES && colon < uri.length - 1 && uri.none(Char::isWhitespace)
    }

    /** `phone_intent kind=settings` opens a page on the safe list. */
    fun safeSettingsPage(fields: Map<String, String>): Boolean = fields["page"] in SAFE_SETTINGS_PAGES
}
