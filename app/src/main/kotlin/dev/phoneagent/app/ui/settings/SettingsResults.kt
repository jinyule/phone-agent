package dev.phoneagent.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.phoneagent.app.R
import dev.phoneagent.app.model.setup.ModelCheck
import dev.phoneagent.app.model.setup.SetupOutcome

/** The last "test and save": what is being checked, or each model's steps and whether it was saved. */
@Composable
internal fun RunResult(run: RunState) {
    when (run) {
        RunState.Idle -> {
            return
        }

        is RunState.Testing -> {
            run.model?.let { Text(stringResource(R.string.form_testing, it.value)) }
        }

        is RunState.Done -> {
            Outcome(run.outcome)
        }
    }
}

@Composable
private fun Outcome(outcome: SetupOutcome) {
    when (outcome) {
        is SetupOutcome.Invalid -> {
            for (problem in outcome.problems) {
                Text(
                    stringResource(problemText(problem)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is SetupOutcome.Failed -> {
            Checks(outcome.checks)
            Text(stringResource(R.string.result_nothing_saved))
        }

        is SetupOutcome.NotSaved -> {
            Checks(outcome.checks)
            Text(stringResource(saveFailureText(outcome.what)), color = MaterialTheme.colorScheme.error)
        }

        is SetupOutcome.Saved -> {
            Checks(outcome.checks)
            Text(stringResource(R.string.result_saved))
        }
    }
}

@Composable
private fun Checks(checks: List<ModelCheck>) {
    for (check in checks) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.result_model, check.model.value),
                    style = MaterialTheme.typography.titleSmall,
                )
                for (line in stepsOf(check.result)) StepRow(line)
            }
        }
    }
}

@Composable
private fun StepRow(line: StepLine) {
    val color =
        if (line.state ==
            StepState.FAILED
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Text(
        stringResource(
            R.string.result_step_line,
            stringResource(stepText(line.step)),
            stringResource(stateText(line.state)),
        ),
        color = color,
    )
    line.note?.let { Text(stringResource(it)) }
    line.kind?.let { Text(stringResource(it), color = color) }
    line.reason?.let { Text(stringResource(it), color = color) }
    line.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
