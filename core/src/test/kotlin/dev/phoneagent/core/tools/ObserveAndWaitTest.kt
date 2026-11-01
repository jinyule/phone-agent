package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.detail
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveAndWaitTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)

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
        ): Pair<ToolResult, FakeContext> {
            val ctx = FakeContext(screen)
            return tools(
                ToolsConfig(vision = vision),
            ).getValue("phone_observe").execute(call("phone_observe", args), ctx) to
                ctx
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
        fun `reports frames that could not be attached`() =
            runTest {
                val none = FakeScreen(sparse).apply { frame = null }
                assertTrue(observe(screen = none).first.text().endsWith("（截不到图）"))
                val gif = FakeScreen(sparse).apply { frame = Frame(byteArrayOf(1), "image/gif", 10, 10, som = true) }
                val (gifResult, gifCtx) = observe(screen = gif)
                assertTrue(gifResult.text().endsWith("（截图格式 image/gif 不支持，没有附图）"))
                assertTrue(gifCtx.blobs.isEmpty())
                val failing = FakeScreen(sparse).apply { frameError = ScreenUnavailableException("截图服务断开") }
                val failed = observe(screen = failing).first
                assertEquals(ToolStatus.OK, failed.status)
                assertTrue(failed.text().endsWith("（截图失败：截图服务断开）"))
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
        fun `is a concurrency-safe read`() {
            val tool = tools().getValue("phone_observe")
            assertEquals(RiskClass.READ, tool.risk)
            assertTrue(tool.concurrencySafe)
            assertEquals(20_000, tool.timeoutMs)
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
                assertEquals(300 + 800, currentTime)
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
        fun `the timeout leaves room for the longest wait`() {
            val tool = tools().getValue("phone_wait")
            assertTrue(tool.timeoutMs > PhoneWaitTool.MAX_WAIT_MS)
            assertEquals(RiskClass.READ, tool.risk)
            assertTrue(tool.concurrencySafe)
        }
    }
}
