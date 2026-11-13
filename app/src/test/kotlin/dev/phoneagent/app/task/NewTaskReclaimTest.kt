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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** "New task" on real runs: the old run is stopped and gives back its log and its coroutines. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
@RunWith(AndroidJUnit4::class)
class NewTaskReclaimTest {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * A controller whose tasks are real runs with logs under [root]; the model refuses before any
     * request ([CapabilitiesOnly]), so each command's turn fails and the run idles.
     */
    private class RealTasks(
        test: TestScope,
        root: File,
    ) {
        /** The logs open now. */
        var open = 0

        /** Holds every log's close until completed. */
        var closing: CompletableDeferred<Unit>? = null

        val runs = mutableListOf<TaskRuntime>()
        val scope = test.backgroundScope.let { CoroutineScope(it.coroutineContext + Job(it.coroutineContext.job)) }
        val tasks: TaskController

        init {
            val work = StandardTestDispatcher(test.testScheduler)
            val disk = FileStoragePort(root.toPath(), work)
            val storage =
                object : StoragePort by disk {
                    override suspend fun openAppender(path: StoragePath): StorageAppender {
                        val inner = disk.openAppender(path)
                        open++
                        return object : StorageAppender by inner {
                            override suspend fun close() {
                                closing?.await()
                                inner.close()
                                open--
                            }
                        }
                    }
                }
            val assembly = assemble(CapabilitiesOnly, ProductConfig(setOf("dev.phoneagent"), setOf("PhoneAgent")))
            tasks =
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
        }
    }

    @Test
    fun eachNewTaskStopsTheOldRunAndLeavesNoLogOpenAndNothingRunning() =
        runTest {
            val real = RealTasks(this, temp.root)
            val tasks = real.tasks
            repeat(3) { round ->
                tasks.send("open settings")
                runCurrent()
                assertEquals(
                    Phase.Idle,
                    real.runs
                        .last()
                        .phase.value,
                )
                assertFalse(tasks.view.value.busy)
                assertEquals("round $round: the run's log is open", 1, real.open)

                tasks.newTask()
                runCurrent()
                assertEquals(TaskView(), tasks.view.value)
                assertEquals("round $round: the old run's log is closed", 0, real.open)
                assertEquals(
                    Phase.Stopped(StopCause.USER),
                    real.runs
                        .last()
                        .phase.value,
                )
                assertEquals(
                    "round $round: nothing left running",
                    0,
                    real.scope.coroutineContext.job.children
                        .count { it.isActive },
                )
            }
            assertEquals(3, real.runs.size)
        }

    @Test
    fun aCommandSentWhileTheOldRunClosesItsLogGoesToTheNewTaskAndIsCountedOnce() =
        runTest {
            val real = RealTasks(this, temp.root)
            val tasks = real.tasks
            tasks.send("open settings")
            runCurrent()
            assertEquals(
                Phase.Idle,
                real.runs
                    .last()
                    .phase.value,
            )

            val closing = CompletableDeferred<Unit>()
            real.closing = closing
            tasks.newTask()
            runCurrent()
            assertTrue(
                "the old run is still closing its log",
                real.runs
                    .last()
                    .phase.value !is Phase.Stopped,
            )
            tasks.send("open the clock")
            runCurrent()
            closing.complete(Unit)
            runCurrent()

            assertEquals("the command started the new task", 2, real.runs.size)
            assertEquals(
                Phase.Stopped(StopCause.USER),
                real.runs
                    .first()
                    .phase.value,
            )
            assertEquals(
                Phase.Idle,
                real.runs
                    .last()
                    .phase.value,
            )
            assertEquals("every command handed", 0, tasks.view.value.queued)
            assertFalse(tasks.view.value.busy)
            assertEquals("only the new task's log is open", 1, real.open)
        }
}
