package dev.phoneagent.core.replay

import dev.phoneagent.core.policy.rules.PolicyTuning
import kotlin.reflect.KProperty1

/**
 * [PolicyTuning] by field name, as `scenario.json` spells it: the tuning a scenario is assembled
 * with ([Assembly.tuning], only the fields it sets) and the complete tuning its verdicts came from
 * ([PolicyPin.tuning], every field). One entry per field; `PolicyPinsTest` fails when a field of
 * [PolicyTuning] has none.
 */
object PolicyPins {
    private class Knob(
        val property: KProperty1<PolicyTuning, Int>,
        val set: (PolicyTuning, Int) -> PolicyTuning,
    )

    private val KNOBS: List<Knob> =
        listOf(
            Knob(PolicyTuning::labelMax) { t, v -> t.copy(labelMax = v) },
            Knob(PolicyTuning::contextMax) { t, v -> t.copy(contextMax = v) },
            Knob(PolicyTuning::shortText) { t, v -> t.copy(shortText = v) },
            Knob(PolicyTuning::totalTextMax) { t, v -> t.copy(totalTextMax = v) },
            Knob(PolicyTuning::labelRead) { t, v -> t.copy(labelRead = v) },
            Knob(PolicyTuning::titleBand) { t, v -> t.copy(titleBand = v) },
            Knob(PolicyTuning::clearNear) { t, v -> t.copy(clearNear = v) },
            Knob(PolicyTuning::rowSwipeMin) { t, v -> t.copy(rowSwipeMin = v) },
            Knob(PolicyTuning::rowMinWidth) { t, v -> t.copy(rowMinWidth = v) },
            Knob(PolicyTuning::carouselMax) { t, v -> t.copy(carouselMax = v) },
            Knob(PolicyTuning::maxElements) { t, v -> t.copy(maxElements = v) },
            Knob(PolicyTuning::maxScreenText) { t, v -> t.copy(maxScreenText = v) },
            Knob(PolicyTuning::maxDepth) { t, v -> t.copy(maxDepth = v) },
            Knob(PolicyTuning::totalsRead) { t, v -> t.copy(totalsRead = v) },
        )

    private val BY_NAME: Map<String, Knob> = KNOBS.associateBy { it.property.name }

    /** Every field name, in declaration order. */
    val NAMES: List<String> = KNOBS.map { it.property.name }

    /**
     * The default tuning with [fields] set. Fails with [IllegalArgumentException] for a name that
     * is no field, or a value [PolicyTuning] refuses.
     */
    fun tuning(fields: Map<String, Int>): PolicyTuning {
        val unknown = fields.keys - BY_NAME.keys
        require(unknown.isEmpty()) { "scenario.json: no PolicyTuning field ${unknown.sorted()}; fields: $NAMES" }
        return fields.entries.fold(
            PolicyTuning(),
        ) { tuning, (name, value) -> BY_NAME.getValue(name).set(tuning, value) }
    }

    /** Every field of [tuning], by name, in declaration order. */
    fun values(tuning: PolicyTuning): Map<String, Int> =
        KNOBS.associateTo(LinkedHashMap()) { it.property.name to it.property.get(tuning) }

    /**
     * The pin of the rules in force with [tuning]: the current [PolicyTuning.RULES_VERSION] and
     * every field's value.
     */
    fun current(tuning: PolicyTuning): PolicyPin = PolicyPin(PolicyTuning.RULES_VERSION, values(tuning))

    /**
     * How [recorded] differs from [current], one line per difference (empty when they are equal):
     * the rules version, and each field whose value differs or that only one of them has.
     */
    fun differences(
        recorded: PolicyPin,
        current: PolicyPin,
    ): List<String> {
        val version =
            "policy.rulesVersion: the fixture was judged by rules ${recorded.rulesVersion}, " +
                "PolicyTuning.RULES_VERSION is ${current.rulesVersion}"
        val fields =
            (recorded.tuning.keys + current.tuning.keys).sortedBy { NAMES.indexOf(it) }.mapNotNull { name ->
                val (was, now) = recorded.tuning[name] to current.tuning[name]
                "policy.tuning.$name: the fixture was judged with $was, replay runs with $now".takeIf { was != now }
            }
        return listOfNotNull(version.takeIf { recorded.rulesVersion != current.rulesVersion }) + fields
    }
}
