package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SystemIntentKind
import java.util.Locale

/**
 * The apps the agent never operates: this app and the hard-refusal list. Copies [ownPackages]
 * and [ownLabels] when built, so later changes to the caller's sets have no effect.
 */
internal class RefusedApps(
    ownPackages: Set<String>,
    ownLabels: Set<String>,
    private val blocked: BlockedApps,
) {
    private val packages: Set<String> = ownPackages.map { it.lowercase(Locale.ROOT) }.toSet()
    private val labels: Set<String> = ownLabels.map(Text::compact).toSet()

    /** Why [pkg] may not be operated, or `null`. Package names compare case-insensitively. */
    fun byPackage(pkg: String?): Refusal? =
        when {
            pkg == null -> null
            pkg.lowercase(Locale.ROOT) in packages -> Refusal.OWN_APP
            else -> CATEGORY_REFUSALS[blocked.categoryOf(pkg)]
        }

    /** Why the app shown as [text] (its label or package name, on an icon or a settings page) may not be operated. */
    fun byName(text: String): Refusal? =
        if (Text.compact(text) in
            labels
        ) {
            Refusal.OWN_APP
        } else {
            CATEGORY_REFUSALS[blocked.categoryNamed(text)] ?: byPackage(text.trim())
        }
}

/** The rows of the matrix that do not depend on the security level: refusals and hand-offs (phase1 §6). */
internal class HardRules(
    private val apps: RefusedApps,
    private val reading: Reading,
) {
    private val settings = reading.settings

    /**
     * Hard refusal: this app or a blocked app (also through its `app_details` settings page); a
     * sensitive settings page opened, shown or acted on; an app settings page of a refused app, or
     * of an app it cannot tell, stopping, clearing or uninstalling it; a launcher icon of a refused app.
     */
    fun refusal(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Verdict? {
        val onScreen =
            if (screen != null &&
                (step is ScreenStep || step == Step.Observe)
            ) {
                shown(step, intent, screen)
            } else {
                null
            }
        return (apps.byPackage(intent.pkg) ?: apps.byPackage(detailsOf(step)) ?: opened(step) ?: onScreen)?.verdict()
    }

    /** The app whose settings page `phone_intent kind=settings page=app_details package=…` opens. */
    private fun detailsOf(step: Step): String? = if (step is Step.Intent) step.fields["package"] else null

    private fun opened(step: Step): Refusal? =
        Refusal.SENSITIVE_SETTINGS.takeIf {
            step is Step.Intent && step.kind == SystemIntentKind.SETTINGS && !Launches.safeSettingsPage(step.fields)
        }

    private fun shown(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot,
    ): Refusal? {
        val details = settings.isAppDetails(screen)
        val managed = if (details) reading.pages.shortTexts(screen).firstNotNullOfOrNull(apps::byName) else null
        val texts = touched(step, intent, screen)
        return when {
            settings.isSensitive(screen) -> Refusal.SENSITIVE_SETTINGS
            managed != null -> managed
            settings.sensitiveEntry(texts, screen) != null -> Refusal.SENSITIVE_SETTINGS
            details && settings.managesApp(texts) -> Refusal.APP_MANAGEMENT
            screen.pkg in LAUNCHERS -> texts.firstNotNullOfOrNull(apps::byName)
            else -> null
        }
    }

    /** The visible text on everything [step] may activate: for a tap every control it may reach, else its target. */
    private fun touched(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot,
    ): List<String> {
        val target = intent.target
        return when {
            step is Step.Tap -> reading.risks.tap(step.aim, screen).texts
            target != null -> reading.risks.texts(target, screen)
            else -> emptyList()
        }
    }

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
    ): Boolean {
        val target = intent.target
        return step is Step.Tap && target != null && reading.lexicon.isEscape(Elements.label(target, screen))
    }

    private fun page(screen: ScreenSnapshot): HandOffs? {
        val pages = reading.pages
        return when {
            pages.isPaymentPassword(screen) -> HandOffs.PAYMENT
            pages.isCaptcha(screen) -> HandOffs.CAPTCHA
            pages.isLogin(screen) -> HandOffs.LOGIN
            ScreenFlag.SECURE in screen.flags -> HandOffs.SECURE_PAGE
            else -> null
        }
    }
}

/** Home screens and app choosers: tapping an app's name there opens that app. */
private val LAUNCHERS =
    setOf(
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.sec.android.app.launcher",
        "com.bbk.launcher2",
        "com.oppo.launcher",
        "net.oneplus.launcher",
        "com.android.intentresolver",
        "android",
    )

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
