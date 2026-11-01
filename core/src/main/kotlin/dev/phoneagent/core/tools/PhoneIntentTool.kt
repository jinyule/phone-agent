package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.PACKAGE_NAME
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * `phone_intent(kind, …)` (phase1 §5): one of the whitelisted system intents, never an arbitrary
 * Intent. Field keys and formats are fixed by contracts addendum §13 and checked here; the
 * platform maps them to Android intents. The policy refines the risk per kind (share is
 * outward, settings changes setup).
 */
internal class PhoneIntentTool(
    private val presenter: ScreenPresenter,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "phone_intent",
        description =
            "用系统功能直接完成常见操作，不用先找到对应的 App。kind 和要填的字段：" +
                "alarm 设闹钟（time 如 07:30，label?）；timer 倒计时（seconds，label?）；" +
                "calendar 新建日程（title，start 如 2026-11-02T10:00，end?，location?）；share 分享文字（text）；" +
                "dial 打开拨号盘并填好号码，不会拨出（number）；navigate 导航（place）；" +
                "settings 打开设置页（page：" + IntentFields.SETTINGS_PAGES.joinToString(" / ") +
                "；app_details 还要填 package）。",
        mask = presenter::mask,
        declare = {
            string("kind", "要用的系统功能。", required = true, enum = IntentFields.KINDS.keys.toList())
            string("time", "alarm：时间，24 小时制 HH:mm，如 07:30。")
            string("label", "alarm、timer：名称，可不填。")
            integer("seconds", "timer：倒计时秒数。", IntentFields.SECONDS)
            string("title", "calendar：日程标题。")
            string("start", "calendar：开始时间，yyyy-MM-ddTHH:mm，如 2026-11-02T10:00。")
            string("end", "calendar：结束时间，格式同 start，可不填。")
            string("location", "calendar：地点，可不填。")
            string("text", "share：要分享的文字。")
            string("number", "dial：电话号码。")
            string("place", "navigate：目的地，地名或地址。")
            string("page", "settings：设置页。", enum = IntentFields.SETTINGS_PAGES)
            string("package", "settings 且 page=app_details：App 的包名。")
        },
    ) {
    override val group: ToolGroup = ToolGroup.PHONE
    override val risk: RiskClass = RiskClass.WRITE
    override val concurrencySafe: Boolean = false

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        val kind = args.choice("kind", IntentFields.KINDS, required = true)
        val fields = kind?.let { IntentFields.read(args, it) }
        val target = if (kind != null && fields != null) LaunchTarget.SystemIntent(kind, fields) else null
        return when (val parsed = args.finish(IntentFields.USAGE, target)) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> send(ctx, parsed.value)
        }
    }

    private suspend fun send(
        ctx: ToolContext,
        target: LaunchTarget.SystemIntent,
    ): ToolResult {
        val screen = ctx.screen ?: return ToolResults.NO_SCREEN
        val done = presenter.mask("已发出系统请求：" + IntentFields.describe(target))
        return when (val launched = screenCall { screen.launch(target) }) {
            is ScreenCall.Done -> ToolResults.outcome(done, launched.value, null, presenter)
            is ScreenCall.Unavailable -> ToolResults.unavailable(launched.error)
        }
    }
}

/** Fields of each [SystemIntentKind] (contracts addendum §13) and their checks. */
internal object IntentFields {
    /** Wire names of the kinds. */
    val KINDS: Map<String, SystemIntentKind> = SystemIntentKind.entries.associateBy { it.name.lowercase() }

    /** Settings pages that may be opened; accessibility, developer, permission and security pages are not. */
    val SETTINGS_PAGES: List<String> =
        listOf(
            "wifi",
            "bluetooth",
            "display",
            "sound",
            "battery",
            "date",
            "locale",
            "location",
            "storage",
            "app_details",
        )

    /** Timer length bounds, seconds. */
    val SECONDS: LongRange = 1L..86_400L

    /** Usage line for errors. */
    const val USAGE: String = "phone_intent(kind, 以及这个 kind 需要的字段，见工具说明)"

    private val ALL =
        listOf(
            "time",
            "label",
            "seconds",
            "title",
            "start",
            "end",
            "location",
            "text",
            "number",
            "place",
            "page",
            "package",
        )
    private val REQUIRED =
        mapOf(
            SystemIntentKind.ALARM to listOf("time"),
            SystemIntentKind.TIMER to listOf("seconds"),
            SystemIntentKind.CALENDAR to listOf("title", "start"),
            SystemIntentKind.SHARE to listOf("text"),
            SystemIntentKind.DIAL to listOf("number"),
            SystemIntentKind.NAVIGATE to listOf("place"),
            SystemIntentKind.SETTINGS to listOf("page"),
        )
    private val OPTIONAL =
        mapOf(
            SystemIntentKind.ALARM to listOf("label"),
            SystemIntentKind.TIMER to listOf("label"),
            SystemIntentKind.CALENDAR to listOf("end", "location"),
            SystemIntentKind.SETTINGS to listOf("package"),
        )
    private val TIME = Regex("([01]?[0-9]|2[0-3]):([0-5][0-9])")
    private val PHONE = Regex("[0-9+*#() -]{3,32}")
    private val DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private const val MAX_FIELD = 200
    private const val MAX_SHARE = 4000
    private const val MIN_DIGITS = 3

    /** The fields for [kind] read from [args], or `null` after recording problems. */
    fun read(
        args: ArgReader,
        kind: SystemIntentKind,
    ): Map<String, String>? {
        val wire = kind.name.lowercase()
        val allowed = REQUIRED.getValue(kind) + OPTIONAL[kind].orEmpty()
        ALL.filter { args.has(it) && it !in allowed }.forEach { args.problem("kind=$wire 不用 `$it`") }
        REQUIRED.getValue(kind).filterNot(args::has).forEach { args.problem("kind=$wire 需要 `$it`") }
        val fields = LinkedHashMap<String, String>()
        for (key in allowed) {
            val value = value(args, key) ?: continue
            fields[key] = value
        }
        checkTogether(args, kind, fields)
        return fields.takeIf { REQUIRED.getValue(kind).all(it::containsKey) }
    }

    /** How the request reads in the status line, e.g. `闹钟 07:30`. */
    fun describe(target: LaunchTarget.SystemIntent): String {
        val f = target.fields
        return when (target.kind) {
            SystemIntentKind.ALARM -> "闹钟 ${f["time"]}" + f["label"]?.let { "（$it）" }.orEmpty()
            SystemIntentKind.TIMER -> "倒计时 ${f["seconds"]} 秒" + f["label"]?.let { "（$it）" }.orEmpty()
            SystemIntentKind.CALENDAR -> "新建日程「${f["title"]}」${f["start"]}"
            SystemIntentKind.SHARE -> "分享文字"
            SystemIntentKind.DIAL -> "打开拨号盘，号码 ${f["number"]}（没有拨出）"
            SystemIntentKind.NAVIGATE -> "导航到「${f["place"]}」"
            SystemIntentKind.SETTINGS -> "打开设置页 ${f["page"]}" + f["package"]?.let { "（$it）" }.orEmpty()
        }
    }

    private fun value(
        args: ArgReader,
        key: String,
    ): String? =
        when (key) {
            "seconds" -> args.integer(key, SECONDS)?.toString()
            "time" -> args.string(key)?.trim()?.let { time(args, it) }
            "start", "end" -> args.string(key)?.trim()?.let { dateTime(args, key, it) }
            "number" -> args.string(key)?.trim()?.let { phone(args, it) }
            "page" -> args.choice(key, SETTINGS_PAGES.associateWith { it })
            "package" -> args.string(key)?.trim()?.let { packageName(args, it) }
            "text" -> args.string(key, max = MAX_SHARE)
            else -> args.string(key, max = MAX_FIELD)?.trim()
        }

    private fun time(
        args: ArgReader,
        text: String,
    ): String? {
        val match = TIME.matchEntire(text) ?: return null.also { args.problem("`time` 应该是 24 小时制 HH:mm，如 07:30") }
        return match.groupValues[1].padStart(2, '0') + ":" + match.groupValues[2]
    }

    private fun dateTime(
        args: ArgReader,
        key: String,
        text: String,
    ): String? =
        try {
            LocalDateTime.parse(text, DATE_TIME).format(DATE_TIME)
        } catch (malformed: DateTimeParseException) {
            // Model input: tell it the expected shape instead of failing the call.
            args.problem("`$key` 应该是 yyyy-MM-ddTHH:mm，如 2026-11-02T10:00（${malformed.parsedString} 无法识别）")
            null
        }

    private fun packageName(
        args: ArgReader,
        text: String,
    ): String? =
        text.takeIf { PACKAGE_NAME.matches(it) } ?: null.also { args.problem("`package` 应该是包名，如 com.tencent.mm") }

    private fun phone(
        args: ArgReader,
        text: String,
    ): String? =
        text.takeIf { PHONE.matches(it) && it.count(Char::isDigit) >= MIN_DIGITS }
            ?: null.also { args.problem("`number` 应该是电话号码，只能有数字和 + * # ( ) - 空格") }

    private fun checkTogether(
        args: ArgReader,
        kind: SystemIntentKind,
        fields: Map<String, String>,
    ) {
        val start = fields["start"]?.let { LocalDateTime.parse(it, DATE_TIME) }
        val end = fields["end"]?.let { LocalDateTime.parse(it, DATE_TIME) }
        if (start != null && end != null && !end.isAfter(start)) args.problem("`end` 要晚于 `start`")
        val appDetails = kind == SystemIntentKind.SETTINGS && fields["page"] == "app_details"
        if (appDetails && "package" !in fields && !args.has("package")) args.problem("page=app_details 需要 `package`")
        if (!appDetails && args.has("package") &&
            kind == SystemIntentKind.SETTINGS
        ) {
            args.problem("只有 page=app_details 才用 `package`")
        }
    }
}
