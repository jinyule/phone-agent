package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.AppEntry
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus

/** `phone_apps(query?)` (phase1 §5): lists launchable apps as `名字 · 包名`, filtered by [query]. */
internal class PhoneAppsTool(
    presenter: ScreenPresenter,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "phone_apps",
        description = "列出手机上能打开的 App（名字 · 包名）。query 按名字或包名筛选，不区分大小写。",
        mask = presenter::mask,
        declare = {
            string("query", "名字或包名里包含的文字，如 地图、tencent。不填列出全部。")
        },
    ) {
    override val group: ToolGroup = ToolGroup.PHONE
    override val risk: RiskClass = RiskClass.READ
    override val concurrencySafe: Boolean = true

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        val query = args.string("query", max = MAX_QUERY)?.let(AppNames::normalize)
        return when (val parsed = args.finish(USAGE, Unit)) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> list(ctx, query)
        }
    }

    private suspend fun list(
        ctx: ToolContext,
        query: String?,
    ): ToolResult {
        val catalog = ctx.apps ?: return AppNames.NO_CATALOG
        val matching =
            catalog
                .apps()
                .filter { it.launchable && (query == null || AppNames.matches(it, query)) }
                .sortedWith(compareBy<AppEntry> { it.label }.thenBy { it.packageName })
        val scope = query?.let { "，名字或包名包含「$it」" }.orEmpty()
        val text =
            if (matching.isEmpty()) {
                "没有找到能打开的 App$scope。"
            } else {
                val shown = matching.take(MAX_LISTED).map(AppNames::line)
                val more = (matching.size - shown.size).takeIf { it > 0 }?.let { "… 另有 $it 个，换个更具体的 query" }
                (listOf("能打开的 App（${matching.size} 个$scope）：") + shown + listOfNotNull(more)).joinToString("\n")
            }
        return ToolResult.ok(text)
    }

    private companion object {
        const val USAGE = "phone_apps(query?)"
        const val MAX_QUERY = 100
        const val MAX_LISTED = 80
    }
}

/** App-name matching of `phone_apps`. */
internal object AppNames {
    /** `ctx.apps` is `null`: the platform cannot list apps. */
    val NO_CATALOG: ToolResult =
        ToolResult.failure(
            ToolStatus.ERROR,
            "这台手机上拿不到 App 列表。可以直接用包名打开（phone_launch app=com.example.app），否则 end_not_done(cause=blocked)。",
        )

    /** [text] for matching: no whitespace, lower case. */
    fun normalize(text: String): String = text.filterNot(Char::isWhitespace).lowercase()

    /** Whether [entry]'s label or package contains the normalized [query]. */
    fun matches(
        entry: AppEntry,
        query: String,
    ): Boolean = normalize(entry.label).contains(query) || entry.packageName.lowercase().contains(query)

    /** `微信 · com.tencent.mm`. */
    fun line(entry: AppEntry): String = entry.label.trim().replace('\n', ' ') + " · " + entry.packageName
}
