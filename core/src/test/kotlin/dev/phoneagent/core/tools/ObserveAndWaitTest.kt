package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.detail
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.FramedSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveAndWaitTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)

    private companion object {
        /** The default read interval of phone_wait (ToolsConfig.waitReadIntervalMs). */
        const val READ_INTERVAL_MS = 250L

        /** A last read that takes a while (tree, OCR) but well within the margin after the longest wait. */
        const val SLOW_READ_MS = 10_000L

        /** More looks than any wait in these tests takes; reaching it means the wait spins. */
        const val SPIN_LIMIT = 100
    }

    private fun tools(config: ToolsConfig = ToolsConfig()) =
        PhaseOneTools.create(formatter, config).associateBy {
            it.def.name
        }

    @Nested
    inner class Observing {
        private val sparse = Screens.CHAT.copy(quality = TreeQuality.SPARSE)

        private suspend fun observe(
            args: String = "{}",
            screen: FakeScreen = FakeScreen(),
            vision: Boolean = true,
            ctx: FakeContext = FakeContext(screen),
        ): Pair<ToolResult, FakeContext> {
            val presenter = ScreenFormatter(lexicon = Screens.LEXICON, vision = vision)
            val observe = PhaseOneTools.create(presenter).single { it.def.name == "phone_observe" }
            return observe.execute(call("phone_observe", args), ctx) to ctx
        }

        @Test
        fun `reads the page at the requested detail, without a frame when the tree is good`() =
            runTest {
                val screen = FakeScreen()
                val (result, _) = observe("""{"detail":"full"}""", screen)
                assertEquals(ToolStatus.OK, result.status)
                assertEquals(formatter.page(Screens.CHAT, SnapshotDetail.FULL), result.text())
                assertEquals(listOf(SnapshotOptions(SnapshotDetail.FULL)), screen.snapshotOptions)
                assertTrue(screen.frames.isEmpty())
                assertEquals("s12", result.detail("screen"))
                assertEquals("GOOD", result.detail("quality"))
                assertEquals("false", result.detail("imageAttached"))
                val brief = observe(screen = FakeScreen()).first
                assertEquals(formatter.page(Screens.CHAT, SnapshotDetail.BRIEF), brief.text())
            }

        @Test
        fun `attaches a Set-of-Mark frame when asked or when the tree is poor`() =
            runTest {
                val screen = FakeScreen(sparse)
                val (result, ctx) = observe(screen = screen)
                val image = result.content.filterIsInstance<Part.Image>().single()
                assertEquals("image/jpeg", image.mime)
                assertEquals(720 to 1280, image.width to image.height)
                assertArrayEquals(byteArrayOf(1, 2, 3), ctx.blobs.getValue(image.blob))
                assertEquals(listOf(true), screen.frames)
                assertEquals(listOf(Triple(SnapshotOptions(), ScreenPort.DEFAULT_FRAME_EDGE, true)), ctx.framedCalls)
                assertTrue(result.text().contains("附图：带编号框的截图"))
                assertEquals("true", result.detail("imageAttached"))

                val asked = FakeScreen()
                observe("""{"image":true}""", asked)
                assertEquals(listOf(true), asked.frames)
                val declined = FakeScreen(sparse)
                observe("""{"image":false}""", declined)
                assertTrue(declined.frames.isEmpty())
            }

        @Test
        fun `never attaches a frame without vision or on a secure page, and says why`() =
            runTest {
                val blind = FakeScreen(sparse)
                assertTrue(observe(screen = blind, vision = false).first.text().endsWith("（当前模型不看图，没有附截图）"))
                assertTrue(blind.frames.isEmpty())
                val secure = FakeScreen(sparse.copy(flags = setOf(ScreenFlag.SECURE)))
                assertTrue(observe("""{"image":true}""", secure).first.text().endsWith("（这一页禁止截屏，没有附截图）"))
                assertTrue(secure.frames.isEmpty())
            }

        @Test
        fun `a withheld screen gets no frame and no word about one`() =
            runTest {
                val withheld = FakeScreen(sparse.copy(quality = TreeQuality.EMPTY, flags = setOf(ScreenFlag.WITHHELD)))
                for (args in listOf("{}", """{"image":true}""")) {
                    val (result, _) = observe(args, withheld)
                    assertEquals(
                        listOf(Part.Text(formatter.page(withheld.current, SnapshotDetail.BRIEF))),
                        result.content,
                    )
                    assertEquals("false", result.detail("imageAttached"))
                    assertEquals(withheld.current.id.value, result.detail("screen"))
                }
                assertTrue(withheld.frames.isEmpty())
            }

        @Test
        fun `the page shown with a frame is the screen read with it, at the detail asked for`() =
            runTest {
                val later =
                    sparse.copy(
                        id = SnapshotId("s99"),
                        elements = listOf(el("e1", ElementRole.BUTTON, "再来一局", box(0, 0, 100, 100))),
                    )
                val frame = Frame(byteArrayOf(4), "image/png", 10, 20, som = true)
                val ctx = FakeContext(FakeScreen(sparse)).apply { framed = { _, _, _ -> FramedSnapshot(later, frame) } }
                val result = observe("""{"detail":"full"}""", ctx = ctx).first
                val page = formatter.page(later, SnapshotDetail.FULL)
                assertEquals(Part.Text("$page\n附图：带编号框的截图，框上的编号就是元素的 @e 编号。"), result.content.first())
                assertEquals(Part.Image(ctx.blobs.keys.single(), "image/png", 10, 20), result.content[1])
                assertEquals(listOf(SnapshotOptions(SnapshotDetail.FULL)), ctx.framedCalls.map { it.first })
                assertEquals("s99", result.detail("screen"))
                assertEquals("true", result.detail("imageAttached"))
            }

        @Test
        fun `without a frame the page says so, and a withheld screen says nothing of one`() =
            runTest {
                val none = observe(screen = FakeScreen(sparse).apply { frame = null })
                assertTrue(none.first.text().endsWith("\n（截不到图）"), none.first.text())
                assertTrue(none.second.blobs.isEmpty())
                assertEquals("false", none.first.detail("imageAttached"))
                val failed = FakeScreen(sparse).apply { frameError = ScreenUnavailableException("截图服务断开") }
                assertTrue(observe(screen = failed).first.text().endsWith("\n（截不到图）"))

                val withheld =
                    sparse.copy(
                        id = SnapshotId("s98"),
                        quality = TreeQuality.EMPTY,
                        flags = setOf(ScreenFlag.WITHHELD),
                    )
                val ctx =
                    FakeContext(FakeScreen(sparse)).apply {
                        framed = {
                            _,
                            _,
                            _,
                            ->
                            FramedSnapshot(withheld, null)
                        }
                    }
                val result = observe(ctx = ctx).first
                assertEquals(listOf(Part.Text(formatter.page(withheld, SnapshotDetail.BRIEF))), result.content)
                assertEquals("s98", result.detail("screen"))
            }

        @Test
        fun `a screen that cannot be read with the frame makes the observation unavailable`() =
            runTest {
                val gone = ScreenUnavailableException("固定的说法")
                val ctx = FakeContext(FakeScreen(sparse)).apply { framed = { _, _, _ -> throw gone } }
                val result = observe(ctx = ctx).first
                assertEquals(ToolStatus.ERROR, result.status)
                assertTrue(result.text().startsWith("屏幕暂时用不了（固定的说法）"), result.text())
            }

        @Test
        fun `invalid arguments, no screen and an unusable screen`() =
            runTest {
                assertEquals(ToolStatus.INVALID_ARGS, observe("""{"detail":"all"}""").first.status)
                assertEquals(ToolStatus.INVALID_ARGS, observe("""{"image":"maybe"}""").first.status)
                val ctx = FakeContext(screen = null)
                assertEquals(
                    ToolResults.NO_SCREEN,
                    tools().getValue("phone_observe").execute(call("phone_observe"), ctx),
                )
                val gone = FakeScreen().apply { unavailable = ScreenUnavailableException("无障碍服务断开") }
                assertTrue(observe(screen = gone).first.text().startsWith("屏幕暂时用不了（无障碍服务断开）"))
            }

        @Test
        fun `a frame the port drew no boxes on is not called numbered`() =
            runTest {
                // No snapshot before it, or boxes that cannot be placed: Frame.som is false.
                val screen =
                    FakeScreen(sparse).apply {
                        frame =
                            Frame(byteArrayOf(1, 2, 3), "image/jpeg", 720, 1280, som = false)
                    }
                val result = observe(screen = screen).first
                assertEquals(1, result.content.filterIsInstance<Part.Image>().size)
                assertFalse(result.text().contains("带编号框"), result.text())
                assertTrue(result.text().contains("附图：这张截图没有编号框，按坐标和上面的元素对照。"), result.text())
            }

        @Test
        fun `the description offers screenshots only to a model that can read images`() {
            val def = tools().getValue("phone_observe").def
            assertTrue(def.description.contains("模型能看图时，image=true 附一张截图，画得上框时带编号框"), def.description)
            assertTrue(def.description.contains("界面元素读不全且模型能看图时会自动附图"), def.description)
            val image =
                def.parameters["properties"]!!
                    .jsonObject
                    .getValue("image")
                    .jsonObject
                    .getValue("description")
                    .jsonPrimitive.content
            assertTrue(image.contains("模型不看图时不会附图"), image)
        }

        @Test
        fun `is a concurrency-safe read`() {
            val tool = tools().getValue("phone_observe")
            assertEquals(RiskClass.READ, tool.risk)
            assertTrue(tool.concurrencySafe)
            assertEquals(20_000, tool.timeoutMs)
        }
    }

    @Test
    fun `without vision no hint points to screenshots`() =
        runTest {
            val presenter = ScreenFormatter(lexicon = Screens.LEXICON, vision = false)
            val blind = PhaseOneTools.create(presenter).associateBy { it.def.name }
            for (snapshot in listOf(
                Samples.sparse,
                Samples.sparse.copy(elements = emptyList(), quality = TreeQuality.EMPTY),
            )) {
                val result =
                    blind
                        .getValue(
                            "phone_observe",
                        ).execute(call("phone_observe"), FakeContext(FakeScreen(snapshot)))
                assertFalse(result.text().contains("image=true"), result.text())
                assertFalse(result.text().contains("看截图"), result.text())
                assertTrue(result.text().contains("hand_to_user"), result.text())
            }
        }

    @Test
    fun `the vision the screen components are assembled with decides both the notes and the frame`() =
        runTest {
            val sparse = Screens.CHAT.copy(quality = TreeQuality.SPARSE)
            for (vision in listOf(true, false)) {
                val presenter = ScreenComponents(lexicon = Screens.LEXICON, vision = vision).presenter
                val observe = PhaseOneTools.create(presenter).single { it.def.name == "phone_observe" }
                for ((args, snapshot) in listOf("{}" to sparse, """{"image":true}""" to Screens.CHAT)) {
                    val screen = FakeScreen(snapshot)
                    val result = observe.execute(call("phone_observe", args), FakeContext(screen))
                    val text = result.text()
                    val case = "vision=$vision $args: $text"
                    assertEquals(vision, screen.frames.isNotEmpty(), case)
                    assertEquals(vision, result.content.any { it is Part.Image }, case)
                    assertEquals(vision, text.contains("附图：带编号框的截图"), case)
                    assertEquals(!vision, text.endsWith("（当前模型不看图，没有附截图）"), case)
                    assertEquals(vision && snapshot == sparse, text.contains("看截图"), case)
                }
            }
        }

    @Nested
    inner class Waiting {
        private val loading = Screens.snapshot("s20", listOf(el("e1", ElementRole.PROGRESS, "加载中")))
        private val loaded =
            Screens
                .snapshot(
                    "s21",
                    listOf(el("e1", ElementRole.TEXT, "订单 已 完成")),
                ).copy(ocr = listOf(OcrLine("广告", box(0, 0, 9, 9))))

        private suspend fun wait(
            args: String,
            screen: FakeScreen?,
        ): ToolResult = tools().getValue("phone_wait").execute(call("phone_wait", args), FakeContext(screen))

        /** A screen that shows [first] until [at] ms of virtual time, then [then]; changes are events. */
        private fun switching(
            first: ScreenSnapshot,
            then: ScreenSnapshot,
            at: Long,
            clock: () -> Long,
        ) = FakeScreen().apply {
            onSnapshot = { if (clock() >= at) then else first }
            onAwaitChange = { timeout ->
                val now = clock()
                if (now < at && at - now <= timeout) {
                    delay(at - now)
                    true
                } else {
                    delay(timeout)
                    false
                }
            }
        }

        @Test
        fun `waits for text to appear, matching across spaces and case, in the tree or OCR`() =
            runTest {
                val screen = switching(loading, loaded, at = 3_000) { currentTime }
                val result = wait("""{"condition":"appear","text":"已完成"}""", screen)
                assertEquals(ToolStatus.OK, result.status)
                assertEquals(3_000, currentTime)
                assertTrue(result.text().startsWith("等到了：「已完成」出现了。\nscreen s21"), result.text())
                assertEquals("true", result.detail("met"))
                assertEquals("s21", result.detail("screen"))
                assertEquals(ToolStatus.OK, wait("""{"condition":"appear","text":"广告"}""", FakeScreen(loaded)).status)
            }

        @Test
        fun `waits for text to disappear`() =
            runTest {
                val screen = switching(loading, loaded, at = 1_500) { currentTime }
                val result = wait("""{"condition":"disappear","text":"加载中"}""", screen)
                assertTrue(result.text().startsWith("等到了：「加载中」消失了。"), result.text())
            }

        @Test
        fun `an unmet condition is an error with the page as it is then`() =
            runTest {
                val screen = switching(loading, loaded, at = Long.MAX_VALUE) { currentTime }
                val result = wait("""{"condition":"appear","text":"已完成","timeoutMs":2000}""", screen)
                assertEquals(ToolStatus.ERROR, result.status)
                assertEquals(2_000, currentTime)
                assertTrue(result.text().startsWith("等了 2 秒，「已完成」还没有出现。\nscreen s20"), result.text())
                assertEquals("false", result.detail("met"))
                val still =
                    wait(
                        """{"condition":"disappear","text":"加载中","timeoutMs":1000}""",
                        switching(
                            loading,
                            loaded,
                            Long.MAX_VALUE,
                        ) {
                            currentTime
                        },
                    )
                assertTrue(still.text().startsWith("等了 1 秒，「加载中」还在。"))
            }

        /** A screen whose page is [first] for the first [reads] reads and [then] after; it never reports a change. */
        private fun silent(
            first: ScreenSnapshot,
            then: ScreenSnapshot,
            reads: Int,
        ) = FakeScreen().apply {
            var count = 0
            onSnapshot = { if (count++ < reads) first else then }
            onAwaitChange = { timeout ->
                delay(timeout)
                false
            }
        }

        @Test
        fun `a change between the first read and the wait is seen within one read interval`() =
            runTest {
                // The page finished loading right after the first read; the port reports no later change.
                val result =
                    wait("""{"condition":"appear","text":"已完成","timeoutMs":5000}""", silent(loading, loaded, 1))
                assertEquals(ToolStatus.OK, result.status, result.text())
                assertTrue(currentTime <= READ_INTERVAL_MS, "found at $currentTime ms")
                assertTrue(result.text().startsWith("等到了：「已完成」出现了。\nscreen s21"), result.text())
            }

        @Test
        fun `a condition that holds at the deadline is met`() =
            runTest {
                val screen =
                    FakeScreen().apply {
                        onSnapshot = { if (currentTime >= 2_000) loaded else loading }
                        onAwaitChange = { timeout ->
                            delay(timeout)
                            false
                        }
                    }
                val appeared = wait("""{"condition":"appear","text":"已完成","timeoutMs":2000}""", screen)
                assertEquals(ToolStatus.OK, appeared.status, appeared.text())
                assertEquals("true", appeared.detail("met"))
                assertTrue(appeared.text().startsWith("等到了：「已完成」出现了。"), appeared.text())
                val gone = wait("""{"condition":"disappear","text":"加载中","timeoutMs":2000}""", screen)
                assertEquals(ToolStatus.OK, gone.status, gone.text())
                assertTrue(gone.text().startsWith("等到了：「加载中」消失了。"), gone.text())
            }

        @Test
        fun `disappear that holds only at the deadline is met by the last read`() =
            runTest {
                // Reads every 250 ms see the spinner until the deadline at 1100 ms falls between two
                // reads; only the read made at the deadline sees it gone.
                val screen =
                    FakeScreen().apply {
                        onSnapshot = { if (currentTime >= 1_100) loaded else loading }
                        onAwaitChange = { timeout ->
                            delay(timeout)
                            false
                        }
                    }
                val gone = wait("""{"condition":"disappear","text":"加载中","timeoutMs":1100}""", screen)
                assertEquals(1_100, currentTime)
                assertEquals(ToolStatus.OK, gone.status, gone.text())
                assertEquals("true", gone.detail("met"))
                assertTrue(gone.text().startsWith("等到了：「加载中」消失了。\nscreen s21"), gone.text())
            }

        @Test
        fun `disappear sees a change before the wait too`() =
            runTest {
                val result =
                    wait("""{"condition":"disappear","text":"加载中","timeoutMs":5000}""", silent(loading, loaded, 1))
                assertEquals(ToolStatus.OK, result.status, result.text())
                assertTrue(currentTime <= READ_INTERVAL_MS, "found at $currentTime ms")
            }

        @Test
        fun `a port that reports changes at once neither spins the loop nor blocks cancellation`() =
            runTest {
                var awaits = 0
                var reads = 0
                val screen =
                    FakeScreen(loading).apply {
                        onSnapshot = {
                            reads++
                            loading
                        }
                        onAwaitChange = {
                            // Returns without suspending; gives up after 200 calls so a spinning loop ends.
                            if (++awaits > 200) delay(Long.MAX_VALUE)
                            true
                        }
                    }
                for (args in listOf("""{"condition":"appear","text":"已完成"}""", """{"condition":"stable"}""")) {
                    awaits = 0
                    reads = 0
                    val job = launch { wait(args, screen) }
                    advanceTimeBy(1_000)
                    val bound = (1_000 / READ_INTERVAL_MS + 2).toInt()
                    assertTrue(awaits <= bound && reads <= bound, "$args: $awaits waits, $reads reads in 1 s")
                    job.cancel()
                    runCurrent()
                    assertTrue(job.isCancelled && job.isCompleted, "$args: cancelled at once")
                }
            }

        @Test
        fun `changes every 16 ms do not make every change a read`() =
            runTest {
                var reads = 0
                val screen =
                    FakeScreen(loading).apply {
                        onSnapshot = {
                            reads++
                            loading
                        }
                        onAwaitChange = {
                            delay(16)
                            true
                        }
                    }
                val result = wait("""{"condition":"appear","text":"已完成","timeoutMs":10000}""", screen)
                assertEquals(ToolStatus.ERROR, result.status)
                assertTrue(reads <= 10_000 / READ_INTERVAL_MS + 2, "$reads reads in 10 s")
            }

        /** [snapshot] withheld by the runtime: only the flag, so matching must look at the flag, not the content. */
        private fun withheld(snapshot: ScreenSnapshot) = snapshot.copy(flags = setOf(ScreenFlag.WITHHELD))

        @Test
        fun `a withheld screen meets no condition, and the result says it cannot be checked`() =
            runTest {
                val stripped = withheld(loading).copy(elements = emptyList(), ocr = null)
                val cases =
                    listOf(
                        """{"condition":"appear","text":"已完成","timeoutMs":1000}""" to withheld(loaded),
                        """{"condition":"disappear","text":"加载中","timeoutMs":1000}""" to stripped,
                        """{"condition":"stable","timeoutMs":1000}""" to withheld(loaded),
                    )
                val wrong =
                    cases.mapNotNull { (args, snapshot) ->
                        val result = wait(args, FakeScreen(snapshot))
                        val expected = "这一屏不能显示，条件无法核实。\n" + formatter.page(snapshot, SnapshotDetail.BRIEF)
                        val right =
                            result.status == ToolStatus.ERROR && result.text() == expected &&
                                result.detail("met") == "false"
                        if (right) null else "$args: ${result.status} ${result.detail("met")} ${result.text()}"
                    }
                assertEquals(emptyList<String>(), wrong)
            }

        @Test
        fun `a withheld screen in between is waited past, whatever it holds`() =
            runTest {
                val appeared =
                    wait(
                        """{"condition":"appear","text":"已完成"}""",
                        switching(withheld(loaded).copy(id = loading.id), loaded, at = 1_000) { currentTime },
                    )
                assertEquals(1_000, currentTime)
                assertEquals(ToolStatus.OK, appeared.status)
                assertEquals("s21", appeared.detail("screen"))
                val stripped = withheld(loading).copy(elements = emptyList(), ocr = null)
                val gone =
                    wait(
                        """{"condition":"disappear","text":"加载中"}""",
                        switching(stripped, loaded, at = 2_000) { currentTime },
                    )
                assertEquals(2_000, currentTime)
                assertEquals(ToolStatus.OK, gone.status)
                assertEquals("s21", gone.detail("screen"))
            }

        /**
         * A screen that shows each phase's snapshot from its start (ms of virtual time) on, the
         * first from 0; every switch is a change event.
         */
        private fun timeline(
            clock: () -> Long,
            vararg phases: Pair<Long, ScreenSnapshot>,
        ) = FakeScreen().apply {
            onSnapshot = { phases.last { (from, _) -> from <= clock() }.second }
            onAwaitChange = { timeout ->
                val now = clock()
                val next = phases.map { it.first }.firstOrNull { it > now && it - now <= timeout }
                delay((next ?: (now + timeout)) - now)
                next != null
            }
        }

        @Test
        fun `stable waits past a withheld screen to the shown one that settles`() =
            runTest {
                // Quiet but withheld until 1000 ms; from then on the loaded page, quiet.
                val screen = timeline({ currentTime }, 0L to withheld(loading), 1_000L to loaded)

                val result = wait("""{"condition":"stable","timeoutMs":5000}""", screen)

                assertEquals(ToolStatus.OK, result.status, result.text())
                assertEquals("画面已经稳定。\n" + formatter.page(loaded, SnapshotDetail.BRIEF), result.text())
                assertEquals("true", result.detail("met"))
                assertEquals("s21", result.detail("screen"))
                // 800 ms of quiet, the withheld read, one read interval, then 800 ms of quiet again.
                assertEquals(800 + READ_INTERVAL_MS + 800, currentTime)
            }

        @Test
        fun `a withheld quiet screen whose port answers at once is read once a read interval, not spun on`() =
            runTest {
                var looks = 0
                val screen =
                    FakeScreen(withheld(loaded)).apply {
                        // No change, without waiting: a port that breaks the contract must not spin the wait.
                        onAwaitChange = {
                            check(++looks < SPIN_LIMIT) { "the wait spins on a withheld screen" }
                            false
                        }
                    }

                val result = wait("""{"condition":"stable","timeoutMs":1000}""", screen)

                assertEquals(ToolStatus.ERROR, result.status)
                assertTrue(result.text().startsWith("这一屏不能显示，条件无法核实。"), result.text())
                assertEquals(1_000, currentTime)
                assertTrue(looks <= 1_000 / READ_INTERVAL_MS + 1, "looks: $looks")
            }

        @Test
        fun `stable cannot be checked when the screen is withheld at the deadline, shown in between or not`() =
            runTest {
                val cases =
                    listOf(
                        "withheld throughout" to arrayOf(0L to withheld(loaded)),
                        // Shown for less than a quiet period, then withheld again to the deadline.
                        "withheld, shown, withheld" to
                            arrayOf(0L to withheld(loading), 1_000L to loaded, 1_500L to withheld(loaded)),
                    )
                val wrong = mutableListOf<String>()
                for ((case, phases) in cases) {
                    val result = wait("""{"condition":"stable","timeoutMs":3000}""", timeline({ currentTime }, *phases))
                    val expected = "这一屏不能显示，条件无法核实。\n" + formatter.page(withheld(loaded), SnapshotDetail.BRIEF)
                    val right =
                        result.status == ToolStatus.ERROR && result.detail("met") == "false" &&
                            result.text() == expected
                    if (!right) wrong += "$case: ${result.status} ${result.detail("met")} ${result.text()}"
                }
                assertEquals(emptyList<String>(), wrong)
            }

        @Test
        fun `stable waits for a quiet period without changes`() =
            runTest {
                var changes = 3
                val screen =
                    FakeScreen(loaded).apply {
                        onAwaitChange = { timeout ->
                            if (changes-- > 0) {
                                delay(100)
                                true
                            } else {
                                delay(timeout)
                                false
                            }
                        }
                    }
                val result = wait("""{"condition":"stable"}""", screen)
                assertTrue(result.text().startsWith("画面已经稳定。"))
                // Each look after a change takes at least one read interval, then 800 ms of quiet.
                assertEquals(3 * READ_INTERVAL_MS + 800, currentTime)
            }

        @Test
        fun `stable gives up while the screen keeps changing`() =
            runTest {
                val screen =
                    FakeScreen(loaded).apply {
                        onAwaitChange = {
                            delay(100)
                            true
                        }
                    }
                val result = wait("""{"condition":"stable","timeoutMs":500}""", screen)
                assertEquals(ToolStatus.ERROR, result.status)
                assertTrue(result.text().startsWith("等了 0.5 秒，画面一直在变。"), result.text())
            }

        @Test
        fun `arguments are checked together`() =
            runTest {
                fun problems(result: ToolResult) = result.text().lines().filter { it.startsWith("- ") }
                val screen = FakeScreen()
                assertEquals(
                    listOf("- condition=stable 不用 `text`"),
                    problems(wait("""{"condition":"stable","text":"x"}""", screen)),
                )
                assertEquals(
                    listOf("- condition=appear 需要 `text`"),
                    problems(wait("""{"condition":"appear"}""", screen)),
                )
                assertEquals(
                    listOf("- condition=disappear 需要 `text`"),
                    problems(wait("""{"condition":"disappear"}""", screen)),
                )
                assertEquals(
                    listOf("- `timeoutMs` 应该是 500–30000 的整数（收到 60000）"),
                    problems(wait("""{"condition":"stable","timeoutMs":60000}""", screen)),
                )
                assertEquals(listOf("- 缺少 `condition`"), problems(wait("{}", screen)))
            }

        @Test
        fun `no screen and an unusable screen`() =
            runTest {
                assertEquals(ToolResults.NO_SCREEN, wait("""{"condition":"stable"}""", null))
                val gone = FakeScreen().apply { unavailable = ScreenUnavailableException("屏幕没了") }
                assertTrue(wait("""{"condition":"stable"}""", gone).text().startsWith("屏幕暂时用不了（屏幕没了）"))
            }

        @Test
        fun `every accepted wait timeout leaves room for a slow last read at the deadline`() =
            runTest {
                for (budget in listOf(30_001L, 44_999L, 45_000L, 60_000L)) {
                    val config = runCatching { ToolsConfig(waitTimeoutMs = budget) }.getOrNull() ?: continue
                    val tool = tools(config).getValue("phone_wait")
                    val start = currentTime
                    // The text is there at the deadline; reading the screen then takes 10 s of virtual time.
                    val screen =
                        FakeScreen().apply {
                            onSnapshot = {
                                if (currentTime - start >= PhoneWaitTool.MAX_WAIT_MS) {
                                    delay(SLOW_READ_MS)
                                    loaded
                                } else {
                                    loading
                                }
                            }
                        }
                    val args = """{"condition":"appear","text":"已完成","timeoutMs":30000}"""
                    val result =
                        withTimeoutOrNull(
                            tool.timeoutMs,
                        ) { tool.execute(call("phone_wait", args), FakeContext(screen)) }
                    assertEquals("true", result?.detail("met"), "waitTimeoutMs=$budget: ${result?.text()}")
                    assertEquals(PhoneWaitTool.MAX_WAIT_MS + SLOW_READ_MS, currentTime - start, "waitTimeoutMs=$budget")
                }
            }

        @Test
        fun `the read interval is a validated setting`() {
            assertEquals(READ_INTERVAL_MS, ToolsConfig().waitReadIntervalMs)
            assertThrows<IllegalArgumentException> { ToolsConfig(waitReadIntervalMs = 49) }
            assertThrows<IllegalArgumentException> { ToolsConfig(waitReadIntervalMs = 2_001) }
        }

        @Test
        fun `the timeout leaves room for the longest wait`() {
            val tool = tools().getValue("phone_wait")
            assertEquals(PhoneWaitTool.MAX_WAIT_MS + 15_000, tool.timeoutMs)
            assertThrows<IllegalArgumentException> { ToolsConfig(waitTimeoutMs = PhoneWaitTool.MAX_WAIT_MS) }
            assertThrows<IllegalArgumentException> { ToolsConfig(waitTimeoutMs = 30_001) }
            assertThrows<IllegalArgumentException> { ToolsConfig(waitTimeoutMs = 44_999) }
            assertEquals(45_000, ToolsConfig(waitTimeoutMs = 45_000).waitTimeoutMs)
            assertEquals(RiskClass.READ, tool.risk)
            assertTrue(tool.concurrencySafe)
        }
    }
}
