package dev.phoneagent.app.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.TaskRuntime
import dev.phoneagent.core.assembly.ProductConfig
import dev.phoneagent.core.assembly.assemble
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.storage.FileStoragePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** "New task" on real runs: the old run is stopped and gives back its log and its coroutines. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
@RunWith(AndroidJUnit4::class)
class NewTaskReclaimTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun eachNewTaskStopsTheOldRunAndLeavesNoLogOpenAndNothingRunning() =
        runTest {
            val work = StandardTestDispatcher(testScheduler)
            val disk = FileStoragePort(temp.root.toPath(), work)
            var open = 0
            val storage =
                object : StoragePort by disk {
                    override suspend fun openAppender(path: StoragePath): StorageAppender {
                        val inner = disk.openAppender(path)
                        open++
                        return object : StorageAppender by inner {
                            override suspend fun close() {
                                inner.close()
                                open--
                            }
                        }
                    }
                }
            val assembly = assemble(CapabilitiesOnly, ProductConfig(setOf("dev.phoneagent"), setOf("PhoneAgent")))
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
            val runs = mutableListOf<TaskRuntime>()
            val tasks =
                TaskController(scope) { runScope ->
                    val n = runs.size + 1
                    val session =
                        AppTaskSession(
                            assembly.setup(
                                TaskId("task-$n"),
                                SessionFiles(storage, StoragePath("s$n")),
                                null,
                                null,
                                null,
                            ),
                            work,
                        )
                    val run =
                        session.start(
                            SessionId("session-$n"),
                            TaskStarted(SecurityLevel.STANDARD, false),
                            runScope,
                        )
                    StartedTask(session, run.also { runs += it })
                }

            repeat(3) { round ->
                tasks.send("open settings")
                runCurrent()
                // The model refuses before any request (CapabilitiesOnly): the turn fails, the run idles.
                assertEquals(Phase.Idle, runs.last().phase.value)
                assertFalse(tasks.view.value.busy)
                assertEquals("round $round: the run's log is open", 1, open)

                tasks.newTask()
                runCurrent()
                assertEquals(TaskView(), tasks.view.value)
                assertEquals("round $round: the old run's log is closed", 0, open)
                assertEquals(Phase.Stopped(StopCause.USER), runs.last().phase.value)
                assertEquals(
                    "round $round: nothing left running",
                    0,
                    scope.coroutineContext.job.children
                        .count { it.isActive },
                )
            }
            assertEquals(3, runs.size)
        }
}
