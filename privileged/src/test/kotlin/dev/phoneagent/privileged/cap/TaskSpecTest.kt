package dev.phoneagent.privileged.cap

import dev.phoneagent.privileged.StartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchGateTest {
    private val target = "com.example.shop"
    private val agent = 7
    private val otherAgent = 8
    private val agentDisplays = setOf(agent, otherAgent)
    private val user = 0

    private fun task(
        taskId: Int,
        displayId: Int,
        pkg: String = target,
        visible: Boolean = false,
        userId: Int = user,
    ) = TaskRow(taskId, displayId, listOf(pkg), topActivity = "$pkg.Main", userId = userId, visible = visible)

    private fun refuse(vararg tasks: TaskRow) =
        LaunchGate.refuse(tasks.toList(), listOf(target), agent, agentDisplays, user)

    @Test
    fun anAppNobodyUsesMayStart() {
        assertNull(refuse())
        assertNull("a background task on the main screen does not block", refuse(task(1, TaskRow.MAIN_DISPLAY)))
        assertNull("our own display does not block", refuse(task(1, agent, visible = true)))
    }

    @Test
    fun anAppVisibleOnTheMainScreenIsNotTaken() {
        assertEquals(StartResult.IN_USE_ON_MAIN, refuse(task(1, TaskRow.MAIN_DISPLAY, visible = true)))
    }

    @Test
    fun anAppOnAnotherAgentDisplayBelongsToThatTask() {
        assertEquals(StartResult.IN_USE_BY_AGENT, refuse(task(1, otherAgent)))
    }

    @Test
    fun theUsersScreenWinsOverAnotherAgentDisplay() {
        val reason = refuse(task(1, otherAgent), task(2, TaskRow.MAIN_DISPLAY, visible = true))
        assertEquals(StartResult.IN_USE_ON_MAIN, reason)
    }

    @Test
    fun otherAppsDoNotBlock() {
        assertNull(refuse(task(1, TaskRow.MAIN_DISPLAY, pkg = "com.example.other", visible = true)))
    }

    @Test
    fun aCopyInAnotherUserDoesNotBlock() {
        assertNull(refuse(task(1, TaskRow.MAIN_DISPLAY, visible = true, userId = 10)))
    }

    @Test
    fun aTaskOfAnUnknownUserBlocks() {
        val unknown = task(1, TaskRow.MAIN_DISPLAY, visible = true, userId = TaskRow.UNKNOWN_USER)
        assertEquals(StartResult.IN_USE_ON_MAIN, refuse(unknown))
    }

    @Test
    fun everyCandidatePackageIsChecked() {
        val tasks = listOf(task(1, TaskRow.MAIN_DISPLAY, pkg = "com.example.browser", visible = true))
        val candidates = listOf(target, "com.example.browser")
        assertEquals(StartResult.IN_USE_ON_MAIN, LaunchGate.refuse(tasks, candidates, agent, agentDisplays, user))
    }

    @Test
    fun aColdStartRestartsWhenEveryTaskIsOnThisDisplay() {
        assertEquals(LaunchGate.ColdStart.RESTART, LaunchGate.coldStart(emptyList(), target, agent, user))
        val here = listOf(task(1, agent))
        assertEquals(LaunchGate.ColdStart.RESTART, LaunchGate.coldStart(here, target, agent, user))
    }

    @Test
    fun aColdStartKeepsTheProcessWhenTheAppHasTasksElsewhere() {
        listOf(TaskRow.MAIN_DISPLAY, otherAgent).forEach { display ->
            val tasks = listOf(task(1, agent), task(2, display))
            assertEquals(
                "display $display",
                LaunchGate.ColdStart.KEEP_PROCESS,
                LaunchGate.coldStart(tasks, target, agent, user),
            )
        }
    }

    @Test
    fun aColdStartIgnoresAnotherUsersCopy() {
        val tasks = listOf(task(1, TaskRow.MAIN_DISPLAY, userId = 10))
        assertEquals(LaunchGate.ColdStart.RESTART, LaunchGate.coldStart(tasks, target, agent, user))
    }
}

class TaskOwnershipTest {
    private val agent = 7
    private val agentDisplays = setOf(agent)
    private val ownership = TaskOwnership()

    private fun task(
        taskId: Int,
        displayId: Int,
    ) = TaskRow(taskId, displayId, listOf("com.example.shop"))

    @Test
    fun aTaskOnAnAgentDisplayMayMoveToTheMainScreen() {
        val tasks = listOf(task(1, agent))
        assertNull(ownership.refuse(1, TaskRow.MAIN_DISPLAY, tasks, agentDisplays))
    }

    @Test
    fun aTaskWeStartedMayComeBackFromTheMainScreen() {
        ownership.remember(1)
        val tasks = listOf(task(1, TaskRow.MAIN_DISPLAY))
        assertNull(ownership.refuse(1, agent, tasks, agentDisplays))
    }

    @Test
    fun theUsersOwnTaskIsNeverPulledIntoTheBackground() {
        val tasks = listOf(task(2, TaskRow.MAIN_DISPLAY))
        val reason = ownership.refuse(2, agent, tasks, agentDisplays)
        assertTrue(reason.orEmpty(), reason.orEmpty().contains("neither started by the agent"))
    }

    @Test
    fun aTaskThatSpilledOntoTheMainScreenMayBeMovedBack() {
        // The agent drove an app that opened another app from the background.
        val tasks = listOf(task(5, TaskRow.MAIN_DISPLAY))
        assertNull(ownership.refuse(5, agent, tasks, agentDisplays, spilled = setOf(5)))
        assertTrue(
            "without the arrival it stays the user's task",
            ownership.refuse(5, agent, tasks, agentDisplays, spilled = setOf(9)) != null,
        )
    }

    @Test
    fun aTaskCannotMoveToSomebodyElsesDisplay() {
        ownership.remember(1)
        val tasks = listOf(task(1, agent))
        val reason = ownership.refuse(1, 99, tasks, agentDisplays)
        assertTrue(reason.orEmpty(), reason.orEmpty().contains("neither the main screen"))
    }

    @Test
    fun aTaskThatIsGoneCannotMove() {
        ownership.remember(1)
        val reason = ownership.refuse(1, agent, emptyList(), agentDisplays)
        assertTrue(reason.orEmpty(), reason.orEmpty().contains("does not exist"))
    }

    @Test
    fun releaseForgetsOwnership() {
        ownership.remember(1)
        assertTrue(ownership.owns(1))
        ownership.clear()
        assertFalse(ownership.owns(1))
        val tasks = listOf(task(1, TaskRow.MAIN_DISPLAY))
        assertTrue(ownership.refuse(1, agent, tasks, agentDisplays).orEmpty().contains("neither started"))
    }

    @Test
    fun anInvalidTaskIdIsNeverOwned() {
        ownership.remember(-1)
        assertFalse(ownership.owns(-1))
    }
}
