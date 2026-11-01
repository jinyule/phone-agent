package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaunchesTest {
    private val alipay = "com.eg.android.AlipayGphone"

    @Test
    fun `an app argument is a package, or the label of a blocked app`() {
        assertEquals("com.tencent.mm", Launches.packageOf("com.tencent.mm", BlockedApps.DEFAULT))
        assertEquals(alipay, Launches.packageOf("支付宝", BlockedApps.DEFAULT))
        // Other names are resolved by the runtime; the policy then sees the screen of the opened app.
        assertNull(Launches.packageOf("微信", BlockedApps.DEFAULT))
    }

    @Test
    fun `links name their package when they say`() {
        assertEquals("com.icbc", Launches.linkPackage("android-app://com.icbc/https/m.icbc.com.cn"))
        assertNull(Launches.linkPackage("android-app://not a package/x"))
        val intent = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        assertEquals("com.google.zxing.client.android", Launches.linkPackage(intent))
        assertNull(Launches.linkPackage("intent:#Intent;action=android.intent.action.VIEW;end"))
        assertEquals(alipay, Launches.linkPackage("alipays://platformapi/startapp?appId=20000056"))
        assertEquals(alipay, Launches.linkPackage("ALIPAY://x"))
        assertNull(Launches.linkPackage("https://example.com/pay"))
        assertNull(Launches.linkPackage(""))
    }

    @Test
    fun `settings links to sensitive pages are recognized`() {
        assertTrue(Launches.opensSensitiveSettings("intent:#Intent;action=android.settings.ACCESSIBILITY_SETTINGS;end"))
        assertTrue(
            Launches.opensSensitiveSettings(
                "intent:#Intent;action=android.settings.APPLICATION_DEVELOPMENT_SETTINGS;end",
            ),
        )
        assertTrue(Launches.opensSensitiveSettings("android-app://com.android.settings/#Intent;action=x.VPN_SETTINGS"))
        assertFalse(Launches.opensSensitiveSettings("intent:#Intent;action=android.settings.WIFI_SETTINGS;end"))
        // Without "settings" a link is someone's web page.
        assertFalse(Launches.opensSensitiveSettings("https://example.com/accessibility"))
        // Trade-off: a web page whose path says settings and privacy is refused too (rare, and safe).
        assertTrue(Launches.opensSensitiveSettings("https://example.com/settings/privacy"))
    }

    @Test
    fun `only listed settings pages are safe`() {
        assertTrue(Launches.safeSettingsPage(mapOf("page" to "wifi")))
        assertTrue(Launches.safeSettingsPage(mapOf("page" to "APP_DETAILS", "pkg" to "com.tencent.mm")))
        assertFalse(Launches.safeSettingsPage(mapOf("page" to "accessibility")))
        assertFalse(Launches.safeSettingsPage(emptyMap()))
    }
}
