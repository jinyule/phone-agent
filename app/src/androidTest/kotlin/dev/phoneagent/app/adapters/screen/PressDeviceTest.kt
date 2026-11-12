package dev.phoneagent.app.adapters.screen

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.phoneagent.app.debug.TemporarySnapshotAnalyzer
import dev.phoneagent.app.service.AgentAccessibility
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presses by ref on [PressActivity] on the main display (fix list fwd S1): which element a press
 * goes to through a wrapper, at both details, short and long; that a press the screen gives to
 * another element since the plan is stale (MOVED) and touches nothing; and that a press the
 * element refuses is not made up for with a touch. Every count of the page is read back once the
 * page is at rest, so a click or touch made late would show.
 *
 * Run as [AndroidScreenDeviceTest] says, with `-e class dev.phoneagent.app.adapters.screen.PressDeviceTest`;
 * it connects and restores the accessibility service the same way. Results are logged under tag
 * `WP07`. Each table runs every cell and then fails with all the cells that went wrong.
 */
@RunWith(AndroidJUnit4::class)
class PressDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    private val screen =
        AndroidScreen(
            surface = SurfaceRef(Display.DEFAULT_DISPLAY, SurfaceKind.MAIN),
            connection = { AgentAccessibility.host.value },
            system = AndroidScreenSystem(context),
            analyzer = TemporarySnapshotAnalyzer,
        )

    /** The counts of the page: parent clicks, parent long clicks, child clicks, child long clicks. */
    private data class Counts(
        val parentClicks: Int,
        val parentLongClicks: Int,
        val childClicks: Int,
        val childLongClicks: Int,
    ) {
        companion object {
            val NONE = Counts(0, 0, 0, 0)

            /** One click (long click when [long]) of the parent, or of the child when [child]. */
            fun one(
                child: Boolean,
                long: Boolean,
            ) = Counts(
                parentClicks = if (!child && !long) 1 else 0,
                parentLongClicks = if (!child && long) 1 else 0,
                childClicks = if (child && !long) 1 else 0,
                childLongClicks = if (child && long) 1 else 0,
            )
        }
    }

    private class Cell(
        val detail: SnapshotDetail,
        val long: Boolean,
    ) {
        override fun toString(): String = "$detail long=$long"
    }

    private val cells = SnapshotDetail.entries.flatMap { detail -> listOf(false, true).map { Cell(detail, it) } }

    private fun ScreenSnapshot.count(viewId: String): Int =
        checkNotNull(elements.singleOrNull { it.resourceId == viewId }?.name?.toIntOrNull()) {
            "no count $viewId on screen $id: ${elements.map { "${it.resourceId}=${it.name}" }}"
        }

    private fun ScreenSnapshot.counts() =
        Counts(count("parent_clicks"), count("parent_long_clicks"), count("child_clicks"), count("child_long_clicks"))

    private fun ScreenSnapshot.label() = elements.single { it.resourceId == "press_label" }

    /** Opens the page anew (all counts 0) with [extras], and its first snapshot at [detail] once it is in front. */
    private suspend fun open(
        detail: SnapshotDetail,
        vararg extras: Pair<String, Boolean>,
    ): ScreenSnapshot {
        val intent =
            Intent()
                .setClassName(testPackage, PressActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        context.startActivity(intent)
        return until(detail) { it.pkg == testPackage && it.elements.any { e -> e.resourceId == "press_label" } }
    }

    /** The first snapshot at [detail] for which [done] holds, reading again on each change. */
    private suspend fun until(
        detail: SnapshotDetail,
        done: (ScreenSnapshot) -> Boolean,
    ): ScreenSnapshot =
        withTimeout(CHANGE_TIMEOUT_MS) {
            var shown = screen.snapshot(SnapshotOptions(detail = detail))
            while (!done(shown)) {
                screen.awaitChange(CHANGE_TIMEOUT_MS)
                shown = screen.snapshot(SnapshotOptions(detail = detail))
            }
            shown
        }

    /**
     * Waits for the child to show enabled in the accessibility tree, reading the tree itself: a
     * snapshot taken meanwhile could push the planned one out of the port's history.
     */
    private suspend fun childEnables() =
        withTimeout(CHANGE_TIMEOUT_MS) {
            val id = "$testPackage:id/press_child"
            val enabled = {
                checkNotNull(AgentAccessibility.host.value).windowsOn(Display.DEFAULT_DISPLAY).any { window ->
                    window.root?.findAccessibilityNodeInfosByViewId(id)?.any { it.isEnabled } == true
                }
            }
            while (!enabled()) screen.awaitChange(CHANGE_TIMEOUT_MS)
        }

    /** The counts once the page shows no change for [REST_MS]. */
    private suspend fun countsAtRest(): Counts =
        withTimeout(CHANGE_TIMEOUT_MS) {
            while (screen.awaitChange(REST_MS)) {
                log("  waiting for the page to rest")
            }
            screen.snapshot(SnapshotOptions(detail = SnapshotDetail.FULL)).counts()
        }

    /** Taps the label planned on [planned] in [cell]; logs and returns the outcome and the counts at rest. */
    private suspend fun press(
        cell: Cell,
        planned: ScreenSnapshot,
        what: String,
    ): Pair<ActionOutcome, Counts> {
        val label = planned.label()
        val outcome = screen.act(UiAction.Tap(Target.Ref(label.ref), cell.long), planned.id)
        val counts = countsAtRest()
        log("$what $cell: tap @${label.ref} on ${planned.id}: ${outcome.status} ${outcome.effect} $counts")
        return outcome to counts
    }

    @Test
    fun aPressGoesToTheNearestAncestorTakingItThroughAWrapper() =
        runBlocking<Unit> {
            val failures = ArrayList<String>()
            for (childEnabled in listOf(true, false)) {
                for (cell in cells) {
                    val planned = open(cell.detail, PressActivity.CHILD_ENABLED to childEnabled)
                    val (outcome, counts) = press(cell, planned, "child enabled=$childEnabled")
                    val expected = Counts.one(child = childEnabled, long = cell.long)
                    if (outcome.status !=
                        ActionOutcome.Status.Ok
                    ) {
                        failures += "$cell child=$childEnabled: ${outcome.status}"
                    }
                    if (counts != expected) failures += "$cell child=$childEnabled: $counts, expected $expected"
                }
            }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aPressTheScreenGivesToTheChildSinceThePlanIsStaleAndTouchesNothing() =
        runBlocking<Unit> {
            val failures = ArrayList<String>()
            for (cell in cells) {
                val planned = open(cell.detail, PressActivity.CHILD_ENABLED to false)
                context.startActivity(
                    Intent()
                        .setClassName(testPackage, PressActivity::class.java.name)
                        .putExtra(PressActivity.ENABLE_CHILD, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                childEnables()
                val (outcome, counts) = press(cell, planned, "child enabled since the plan")
                if (outcome.status !=
                    ActionOutcome.Status.StaleRef(StaleReason.MOVED)
                ) {
                    failures += "$cell: ${outcome.status}"
                }
                if (counts != Counts.NONE) failures += "$cell: $counts"
            }

            assertEquals(emptyList<String>(), failures)
        }

    @Test
    fun aPressTheChildRefusesIsNotMadeUpForWithATouch() =
        runBlocking<Unit> {
            val failures = ArrayList<String>()
            for (cell in cells) {
                val planned = open(cell.detail, PressActivity.CHILD_REFUSES to true)
                val (outcome, counts) = press(cell, planned, "child refuses")
                val reason = (outcome.status as? ActionOutcome.Status.Failed)?.reason
                if (reason?.startsWith("the app refused the") != true) failures += "$cell: ${outcome.status}"
                if (counts != Counts.NONE) failures += "$cell: $counts"
            }

            assertEquals(emptyList<String>(), failures)
        }

    companion object {
        private const val TAG = "WP07"
        private const val CHANGE_TIMEOUT_MS = 5_000L

        /** How long the page shows no change before its counts are read. */
        private const val REST_MS = 1_000L

        private fun log(message: String) {
            Log.i(TAG, message)
        }

        @JvmStatic
        @BeforeClass
        fun connectTheService() = AndroidScreenDeviceTest.connectTheService()

        @JvmStatic
        @AfterClass
        fun leaveTheDeviceAsFound() = AndroidScreenDeviceTest.leaveTheDeviceAsFound()
    }
}
