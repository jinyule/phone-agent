package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.TreeQuality

/**
 * Header and notes of a page, e.g. `screen s12 · com.tencent.mm/.ui.LauncherUI · agent 屏 · 坐标 0–999`.
 *
 * The tree-quality note says what to do instead of reading the tree. With [vision] it suggests a
 * screenshot; without, it never does (the model cannot read one): it points to the full text
 * page and the OCR coordinates, or to the user.
 */
internal class PageHeader(
    private val vision: Boolean,
) {
    /** The header line, the [notes], then [note] when given. */
    fun lines(
        snapshot: ScreenSnapshot,
        note: String?,
    ): List<String> = listOf(title(snapshot)) + notes(snapshot) + listOfNotNull(note)

    /** The page-state line (flags) and the tree-quality note, each only when there is something to say. */
    fun notes(snapshot: ScreenSnapshot): List<String> {
        val flags = ScreenFlag.entries.filter { it in snapshot.flags }.map(::flagWord)
        val state = flags.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "状态：")
        return listOfNotNull(state, qualityNote(snapshot))
    }

    private fun title(snapshot: ScreenSnapshot): String {
        val pkg = snapshot.pkg
        val activity = snapshot.activity
        val app =
            when {
                pkg == null -> activity ?: "未知应用"
                activity == null -> pkg
                activity.startsWith("$pkg.") -> "$pkg/${activity.removePrefix(pkg)}"
                else -> "$pkg/$activity"
            }
        val surface =
            when (snapshot.surface.kind) {
                SurfaceKind.AGENT -> "agent 屏"
                SurfaceKind.MAIN -> "主屏"
            }
        return "screen ${snapshot.id} · $app · $surface · 坐标 0–999"
    }

    private fun qualityNote(snapshot: ScreenSnapshot): String? {
        val secure = ScreenFlag.SECURE in snapshot.flags
        return when (snapshot.quality) {
            TreeQuality.GOOD -> {
                null
            }

            TreeQuality.SPARSE -> {
                when {
                    secure -> "树不完整，而且这一页禁止截屏：读不到的部分只能请用户处理（hand_to_user kind=secure_page）"
                    vision -> "树不完整：有些内容读不到，可以 phone_observe image=true 看截图，再按坐标操作"
                    else -> SPARSE_TEXT_ONLY
                }
            }

            TreeQuality.EMPTY -> {
                when {
                    secure -> "读不到界面元素，而且这一页禁止截屏：请用户处理（hand_to_user kind=secure_page）"
                    vision -> "读不到界面元素：用 phone_observe image=true 看截图，再按坐标操作"
                    else -> EMPTY_TEXT_ONLY
                }
            }
        }
    }

    private companion object {
        const val SPARSE_TEXT_ONLY =
            "树不完整：有些内容读不到。可以 phone_observe detail=full 读全部元素，屏上文字可以按坐标点；" +
                "还缺的部分请用户处理（hand_to_user kind=other）"
        const val EMPTY_TEXT_ONLY =
            "读不到界面元素：屏上文字可以按坐标点；没有屏上文字或不够用时，请用户处理（hand_to_user kind=other）"
    }

    private fun flagWord(flag: ScreenFlag): String =
        when (flag) {
            ScreenFlag.SECURE -> "禁止截屏"
            ScreenFlag.KEYBOARD -> "键盘已弹出"
            ScreenFlag.SYSTEM_PICKER -> "系统选择框在前面"
            ScreenFlag.LOGIN -> "登录页"
            ScreenFlag.CAPTCHA -> "人机验证"
            ScreenFlag.PAYMENT -> "支付页"
            ScreenFlag.RATE_LIMIT -> "App 提示操作太频繁"
            ScreenFlag.LOCKED -> "手机已锁屏"
        }
}
