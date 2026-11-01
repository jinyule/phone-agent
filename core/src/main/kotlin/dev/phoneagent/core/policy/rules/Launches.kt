package dev.phoneagent.core.policy.rules

import java.util.Locale

/** What `phone_launch` and `phone_intent` open (policy-rules.md §2). */
internal object Launches {
    private val INTENT_PACKAGE = Regex(";package=([^;#]+)")

    /** Deep-link schemes owned by apps on the hard-refusal list. */
    private val SCHEMES: Map<String, String> =
        mapOf(
            "alipay" to "com.eg.android.AlipayGphone",
            "alipays" to "com.eg.android.AlipayGphone",
        )

    /** `phone_intent kind=settings page=…` values that open harmless pages (policy-rules.md §7). */
    private val SAFE_SETTINGS_PAGES =
        setOf(
            "wifi",
            "bluetooth",
            "display",
            "sound",
            "battery",
            "date",
            "locale",
            "location",
            "storage",
            "app_details",
        )

    /** Fragments of settings actions and URIs of the pages the agent may never open. */
    private val SENSITIVE_SETTINGS =
        listOf(
            "accessibility",
            "development",
            "developer",
            "adb",
            "debug",
            "permission",
            "security",
            "privacy",
            "device_admin",
            "deviceadmin",
            "unknown_app",
            "install_unknown",
            "usage_access",
            "notification_listener",
            "overlay",
            "write_settings",
            "vpn",
            "biometric",
            "fingerprint",
            "lock",
            "reset",
            "credential",
        )

    /** The package [app] names: itself when it is a package name, else the blocked app with that label, else `null`. */
    fun packageOf(
        app: String,
        blocked: BlockedApps,
    ): String? = if (Text.PACKAGE.matches(app)) app else blocked.packageNamed(app)

    /** The package a link opens when it says: `android-app://pkg/…`, `intent:…;package=pkg;…`, a known scheme. */
    fun linkPackage(uri: String): String? {
        val scheme = uri.substringBefore(':').lowercase(Locale.ROOT)
        return if (scheme == "android-app") {
            uri.substringAfter("://").substringBefore('/').takeIf(Text.PACKAGE::matches)
        } else if (scheme == "intent") {
            INTENT_PACKAGE.find(uri)?.let { it.groupValues[1] }
        } else {
            SCHEMES[scheme]
        }
    }

    /** A link into the settings app that names a sensitive page (accessibility, developer options …). */
    fun opensSensitiveSettings(uri: String): Boolean {
        val lower = uri.lowercase(Locale.ROOT)
        return "settings" in lower && SENSITIVE_SETTINGS.any { it in lower }
    }

    /** `phone_intent kind=settings` opens a page on the safe list. */
    fun safeSettingsPage(fields: Map<String, String>): Boolean =
        fields["page"]?.lowercase(Locale.ROOT) in SAFE_SETTINGS_PAGES
}
