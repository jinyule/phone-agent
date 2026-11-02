package dev.phoneagent.core.session

/**
 * The turn and step pairing of [RunRules]: `turn/start` comes with no turn open and a number above
 * every earlier turn's; `step/start` in the open turn, with no step open and a number above the
 * turn's earlier steps; `step/end` ends the open step, and `turn/end` the open turn once its step
 * has ended.
 */
internal class TurnPairing {
    /** The open turn, or `null` between turns. */
    private var openTurn: Int? = null

    /** The last turn started, 0 before the first. */
    private var lastTurn = 0

    /** The open step of the open turn, or `null` between steps. */
    private var openStep: Int? = null

    /** The last step started in the open turn, 0 before its first. */
    private var lastStep = 0

    /** A turn is open. */
    val inTurn: Boolean get() = openTurn != null

    /** Why [event] breaks the pairing, or `null` when it does not (and for records that are no turn or step). */
    fun violation(event: SessionEvent): String? =
        when {
            event is TurnStarted -> startViolation(event)
            event is TurnEnded -> endViolation(event)
            event is StepStarted -> startViolation(event)
            event is StepEnded -> endViolation(event)
            else -> null
        }

    /** Takes [event] into account for later checks. */
    fun accept(event: SessionEvent) {
        when {
            event is TurnStarted -> {
                openTurn = event.turn
                lastTurn = event.turn
                lastStep = 0
            }

            event is TurnEnded -> {
                openTurn = null
            }

            event is StepStarted -> {
                openStep = event.step
                lastStep = event.step
            }

            event is StepEnded -> {
                openStep = null
            }
        }
    }

    private fun startViolation(event: TurnStarted): String? =
        if (openTurn == null && event.turn > lastTurn) {
            null
        } else {
            "turn/start (${event.turn}) needs no turn open and a number above $lastTurn (${opened()})"
        }

    private fun endViolation(event: TurnEnded): String? =
        if (openTurn == event.turn && openStep == null) {
            null
        } else {
            "turn/end (${event.turn}) needs turn ${event.turn} open and its step ended (${opened()})"
        }

    private fun startViolation(event: StepStarted): String? =
        if (openTurn == event.turn && openStep == null && event.step > lastStep) {
            null
        } else {
            "step/start (${event.turn}/${event.step}) needs turn ${event.turn} open, no step open " +
                "and a number above $lastStep (${opened()})"
        }

    private fun endViolation(event: StepEnded): String? =
        if (openTurn == event.turn && openStep == event.step) {
            null
        } else {
            "step/end (${event.turn}/${event.step}) needs that step open (${opened()})"
        }

    private fun opened(): String {
        val turn = openTurn?.let { "turn $it" } ?: "no turn"
        val step = openStep?.let { "step $it" } ?: "no step"
        return "open: $turn, $step"
    }
}
