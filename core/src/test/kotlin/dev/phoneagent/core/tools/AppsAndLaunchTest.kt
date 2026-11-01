package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeCatalog
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AppsAndLaunchTest {
    private val tools = PhaseOneTools.create(ScreenFormatter(lexicon = Screens.LEXICON)).associateBy { it.def.name }
    private val catalog =
        FakeCatalog(
            listOf(
                AppEntry("com.tencent.mm", "微信", launchable = true),
                AppEntry("com.autonavi.minimap", "高德地图", launchable = true),
                AppEntry("com.baidu.BaiduMap", "百度地图", launchable = true),
                AppEntry("com.example.notes", "便签", launchable = true),
                AppEntry("com.example.notes2", "便签", launchable = true),
                AppEntry("com.android.systemui", "系统界面", launchable = false),
            ),
        )

    private fun problems(result: ToolResult) = result.text().lines().filter { it.startsWith("- ") }

    @Nested
    inner class Listing {
        private suspend fun apps(
            args: String = "{}",
            ctx: FakeContext = FakeContext(apps = catalog),
        ) = tools.getValue("phone_apps").execute(call("phone_apps", args), ctx)

        @Test
        fun `lists launchable apps sorted by name`() =
            runTest {
                val text = apps().text()
                assertEquals(
                    listOf(
                        "能打开的 App（5 个）：",
                        "便签 · com.example.notes",
                        "便签 · com.example.notes2",
                        "微信 · com.tencent.mm",
                        "百度地图 · com.baidu.BaiduMap",
                        "高德地图 · com.autonavi.minimap",
                    ),
                    text.lines(),
                )
                assertFalse(text.contains("systemui"))
            }

        @Test
        fun `filters by name or package, ignoring case and spaces`() =
            runTest {
                assertEquals(
                    listOf("能打开的 App（2 个，名字或包名包含「地图」）：", "百度地图 · com.baidu.BaiduMap", "高德地图 · com.autonavi.minimap"),
                    apps("""{"query":" 地 图 "}""").text().lines(),
                )
                assertEquals(
                    listOf("能打开的 App（1 个，名字或包名包含「baidumap」）：", "百度地图 · com.baidu.BaiduMap"),
                    apps("""{"query":"BaiduMap"}""").text().lines(),
                )
                assertEquals("没有找到能打开的 App，名字或包名包含「抖音」。", apps("""{"query":"抖音"}""").text())
                assertEquals("没有找到能打开的 App。", apps(ctx = FakeContext(apps = FakeCatalog(emptyList()))).text())
            }

        @Test
        fun `caps a long list and asks for a narrower query`() =
            runTest {
                val many = FakeCatalog((1..100).map { AppEntry("com.example.app$it", "应用$it", launchable = true) })
                val lines = apps(ctx = FakeContext(apps = many)).text().lines()
                assertEquals("能打开的 App（100 个）：", lines.first())
                assertEquals(82, lines.size)
                assertEquals("… 另有 20 个，换个更具体的 query", lines.last())
            }

        @Test
        fun `without a catalog it is an error, and arguments are checked`() =
            runTest {
                assertEquals(AppNames.NO_CATALOG, apps(ctx = FakeContext()))
                assertEquals(ToolStatus.INVALID_ARGS, apps("""{"query":""}""").status)
                assertEquals(ToolStatus.INVALID_ARGS, apps("""{"name":"微信"}""").status)
                val tool = tools.getValue("phone_apps")
                assertEquals(RiskClass.READ, tool.risk)
                assertTrue(tool.concurrencySafe)
            }
    }

    @Nested
    inner class Launching {
        private suspend fun launch(
            args: String,
            screen: FakeScreen? = FakeScreen(),
            apps: FakeCatalog? = catalog,
        ) = tools.getValue("phone_launch").execute(call("phone_launch", args), FakeContext(screen, apps))

        @Test
        fun `launches by exact name or package, cold when asked`() =
            runTest {
                val screen = FakeScreen()
                val byName = launch("""{"app":"微信"}""", screen)
                assertEquals(ToolStatus.OK, byName.status)
                assertTrue(byName.text().startsWith("已打开 微信（com.tencent.mm），前台换成了另一个 App。\nscreen s12"), byName.text())
                val cold = launch("""{"app":"COM.AUTONAVI.MINIMAP","cold":true}""", screen)
                assertTrue(cold.text().startsWith("已重新打开 高德地图（com.autonavi.minimap）"), cold.text())
                launch("""{"app":"高德 地图"}""", screen)
                assertEquals(
                    listOf(
                        LaunchTarget.App("com.tencent.mm"),
                        LaunchTarget.App("com.autonavi.minimap", cold = true),
                        LaunchTarget.App("com.autonavi.minimap"),
                    ),
                    screen.launches,
                )
            }

        @Test
        fun `suggests near matches and asks to disambiguate, never guesses`() =
            runTest {
                val screen = FakeScreen()
                val near = launch("""{"app":"地图"}""", screen)
                assertEquals(ToolStatus.ERROR, near.status)
                assertEquals(
                    "没有叫「地图」的 App。名字相近的有：高德地图 · com.autonavi.minimap、百度地图 · com.baidu.BaiduMap。" +
                        "用桌面上显示的准确名字或包名，或者先用 phone_apps 查一下。",
                    near.text(),
                )
                assertEquals(
                    "没有叫「抖音」的 App。用桌面上显示的准确名字或包名，或者先用 phone_apps 查一下。",
                    launch("""{"app":"抖音"}""", screen).text(),
                )
                assertEquals(
                    "有 2 个 App 都叫「便签」：便签 · com.example.notes、便签 · com.example.notes2。用包名再试一次。",
                    launch("""{"app":"便签"}""", screen).text(),
                )
                // Not launchable, so not found.
                assertEquals(ToolStatus.ERROR, launch("""{"app":"com.android.systemui"}""", screen).status)
                assertTrue(screen.launches.isEmpty())
            }

        @Test
        fun `without a catalog only package names work`() =
            runTest {
                val screen = FakeScreen()
                assertEquals(AppNames.NO_CATALOG_BY_NAME, launch("""{"app":"微信"}""", screen, apps = null))
                val byPackage = launch("""{"app":"com.tencent.mm"}""", screen, apps = null)
                assertTrue(byPackage.text().startsWith("已打开 com.tencent.mm，"), byPackage.text())
                assertEquals(listOf(LaunchTarget.App("com.tencent.mm")), screen.launches)
            }

        @Test
        fun `opens web links and deep links, refuses intent-carrying schemes`() =
            runTest {
                val screen = FakeScreen()
                val opened = launch("""{"link":"https://example.com/a?card=6222021234567894"}""", screen)
                assertTrue(opened.text().startsWith("已打开链接 https://example.com/a?card=「银行卡 尾号7894」"), opened.text())
                launch("""{"link":"weixin://dl/scan"}""", screen)
                assertEquals(
                    listOf(
                        LaunchTarget.Link("https://example.com/a?card=6222021234567894"),
                        LaunchTarget.Link("weixin://dl/scan"),
                    ),
                    screen.launches,
                )
                val refused =
                    listOf(
                        "intent://x#Intent;end",
                        "INTENT:#Intent;end",
                        "file:///sdcard/a",
                        "content://x/y",
                        "javascript:alert(1)",
                        "data:text/html,x",
                        "android-app://com.a",
                        "jar:file:x!/",
                        "no-scheme",
                        "https://a b",
                    )
                for (link in refused) {
                    assertEquals(listOf("- " + Links.REFUSED), problems(launch("""{"link":"$link"}""", screen)), link)
                }
                assertEquals(2, screen.launches.size)
            }

        @Test
        fun `app and link are exclusive, cold goes with app only`() =
            runTest {
                val both = launch("""{"app":"微信","link":"https://a.com"}""")
                assertEquals(listOf("- `app` 和 `link` 要给一个，而且只给一个"), problems(both))
                assertEquals(listOf("- `app` 和 `link` 要给一个，而且只给一个"), problems(launch("{}")))
                val coldLink = launch("""{"link":"https://a.com","cold":true}""")
                assertEquals(listOf("- `cold` 只能和 `app` 一起用"), problems(coldLink))
            }

        @Test
        fun `no screen, an unusable screen and launch outcomes`() =
            runTest {
                assertEquals(ToolResults.NO_SCREEN, launch("""{"app":"微信"}""", screen = null))
                val gone = FakeScreen().apply { unavailable = ScreenUnavailableException("屏幕没了") }
                assertTrue(launch("""{"link":"https://a.com"}""", gone).text().startsWith("屏幕暂时用不了"))
                val failure = ActionOutcome(ActionOutcome.Status.Failed("没有能打开这个链接的 App"), ActionOutcome.Effect.UNKNOWN)
                val failing = FakeScreen().apply { onLaunch = { failure } }
                val failed = launch("""{"link":"weixin://x"}""", failing)
                assertEquals(ToolStatus.ERROR, failed.status)
                assertTrue(failed.text().startsWith("没有做成：没有能打开这个链接的 App。"))
                val tool = tools.getValue("phone_launch")
                assertEquals(RiskClass.WRITE, tool.risk)
                assertFalse(tool.concurrencySafe)
            }
    }
}
