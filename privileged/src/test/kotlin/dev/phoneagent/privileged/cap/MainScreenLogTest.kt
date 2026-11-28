package dev.phoneagent.privileged.cap

import dev.phoneagent.privileged.ArrivalKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenLogTest {
    private val log = MainScreenLog()
    private val agent = 7

    private fun task(
        taskId: Int,
        displayId: Int,
        pkg: String = "com.example.shop",
        activity: String = "$pkg.Main",
        standard: Boolean = true,
    ) = TaskRow(taskId, displayId, listOf(pkg), topActivity = activity, userId = 0, standard = standard)

    @Test
    fun nothingIsRecordedBeforeTheWindowOpens() {
        log.scan(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0)
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY)), now = 10)
        assertEquals(emptyList<MainScreenLog.Arrival>(), log.since(0))
        assertEquals("the last snapshot is the baseline", 2, log.topTaskId)
    }

    @Test
    fun watchReportsTheCurrentTopTask() {
        assertEquals(-1, log.watch(emptyList(), now = 0, windowMs = 3_000))
        assertEquals(5, log.watch(listOf(task(5, TaskRow.MAIN_DISPLAY)), now = 1, windowMs = 3_000))
    }

    @Test
    fun aNewTaskOnTopIsOneArrival() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 3_000)
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY, pkg = "com.example.pay")), now = 100)
        val arrival = log.since(0).single()
        assertEquals(2, arrival.taskId)
        assertEquals("com.example.pay", arrival.packageName)
        assertEquals(100, arrival.at)
        assertEquals(ArrivalKeys.FROM_NEW, arrival.from)
        assertFalse(arrival.sameTask)
        assertTrue(arrival.standard)
    }

    @Test
    fun theSameTaskWithANewPageIsAlsoRecorded() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY, activity = "a.Home")), now = 0, windowMs = 3_000)
        log.scan(listOf(task(1, TaskRow.MAIN_DISPLAY, activity = "a.Checkout")), now = 50)
        val arrival = log.since(0).single()
        assertTrue(arrival.sameTask)
        assertEquals("a.Checkout", arrival.topActivity)
        assertEquals(TaskRow.MAIN_DISPLAY, arrival.from)
    }

    @Test
    fun anUnchangedTopScreenRecordsNothing() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 3_000)
        log.scan(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 50)
        log.scan(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 100)
        assertEquals(emptyList<MainScreenLog.Arrival>(), log.since(0))
    }

    @Test
    fun aTaskComingFromTheAgentDisplayNamesThatDisplay() {
        log.watch(
            listOf(task(1, TaskRow.MAIN_DISPLAY, pkg = "com.example.home"), task(9, agent)),
            now = 0,
            windowMs = 3_000,
        )
        // Task 9 spilled from the agent display to the main screen.
        val home = task(1, TaskRow.MAIN_DISPLAY, pkg = "com.example.home")
        log.scan(listOf(task(9, TaskRow.MAIN_DISPLAY), home), now = 80)
        val arrival = log.since(0).single()
        assertEquals(9, arrival.taskId)
        assertEquals(agent, arrival.from)
    }

    @Test
    fun arrivalsAfterTheWindowAreNotRecorded() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 100)
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY)), now = 50)
        log.scan(listOf(task(3, TaskRow.MAIN_DISPLAY)), now = 100)
        assertEquals(listOf(2), log.since(0).map { it.taskId })
        assertFalse(log.watching(100))
        assertTrue(log.watching(99))
    }

    @Test
    fun aSecondWatchExtendsTheWindowButNeverShortensIt() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 5_000)
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 10, windowMs = 100)
        assertTrue("the longer window still holds", log.watching(4_999))
    }

    @Test
    fun theWindowIsBounded() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 10 * MainScreenLog.MAX_WINDOW_MS)
        assertFalse(log.watching(MainScreenLog.MAX_WINDOW_MS))
        assertTrue(log.watching(MainScreenLog.MAX_WINDOW_MS - 1))
    }

    @Test
    fun aNegativeWindowRecordsNothing() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = -5_000)
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY)), now = 1)
        assertEquals(emptyList<MainScreenLog.Arrival>(), log.since(0))
    }

    @Test
    fun sinceFiltersOlderArrivals() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 10_000)
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY)), now = 100)
        log.scan(listOf(task(3, TaskRow.MAIN_DISPLAY)), now = 200)
        assertEquals(listOf(2, 3), log.since(0).map { it.taskId })
        assertEquals(listOf(3), log.since(200).map { it.taskId })
        assertEquals(emptyList<Int>(), log.since(201).map { it.taskId })
    }

    @Test
    fun oldArrivalsAreDropped() {
        val short = MainScreenLog(keepMs = 500, maxWindowMs = 10_000)
        short.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 10_000)
        short.scan(listOf(task(2, TaskRow.MAIN_DISPLAY)), now = 10)
        short.scan(listOf(task(3, TaskRow.MAIN_DISPLAY)), now = 600)
        assertEquals("the arrival at 10 is older than keepMs", listOf(3), short.since(0).map { it.taskId })
    }

    @Test
    fun arrivedTasksAreTheSpillBackCandidates() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 3_000)
        assertEquals(emptySet<Int>(), log.arrivedTasks())
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY)), now = 10)
        assertEquals(setOf(2), log.arrivedTasks())
        // Changes outside a window are not spill-back candidates.
        log.scan(listOf(task(3, TaskRow.MAIN_DISPLAY)), now = 4_000)
        assertEquals(setOf(2), log.arrivedTasks())
    }

    @Test
    fun theDisplayOfATaskIsTheLastOneItWasSeenOn() {
        log.scan(listOf(task(1, TaskRow.MAIN_DISPLAY), task(9, agent)), now = 0)
        assertEquals(agent, log.displayOf(9))
        assertEquals(TaskRow.MAIN_DISPLAY, log.displayOf(1))
        log.scan(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 10)
        assertEquals("task 9 is gone", -1, log.displayOf(9))
    }

    @Test
    fun aLauncherOrAssistantArrivalIsMarkedNotStandard() {
        log.watch(listOf(task(1, TaskRow.MAIN_DISPLAY)), now = 0, windowMs = 3_000)
        log.scan(listOf(task(2, TaskRow.MAIN_DISPLAY, standard = false)), now = 10)
        assertFalse(log.since(0).single().standard)
    }
}
