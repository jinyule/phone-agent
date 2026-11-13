package dev.phoneagent.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.phoneagent.app.R
import dev.phoneagent.app.task.ChatEntry
import dev.phoneagent.app.task.PendingPrompt
import dev.phoneagent.app.task.TaskProblem
import dev.phoneagent.app.task.TaskView
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ports.ConfirmCard

/**
 * The chat with the app's task: the conversation and its steps, the prompt the task waits for
 * (confirmation, question, handoff), what kept the last command from running, and the composer.
 * While the task waits for a question the composer answers it. Stop shows while a run is opened,
 * runs or waits; "new task" once the task has no work.
 */
@Composable
internal fun ChatScreen(
    view: TaskView,
    actions: ChatActions,
    onModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = view.chat
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val entries = chat?.entries.orEmpty()
        val list = rememberLazyListState()
        LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.animateScrollToItem(entries.lastIndex) }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.chat_empty), modifier = Modifier.padding(vertical = 16.dp)) }
            }
            itemsIndexed(entries) { _, entry -> Entry(entry) }
        }
        chat?.waitingFor?.let { Prompt(it, actions) }
        view.problem?.let { Problem(it, onModels) }
        Status(view, actions)
        Composer(view, actions)
    }
}

@Composable
private fun Entry(entry: ChatEntry) {
    when (entry) {
        is ChatEntry.UserText -> {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            )
        }

        is ChatEntry.AssistantText -> {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
        }

        is ChatEntry.Step -> {
            StepCardView(entry)
        }

        is ChatEntry.Stopped -> {
            Text(stringResource(stopText(entry.cause)), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StepCardView(step: ChatEntry.Step) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    toolText(step.tool)?.let { stringResource(it) } ?: step.tool,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(stepStateText(step)), style = MaterialTheme.typography.labelMedium)
            }
            val card = step.card
            if (card != null) {
                Text(card.summary, style = MaterialTheme.typography.bodyMedium)
                shownDetails(card.details).forEach { detail ->
                    val label = detailText(detail.key)?.let { stringResource(it) } ?: detail.key
                    val value = detailValueText(detail)?.let { stringResource(it) } ?: detail.value
                    Text(stringResource(R.string.detail_line, label, value), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (step.showsWithheld) {
                Text(stringResource(R.string.step_withheld), style = MaterialTheme.typography.bodySmall)
            }
            step.place?.let { Text(stringResource(placeText(it)), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Prompt(
    prompt: PendingPrompt,
    actions: ChatActions,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (prompt) {
                is PendingPrompt.Confirm -> ConfirmPrompt(prompt, actions)
                is PendingPrompt.Ask -> AskPrompt(prompt, actions)
                is PendingPrompt.Handoff -> HandoffPrompt(prompt, actions)
            }
        }
    }
}

@Composable
private fun ConfirmPrompt(
    prompt: PendingPrompt.Confirm,
    actions: ChatActions,
) {
    val card = prompt.card
    Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(reasonText(card.reason)), style = MaterialTheme.typography.labelLarge)
    Text(card.action, style = MaterialTheme.typography.bodyLarge)
    confirmLines(card).forEach { (label, value) ->
        Text(
            stringResource(R.string.detail_line, stringResource(label), value),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { actions.answer(prompt.id, UserReply.Approve) },
        ) { Text(stringResource(R.string.confirm_approve)) }
        OutlinedButton(onClick = {
            actions.answer(prompt.id, UserReply.Reject)
        }) { Text(stringResource(R.string.confirm_reject)) }
    }
}

/** The labelled lines of [card] it has: target, content, amount, app. */
@Composable
private fun confirmLines(card: ConfirmCard): List<Pair<Int, String>> {
    val amount =
        card.amount?.let { money ->
            val digits = amountDigits(money)
            money.currency?.let { stringResource(R.string.confirm_amount, it, digits) } ?: digits
        }
    return listOfNotNull(
        card.target?.let { R.string.detail_target to it },
        card.content?.let { R.string.detail_content to it },
        amount?.let { R.string.detail_amount to it },
        card.pkg?.let { R.string.detail_app to it },
    )
}

@Composable
private fun AskPrompt(
    prompt: PendingPrompt.Ask,
    actions: ChatActions,
) {
    Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleSmall)
    Text(prompt.question.text, style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        prompt.question.options.forEach { option ->
            OutlinedButton(onClick = { actions.answer(prompt.id, UserReply.Answer(option)) }) { Text(option) }
        }
    }
    Text(stringResource(R.string.ask_type_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { actions.answer(prompt.id, UserReply.Decline) }) { Text(stringResource(R.string.ask_skip)) }
}

@Composable
private fun HandoffPrompt(
    prompt: PendingPrompt.Handoff,
    actions: ChatActions,
) {
    Text(stringResource(kindText(prompt.kind)), style = MaterialTheme.typography.titleSmall)
    Text(prompt.reason, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { actions.answer(prompt.id, UserReply.HandoffDone) },
        ) { Text(stringResource(R.string.handoff_did_it)) }
        OutlinedButton(onClick = {
            actions.answer(prompt.id, UserReply.Decline)
        }) { Text(stringResource(R.string.handoff_give_back)) }
    }
}

@Composable
private fun Problem(
    problem: TaskProblem,
    onModels: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(problemText(problem)), color = MaterialTheme.colorScheme.error)
        if (problem == TaskProblem.NO_MODEL || problem == TaskProblem.KEY_MISSING) {
            TextButton(onClick = onModels) { Text(stringResource(R.string.chat_open_models)) }
        }
    }
}

@Composable
private fun Status(
    view: TaskView,
    actions: ChatActions,
) {
    val chat = view.chat
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            view.starting -> Text(stringResource(R.string.chat_starting), modifier = Modifier.weight(1f))
            view.busy -> Text(stringResource(R.string.chat_working), modifier = Modifier.weight(1f))
            else -> Text("", modifier = Modifier.weight(1f))
        }
        if (view.stoppable) {
            OutlinedButton(onClick = actions::stop, modifier = Modifier.testTag(ChatTags.STOP)) {
                Text(stringResource(R.string.chat_stop))
            }
        } else if (chat != null && !view.busy) {
            TextButton(onClick = actions::newTask) { Text(stringResource(R.string.chat_new_task)) }
        }
    }
}

@Composable
private fun Composer(
    view: TaskView,
    actions: ChatActions,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val answering = view.chat?.waitingFor is PendingPrompt.Ask
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    stringResource(if (answering) R.string.chat_answer_hint else R.string.chat_input_hint),
                )
            },
            modifier = Modifier.weight(1f).testTag(ChatTags.INPUT),
        )
        Button(
            onClick = {
                actions.send(text)
                text = ""
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.testTag(ChatTags.SEND),
        ) { Text(stringResource(R.string.chat_send)) }
    }
}
