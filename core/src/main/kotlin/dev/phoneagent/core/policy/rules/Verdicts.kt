package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.HandoffKind

/**
 * Refusals (policy-rules.md §6). [reason] is a stable rule id for logs and the trajectory;
 * [hint] tells the model what to do instead and names no list entry, word or threshold, so it
 * cannot be used to find a way around the rule.
 */
internal enum class Refusal(
    val reason: String,
    val hint: String,
) {
    OWN_APP(
        "hard.own_app",
        "The agent may not operate its own app. Leave this screen (phone_key back or home) and continue in " +
            "another app, or tell the user.",
    ),
    FINANCE_APP(
        "hard.finance_app",
        "Banking, brokerage and payment apps are off-limits to the agent. Do not try to reach them another " +
            "way. Tell the user this part is theirs to do, or end with end_not_done.",
    ),
    SYSTEM_APP(
        "hard.system_app",
        "System screens (status bar, app installer, permission and security managers) are off-limits to the " +
            "agent. Press back or home and continue another way, or tell the user.",
    ),
    SENSITIVE_SETTINGS(
        "hard.sensitive_settings",
        "Accessibility, developer, permission, security and account settings are off-limits to the agent. " +
            "Go back and continue another way, or ask the user to change this setting themselves.",
    ),
    SPEND_UNATTENDED(
        "spend.unattended",
        "Nobody can approve a payment in this unattended task, so it was not made. Do not try to pay " +
            "another way. End with end_not_done and say plainly that the payment still needs the user.",
    ),
    STALE_SCREEN(
        "call.stale_screen",
        "This call's ref is not from the latest screen. Call phone_observe, then use its refs together with its " +
            "screen id.",
    ),
    UNRESOLVED(
        "call.unresolved",
        "The arguments of this call could not be checked, so it was not run. Fix the arguments and try again.",
    ),
    UNKNOWN_TOOL(
        "call.unknown_tool",
        "This tool is not covered by the safety policy, so it was not run. Use another tool.",
    ),
    ;

    fun verdict(): Verdict.Refuse = Verdict.Refuse(reason, hint)
}

/** Hand-offs (policy-rules.md §6). [hint] is for the user, in English; the app localizes by [kind]. */
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
