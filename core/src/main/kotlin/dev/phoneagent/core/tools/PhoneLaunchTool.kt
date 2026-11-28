package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.PACKAGE_NAME
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus

/**
 * `phone_launch(app | link, cold?)` (phase1 §5). `app` is a package name only: anything else is
 * an argument error that points to `phone_apps`, so a launch never guesses from a label. With
 * [ToolContext.apps] the package must be a launchable app there (matched case-insensitively and
 * named by its label in the result); without a catalog it is launched as given. `cold` is
 * [LaunchTarget.App.cold]. `link` must have a scheme of [LaunchTarget.Link.SCHEMES] (web pages,
 * geo, mailto, market); anything else is refused at the boundary, the same way the adapter would.
 */
internal class PhoneLaunchTool(
    private val presenter: ScreenPresenter,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "phone_launch",
        description =
            "打开 App 或链接。app 填包名（如 com.tencent.mm），不知道包名先用 phone_apps 查；" +
                "cold=true 清掉这个 App 的任务栈，从首页重新打开（进程不重启）。" +
                "link 填网址（https://…）或 geo:、mailto:、market: 链接；App 的深链打不开，打开 App 用 app。" +
                "app 和 link 只填一个。返回打开后的一屏。",
        mask = presenter::mask,
        declare = {
            string("app", "包名，如 com.tencent.mm。只认包名，App 名字要先用 phone_apps 查到包名。")
            string("link", "网址（https://… 或 http://…），或 geo:、mailto:、market: 链接。")
            boolean("cold", "true 表示清掉这个 App 的任务栈，从首页重新打开（进程不重启）。只和 app 一起用。")
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
        val app = args.string("app", max = MAX_NAME)?.trim()
        val link = args.string("link", max = MAX_LINK)?.trim()
        val cold = args.boolean("cold") ?: false
        when {
            args.has("app") == args.has("link") -> args.problem("`app` 和 `link` 要给一个，而且只给一个")
            link != null && cold -> args.problem("`cold` 只能和 `app` 一起用")
            link != null && !Links.allowed(link) -> args.problem(Links.REFUSED)
            app != null && !PACKAGE_NAME.matches(app) -> args.problem(notAPackage(app))
        }
        val plan: Launch? = app?.let { Launch.ByApp(it, cold) } ?: link?.let { Launch.ByLink(it) }
        return when (val parsed = args.finish(USAGE, plan)) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> launch(ctx, parsed.value)
        }
    }

    private suspend fun launch(
        ctx: ToolContext,
        plan: Launch,
    ): ToolResult {
        val screen = ctx.screen ?: return ToolResults.NO_SCREEN
        val resolved =
            when (plan) {
                is Launch.ByLink -> Resolved(LaunchTarget.Link(plan.uri), "已打开链接 ${presenter.mask(plan.uri)}")
                is Launch.ByApp -> resolveApp(plan, ctx.apps)
            }
        return when (resolved) {
            is Unresolved -> {
                resolved.result
            }

            is Resolved -> {
                when (val launched = screenCall { screen.launch(resolved.target) }) {
                    is ScreenCall.Done -> ToolResults.outcome(resolved.done, launched.value, null, presenter)
                    is ScreenCall.Unavailable -> ToolResults.unavailable(launched.error, presenter)
                }
            }
        }
    }

    private suspend fun resolveApp(
        plan: Launch.ByApp,
        catalog: AppCatalog?,
    ): Resolution {
        val verb = if (plan.cold) "已重新打开 " else "已打开 "
        val entries = catalog?.apps()?.filter { it.launchable }
        val entry = entries?.firstOrNull { it.packageName.equals(plan.packageName, ignoreCase = true) }
        return when {
            entries == null -> {
                Resolved(LaunchTarget.App(plan.packageName, plan.cold), verb + plan.packageName)
            }

            entry == null -> {
                Unresolved(
                    ToolResult.failure(
                        ToolStatus.ERROR,
                        "手机上没有包名为 ${plan.packageName} 的可打开的 App。先用 phone_apps 查到包名再启动。",
                    ),
                )
            }

            else -> {
                Resolved(
                    LaunchTarget.App(entry.packageName, plan.cold),
                    verb + entry.label.trim() + "（" + entry.packageName + "）",
                )
            }
        }
    }

    private fun notAPackage(app: String): String =
        "`app` 要填包名，如 com.tencent.mm（收到 \"${clipShown(app)}\"）：先用 phone_apps 查到包名再启动"

    private sealed interface Launch {
        data class ByApp(
            val packageName: String,
            val cold: Boolean,
        ) : Launch

        data class ByLink(
            val uri: String,
        ) : Launch
    }

    private sealed interface Resolution

    private data class Resolved(
        val target: LaunchTarget,
        val done: String,
    ) : Resolution

    private data class Unresolved(
        val result: ToolResult,
    ) : Resolution

    private companion object {
        const val USAGE = "phone_launch(app 或 link, cold?)"
        const val MAX_NAME = 100
        const val MAX_LINK = 2000
    }
}

/** Which links `phone_launch` may open: the shared [LaunchTarget.Link.SCHEMES]. */
internal object Links {
    /** Problem text for a refused link. */
    const val REFUSED: String =
        "`link` 只能是网址（https://…、http://…）或 geo:、mailto:、market: 链接；" +
            "App 深链、intent:、file: 这类链接不能打开。打开 App 用 `app`"

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):\\S+$")

    /** Whether [uri] has no whitespace and a scheme of [LaunchTarget.Link.SCHEMES]. */
    fun allowed(uri: String): Boolean {
        val scheme =
            SCHEME
                .find(uri)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return scheme != null && scheme in LaunchTarget.Link.SCHEMES
    }
}
