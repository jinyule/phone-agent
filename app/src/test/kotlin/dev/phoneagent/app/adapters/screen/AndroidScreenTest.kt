package dev.phoneagent.app.adapters.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidScreenTest {
    private val host = FakeHost()
    private val system = FakeSystem()
    private val analyzer = FakeAnalyzer()

    /** Device services without a device. */
    private class FakeSystem : ScreenSystem {
        override val ownPackage: String = OWN
        override val clipboard: ClipboardAccess =
            object : ClipboardAccess {
                override fun save(): SavedClip? = null

                override fun put(
                    text: String,
                    sensitive: Boolean,
                ) = error("not saved, so never put")

                override fun restore(saved: SavedClip) = error("not saved, so never restored")
            }
        var locked = false
        val activities = mutableSetOf<String>()
        val started = ArrayList<Pair<Intent, Int>>()
        var onStart: () -> StartResult = { StartResult.Started }

        override fun uptimeMs(): Long = 0

        override fun locked(): Boolean = locked

        override fun pickerPackages(): Set<String> = setOf("com.android.permissioncontroller")

        override fun isActivity(
            packageName: String,
            className: String,
        ): Boolean = "$packageName/$className" in activities

        override fun launchIntent(packageName: String): Intent? =
            if (packageName == "com.missing") null else Intent(Intent.ACTION_MAIN).setPackage(packageName)

        override fun resolvedPackage(intent: Intent): String? = intent.`package`

        override fun start(
            intent: Intent,
            displayId: Int,
        ): StartResult {
            started += intent to displayId
            return onStart()
        }
    }

    /** Fingerprint = names, values and bounds in order; counts diff calls. */
    private class FakeAnalyzer : SnapshotAnalyzer {
        var diffs = 0

        override fun fingerprint(
            pkg: String?,
            activity: String?,
            elements: List<UiElement>,
            ocr: List<OcrLine>?,
        ): String = "$pkg|" + elements.joinToString(";") { "${it.name}=${it.value}@${it.bounds}" }

        override fun quality(
            elements: List<UiElement>,
            surface: SurfaceRef,
            flags: Set<ScreenFlag>,
        ): TreeQuality = if (elements.isEmpty()) TreeQuality.EMPTY else TreeQuality.GOOD

        override fun diff(
            before: ScreenSnapshot,
            after: ScreenSnapshot,
        ): ScreenDiff {
            diffs++
            return ScreenDiff(added = after.elements.filter { a -> before.elements.none { it.name == a.name } })
        }
    }

    private fun TestScope.screen(
        displayId: Int = 0,
        connected: Boolean = true,
    ) = AndroidScreen(
        surface = SurfaceRef(displayId, if (displayId == 0) SurfaceKind.MAIN else SurfaceKind.AGENT),
        connection = { host.takeIf { connected } },
        system = system,
        analyzer = analyzer,
        options = AndroidScreen.Options(io = StandardTestDispatcher(testScheduler)),
    )

    private fun button(
        name: String,
        bounds: Rect,
        pkg: String = APP,
    ): AccessibilityNodeInfo =
        node("android.widget.Button", bounds, pkg) {
            text = name
            isClickable = true
        }

    private fun show(
        vararg nodes: AccessibilityNodeInfo,
        displayId: Int = 0,
        pkg: String = APP,
        extra: List<AccessibilityWindowInfo> = emptyList(),
    ) {
        val root = node("android.widget.FrameLayout", GEOMETRY.pixels, pkg).with(*nodes)
        host.windows = mapOf(displayId to listOf(window(root, id = 1, layer = 1, displayId = displayId)) + extra)
    }

    private fun ScreenSnapshot.ref(name: String): Target.Ref = Target.Ref(elements.single { it.name == name }.ref)

    private val allNodes = ArrayList<AccessibilityNodeInfo>()

    private fun tracked(node: AccessibilityNodeInfo) = node.also { allNodes += it }

    @Test
    fun snapshotsReadTheDisplayAndNumberTheScreens() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))
            host.windowStates =
                listOf(
                    A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, "android.app.Dialog"),
                    A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, "$APP.MainActivity"),
                )
            system.activities += "$APP/$APP.MainActivity"
            val screen = screen()

            val first = screen.snapshot()
            system.locked = true
            val second = screen.snapshot()

            assertEquals(SnapshotId("s1"), first.id)
            assertEquals(SnapshotId("s2"), second.id)
            assertEquals(APP, first.pkg)
            assertEquals("the dialog is not an activity", "$APP.MainActivity", first.activity)
            assertEquals(listOf("OK"), first.elements.map { it.name })
            assertEquals(TreeQuality.GOOD, first.quality)
            assertEquals(analyzer.fingerprint(APP, null, first.elements, null), first.fingerprint)
            assertNull(first.ocr)
            assertEquals(emptySet<ScreenFlag>(), first.flags)
            assertEquals(setOf(ScreenFlag.LOCKED), second.flags)
        }

    @Test
    fun keyboardsAndSystemPickersAreFlagged() =
        runTest {
            val keyboard = window(null, id = 9, layer = 9, type = AccessibilityWindowInfo.TYPE_INPUT_METHOD)
            val picker = "com.android.permissioncontroller"
            show(button("Allow", Rect(0, 0, 500, 100), picker), pkg = picker, extra = listOf(keyboard))

            assertEquals(setOf(ScreenFlag.KEYBOARD, ScreenFlag.SYSTEM_PICKER), screen().snapshot().flags)
        }

    @Test
    fun tappingARefClicksTheMatchedNodeAndReportsTheEffect() =
        runTest {
            val ok = tracked(button("OK", Rect(0, 1000, 500, 1100)))
            show(ok)
            val screen = screen()
            val planned = screen.snapshot()
            ok.log.answer = { _, _ ->
                show(button("OK", Rect(0, 1000, 500, 1100)), button("Done", Rect(0, 1200, 500, 1300)))
                true
            }

            val outcome = screen.act(UiAction.Tap(planned.ref("OK")), planned.id)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(ActionOutcome.Effect.CHANGED, outcome.effect)
            assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), ok.log.ids)
            assertEquals(listOf("OK", "Done"), outcome.after?.elements?.map { it.name })
            assertEquals(listOf("Done"), outcome.diff?.added?.map { it.name })
            assertEquals("the after-screen is registered", SnapshotId("s2"), outcome.after?.id)
            assertTrue(host.gestures.isEmpty())
            // No event came: the settle wait ran to its cap, in virtual time.
            assertEquals(ScreenTimings().settleCapMs, currentTime)
        }

    @Test
    fun elementsKeepTheirRefsFromOneScreenToTheNext() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)), button("Cancel", Rect(500, 1000, 1000, 1100)))
            val screen = screen()
            val first = screen.snapshot()
            // A banner appears on top and pushes the buttons down.
            show(
                button("Update available", Rect(0, 0, 1000, 100)),
                button("OK", Rect(0, 1100, 500, 1200)),
                button("Cancel", Rect(500, 1100, 1000, 1200)),
            )

            val second = screen.snapshot()

            assertEquals(first.ref("OK"), second.ref("OK"))
            assertEquals(first.ref("Cancel"), second.ref("Cancel"))
            val banner = second.ref("Update available").ref
            assertTrue("a new element gets a ref no earlier element had", first.elements.none { it.ref == banner })
            assertEquals(
                second.elements.size,
                second.elements
                    .map { it.ref }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun actionsWithoutAScreenIdAreComparedWithTheLatestScreen() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))
            val screen = screen()
            screen.snapshot()
            host.onGesture = { show(button("OK", Rect(0, 1000, 500, 1100)), button("Done", Rect(0, 1200, 500, 1300))) }

            val outcome = screen.act(UiAction.Tap(Target.Point(250, 525)), on = null)

            assertEquals(listOf("Done"), outcome.diff?.added?.map { it.name })
        }

    @Test
    fun withoutAnyEarlierScreenThereIsNoDiff() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))

            val outcome = screen().act(UiAction.Tap(Target.Point(250, 525)), on = null)

            assertNull(outcome.diff)
            assertEquals(0, analyzer.diffs)
        }

    @Test
    fun aTapWithoutVisibleChangeIsNoChange() =
        runTest {
            val ok = button("OK", Rect(0, 1000, 500, 1100))
            show(ok)
            val screen = screen()
            val planned = screen.snapshot()

            val outcome = screen.act(UiAction.Tap(planned.ref("OK")), planned.id)

            assertEquals(ActionOutcome.Effect.NO_CHANGE, outcome.effect)
        }

    @Test
    fun aRefThatNoLongerMatchesIsStaleAndTouchesNothing() =
        runTest {
            val ok = tracked(button("OK", Rect(0, 1000, 500, 1100)))
            show(ok)
            val screen = screen()
            val planned = screen.snapshot()
            // The layout shifted: same button, 10 grid cells lower.
            val moved = tracked(button("OK", Rect(0, 1020, 500, 1120)))
            show(moved)

            val outcome = screen.act(UiAction.Tap(planned.ref("OK")), planned.id)

            assertEquals(ActionOutcome.Status.StaleRef, outcome.status)
            assertTrue(allNodes.all { it.log.actions.isEmpty() })
            assertTrue(host.gestures.isEmpty())
            assertEquals(0, analyzer.diffs)
            assertEquals("answered without waiting", 0, currentTime)
        }

    @Test
    fun aForgottenScreenIsStaleAndARefWithoutScreenIsInvalid() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))
            val screen = screen()
            val planned = screen.snapshot()

            val forgotten = screen.act(UiAction.Tap(planned.ref("OK")), SnapshotId("s99"))
            assertEquals(ActionOutcome.Status.StaleRef, forgotten.status)
            assertEquals(
                ActionOutcome.Status.Failed("element @${planned.ref("OK").ref.value} needs the screen id it came from"),
                screen.act(UiAction.Tap(planned.ref("OK")), on = null).status,
            )
            assertEquals(
                ActionOutcome.Status.Failed("screen s1 has no element @e7"),
                screen.act(UiAction.Tap(Target.Ref(ElementRef("e7"))), planned.id).status,
            )
        }

    @Test
    fun onlyTheLastFewScreensResolveRefs() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))
            val screen = screen()
            val old = screen.snapshot()
            repeat(ScreenTimings().snapshotsKept) { screen.snapshot() }

            assertEquals(ActionOutcome.Status.StaleRef, screen.act(UiAction.Tap(old.ref("OK")), old.id).status)
        }

    @Test
    fun itNeverOperatesItsOwnScreens() =
        runTest {
            val own = tracked(button("Stop task", Rect(0, 1000, 500, 1100), pkg = OWN))
            show(own, pkg = OWN)
            val screen = screen()
            val planned = screen.snapshot()

            val byRef = screen.act(UiAction.Tap(planned.ref("Stop task")), planned.id)
            val byPoint = screen.act(UiAction.Tap(Target.Point(250, 525)), on = null)
            val typing = screen.act(UiAction.Type(planned.ref("Stop task"), "x"), planned.id)

            for (outcome in listOf(byRef, byPoint, typing)) {
                assertEquals(
                    ActionOutcome.Status.Refused("PhoneAgent does not operate its own screens"),
                    outcome.status,
                )
            }
            assertTrue(own.log.actions.isEmpty())
            assertTrue(host.gestures.isEmpty())
        }

    @Test
    fun pointsAreTappedWithAGestureOnTheSurfacesDisplay() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)), displayId = 5)
            host.sizes = mapOf(5 to Size(GEOMETRY.width, GEOMETRY.height))

            val outcome = screen(displayId = 5).act(UiAction.Tap(Target.Point(250, 525)), on = null)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            val (display, strokes) = host.gestures.single()
            assertEquals(5, display)
            assertEquals(listOf(PixelPoint(250.5f, 1051f)), strokes.single().points)
            assertEquals(5, outcome.after?.surface?.displayId)
        }

    @Test
    fun scrollingWithoutTargetUsesTheMainList() =
        runTest {
            val small = tracked(node("android.widget.ScrollView", Rect(0, 0, 1000, 200)) { isScrollable = true })
            val list =
                tracked(
                    node("androidx.recyclerview.widget.RecyclerView", Rect(0, 200, 1000, 1800)) {
                        isScrollable = true
                        addAction(AccessibilityAction.ACTION_SCROLL_DOWN)
                    },
                )
            show(small, list)

            val outcome = screen().act(UiAction.Scroll(null, Direction.DOWN), on = null)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(listOf(AccessibilityAction.ACTION_SCROLL_DOWN.id), list.log.ids)
            assertTrue(small.log.actions.isEmpty())
        }

    @Test
    fun backIsAGlobalActionOnTheMainDisplay() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))

            val outcome = screen().act(UiAction.Key(SystemKey.BACK), on = null)

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), host.globalActions)
        }

    @Test
    fun typingTriesEveryLevelAndSaysWhyNoneWorked() =
        runTest {
            val field =
                tracked(
                    node("android.widget.EditText", Rect(0, 200, 1000, 300)) {
                        isEditable = true
                        text = "old"
                        contentDescription = "Name"
                    },
                )
            show(field)
            val screen = screen()
            val planned = screen.snapshot()

            // The fake field never shows the new text, so every level is tried in order.
            val outcome = screen.act(UiAction.Type(planned.ref("Name"), "Ada"), planned.id)

            assertEquals(
                ActionOutcome.Status.Failed(
                    "the field did not accept the text (ime_connection: no input connection; " +
                        "paste: the clipboard cannot be restored afterwards)",
                ),
                outcome.status,
            )
            val (action, args) = field.log.actions.first()
            assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action)
            val sent = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
            assertEquals("Ada", sent.toString())
            assertTrue("focus before the input connection", AccessibilityNodeInfo.ACTION_FOCUS in field.log.ids)
        }

    @Test
    fun typingNeedsATextField() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))
            val screen = screen()
            val planned = screen.snapshot()

            assertEquals(
                ActionOutcome.Status.Failed("that element is not a text field; tap it first"),
                screen.act(UiAction.Type(planned.ref("OK"), "x"), planned.id).status,
            )
            assertEquals(
                ActionOutcome.Status.Failed("there is no text field at that point"),
                screen.act(UiAction.Type(Target.Point(250, 525), "x"), on = null).status,
            )
        }

    @Test
    fun wheelsAreNotSupportedYet() =
        runTest {
            show(button("OK", Rect(0, 1000, 500, 1100)))

            val outcome = screen().act(UiAction.SetWheel(Target.Point(1, 1), "7"), on = null)

            assertEquals(ActionOutcome.Status.Failed("set_wheel is not supported on this screen yet"), outcome.status)
        }

    @Test
    fun aDisconnectedServiceOrMissingDisplayMakesTheScreenUnavailable() =
        runTest {
            val offline = failureOf { screen(connected = false).snapshot() }
            assertEquals("the accessibility service is not connected", (offline as ScreenUnavailableException).message)

            val gone = screen(displayId = 8)
            val error = failureOf { gone.act(UiAction.Key(SystemKey.HOME), on = null) }
            assertEquals("display 8 does not exist", (error as ScreenUnavailableException).message)
        }

    @Test
    fun launchStartsTheAppOnTheSurfacesDisplayAndWaitsForIt() =
        runTest {
            show(button("Home", Rect(0, 0, 500, 100)), pkg = "com.launcher")
            host.sizes = mapOf(0 to Size(GEOMETRY.width, GEOMETRY.height))
            system.onStart = {
                show(button("Inbox", Rect(0, 0, 500, 100), "com.mail"), pkg = "com.mail")
                host.flow.tryEmit(
                    A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.mail", "com.mail.Main"),
                )
                StartResult.Started
            }

            val outcome = screen().launch(LaunchTarget.App("com.mail"))

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(ActionOutcome.Effect.NEW_APP, outcome.effect)
            assertEquals("com.mail", outcome.after?.pkg)
            assertEquals(0, system.started.single().second)
            assertEquals(
                "com.mail",
                system.started
                    .single()
                    .first.`package`,
            )
        }

    @Test
    fun aLaunchIsComparedWithTheLatestScreen() =
        runTest {
            show(button("Home", Rect(0, 0, 500, 100)), pkg = "com.launcher")
            val screen = screen()
            screen.snapshot()
            system.onStart = {
                show(
                    button("Home", Rect(0, 0, 500, 100)),
                    button("Widget", Rect(0, 200, 500, 300)),
                    pkg = "com.launcher",
                )
                StartResult.Started
            }

            val outcome = screen.launch(LaunchTarget.Link("https://example.com"))

            assertEquals(listOf("Widget"), outcome.diff?.added?.map { it.name })
        }

    @Test
    fun anAppThatDoesNotComeToTheFrontIsAFailedLaunch() =
        runTest {
            show(button("Home", Rect(0, 0, 500, 100)), pkg = "com.launcher")

            val outcome = screen().launch(LaunchTarget.App("com.mail"))

            assertEquals(
                ActionOutcome.Status.Failed("com.mail did not come to the front within 5000 ms"),
                outcome.status,
            )
        }

    @Test
    fun launchRefusesItsOwnAppAndReportsStartFailures() =
        runTest {
            show(button("Home", Rect(0, 0, 500, 100)), pkg = "com.launcher")
            val screen = screen()

            assertEquals(
                ActionOutcome.Status.Refused("PhoneAgent does not operate its own screens"),
                screen.launch(LaunchTarget.App(OWN)).status,
            )
            assertTrue(system.started.isEmpty())
            assertEquals(
                ActionOutcome.Status.Failed(
                    "links with scheme 'intent' are not opened: it can start any component of any app; " +
                        "allowed: [geo, http, https, mailto, market]",
                ),
                screen.launch(LaunchTarget.Link("intent:#Intent;end")).status,
            )
            system.onStart = { StartResult.NotFound }
            assertEquals(
                ActionOutcome.Status.Failed("no app on this device opens it"),
                screen.launch(LaunchTarget.Link("https://example.com")).status,
            )
            system.onStart = { StartResult.Denied("background start blocked") }
            assertEquals(
                ActionOutcome.Status.Failed("the system refused to open it: background start blocked"),
                screen.launch(LaunchTarget.Link("https://example.com")).status,
            )
        }

    @Test
    fun framesAreScaledJpegsOrNullForSecureWindows() =
        runTest {
            val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
            host.shot = Screenshot.Captured(bitmap)
            val screen = screen()

            val frame = screen.frame(maxEdge = 400)!!

            assertEquals(200 to 400, frame.width to frame.height)
            assertTrue("the port owns the screenshot", bitmap.isRecycled)

            host.shot = Screenshot.Secure
            assertNull(screen.frame())
            host.shot = Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
            assertNull(screen.frame())
            host.shot = Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY)
            assertEquals(ScreenUnavailableException::class, failureOf { screen.frame() }!!::class)
            assertEquals(IllegalArgumentException::class, failureOf { screen.frame(0) }!!::class)
        }

    @Test
    fun awaitChangeIsDrivenByEventsOfItsDisplay() =
        runTest {
            val screen = screen()

            assertEquals(false, screen.awaitChange(1_000))
            assertEquals(1_000, currentTime)

            backgroundScope.launchEmit(host, delayMs = 100)
            assertEquals(true, screen.awaitChange(5_000))
            assertEquals(1_000 + 100 + ScreenTimings().quietMs, currentTime)
        }

    @Test
    fun snapshotOptionsChooseTheDetail() =
        runTest {
            val inner = node("android.widget.LinearLayout", Rect(0, 0, 1000, 500))
            show(inner.with(node("android.view.View", Rect(0, 0, 10, 10))))

            val brief = screen().snapshot(SnapshotOptions())
            val full = screen().snapshot(SnapshotOptions(detail = SnapshotDetail.FULL))

            assertEquals(0, brief.elements.size)
            assertEquals(3, full.elements.size)
        }
}

private fun CoroutineScope.launchEmit(
    host: FakeHost,
    delayMs: Long,
) = launch {
    delay(delayMs)
    host.flow.emit(A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null))
}

/** The exception [block] fails with, or `null`; only the two failures the port documents are caught. */
private suspend fun failureOf(block: suspend () -> Unit): Exception? =
    try {
        block()
        null
    } catch (expected: ScreenUnavailableException) {
        expected
    } catch (expected: IllegalArgumentException) {
        expected
    }
