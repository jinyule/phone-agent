package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.ScreenPresenter

/** `phone_type(screen, ref?, text, append?, submit?)` (phase1 §5): enter text into a field. */
internal class PhoneTypeTool(
    presenter: ScreenPresenter,
    timeoutMs: Long,
) : ScreenActionTool(
        name = "phone_type",
        description =
            "在输入框里输入文字。ref 指定输入框；不填就输入到当前有焦点的输入框。默认替换框里原有的内容，" +
                "append=true 改为追加到末尾；submit=true 输入后按回车或键盘上的发送、搜索键。" +
                "密码、验证码、支付密码不要自己输入，用 hand_to_user 请用户来。",
        presenter = presenter,
        timeoutMs = timeoutMs,
        declare = {
            string("screen", "这次输入依据的那一屏的编号，如 s12。", required = true)
            string("ref", "输入框的编号，如 @e9。不填就用当前有焦点的输入框。")
            string("text", "要输入的文字。", required = true)
            boolean("append", "true 表示追加到原有内容后面。默认 false（替换）。")
            boolean("submit", "true 表示输入后按回车或发送、搜索键。默认 false。")
        },
    ) {
    override val usage: String = "phone_type(screen, ref?, text, append?, submit?)"

    override fun plan(args: ArgReader): ScreenPlan? {
        val screen = args.snapshot("screen")
        val ref = args.ref("ref")
        val text = args.string("text", required = true, max = MAX_TEXT, allowBlank = true)
        val append = args.boolean("append") ?: false
        val submit = args.boolean("submit") ?: false
        // An invalid `ref` is already a recorded problem, so `finish` rejects the call.
        return if (screen == null || text == null) {
            null
        } else {
            val target = ref?.let(Target::Ref)
            val where = target?.shown() ?: "当前焦点输入框"
            val done = (if (append) "已追加输入到 " else "已输入到 ") + where + (if (submit) " 并提交" else "")
            ScreenPlan(UiAction.Type(target, text, append, submit), screen, done)
        }
    }

    private companion object {
        const val MAX_TEXT = 5000
    }
}
