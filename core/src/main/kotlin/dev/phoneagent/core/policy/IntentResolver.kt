package dev.phoneagent.core.policy

import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall

/**
 * Works out what a call will do (phase1 §3.6, §5). Part of the policy, so a tool that
 * under-reports its effect cannot get around a rule (AGENTS.md §3.3). The runtime's policy guard
 * calls [resolve] and then [PolicyEngine.evaluate]; the implementation lives in the policy
 * capability and reads the tool names and argument names of phase1 §5.
 *
 * Pure, deterministic and never throws.
 */
public fun interface IntentResolver {
    /**
     * The intent of [call] on [screen] (the snapshot the call was planned on, or `null`).
     *
     * [risk] is a floor: a tool declared [RiskClass.SPEND] always resolves to [IntentKind.SPEND].
     * When a writing tool's arguments cannot be resolved, or the tool name is unknown and [risk]
     * is not [RiskClass.READ], the result is the most cautious intent the risk class allows.
     */
    public fun resolve(
        call: ToolCall,
        risk: RiskClass,
        screen: ScreenSnapshot?,
    ): ActionIntent
}
