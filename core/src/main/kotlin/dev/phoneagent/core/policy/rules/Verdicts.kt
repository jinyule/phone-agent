package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.HandoffKind

/**
 * Refusals (policy-rules.md §6), the only place their model-visible text is written. [reason] is
 * a stable rule id for logs and the trajectory; [hint] tells the model, in Chinese like the tool
 * descriptions, what to do instead, and names no list entry, word or threshold, so it cannot be
 * used to find a way around the rule.
 */
internal enum class Refusal(
    val reason: String,
    val hint: String,
) {
    OWN_APP(
        "hard.own_app",
        "智能体不能操作自己的 App。请离开这个界面（phone_key back 或 home），在别的 App 里继续，或者告诉用户。",
    ),
    FINANCE_APP(
        "hard.finance_app",
        "银行、证券和支付类 App 不允许智能体操作，也不要绕道进入。请告诉用户这一步要由用户自己完成，或者用 end_not_done 结束。",
    ),
    SYSTEM_APP(
        "hard.system_app",
        "系统界面（状态栏、安装器、权限和安全管理）不允许智能体操作。请按返回或主屏键（phone_key back 或 home）换一种方式继续，或者告诉用户。",
    ),
    SENSITIVE_SETTINGS(
        "hard.sensitive_settings",
        "无障碍、开发者、权限、安全和账户等设置不允许智能体打开或修改。请返回（phone_key back）换一种方式继续，或者请用户自己改这项设置。",
    ),
    APP_MANAGEMENT(
        "hard.app_management",
        "不能在系统设置里强行停止、清除数据、卸载 App 或修改权限。请返回（phone_key back），或者告诉用户需要用户自己操作。",
    ),
    SPEND_UNATTENDED(
        "spend.unattended",
        "这是无人值守的任务，没有人能批准付款，所以没有付款。不要换别的方式付款。请用 end_not_done 结束，并说明付款还需要用户本人完成。",
    ),
    STALE_SCREEN(
        "call.stale_screen",
        "这次调用里的 ref 不是最新一屏的。请先调用 phone_observe，再用它给出的 ref 和 screen 编号。",
    ),
    NO_SCREEN(
        "call.no_screen",
        "还没有可用的屏幕，不能执行这个动作。请先调用 phone_observe 看清当前屏幕。",
    ),
    LAUNCH_PACKAGE(
        "call.launch_package",
        "phone_launch 的 app 要写包名。请先用 phone_apps 查到包名再启动。",
    ),
    LINK_SCHEME(
        "call.link_scheme",
        "这个链接打不开：只能打开网页、地图、邮件和应用商店链接。要打开 App，请先用 phone_apps 查到包名，再用 phone_launch 的 app 参数启动。",
    ),
    UNRESOLVED(
        "call.unresolved",
        "这次调用的参数无法检查，没有执行。请修正参数后重试。",
    ),
    UNKNOWN_TOOL(
        "call.unknown_tool",
        "安全策略不认识这个工具，没有执行。请换一个工具。",
    ),
    ;

    fun verdict(): Verdict.Refuse = Verdict.Refuse(reason, hint)
}

/**
 * Hand-offs (policy-rules.md §6). [hint] is for the user, in English as the contract says
 * (`Verdict.HandOff.hint`); the app localizes by [kind].
 */
internal enum class HandOffs(
    val kind: HandoffKind,
    val hint: String,
) {
    LOGIN(HandoffKind.LOGIN, "Please sign in yourself, then tap Done."),
    PASSWORD(HandoffKind.PASSWORD, "Please enter the password yourself, then tap Done."),
    CODE(HandoffKind.CAPTCHA, "Please enter the verification code yourself, then tap Done."),
    CAPTCHA(HandoffKind.CAPTCHA, "Please complete the verification yourself, then tap Done."),
    PAYMENT(HandoffKind.PAYMENT, "Please confirm the payment yourself, then tap Done."),
    SECURE_PAGE(
        HandoffKind.SECURE_PAGE,
        "This page hides its content from the agent. Please do this step yourself, then tap Done.",
    ),
    ;

    fun verdict(): Verdict.HandOff = Verdict.HandOff(kind, hint)
}
