package dev.phoneagent.core.tools

import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult

/**
 * `request_confirmation(action, target, content, amount?)` (phase1 §5): the model asks for a
 * confirmation it thinks is needed. It can only add confirmations.
 *
 * Precondition (contracts addendum §5): [execute] runs only after the PolicyEngine answered the
 * call with `Verdict.Confirm` built from these arguments, the user approved, and the re-check after
 * approval gave Allow; a rejection, an expired or an impossible confirmation never reaches it. That
 * is why [execute] reports "用户已确认" without asking again. The tool is declared [RiskClass.WRITE]
 * as a conservative floor: a policy that does not know it must not let it through as a read, and
 * the registry-by-resolver check (WP09) then flags the gap.
 *
 * The action is echoed back unmasked: the model wrote it, so masking would only make the echo
 * differ from its own arguments; the trajectory card masks it like every card detail.
 */
internal class RequestConfirmationTool(
    mask: (String) -> String,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "request_confirmation",
        description =
            "做一件有后果的事之前，主动请用户确认：action 写要做什么，target 写对谁或对什么，content 写具体内容，" +
                "amount 写金额（如 ¥128.00）。用户同意才返回成功；用户拒绝就不要做。" +
                "它只会多一次确认：系统规则要求的确认照样会出现。",
        mask = mask,
        declare = {
            string("action", "要做的事，如 发送消息、下单、删除。", required = true)
            string("target", "对谁或对什么，如 联系人名字、商品名。", required = true)
            string("content", "具体内容，如 消息原文、订单内容。", required = true)
            string("amount", "涉及的金额和币种，如 ¥128.00。没有就不填。")
        },
    ) {
    override val group: ToolGroup = ToolGroup.GENERAL
    override val risk: RiskClass = RiskClass.WRITE
    override val concurrencySafe: Boolean = false

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        val action = args.string("action", required = true, max = MAX_FIELD)
        args.string("target", required = true, max = MAX_FIELD)
        args.string("content", required = true, max = MAX_CONTENT)
        args.string("amount", max = MAX_FIELD)
        return when (val parsed = args.finish(USAGE, action)) {
            is Parsed.Invalid -> {
                ToolResults.invalid(parsed.message)
            }

            is Parsed.Ok -> {
                ToolResult.ok("用户已确认：${parsed.value}。这次确认只覆盖你申报的这件事；真正执行时，系统规则要求的确认可能还会出现。")
            }
        }
    }

    private companion object {
        const val USAGE = "request_confirmation(action, target, content, amount?)"
        const val MAX_FIELD = 200
        const val MAX_CONTENT = 2000
    }
}
