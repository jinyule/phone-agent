package dev.phoneagent.privileged.cap

/**
 * What `grantSelf` may grant (phase1-architecture §3.5, D4). Exactly one permission:
 * `WRITE_SECURE_SETTINGS` lets the app write `enabled_accessibility_services` and
 * `adb_wifi_enabled` itself, which is what makes onboarding a single manual step. It is a
 * `development` permission, so the shell user may grant it.
 *
 * The allow-list lives here rather than at the call site so there is one place to review.
 */
internal object GrantSpec {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    private val ALLOWED = setOf(WRITE_SECURE_SETTINGS)

    fun isAllowed(permission: String?): Boolean = permission in ALLOWED
}
