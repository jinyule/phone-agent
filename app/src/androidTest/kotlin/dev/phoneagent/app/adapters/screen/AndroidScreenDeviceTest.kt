package dev.phoneagent.app.adapters.screen

import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.phoneagent.app.debug.TemporarySnapshotAnalyzer
import dev.phoneagent.app.service.AgentAccessibility
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The accessibility ScreenPort against the real Settings app on the main display.
 *
 * Needs the service enabled first (it runs in the app process, so it is bound again after
 * `am instrument` restarts the process):
 * ```
 * adb -s <device> shell settings put secure enabled_accessibility_services \
 *     dev.phoneagent/dev.phoneagent.app.service.AgentAccessibilityService
 * adb -s <device> shell am instrument -w -e class dev.phoneagent.app.adapters.screen.AndroidScreenDeviceTest \
 *     dev.phoneagent.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Results are logged under tag `WP07`; the frame is written to the app's files dir as `wp07-frame.jpg`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidScreenDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val system = AndroidScreenSystem(context)
    private val screen =
        AndroidScreen(
            surface = SurfaceRef(Display.DEFAULT_DISPLAY, SurfaceKind.MAIN),
            connection = { AgentAccessibility.host.value },
            system = system,
            analyzer = TemporarySnapshotAnalyzer,
        )

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun describe(snapshot: ScreenSnapshot?): String =
        if (snapshot == null) {
            "no snapshot"
        } else {
            "screen ${snapshot.id} pkg=${snapshot.pkg} activity=${snapshot.activity} quality=${snapshot.quality} " +
                "flags=${snapshot.flags} elements=${snapshot.elements.size} fingerprint=${snapshot.fingerprint}"
        }

    private fun ActionOutcome.describe(): String =
        "status=$status effect=$effect diff=${diff?.let {
            "+${it.added.size} -${it.removed.size} ~${it.changed.size}"
        }} after: ${describe(
            after,
        )}"

    private fun ScreenSnapshot.find(predicate: (UiElement) -> Boolean): UiElement =
        elements.firstOrNull(predicate)
            ?: error("no matching element on ${describe(this)}: ${elements.map { it.name }}")

    private suspend fun openSettings(): ScreenSnapshot {
        val launched = screen.launch(LaunchTarget.App(SETTINGS, cold = true))
        log("launch settings cold: ${launched.describe()}")
        assertEquals(ActionOutcome.Status.Ok, launched.status)
        assertEquals(SETTINGS, launched.after?.pkg)
        return screen.snapshot()
    }

    @Test
    fun readsTheSettingsScreen() =
        runBlocking<Unit> {
            val snapshot = openSettings()

            log("snapshot: ${describe(snapshot)}")
            snapshot.elements.take(LOGGED_ELEMENTS).forEach { log("  $it") }
            assertEquals(SETTINGS, snapshot.pkg)
            assertEquals(TreeQuality.GOOD, snapshot.quality)
            assertTrue("activity ${snapshot.activity}", snapshot.activity?.startsWith("com.android.settings") == true)
            val row = snapshot.find { it.name?.startsWith("Network & internet") == true }
            assertEquals(ElementRole.BUTTON, row.role)
            assertTrue(row.bounds.left == 0 && row.bounds.right == 999)
            assertTrue(snapshot.elements.any { it.role == ElementRole.LIST })
            assertTrue(system.isActivity(SETTINGS, "com.android.settings.Settings"))
            assertTrue(!system.isActivity(SETTINGS, "android.app.Dialog"))
        }

    @Test
    fun tapsScrollsAndGoesBack() =
        runBlocking<Unit> {
            val home = openSettings()
            val row = home.find { it.name?.startsWith("Network & internet") == true }

            val tapped = screen.act(UiAction.Tap(Target.Ref(row.ref)), home.id)
            log("tap @${row.ref} '${row.name}': ${tapped.describe()}")
            assertEquals(ActionOutcome.Status.Ok, tapped.status)
            assertEquals(ActionOutcome.Effect.CHANGED, tapped.effect)
            assertTrue(tapped.after!!.elements.none { it.name?.startsWith("Network & internet Mobile") == true })

            val back = screen.act(UiAction.Key(SystemKey.BACK), on = null)
            log("back: ${back.describe()}")
            assertEquals(ActionOutcome.Status.Ok, back.status)
            val again = screen.snapshot()
            val rowAgain = again.find { it.name?.startsWith("Network & internet") == true }

            val scrolled = screen.act(UiAction.Scroll(null, Direction.DOWN), on = null)
            log("scroll down: ${scrolled.describe()}")
            assertEquals(ActionOutcome.Status.Ok, scrolled.status)
            assertEquals(ActionOutcome.Effect.CHANGED, scrolled.effect)
            assertNotNull("diffed against the screen read before", scrolled.diff)
            val kept =
                scrolled.after!!.elements.filter { moved ->
                    moved.name != null && again.elements.any { it.ref == moved.ref && it.name == moved.name }
                }
            log("refs kept across the scroll: ${kept.map { "@${it.ref.value} ${it.name}" }}")
            assertTrue("rows still on screen keep their refs", kept.isNotEmpty())

            // The row planned on the screen before scrolling has moved: refused without touching the device.
            val stale = screen.act(UiAction.Tap(Target.Ref(rowAgain.ref)), again.id)
            log("tap @${rowAgain.ref} planned on ${again.id} after scrolling: ${stale.describe()}")
            assertEquals(ActionOutcome.Status.StaleRef, stale.status)
        }

    @Test
    fun typesIntoTheSettingsSearch() =
        runBlocking<Unit> {
            val home = openSettings()
            val search = home.find { it.resourceId == "search_action_bar" }

            val opened = screen.act(UiAction.Tap(Target.Ref(search.ref)), home.id)
            log("tap search: ${opened.describe()}")
            assertEquals(ActionOutcome.Status.Ok, opened.status)
            // The search screen belongs to another package; QUERY_ALL_PACKAGES makes its activity visible.
            assertNotNull("activity of ${opened.after?.pkg}", opened.after?.activity)
            opened.after
                ?.elements
                ?.filter { it.role == ElementRole.INPUT }
                ?.forEach { log("  field $it") }

            val typed = screen.act(UiAction.Type(null, "wifi"), on = null)
            log("type 'wifi' into the focused field: ${typed.describe()}")
            assertEquals(ActionOutcome.Status.Ok, typed.status)
            val field = typed.after!!.find { it.role == ElementRole.INPUT }
            log("  field after: $field")
            assertEquals("wifi", field.value)

            val appended = screen.act(UiAction.Type(Target.Ref(field.ref), " hotspot", append = true), typed.after!!.id)
            log("append ' hotspot': ${appended.describe()}")
            assertEquals(ActionOutcome.Status.Ok, appended.status)
            assertEquals("wifi hotspot", appended.after!!.find { it.role == ElementRole.INPUT }.value)

            val submitted = screen.act(UiAction.Type(null, "battery", submit = true), on = null)
            log("type 'battery' and submit: ${submitted.describe()}")
            screen.act(UiAction.Key(SystemKey.BACK), on = null)
        }

    @Test
    fun theInputConnectionLevelTypesWithoutSetText() =
        runBlocking<Unit> {
            val home = openSettings()
            screen.act(UiAction.Tap(Target.Ref(home.find { it.resourceId == "search_action_bar" }.ref)), home.id)
            val host = AgentAccessibility.host.value!!
            val field = Aims.focusedEditable(host.windowsOn(Display.DEFAULT_DISPLAY))
            assertNotNull("the search field has input focus", field)
            field!!
            val request = TextEntry.Request("bluetooth", append = false, before = NodeText.fieldText(field))

            val sent = EntryLevels.imeConnection(field, host) { error("already focused") }.send(request)
            screen.awaitChange(CHANGE_TIMEOUT_MS)
            val shown = TextEntry.read(field)
            log("input connection level: sent=$sent editor=${host.imeConnection()?.editorPackage} shown=$shown")
            assertEquals(TextEntry.Sent.Delivered, sent)
            assertEquals(TextEntry.Shown.Text("bluetooth"), shown)

            val manager = context.getSystemService(ClipboardManager::class.java)
            val paste = EntryLevels.paste(field, AndroidClipboard(manager)) { error("not reached") }.send(request)
            log("paste level from the background: $paste")
            screen.act(UiAction.Key(SystemKey.BACK), on = null)
        }

    @Test
    fun capturesAFrameAndWaitsForChanges() =
        runBlocking<Unit> {
            openSettings()

            val frame = screen.frame()
            assertNotNull(frame)
            frame!!
            val file = File(context.filesDir, "wp07-frame.jpg")
            file.writeBytes(frame.bytes)
            log("frame ${frame.width}x${frame.height} ${frame.mime} ${frame.bytes.size} bytes -> ${file.absolutePath}")
            assertEquals("image/jpeg", frame.mime)
            assertEquals(1280, maxOf(frame.width, frame.height))

            val quietStart = System.nanoTime()
            val quiet = screen.awaitChange(QUIET_PROBE_MS)
            log("awaitChange on an idle screen: $quiet after ${(System.nanoTime() - quietStart) / NANOS_PER_MS} ms")

            val change = async(start = CoroutineStart.UNDISPATCHED) { screen.awaitChange(CHANGE_TIMEOUT_MS) }
            val start = System.nanoTime()
            context.startActivity(
                Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val changed = change.await()
            log(
                "awaitChange around opening display settings: $changed after " +
                    "${(System.nanoTime() - start) / NANOS_PER_MS} ms",
            )
            assertTrue(changed)
        }

    @Test
    fun launchesIntentsAndRefusesItsOwnUi() =
        runBlocking<Unit> {
            val display =
                screen.launch(
                    LaunchTarget.SystemIntent(SystemIntentKind.SETTINGS, mapOf("page" to "display")),
                )
            log("intent settings/display: ${display.describe()}")
            assertEquals(ActionOutcome.Status.Ok, display.status)
            assertEquals(SETTINGS, display.after?.pkg)

            val smuggled =
                screen.launch(
                    LaunchTarget.Link("intent:#Intent;component=com.android.settings/.Settings;end"),
                )
            log("intent: link: ${smuggled.describe()}")
            assertEquals(
                ActionOutcome.Status.Failed(
                    "links with scheme 'intent' are not opened: it can start any component of any app; " +
                        "allowed: [geo, http, https, mailto, market]",
                ),
                smuggled.status,
            )

            val missing = screen.launch(LaunchTarget.App("com.example.notinstalled"))
            log("missing app: ${missing.describe()}")
            assertTrue(missing.status is ActionOutcome.Status.Failed)

            val own = screen.launch(LaunchTarget.App(context.packageName))
            log("own app: ${own.describe()}")
            assertTrue(own.status is ActionOutcome.Status.Refused)

            val opened = async(start = CoroutineStart.UNDISPATCHED) { screen.awaitChange(CHANGE_TIMEOUT_MS) }
            context.startActivity(
                Intent(context, dev.phoneagent.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            opened.await()
            val ownScreen = screen.snapshot()
            log("own screen: ${describe(ownScreen)}")
            val tap = screen.act(UiAction.Tap(Target.Point(500, 500)), on = null)
            log("tap on own screen: ${tap.describe()}")
            assertTrue(tap.status is ActionOutcome.Status.Refused)
            val home = screen.act(UiAction.Key(SystemKey.HOME), on = null)
            log("HOME on own screen: ${home.describe()}")
            assertTrue(home.status is ActionOutcome.Status.Refused)
            shell("input keyevent KEYCODE_HOME")
        }

    @Test
    fun listsTheAppsOfTheUser() =
        runBlocking<Unit> {
            val apps = AndroidAppCatalog(context).apps()

            log("app catalog: ${apps.size} apps, ${apps.count { it.launchable }} launchable")
            apps.take(LOGGED_ELEMENTS).forEach { log("  $it") }
            assertTrue(apps.any { it.packageName == SETTINGS && it.launchable })
            assertTrue("apps without a launcher entry are listed too", apps.any { !it.launchable })
            assertTrue(apps.none { it.packageName == context.packageName })
        }

    @Test
    fun reportsWhetherTheClipboardIsReadable() {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val saved = AndroidClipboard(manager).save()
        log("clipboard readable from the app process while Settings is in front: ${saved != null}")
    }

    companion object {
        private const val TAG = "WP07"
        private const val SETTINGS = "com.android.settings"
        private const val LOGGED_ELEMENTS = 40
        private const val QUIET_PROBE_MS = 1_000L
        private const val CHANGE_TIMEOUT_MS = 5_000L
        private const val NANOS_PER_MS = 1_000_000
        private const val CONNECT_TIMEOUT_MS = 20_000L

        private const val SERVICE = "dev.phoneagent/dev.phoneagent.app.service.AgentAccessibilityService"

        /**
         * `am instrument` force-stops the app, which the system records as a crash of its
         * accessibility service and does not rebind. Turning the service off and on again binds it
         * in this (instrumented) process. The UiAutomation must not suppress accessibility services.
         */
        @JvmStatic
        @BeforeClass
        fun connectTheService() {
            if (AgentAccessibility.host.value == null) {
                shell("settings delete secure enabled_accessibility_services")
                shell("settings put secure enabled_accessibility_services $SERVICE")
            }
            runBlocking { withTimeout(CONNECT_TIMEOUT_MS) { AgentAccessibility.host.first { it != null } } }
        }

        /** Leaves the device on the launcher; the port refuses keys on this app's own screens. */
        @JvmStatic
        @AfterClass
        fun goHome() {
            shell("input keyevent KEYCODE_HOME")
        }

        /** Runs [command] as the shell user; returns its output. Keeps accessibility services running. */
        fun shell(command: String): String {
            val automation =
                InstrumentationRegistry
                    .getInstrumentation()
                    .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            return ParcelFileDescriptor
                .AutoCloseInputStream(automation.executeShellCommand(command))
                .use { it.readBytes().decodeToString() }
        }
    }
}
