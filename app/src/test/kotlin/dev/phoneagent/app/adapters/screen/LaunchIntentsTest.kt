package dev.phoneagent.app.adapters.screen

import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.SystemIntentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class LaunchIntentsTest {
    private fun ready(built: LaunchIntents.Built): LaunchIntents.Built.Ready = built as LaunchIntents.Built.Ready

    private fun invalid(built: LaunchIntents.Built): String = (built as LaunchIntents.Built.Invalid).reason

    private fun system(
        kind: SystemIntentKind,
        vararg fields: Pair<String, String>,
    ) = LaunchIntents.system(LaunchTarget.SystemIntent(kind, fields.toMap()), ZoneOffset.UTC)

    @Test
    fun appsStartFromTheirLauncherIntentInANewTask() {
        val launcher = Intent(Intent.ACTION_MAIN).setPackage("com.example.app")

        val warm = ready(LaunchIntents.app(LaunchTarget.App("com.example.app")) { launcher })
        assertEquals("com.example.app", warm.expectPackage)
        assertTrue(warm.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(warm.intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertEquals(0, warm.intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val cold = ready(LaunchIntents.app(LaunchTarget.App("com.example.app", cold = true)) { Intent(launcher) })
        assertTrue(cold.intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test
    fun anAppWithoutLauncherIntentIsInvalid() {
        assertEquals(
            "com.missing is not installed or has no launcher activity",
            invalid(LaunchIntents.app(LaunchTarget.App("com.missing")) { null }),
        )
    }

    private fun link(uri: String) = LaunchIntents.link(LaunchTarget.Link(uri))

    @Test
    fun linksAreBrowsableViewsWithoutComponent() {
        val intent = ready(link(" https://example.com/a?b=1 ")).intent

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/a?b=1", intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull(intent.component)
        assertNull(intent.selector)
        assertNull(intent.`package`)
        assertNull(intent.extras)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test
    fun onlyListedSchemesAreOpened() {
        for (uri in listOf("http://example.com", "geo:0,0?q=cafe", "mailto:a@example.com", "market://details?id=a.b")) {
            assertEquals(uri, uri, ready(link(uri)).intent.dataString)
        }
        // The adapter opens exactly the whitelist tools check against (fix list r4, rebase follow-up 2).
        for (scheme in LaunchTarget.Link.SCHEMES) {
            for (uri in listOf("$scheme://example.com/a", "${scheme.uppercase()}://example.com/a")) {
                assertEquals(uri, uri, ready(link(uri)).intent.dataString)
            }
        }
        assertEquals(
            "links with scheme 'tel' are not opened: it is not a web or known deep link; " +
                "allowed: ${LaunchTarget.Link.SCHEMES.sorted()}",
            invalid(link("tel:5551234")),
        )
    }

    @Test
    fun linksThatSmuggleIntentsOrReadLocalDataAreRefusedWithTheReason() {
        val allowed = "allowed: [geo, http, https, mailto, market]"
        assertEquals(
            "links with scheme 'intent' are not opened: it can start any component of any app; $allowed",
            invalid(link("intent:#Intent;component=com.android.settings/.Settings;end")),
        )
        assertEquals(
            "links with scheme 'android-app' are not opened: it can start any component of any app; $allowed",
            invalid(link("android-app://com.android.settings/https/example.com")),
        )
        assertEquals(
            "links with scheme 'content' are not opened: it reads data from other apps; $allowed",
            invalid(link("content://contacts/people/1")),
        )
        assertEquals(
            "links with scheme 'file' are not opened: it reads local files; $allowed",
            invalid(link("file:///sdcard/x")),
        )
        assertEquals(
            "links with scheme 'javascript' are not opened: it runs code; $allowed",
            invalid(link("JavaScript:alert(1)")),
        )
    }

    @Test
    fun deepLinksOutsideTheListAreRefused() {
        for (uri in listOf("weixin://dl/scan", "alipays://platformapi/startapp?appId=1", "sms:123", "tel:123")) {
            assertEquals(
                uri,
                "links with scheme '${uri.substringBefore(':')}' are not opened: it is not a web or known deep link; " +
                    "allowed: [geo, http, https, mailto, market]",
                invalid(link(uri)),
            )
        }
    }

    @Test
    fun anIntentFragmentInAWebLinkStaysPartOfTheUri() {
        // Intent.parseUri would turn this into an explicit intent for the named component.
        val uri = "https://example.com/#Intent;component=com.evil/.Steal;S.x=y;end"

        val intent = ready(link(uri)).intent

        assertEquals(uri, intent.dataString)
        assertNull(intent.component)
        assertNull(intent.extras)
    }

    @Test
    fun malformedWebLinksAreRefused() {
        assertEquals("web link has no host: 'https:///path'", invalid(link("https:///path")))
        assertTrue(invalid(link("1http:x")).startsWith("link needs an absolute URI"))
    }

    @Test
    fun alarmTakesAnHourAndMinute() {
        val intent = ready(system(SystemIntentKind.ALARM, "time" to "7:05", "label" to "Run")).intent

        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(7, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(5, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Run", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(false, intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val late = system(SystemIntentKind.ALARM, "time" to "24:00")
        assertEquals("alarm time must be HH:mm, got '24:00'", invalid(late))
    }

    @Test
    fun timerSecondsAreBounded() {
        val timer = ready(system(SystemIntentKind.TIMER, "seconds" to "90")).intent
        assertEquals(90, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        for (bad in listOf("0", "86401", "1.5", "ten")) {
            val reason = invalid(system(SystemIntentKind.TIMER, "seconds" to bad))
            assertTrue(bad, reason.startsWith("timer seconds must be"))
        }
    }

    @Test
    fun calendarTimesAreLocalAndOrdered() {
        val intent =
            ready(
                system(
                    SystemIntentKind.CALENDAR,
                    "title" to "Dentist",
                    "start" to "2026-11-02T09:30",
                    "end" to "2026-11-02T10:00",
                    "location" to "Main St",
                ),
            ).intent

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals(1_793_611_800_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals(1_793_613_600_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        assertEquals("Main St", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))

        assertEquals(
            "calendar start must be local time yyyy-MM-ddTHH:mm, got 'tomorrow'",
            invalid(system(SystemIntentKind.CALENDAR, "title" to "x", "start" to "tomorrow")),
        )
        assertEquals(
            "calendar end must be local time yyyy-MM-ddTHH:mm, got '10am'",
            invalid(system(SystemIntentKind.CALENDAR, "title" to "x", "start" to "2026-11-02T09:30", "end" to "10am")),
        )
        assertEquals(
            "calendar end is before start",
            invalid(
                system(
                    SystemIntentKind.CALENDAR,
                    "title" to "x",
                    "start" to "2026-11-02T09:30",
                    "end" to "2026-11-02T09:00",
                ),
            ),
        )
    }

    @Test
    fun shareGoesThroughTheChooser() {
        val intent = ready(system(SystemIntentKind.SHARE, "text" to "hello")).intent

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals("hello", inner?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun dialOnlyOpensTheDialer() {
        val intent = ready(system(SystemIntentKind.DIAL, "number" to "+1 (555) 010-9999")).intent

        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data?.scheme)
        assertEquals("+1 (555) 010-9999", intent.data?.schemeSpecificPart)
        assertTrue(invalid(system(SystemIntentKind.DIAL, "number" to "123;call")).startsWith("dial number may contain"))
    }

    @Test
    fun navigateEncodesThePlace() {
        val intent = ready(system(SystemIntentKind.NAVIGATE, "place" to "Café & Bar")).intent

        assertEquals("geo:0,0?q=Caf%C3%A9%20%26%20Bar", intent.dataString)
        assertEquals("navigate needs a place", invalid(system(SystemIntentKind.NAVIGATE, "place" to "  ")))
    }

    @Test
    fun settingsOpensOnlyWhitelistedPages() {
        val wifi = ready(system(SystemIntentKind.SETTINGS, "page" to "wifi")).intent
        assertEquals(Settings.ACTION_WIFI_SETTINGS, wifi.action)
        val details =
            ready(system(SystemIntentKind.SETTINGS, "page" to "app_details", "package" to "com.example.app")).intent
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertEquals("package:com.example.app", details.dataString)

        val developer = invalid(system(SystemIntentKind.SETTINGS, "page" to "developer"))
        assertTrue(developer.startsWith("settings page must be one of"))
        assertEquals(
            "settings page app_details needs a valid 'package'",
            invalid(system(SystemIntentKind.SETTINGS, "page" to "app_details", "package" to "not a package")),
        )
        assertEquals(
            "'package' is only used with page app_details",
            invalid(system(SystemIntentKind.SETTINGS, "page" to "wifi", "package" to "com.example.app")),
        )
    }

    @Test
    fun missingAndUnknownKeysNameTheExpectedOnes() {
        assertEquals(
            "calendar takes fields required [start, title], optional [end, location]; missing [start]; unknown [when]",
            invalid(system(SystemIntentKind.CALENDAR, "title" to "x", "when" to "now")),
        )
        assertEquals(
            "share takes fields required [text]; missing [text]",
            invalid(system(SystemIntentKind.SHARE)),
        )
        assertEquals(
            "intent fields are limited to 4000 characters",
            invalid(system(SystemIntentKind.SHARE, "text" to "x".repeat(4_001))),
        )
    }
}
