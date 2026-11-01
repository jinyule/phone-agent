package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SystemIntentKind

/** The rows of the matrix that do not depend on the security level: refusals and hand-offs (phase1 §6). */
internal class HardRules(
    private val ownPackages: Set<String>,
    private val blocked: BlockedApps,
) {
    /**
     * Hard refusal: the app itself or a blocked app (also through its `app_details` settings page),
     * or a sensitive settings page being opened or acted on.
     */
    fun refusal(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Verdict? =
        app(intent.pkg)
            ?: app(detailsOf(step))
            ?: if (opensSensitiveSettings(step) || onSensitiveSettings(step, intent, screen)) settings() else null

    /** The app whose settings page `phone_intent kind=settings page=app_details package=…` opens. */
    private fun detailsOf(step: Step): String? = if (step is Step.Intent) step.fields["package"] else null

    private fun app(pkg: String?): Verdict? =
        when {
            pkg == null -> null
            pkg in ownPackages -> Refusal.OWN_APP.verdict()
            else -> CATEGORY_REFUSALS[blocked.categoryOf(pkg)]?.verdict()
        }

    private fun settings(): Verdict = Refusal.SENSITIVE_SETTINGS.verdict()

    private fun opensSensitiveSettings(step: Step): Boolean =
        (step is Step.Launch && step.link && Launches.opensSensitiveSettings(step.target)) ||
            (step is Step.Intent && step.kind == SystemIntentKind.SETTINGS && !Launches.safeSettingsPage(step.fields))

    /** Reading or acting on a sensitive settings page, or tapping the entry that opens one. */
    private fun onSensitiveSettings(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Boolean =
        (step is ScreenStep || step == Step.Observe) &&
            screen != null &&
            (Pages.isSensitiveSettings(screen) || intent.target?.let { Pages.sensitiveEntry(it, screen) } != null)

    /**
     * Hand-off: typing into a password, code or payment-password field; or interacting with a
     * payment-password, captcha, login or FLAG_SECURE page, unless the tap just closes or skips it.
     */
    fun handOff(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Verdict? {
        val page = if (screen != null && interacts(step) && !escapes(step, intent, screen)) page(screen) else null
        return (FIELD_HAND_OFFS[intent.fieldClass] ?: page)?.verdict()
    }

    /** Taps, typing, swipes and Enter; scrolling and reading only look. */
    private fun interacts(step: Step): Boolean = step is ScreenStep && step !is Step.Scroll

    private fun escapes(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot,
    ): Boolean = step is Step.Tap && Lexicon.isEscape(Targets.label(intent.target, screen))

    private fun page(screen: ScreenSnapshot): HandOffs? =
        when {
            Pages.isPaymentPassword(screen) -> HandOffs.PAYMENT
            Pages.isCaptcha(screen) -> HandOffs.CAPTCHA
            Pages.isLogin(screen) -> HandOffs.LOGIN
            ScreenFlag.SECURE in screen.flags -> HandOffs.SECURE_PAGE
            else -> null
        }
}

private val CATEGORY_REFUSALS =
    mapOf(
        AppCategory.BANK to Refusal.FINANCE_APP,
        AppCategory.BROKER to Refusal.FINANCE_APP,
        AppCategory.PAYMENT to Refusal.FINANCE_APP,
        AppCategory.SYSTEM to Refusal.SYSTEM_APP,
    )

private val FIELD_HAND_OFFS =
    mapOf(
        FieldClass.PASSWORD to HandOffs.PASSWORD,
        FieldClass.VERIFICATION_CODE to HandOffs.CODE,
        FieldClass.PAYMENT_PASSWORD to HandOffs.PAYMENT,
    )
