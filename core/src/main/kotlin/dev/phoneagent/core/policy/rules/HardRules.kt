package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
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
     * sensitive settings page opened, shown or acted on; on the settings pages of one app (info,
     * storage, their dialogs), the whole page of a refused app, and for any other app (or one it
     * cannot tell) stopping, clearing, disabling or uninstalling it, also by confirming a dialog
     * that says so; a launcher icon of a refused app; any action on or reading of a screen beyond
     * the limits ([Reading.readable]), since none of this can be checked there.
     */
    fun refusal(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Verdict? {
        val onScreen =
            when {
                screen == null || (step !is ScreenStep && step != Step.Observe) -> null
                !reading.readable(screen) -> Refusal.UNREADABLE_SCREEN
                else -> shown(step, intent, screen)
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
        val reached = reached(step, screen)
        val texts =
            if (reached ==
                null
            ) {
                intent.target?.let { reading.risks.texts(it, screen) }.orEmpty()
            } else {
                reached.texts
            }
        return when {
            settings.isSensitive(screen) -> {
                Refusal.SENSITIVE_SETTINGS
            }

            managed != null -> {
                managed
            }

            settings.sensitiveEntry(texts, screen) != null -> {
                Refusal.SENSITIVE_SETTINGS
            }

            details && (settings.managesApp(texts) || confirmsManaging(reached, intent, screen)) -> {
                Refusal.APP_MANAGEMENT
            }

            screen.pkg in LAUNCHERS -> {
                texts.firstNotNullOfOrNull(apps::byName)
            }

            else -> {
                null
            }
        }
    }

    /**
     * Everything [step] may activate (policy-rules.md §6): for a tap every control it may reach,
     * for a swipe every one where it starts, for a key (Enter: the engine lets the others through
     * before the hard rules) every one the focused control may be; `null` for a step that acts on
     * its target alone.
     */
    private fun reached(
        step: Step,
        screen: ScreenSnapshot,
    ): Assessment? =
        when {
            step is Step.Tap -> reading.risks.tap(step.aim, step.long, screen)
            step is Step.Swipe -> reading.risks.at(step.from, long = false, screen)
            step is Step.Key -> reading.risks.pressed(screen)
            else -> null
        }

    /**
     * The step confirms a dialog about managing the app: one of the controls it may activate
     * ([reached]), or its target, is a generic or deleting button ("确定", "删除") whose dialog
     * (its window, or the whole screen when the window is unknown) stops, clears, disables or
     * uninstalls an app. Each control is checked with its own risk; the target with the intent's.
     */
    private fun confirmsManaging(
        reached: Assessment?,
        intent: ActionIntent,
        screen: ScreenSnapshot,
    ): Boolean {
        val each =
            reached?.reached.orEmpty().map { it.element to it.risk?.kind } +
                listOfNotNull(intent.target?.let { it to intent.kind })
        return each.any { (element, kind) ->
            (kind == IntentKind.DELETE || reading.lexicon.isGeneric(Elements.label(element, screen))) &&
                settings.dialogManages(element, screen)
        }
    }

    /**
     * Hand-off: typing into a password, code or payment-password field; or interacting with a
     * payment-password, captcha, login or FLAG_SECURE page, unless the tap just closes or skips it
     * whatever it reaches ([escapes]).
     */
    fun handOff(
        step: Step,
        intent: ActionIntent,
        screen: ScreenSnapshot?,
    ): Verdict? {
        val page = if (screen != null && interacts(step) && !escapes(step, screen)) page(screen) else null
        return (FIELD_HAND_OFFS[intent.fieldClass] ?: page)?.verdict()
    }

    /** Taps, typing, swipes and Enter; scrolling and reading only look. */
    private fun interacts(step: Step): Boolean = step is ScreenStep && step !is Step.Scroll

    /**
     * A tap that only leaves the page: every control it may reach is labelled "关闭", "取消" … and
     * every text on them says so too. One other candidate under the point ("一键登录") is enough
     * to hand off, since which of them takes the touch is not known.
     */
    private fun escapes(
        step: Step,
        screen: ScreenSnapshot,
    ): Boolean {
        if (step !is Step.Tap) return false
        val touched = reading.risks.tap(step.aim, step.long, screen)
        val labels = touched.candidates.map { Elements.label(it, screen) }
        return labels.isNotEmpty() && (labels + touched.texts).all(reading.lexicon::isEscape)
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
