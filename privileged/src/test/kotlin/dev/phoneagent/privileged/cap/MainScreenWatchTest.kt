package dev.phoneagent.privileged.cap

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/** The watch's own thread: work that fails there is logged, and the watch keeps going. */
@RunWith(RobolectricTestRunner::class)
class MainScreenWatchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge(context)
    private val home = ComponentName("com.android.launcher3", "com.android.launcher3.Home")
    private val shop = ComponentName("com.example.shop", "com.example.shop.Main")
    private lateinit var watch: MainScreenWatch
    private lateinit var looper: Looper

    private fun start() {
        val before = ShadowLooper.getAllLoopers().toSet()
        watch = MainScreenWatch(bridge, TaskTable(bridge))
        looper = (ShadowLooper.getAllLoopers() - before).single { it.thread.name == "phoneagent-main-watch" }
        bridge.atm.tasks += CapabilityHarness.task(1, TaskRow.MAIN_DISPLAY, home)
        watch.watch(WINDOW_MS)
        shadowOf(looper).idle()
    }

    private fun advance(ms: Long) {
        shadowOf(looper).idleFor(Duration.ofMillis(ms))
    }

    private fun shopArrives() {
        bridge.atm.tasks.add(0, CapabilityHarness.task(2, TaskRow.MAIN_DISPLAY, shop))
    }

    @After
    fun close() {
        watch.close()
    }

    @Test
    fun aPollThatFailsDoesNotEndThePolling() {
        start()
        bridge.atm.tornReads = 1
        advance(POLL_MS)
        assertEquals("the failing poll read the list", 0, bridge.atm.tornReads)
        shopArrives()
        advance(POLL_MS)
        assertEquals(setOf(2), watch.spilled())
    }

    @Test
    fun aSignalWhoseScanFailsDoesNotEndTheWatch() {
        bridge.atm.acceptsListener = true
        start()
        val listener = checkNotNull(bridge.atm.listener) { "the listener was registered" }
        bridge.atm.tornReads = 1
        signal(listener)
        advance(COALESCE_MS)
        assertEquals("the failing scan read the list", 0, bridge.atm.tornReads)
        shopArrives()
        signal(listener)
        advance(COALESCE_MS)
        assertEquals(setOf(2), watch.spilled())
        assertTrue(watch.start())
    }

    /** What the system does when a task moves: one `ITaskStackListener` call. */
    private fun signal(listener: Any) {
        listener.javaClass.getMethod("onTaskStackChanged").invoke(listener)
    }

    private companion object {
        const val WINDOW_MS = 10_000
        const val POLL_MS = 100L
        const val COALESCE_MS = 30L
    }
}
