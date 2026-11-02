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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        fun `launches by package name, cold when asked`() =
            runTest {
                val screen = FakeScreen()
                val opened = launch("""{"app":"com.tencent.mm"}""", screen)
                assertEquals(ToolStatus.OK, opened.status)
                assertTrue(opened.text().startsWith("已打开 微信（com.tencent.mm），前台换成了另一个 App。\nscreen s12"), opened.text())
                val cold = launch("""{"app":"COM.AUTONAVI.MINIMAP","cold":true}""", screen)
                assertTrue(cold.text().startsWith("已重新打开 高德地图（com.autonavi.minimap）"), cold.text())
                assertEquals(
                    listOf(LaunchTarget.App("com.tencent.mm"), LaunchTarget.App("com.autonavi.minimap", cold = true)),
                    screen.launches,
                )
            }

        @Test
        fun `an app name is not a package name, so it is invalid and points to phone_apps`() =
            runTest {
                val screen = FakeScreen()
                for (apps in listOf(catalog, null)) {
                    for (name in listOf("微信", "高德 地图", "WeChat")) {
                        val result = launch("""{"app":"$name"}""", screen, apps)
                        assertEquals(ToolStatus.INVALID_ARGS, result.status, name)
                        assertEquals(
                            listOf("- `app` 要填包名，如 com.tencent.mm（收到 \"$name\"）：先用 phone_apps 查到包名再启动"),
                            problems(result),
                            name,
                        )
                    }
                }
                assertTrue(screen.launches.isEmpty())
            }

        @Test
        fun `a package that is not a launchable app is an error pointing to phone_apps`() =
            runTest {
                val screen = FakeScreen()
                for (pkg in listOf("com.android.systemui", "com.example.missing")) {
                    val result = launch("""{"app":"$pkg"}""", screen)
                    assertEquals(ToolStatus.ERROR, result.status, pkg)
                    assertEquals("手机上没有包名为 $pkg 的可打开的 App。先用 phone_apps 查到包名再启动。", result.text())
                }
                assertTrue(screen.launches.isEmpty())
            }

        @Test
        fun `without a catalog a package name is launched as given`() =
            runTest {
                val screen = FakeScreen()
                val byPackage = launch("""{"app":"com.tencent.mm"}""", screen, apps = null)
                assertTrue(byPackage.text().startsWith("已打开 com.tencent.mm，"), byPackage.text())
                assertEquals(listOf(LaunchTarget.App("com.tencent.mm")), screen.launches)
            }

        @Test
        fun `the description asks for package names and says what cold does in M1`() {
            val def = tools.getValue("phone_launch").def
            val properties = def.parameters["properties"]!!.jsonObject
            val text = { name: String ->
                properties
                    .getValue(name)
                    .jsonObject
                    .getValue("description")
                    .jsonPrimitive.content
            }
            assertTrue(def.description.contains("app 填包名（如 com.tencent.mm），不知道包名先用 phone_apps 查"), def.description)
            assertFalse(def.description.contains("名字（如 微信）"), def.description)
            assertTrue(text("app").startsWith("包名"), text("app"))
            for (cold in listOf(def.description, text("cold"))) {
                assertTrue(cold.contains("清掉这个 App 的任务栈") && cold.contains("进程不重启"), cold)
                assertFalse(cold.contains("先结束"), cold)
            }
        }

        @Test
        fun `opens web and store links, refuses intent-carrying schemes`() =
            runTest {
                val screen = FakeScreen()
                val opened = launch("""{"link":"https://example.com/a?card=6222021234567894"}""", screen)
                assertTrue(opened.text().startsWith("已打开链接 https://example.com/a?card=「疑似银行卡 尾号7894」"), opened.text())
                launch("""{"link":"market://details?id=com.tencent.mm"}""", screen)
                assertEquals(
                    listOf(
                        LaunchTarget.Link("https://example.com/a?card=6222021234567894"),
                        LaunchTarget.Link("market://details?id=com.tencent.mm"),
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
        fun `links are limited to the schemes the device opens`() =
            runTest {
                val screen = FakeScreen()
                val allowed =
                    listOf(
                        "https://a.com/x",
                        "HTTP://a.com",
                        "geo:0,0?q=西湖",
                        "mailto:a@example.com",
                        "market://details?id=com.a",
                    )
                for (link in allowed) {
                    assertEquals(ToolStatus.OK, launch("""{"link":"$link"}""", screen).status, link)
                }
                val refused =
                    listOf(
                        "weixin://dl/scan",
                        "tel:10086",
                        "sms:10086?body=hi",
                        "alipays://platformapi/startapp?appId=1",
                        "ftp://a.com/x",
                    )
                for (link in refused) {
                    assertEquals(ToolStatus.INVALID_ARGS, launch("""{"link":"$link"}""", screen).status, link)
                }
                assertEquals(allowed.map { LaunchTarget.Link(it) }, screen.launches)
                assertEquals(setOf("https", "http", "geo", "mailto", "market"), LaunchTarget.Link.SCHEMES)
                val description = tools.getValue("phone_launch").def.description
                assertFalse(description.contains("weixin"), description)
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
                assertEquals(ToolResults.NO_SCREEN, launch("""{"app":"com.tencent.mm"}""", screen = null))
                val gone = FakeScreen().apply { unavailable = ScreenUnavailableException("屏幕没了") }
                assertTrue(launch("""{"link":"https://a.com"}""", gone).text().startsWith("屏幕暂时用不了"))
                val failure = ActionOutcome(ActionOutcome.Status.Failed("没有能打开这个链接的 App"), ActionOutcome.Effect.UNKNOWN)
                val failing = FakeScreen().apply { onLaunch = { failure } }
                val failed = launch("""{"link":"market://details?id=com.a"}""", failing)
                assertEquals(ToolStatus.ERROR, failed.status)
                assertTrue(failed.text().startsWith("没有做成：没有能打开这个链接的 App。"))
                val tool = tools.getValue("phone_launch")
                assertEquals(RiskClass.WRITE, tool.risk)
                assertFalse(tool.concurrencySafe)
            }
    }
}
