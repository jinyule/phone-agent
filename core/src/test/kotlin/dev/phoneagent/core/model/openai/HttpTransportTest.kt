package dev.phoneagent.core.model.openai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class HttpTransportTest {
    @StartStop
    private val server = MockWebServer()

    private val ioThread = Executors.newSingleThreadExecutor()
    private val io = ioThread.asCoroutineDispatcher()
    private val events = Events()
    private val client: OkHttpClient =
        HttpTimeouts()
            .client()
            .newBuilder()
            .eventListener(events)
            .build()

    @AfterEach
    fun stop() {
        ioThread.shutdownNow()
    }

    private fun request() = Request.Builder().url(server.url("/v1/x")).build()

    /** Connections the pool holds that a call still owns. */
    private fun connectionsInUse() =
        client.connectionPool.connectionCount() - client.connectionPool.idleConnectionCount()

    @Test
    fun `a cancellation after execute returns but before the caller resumes releases the connection`() {
        server.enqueue(MockResponse.Builder().body("unread body").build())
        val caller = QueueDispatcher()
        val job =
            CoroutineScope(caller + Job()).launch {
                HttpTransport(client, io).send(request(), Redactor.SHAPES_ONLY) { it.maskedText() }
            }
        caller.runNext() // starts the call; the caller is now suspended while execute() runs on io
        val resume = caller.next() // queued once execute() has returned the response
        job.cancel() // cancelled before the caller gets the response
        resume.run()
        caller.runUntilIdle()
        assertTrue(job.isCancelled && job.isCompleted, "the caller saw the cancellation")
        assertTrue(events.canceled || events.released, "the call was cancelled or its connection released")
        assertEquals(0, connectionsInUse(), "no connection stays allocated to the abandoned call")
    }

    @Test
    fun `a normal exchange neither cancels the call nor keeps its connection`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val text = HttpTransport(client, io).send(request(), Redactor.SHAPES_ONLY) { it.maskedText() }
            assertEquals("ok", text)
            assertFalse(events.canceled, "nothing cancels a call that finished")
            assertEquals(0, connectionsInUse())
        }

    @Test
    fun `a reader that fails still closes the response`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().body("ok").build())
            val failure =
                assertThrows<IllegalStateException> {
                    HttpTransport(client, io).send(request(), Redactor.SHAPES_ONLY) { error("reader failed") }
                }
            assertEquals("reader failed", failure.message)
            assertEquals(0, connectionsInUse())
        }

    /** A dispatcher whose tasks run only when the test says so. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.put(block)
        }

        /** Waits for the next task without running it. */
        fun next(): Runnable = checkNotNull(tasks.poll(WAIT_S, TimeUnit.SECONDS)) { "nothing was dispatched" }

        fun runNext() = next().run()

        fun runUntilIdle() {
            while (true) (tasks.poll() ?: return).run()
        }
    }

    private class Events : EventListener() {
        @Volatile var canceled = false

        @Volatile var released = false

        override fun canceled(call: Call) {
            canceled = true
        }

        override fun connectionReleased(
            call: Call,
            connection: Connection,
        ) {
            released = true
        }
    }

    private companion object {
        const val WAIT_S = 5L
    }
}
