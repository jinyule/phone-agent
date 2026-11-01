package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.ScreenPresenter

/** `phone_key(key)` (phase1 §5): press a whitelisted system key. */
internal class PhoneKeyTool(
    presenter: ScreenPresenter,
    timeoutMs: Long,
) : ScreenActionTool(
        name = "phone_key",
        description = "按系统键：back 返回上一页或关闭弹窗，home 回到桌面（后台屏上没有桌面，换 App 用 phone_launch），enter 回车。",
        presenter = presenter,
        timeoutMs = timeoutMs,
        declare = {
            string("key", "要按的键。", required = true, enum = KEYS.keys.toList())
        },
    ) {
    override val usage: String = "phone_key(key=back/home/enter)"

    override fun plan(args: ArgReader): ScreenPlan? =
        args.choice("key", KEYS, required = true)?.let { key ->
            ScreenPlan(UiAction.Key(key), on = null, done = "已按" + WORDS.getValue(key))
        }
}

private val KEYS = linkedMapOf("back" to SystemKey.BACK, "home" to SystemKey.HOME, "enter" to SystemKey.ENTER)

private val WORDS = mapOf(SystemKey.BACK to "返回键", SystemKey.HOME to "主页键", SystemKey.ENTER to "回车键")
