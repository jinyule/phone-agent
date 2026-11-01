package dev.phoneagent.core.tools

import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.Tool

/**
 * The phase-1 tool set (phase1 §5) in manifest order: the phone group, then the general group.
 * The order and every definition are fixed: they are part of the cached prompt prefix and of the
 * prompt-surface golden files.
 */
public object PhaseOneTools {
    /** All phase-1 tools, rendering screens with [presenter] and tuned by [config]. */
    public fun create(
        presenter: ScreenPresenter,
        config: ToolsConfig = ToolsConfig(),
    ): List<Tool> {
        val mask = presenter::mask
        return listOf(
            PhoneObserveTool(presenter, config.vision, config.observeTimeoutMs),
            PhoneAppsTool(presenter, config.appsTimeoutMs),
            PhoneWaitTool(presenter, config.stableQuietMs, config.waitTimeoutMs),
            PhoneTapTool(presenter, config.actionTimeoutMs),
            PhoneTypeTool(presenter, config.typeTimeoutMs),
            PhoneScrollTool(presenter, config.actionTimeoutMs),
            PhoneSwipeTool(presenter, config.actionTimeoutMs),
            PhoneKeyTool(presenter, config.actionTimeoutMs),
            PhoneLaunchTool(presenter, config.launchTimeoutMs),
            PhoneIntentTool(presenter, config.launchTimeoutMs),
            AskUserTool(mask, config.userTimeoutMs),
            HandToUserTool(mask, config.userTimeoutMs),
            RequestConfirmationTool(mask, config.userTimeoutMs),
            EndNotDoneTool(mask, config.controlTimeoutMs),
        )
    }
}
