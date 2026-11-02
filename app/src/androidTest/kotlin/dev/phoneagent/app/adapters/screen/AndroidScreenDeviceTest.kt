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
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenFlag
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The accessibility ScreenPort against the real Settings app on the main display.
 *
 * Run with both APKs installed:
 * ```
 * adb -s <device> shell am instrument -w -e class dev.phoneagent.app.adapters.screen.AndroidScreenDeviceTest \
 *     dev.phoneagent.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * The test turns on this app's accessibility service itself and touches no other: it reads
 * `enabled_accessibility_services`, takes only this app's component out and puts it back (which
 * binds it in the instrumented process), and afterwards restores the value it read, also when
 * connecting fails. Check with `adb -s <device> shell settings get secure enabled_accessibility_services`.
 *
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

            // A short drag moves the rows without paging them all away (Scroll pages a whole screen).
            val dragged = screen.act(UiAction.Swipe(Target.Point(500, 800), Target.Point(500, 600), DRAG_MS), again.id)
            log("drag up by a fifth: ${dragged.describe()}")
            assertEquals(ActionOutcome.Status.Ok, dragged.status)
            assertEquals(ActionOutcome.Effect.CHANGED, dragged.effect)
            val bars = systemBarWindows()
            log("status and navigation bar windows: $bars")
            assertTrue("the device has a status bar", bars.isNotEmpty())
            assertTrue(
                "the bars are not part of the screen",
                (again.elements + dragged.after!!.elements).none { it.window in bars },
            )
            // Checked on the JVM too, where they fail without carried refs (ScrollChecksMirrorTest).
            val moved = movedRows(again, dragged.after!!)
            log(
                "rows the drag moved: ${moved.map { (was, now) ->
                    "${was.name}: @${was.ref.value} top ${was.bounds.top} -> @${now.ref.value} top ${now.bounds.top}"
                }}",
            )
            assertTrue("some named row stayed on screen and moved", moved.isNotEmpty())
            moved.forEach { (was, now) -> assertEquals("ref of '${was.name}'", was.ref, now.ref) }

            val scrolled = screen.act(UiAction.Scroll(null, Direction.DOWN), on = null)
            log("scroll down: ${scrolled.describe()}")
            assertEquals(ActionOutcome.Status.Ok, scrolled.status)
            assertEquals(ActionOutcome.Effect.CHANGED, scrolled.effect)
            assertNotNull("diffed against the screen read before", scrolled.diff)
            val vanished = vanishedRefs(dragged.after!!, scrolled.after!!)
            log("refs of rows scrolled away: ${vanished.map { it.value }}")
            assertTrue("some named row scrolled away", vanished.isNotEmpty())
            assertTrue("no ref of a row scrolled away is reused", scrolled.after!!.elements.none { it.ref in vanished })
            assertTrue("the bars are not part of the screen", scrolled.after!!.elements.none { it.window in bars })

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
            assertEquals(ActionOutcome.Status.Ok, submitted.status)
            assertEquals("battery", submitted.after!!.find { it.role == ElementRole.INPUT }.value)

            // Submitting hid the keyboard, and a field without it has no editor action: tap it first.
            val searchField = submitted.after!!.find { it.role == ElementRole.INPUT }
            val refocused = screen.act(UiAction.Tap(Target.Ref(searchField.ref)), submitted.after!!.id)
            log("tap the search field again: ${refocused.describe()}")
            assertEquals(ActionOutcome.Status.Ok, refocused.status)
            val retyped = screen.act(UiAction.Type(null, "display"), on = null)
            log("type 'display' into the focused field: ${retyped.describe()}")
            assertEquals(ActionOutcome.Status.Ok, retyped.status)
            // An explicit ENTER; Ok means the editor action was sent (the search then hides the keyboard).
            val entered = screen.act(UiAction.Key(SystemKey.ENTER), on = null)
            log("ENTER: ${entered.describe()}")
            assertEquals(ActionOutcome.Status.Ok, entered.status)
            assertEquals("display", entered.after!!.find { it.role == ElementRole.INPUT }.value)
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
            assertTrue("Settings is not secure", ScreenFlag.SECURE !in screen.snapshot().flags)

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

    /** Ids of the status and navigation bar windows on the main display. */
    private fun systemBarWindows(): Set<Int> {
        val host = AgentAccessibility.host.value!!
        val size = host.displaySize(Display.DEFAULT_DISPLAY)!!
        return host
            .windowsOn(Display.DEFAULT_DISPLAY)
            .filter { ScreenReader.isSystemBar(it, DisplayGeometry(size.width, size.height)) }
            .map { it.id }
            .toSet()
    }

    /**
     * Named elements on both screens, unique by name and role on each, with the same height (not
     * cut by the list's edge) and another top: the rows the scroll moved, before and after.
     */
    private fun movedRows(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): List<Pair<UiElement, UiElement>> {
        fun ScreenSnapshot.unique(): Map<Pair<String, ElementRole>, UiElement> =
            elements
                .filter { it.name != null }
                .groupBy { it.name!! to it.role }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
        val now = after.unique()
        return before.unique().mapNotNull { (key, was) ->
            now[key]
                ?.takeIf {
                    it.bounds.top != was.bounds.top &&
                        it.bounds.bottom - it.bounds.top == was.bounds.bottom - was.bounds.top
                }?.let { was to it }
        }
    }

    /** Refs of the named elements of [before] whose name is nowhere on [after]. */
    private fun vanishedRefs(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): Set<ElementRef> =
        before.elements
            .filter { it.name != null && after.elements.none { now -> now.name == it.name } }
            .map { it.ref }
            .toSet()

    /** The latest screen once [pkg] is in front; waits on changes, not on time. */
    private suspend fun inFront(pkg: String): ScreenSnapshot =
        withTimeout(CHANGE_TIMEOUT_MS) {
            var shown = screen.snapshot()
            while (shown.pkg != pkg) {
                screen.awaitChange(CHANGE_TIMEOUT_MS)
                shown = screen.snapshot()
            }
            shown
        }

    @Test
    fun aSecurePageGivesNoFrameAndItsSnapshotsSaySecure() =
        runBlocking<Unit> {
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            context.startActivity(
                Intent()
                    .setClassName(testPackage, SecureActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val before = inFront(testPackage)
            log("secure page before a frame: ${describe(before)}")
            assertTrue(before.elements.any { it.name == SecureActivity.LABEL })
            assertTrue("not known before a frame is asked for", ScreenFlag.SECURE !in before.flags)

            val frame = screen.frame()
            val after = screen.snapshot()
            log("secure page: frame=${frame?.let { "${it.width}x${it.height}" }} after: ${describe(after)}")
            assertNull(frame)
            assertTrue(ScreenFlag.SECURE in after.flags)

            val back = screen.act(UiAction.Key(SystemKey.BACK), on = null)
            log("back from the secure page: ${back.describe()}")
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
        private const val DRAG_MS = 1_000L

        private const val SERVICE = "dev.phoneagent/dev.phoneagent.app.service.AgentAccessibilityService"
        private const val ENABLED = "enabled_accessibility_services"

        /** The enabled services as found before the run (`null`: the setting was unset); restored afterwards. */
        private var found: String? = null
        private var toRestore = false

        /**
         * `am instrument` force-stops the app, which the system records as a crash of its
         * accessibility service and does not rebind. Taking only this app's service out and putting
         * it back binds it in this (instrumented) process; other services keep their place. The
         * UiAutomation must not suppress accessibility services.
         */
        @JvmStatic
        @BeforeClass
        fun connectTheService() {
            found = shell("settings get secure $ENABLED").trim().takeUnless { it.isEmpty() || it == "null" }
            toRestore = true
            log("enabled accessibility services before the run: $found")
            var connected = false
            try {
                if (AgentAccessibility.host.value == null) {
                    val others = found?.split(':')?.filter { it.isNotBlank() && it != SERVICE }.orEmpty()
                    putServices(others)
                    putServices(others + SERVICE)
                }
                runBlocking { withTimeout(CONNECT_TIMEOUT_MS) { AgentAccessibility.host.first { it != null } } }
                connected = true
            } finally {
                if (!connected) restoreServices()
            }
        }

        /**
         * Leaves the device on the launcher (the port refuses keys on this app's own screens) and
         * the enabled services as they were found.
         */
        @JvmStatic
        @AfterClass
        fun leaveTheDeviceAsFound() {
            shell("input keyevent KEYCODE_HOME")
            restoreServices()
        }

        private fun putServices(services: List<String>) {
            if (services.isEmpty()) {
                shell("settings delete secure $ENABLED")
            } else {
                shell("settings put secure $ENABLED ${services.joinToString(":")}")
            }
        }

        private fun restoreServices() {
            if (!toRestore) return
            toRestore = false
            val value = found
            if (value ==
                null
            ) {
                shell("settings delete secure $ENABLED")
            } else {
                shell("settings put secure $ENABLED $value")
            }
            log("enabled accessibility services restored to: ${shell("settings get secure $ENABLED").trim()}")
        }

        private fun log(message: String) {
            Log.i(TAG, message)
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
