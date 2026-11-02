package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import java.util.Locale

/**
 * Page-level facts the matrix reacts to (policy-rules.md §2, §6). Lists are in [Text.compact]
 * form; texts up to [PolicyTuning.shortText] count as labels and headings.
 *
 * Word lists here are adapted from Mika's `IrreversibleDetector` (Copyright 2026 wangzan101,
 * Apache-2.0). Modified by PhoneAgent contributors: login flows without a password box, no bare
 * "向右滑动" among the captcha words, settings pages told by every title candidate, app settings
 * pages, and thresholds from [PolicyTuning]. See NOTICE.
 */
internal class Pages(
    private val tuning: PolicyTuning,
    private val amounts: Amounts,
) {
    /**
     * A login page: flagged; or it names a login flow ("本机号码一键登录", "验证码登录"); or it says
     * "登录" and asks for a password, a code, an account or a phone number.
     */
    fun isLogin(screen: ScreenSnapshot): Boolean =
        ScreenFlag.LOGIN in screen.flags ||
            mentions(screen, LOGIN_FLOWS) ||
            (mentions(screen, LOGIN) && screen.elements.any { asks(it, SECRET_HINTS) || asks(it, ACCOUNT_HINTS) })

    /** A password field, or a field whose hint or name contains one of [words]. */
    private fun asks(
        element: UiElement,
        words: List<String>,
    ): Boolean {
        val said = Text.compact(listOfNotNull(element.hint, element.name).joinToString(" "))
        return Elements.editable(element) && (element.secret || words.any { it in said })
    }

    fun isCaptcha(screen: ScreenSnapshot): Boolean = ScreenFlag.CAPTCHA in screen.flags || mentions(screen, CAPTCHA)

    fun isPaymentPassword(screen: ScreenSnapshot): Boolean = mentions(screen, PAYMENT_PASSWORD)

    /** A checkout or payment sheet: flagged, shows a total, or lists payment methods. */
    fun isPayment(screen: ScreenSnapshot): Boolean =
        ScreenFlag.PAYMENT in screen.flags || amounts.showsTotal(screen) || mentions(screen, PAY_SHEET)

    /** The page heading for a confirmation card: the first named text in the top band. */
    fun title(screen: ScreenSnapshot): String? =
        screen.elements
            .firstOrNull {
                it.role == ElementRole.TEXT && !it.name.isNullOrBlank() && it.bounds.top <= tuning.titleBand
            }?.name

    /** Short visible texts (element names and OCR lines) in [Text.compact] form. */
    fun shortTexts(screen: ScreenSnapshot): List<String> =
        Elements.pieces(screen).map { Text.compact(it.text) }.filter { it.length <= tuning.shortText }

    private fun mentions(
        screen: ScreenSnapshot,
        words: List<String>,
    ): Boolean = shortTexts(screen).any { text -> words.any { it in text } }

    /** Constants. */
    companion object {
        /** The system settings app (AOSP and the major Chinese ROMs ship it under this name). */
        const val SETTINGS: String = "com.android.settings"
    }
}

/** Pages of the settings app the agent may not use (policy-rules.md §2, §6 hard refusals). */
internal class SettingsPages(
    private val tuning: PolicyTuning,
    private val pages: Pages,
) {
    /**
     * A sensitive settings page (accessibility, developer options …), told by its activity or by
     * any title candidate: a short text in the top band that is not part of a control, from the
     * tree or from OCR.
     */
    fun isSensitive(screen: ScreenSnapshot): Boolean =
        screen.pkg == Pages.SETTINGS &&
            (activityHas(screen, SENSITIVE_ACTIVITIES) || titles(screen).any { sensitive(it) != null })

    /** The sensitive settings page that activating a control showing [texts] opens, or `null`. */
    fun sensitiveEntry(
        texts: List<String>,
        screen: ScreenSnapshot,
    ): String? = if (screen.pkg == Pages.SETTINGS) texts.firstNotNullOfOrNull(::sensitive) else null

    /**
     * A settings page of one app where it can be stopped, cleared, disabled or uninstalled: its
     * app info page, its storage page, and the dialogs they open ("要删除应用数据吗？"); told by
     * the activity or by any short text on the screen.
     */
    fun isAppDetails(screen: ScreenSnapshot): Boolean =
        screen.pkg == Pages.SETTINGS &&
            (activityHas(screen, DETAILS_ACTIVITIES) || pages.shortTexts(screen).any { t -> DETAILS.any { it in t } })

    /** A control showing [texts] stops, clears, disables or uninstalls an app. */
    fun managesApp(texts: List<String>): Boolean =
        texts.any { text -> Text.compact(text).let { t -> MANAGEMENT.any { it in t } } }

    private fun titles(screen: ScreenSnapshot): List<String> {
        val named =
            screen.elements.filter {
                !it.name.isNullOrBlank() && it.bounds.top <= tuning.titleBand && !Elements.clickable(it) &&
                    !Tree.inControl(it, screen)
            }
        val drawn = screen.ocr.orEmpty().filter { it.bounds.top <= tuning.titleBand }
        return (named.mapNotNull { it.name } + drawn.map { it.text })
            .map(Text::compact)
            .filter { it.length <= tuning.shortText }
    }

    private fun activityHas(
        screen: ScreenSnapshot,
        fragments: List<String>,
    ): Boolean {
        val activity = screen.activity.orEmpty().lowercase(Locale.ROOT)
        return fragments.any { it in activity }
    }

    private fun sensitive(text: String): String? =
        Text.compact(text).let { t ->
            SENSITIVE_SETTINGS.firstOrNull {
                it in
                    t
            }
        }
}

private val LOGIN = listOf("登录", "登入", "login", "signin")

/** Steps of a login flow that need no password box: one-tap, phone number and code logins. */
private val LOGIN_FLOWS =
    listOf("一键登录", "手机号登录", "手机号码登录", "验证码登录", "短信登录", "免密登录", "密码登录", "signinwith", "loginwith")

private val SECRET_HINTS = listOf("验证码", "code")

private val ACCOUNT_HINTS =
    listOf("手机号", "账号", "帐号", "用户名", "邮箱", "phone", "mobile", "account", "email", "username")

private val CAPTCHA =
    listOf(
        "滑动验证",
        "拖动滑块",
        "滑动完成验证",
        "安全验证",
        "人机验证",
        "请按顺序点击",
        "图形验证",
        "i'mnotarobot",
        "verifyyouarehuman",
        "slidetoverify",
        "completethepuzzle",
    )

private val PAYMENT_PASSWORD =
    listOf("支付密码", "交易密码", "付款密码", "验证指纹", "指纹支付", "面容支付", "刷脸支付", "paymentpassword", "paymentpin")

private val PAY_SHEET = listOf("支付方式", "付款方式", "收银台", "免密支付", "选择付款", "paymentmethod")

/** Titles and entries of the settings pages the agent may never open (phase1 §6 hard refusal). */
private val SENSITIVE_SETTINGS =
    listOf(
        "无障碍",
        "辅助功能",
        "开发者选项",
        "开发人员选项",
        "无线调试",
        "usb调试",
        "权限",
        "设备管理",
        "安装未知应用",
        "未知来源",
        "安全",
        "隐私",
        "锁屏",
        "指纹",
        "人脸",
        "面部",
        "生物识别",
        "凭据",
        "通知使用权",
        "使用情况访问",
        "悬浮窗",
        "显示在其他应用",
        "修改系统设置",
        "vpn",
        "恢复出厂",
        "重置",
        "账户",
        "帐户",
        "账号与同步",
        "accessibility",
        "developeroptions",
        "wirelessdebugging",
        "usbdebugging",
        "permission",
        "specialappaccess",
        "deviceadmin",
        "installunknownapps",
        "security",
        "privacy",
        "lockscreen",
        "screenlock",
        "fingerprint",
        "faceunlock",
        "biometric",
        "credential",
        "notificationaccess",
        "usageaccess",
        "displayoverotherapps",
        "modifysystemsettings",
        "resetoptions",
        "factoryreset",
        "accounts",
    )

/** Lower-case fragments of settings activity names that host those pages. */
private val SENSITIVE_ACTIVITIES =
    listOf(
        "accessibility",
        "development",
        "wirelessdebugging",
        "permission",
        "deviceadmin",
        "specialaccess",
        "externalsources",
        "unknownsources",
        "security",
        "privacy",
        "lockscreen",
        "screenlock",
        "biometric",
        "fingerprint",
        "faceunlock",
        "credential",
        "trustagent",
        "notificationaccess",
        "usageaccess",
        "overlay",
        "writesettings",
        "vpn",
        "masterclear",
        "reset",
        "account",
    )

/** Texts only the settings pages of one app (info, storage, their dialogs) show. */
private val DETAILS =
    listOf(
        "应用信息",
        "应用详情",
        "appinfo",
        "强行停止",
        "强制停止",
        "forcestop",
        "存储和缓存",
        "storage&cache",
        "清除存储",
        "清除数据",
        "清空数据",
        "删除应用数据",
        "清除应用数据",
        "clearstorage",
        "cleardata",
        "clearappdata",
        "deleteappdata",
    )

/** Lower-case fragments of the activities hosting the settings pages of one app. */
private val DETAILS_ACTIVITIES = listOf("installedappdetails", "appinfo", "appdetail", "appstorage")

/** What a control on an app's settings page does to the app itself. */
private val MANAGEMENT =
    listOf(
        "强行停止",
        "强制停止",
        "结束运行",
        "停止运行",
        "清除数据",
        "清除存储",
        "清空数据",
        "删除数据",
        "删除应用数据",
        "清除应用数据",
        "卸载",
        "停用",
        "禁用",
        "forcestop",
        "uninstall",
        "cleardata",
        "clearstorage",
        "clearappdata",
        "deleteappdata",
        "disable",
    )
