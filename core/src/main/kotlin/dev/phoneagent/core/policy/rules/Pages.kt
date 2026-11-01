package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/** Page-level facts the matrix reacts to (policy-rules.md §2, §6). Lists are in [Text.compact] form. */
internal object Pages {
    /** The system settings app (AOSP and the major Chinese ROMs ship it under this name). */
    const val SETTINGS: String = "com.android.settings"

    /** Text this long or shorter counts as a label or heading; longer text is content. */
    private const val SHORT = 20

    /** A page title sits in the top band of the screen (0–999). */
    private const val TITLE_BAND = 150

    private val LOGIN = listOf("登录", "登入", "login", "signin")

    private val CAPTCHA =
        listOf(
            "滑动验证",
            "拖动滑块",
            "向右滑动",
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

    fun isLogin(screen: ScreenSnapshot): Boolean =
        ScreenFlag.LOGIN in screen.flags ||
            (screen.elements.any(::asksSecret) && shortTexts(screen).any { text -> LOGIN.any { it in text } })

    /** A password field, or a field asking for a code. */
    private fun asksSecret(element: UiElement): Boolean =
        element.secret ||
            (Elements.editable(element) && Text.compact(element.hint.orEmpty()).let { "验证码" in it || "code" in it })

    fun isCaptcha(screen: ScreenSnapshot): Boolean = ScreenFlag.CAPTCHA in screen.flags || mentions(screen, CAPTCHA)

    fun isPaymentPassword(screen: ScreenSnapshot): Boolean = mentions(screen, PAYMENT_PASSWORD)

    /** A checkout or payment sheet: flagged, shows a total, or lists payment methods. */
    fun isPayment(screen: ScreenSnapshot): Boolean =
        ScreenFlag.PAYMENT in screen.flags || Amounts.showsTotal(screen) || mentions(screen, PAY_SHEET)

    fun isSensitiveSettings(screen: ScreenSnapshot): Boolean =
        screen.pkg == SETTINGS &&
            (
                SENSITIVE_ACTIVITIES.any { it in screen.activity.orEmpty().lowercase() } ||
                    title(screen)?.let(::sensitive) != null
            )

    /** The sensitive settings page tapping [element] opens, or `null`. */
    fun sensitiveEntry(
        element: UiElement,
        screen: ScreenSnapshot,
    ): String? = if (screen.pkg == SETTINGS) sensitive(Elements.label(element, screen)) else null

    private fun sensitive(text: String): String? =
        Text.compact(text).let { t ->
            SENSITIVE_SETTINGS.firstOrNull {
                it in
                    t
            }
        }

    /** The page heading: the first named text in the top band. */
    fun title(screen: ScreenSnapshot): String? =
        screen.elements
            .firstOrNull {
                it.role == ElementRole.TEXT && !it.name.isNullOrBlank() &&
                    it.bounds.top <= TITLE_BAND
            }?.name

    private fun mentions(
        screen: ScreenSnapshot,
        words: List<String>,
    ): Boolean = shortTexts(screen).any { text -> words.any { it in text } }

    private fun shortTexts(screen: ScreenSnapshot): List<String> =
        Elements.pieces(screen).map { Text.compact(it.text) }.filter { it.length <= SHORT }
}
