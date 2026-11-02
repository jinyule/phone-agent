package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LaunchesTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "https://example.com/a",
            "HTTPS://example.com",
            "http://x",
            "geo:0,0?q=西湖",
            "mailto:a@example.com",
            "market://details?id=a.b",
        ],
    )
    fun `web, map, mail and store links open`(link: String) {
        assertTrue(Launches.linkAllowed(link), link)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "intent:#Intent;component=cmb.pb/.Main;end",
            "alipays://platformapi/startapp",
            "android-app://com.icbc/x",
            "weixin://dl/scan",
            "file:///sdcard/a.apk",
            "https:",
            "https://a b",
            ":https",
            "example.com",
            "",
        ],
    )
    fun `other links do not`(link: String) {
        assertFalse(Launches.linkAllowed(link), link)
    }

    @Test
    fun `only listed settings pages are safe, written exactly`() {
        assertTrue(Launches.safeSettingsPage(mapOf("page" to "wifi")))
        assertTrue(Launches.safeSettingsPage(mapOf("page" to "app_details", "package" to "com.tencent.mm")))
        assertFalse(Launches.safeSettingsPage(mapOf("page" to "WIFI")))
        assertFalse(Launches.safeSettingsPage(mapOf("page" to "accessibility")))
        assertFalse(Launches.safeSettingsPage(emptyMap()))
    }
}
