package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneIntentToolTest {
    private val tool = PhaseOneTools.create(ScreenFormatter()).single { it.def.name == "phone_intent" }

    private suspend fun intent(
        args: String,
        screen: FakeScreen? = FakeScreen(),
    ): ToolResult = tool.execute(call("phone_intent", args), FakeContext(screen))

    /** The intent sent for [args], with the status line. */
    private suspend fun sent(args: String): Pair<LaunchTarget, String> {
        val screen = FakeScreen()
        val result = intent(args, screen)
        assertEquals(ToolStatus.OK, result.status, result.text())
        return screen.launches.single() to result.text().lines().first()
    }

    private suspend fun problems(args: String): List<String> {
        val result = intent(args)
        assertEquals(ToolStatus.INVALID_ARGS, result.status, result.text())
        return result
            .text()
            .lines()
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ") }
    }

    private fun system(
        kind: SystemIntentKind,
        vararg fields: Pair<String, String>,
    ) = LaunchTarget.SystemIntent(kind, mapOf(*fields))

    @Test
    fun `every kind with its fields per contracts addendum 13`() =
        runTest {
            assertEquals(
                system(SystemIntentKind.ALARM, "time" to "07:05", "label" to "起床") to
                    "已发出系统请求：闹钟 07:05（起床），前台换成了另一个 App。",
                sent("""{"kind":"alarm","time":"7:05","label":" 起床 "}"""),
            )
            assertEquals(
                system(SystemIntentKind.TIMER, "seconds" to "300") to "已发出系统请求：倒计时 300 秒，前台换成了另一个 App。",
                sent("""{"kind":"timer","seconds":300}"""),
            )
            assertEquals(
                system(SystemIntentKind.TIMER, "seconds" to "60", "label" to "泡面"),
                sent("""{"kind":"timer","seconds":"60","label":"泡面"}""").first,
            )
            assertEquals(
                system(
                    SystemIntentKind.CALENDAR,
                    "title" to "开会",
                    "start" to "2026-11-02T10:00",
                    "end" to "2026-11-02T11:30",
                    "location" to "三楼",
                ) to
                    "已发出系统请求：新建日程「开会」2026-11-02T10:00，前台换成了另一个 App。",
                sent(
                    """{"kind":"calendar","title":"开会","start":"2026-11-02T10:00",""" +
                        """"end":"2026-11-02T11:30","location":"三楼"}""",
                ),
            )
            assertEquals(
                system(SystemIntentKind.SHARE, "text" to "卡号 6222021234567894") to "已发出系统请求：分享文字，前台换成了另一个 App。",
                sent("""{"kind":"share","text":"卡号 6222021234567894"}"""),
            )
            assertEquals(
                system(SystemIntentKind.DIAL, "number" to "+86 (010) 1234-5678") to
                    "已发出系统请求：打开拨号盘，号码 +86 (010) 1234-5678（没有拨出），前台换成了另一个 App。",
                sent("""{"kind":"dial","number":"+86 (010) 1234-5678"}"""),
            )
            assertEquals(
                system(SystemIntentKind.NAVIGATE, "place" to "北京南站") to "已发出系统请求：导航到「北京南站」，前台换成了另一个 App。",
                sent("""{"kind":"navigate","place":"北京南站"}"""),
            )
            assertEquals(
                system(SystemIntentKind.SETTINGS, "page" to "wifi") to "已发出系统请求：打开设置页 wifi，前台换成了另一个 App。",
                sent("""{"kind":"settings","page":"wifi"}"""),
            )
            assertEquals(
                system(SystemIntentKind.SETTINGS, "page" to "app_details", "package" to "com.tencent.mm") to
                    "已发出系统请求：打开设置页 app_details（com.tencent.mm），前台换成了另一个 App。",
                sent("""{"kind":"settings","page":"app_details","package":"com.tencent.mm"}"""),
            )
        }

    @Test
    fun `required and foreign fields are named`() =
        runTest {
            assertEquals(listOf("kind=alarm 需要 `time`"), problems("""{"kind":"alarm"}"""))
            assertEquals(
                listOf("kind=calendar 需要 `title`", "kind=calendar 需要 `start`"),
                problems("""{"kind":"calendar"}"""),
            )
            assertEquals(listOf("kind=share 不用 `label`"), problems("""{"kind":"share","text":"x","label":"y"}"""))
            assertEquals(
                listOf("kind=dial 不用 `package`"),
                problems("""{"kind":"dial","number":"10086","package":"com.a.b"}"""),
            )
            assertEquals(listOf("缺少 `kind`"), problems("{}"))
            assertEquals(
                listOf("`kind` 只能是 alarm / timer / calendar / share / dial / navigate / settings（收到 \"sms\"）"),
                problems("""{"kind":"sms"}"""),
            )
        }

    @Test
    fun `formats are checked strictly`() =
        runTest {
            assertEquals(listOf("`time` 应该是 24 小时制 HH:mm，如 07:30"), problems("""{"kind":"alarm","time":"24:00"}"""))
            assertEquals(listOf("`time` 应该是 24 小时制 HH:mm，如 07:30"), problems("""{"kind":"alarm","time":"7点"}"""))
            assertEquals(listOf("`seconds` 应该是 1–86400 的整数（收到 0）"), problems("""{"kind":"timer","seconds":0}"""))
            assertEquals(
                listOf("`start` 应该是 yyyy-MM-ddTHH:mm，如 2026-11-02T10:00（2026-02-30T10:00 无法识别）"),
                problems("""{"kind":"calendar","title":"a","start":"2026-02-30T10:00"}"""),
            )
            assertEquals(
                listOf("`end` 应该是 yyyy-MM-ddTHH:mm，如 2026-11-02T10:00（明天 无法识别）"),
                problems("""{"kind":"calendar","title":"a","start":"2026-11-02T10:00","end":"明天"}"""),
            )
            assertEquals(
                listOf("`end` 要晚于 `start`"),
                problems("""{"kind":"calendar","title":"a","start":"2026-11-02T10:00","end":"2026-11-02T10:00"}"""),
            )
            val badNumber = listOf("`number` 应该是电话号码，只能有数字和 + * # ( ) - 空格")
            assertEquals(badNumber, problems("""{"kind":"dial","number":"call 10086"}"""))
            assertEquals(badNumber, problems("""{"kind":"dial","number":"+-"}"""))
            val pages = IntentFields.SETTINGS_PAGES.joinToString(" / ")
            assertEquals("wifi", IntentFields.SETTINGS_PAGES.first())
            val developer = problems("""{"kind":"settings","page":"developer"}""")
            assertEquals(listOf("`page` 只能是 $pages（收到 \"developer\"）"), developer)
            assertEquals(
                listOf("page=app_details 需要 `package`"),
                problems("""{"kind":"settings","page":"app_details"}"""),
            )
            assertEquals(
                listOf("`package` 应该是包名，如 com.tencent.mm"),
                problems("""{"kind":"settings","page":"app_details","package":"微信"}"""),
            )
            assertEquals(
                listOf("只有 page=app_details 才用 `package`"),
                problems("""{"kind":"settings","page":"wifi","package":"com.a.b"}"""),
            )
        }

    @Test
    fun `no screen and an unusable screen`() =
        runTest {
            assertEquals(ToolResults.NO_SCREEN, intent("""{"kind":"timer","seconds":5}""", screen = null))
            val gone = FakeScreen().apply { unavailable = ScreenUnavailableException("屏幕没了") }
            assertTrue(intent("""{"kind":"timer","seconds":5}""", gone).text().startsWith("屏幕暂时用不了"))
            assertEquals(RiskClass.WRITE, tool.risk)
        }

    @Test
    fun `the description lists every settings page`() {
        IntentFields.SETTINGS_PAGES.forEach { assertTrue(tool.def.description.contains(it), it) }
    }
}
