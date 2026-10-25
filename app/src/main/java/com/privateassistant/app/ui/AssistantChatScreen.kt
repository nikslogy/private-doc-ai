package com.privateassistant.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateassistant.app.R
import com.privateassistant.platform.vault.VaultDocument

/** Chat text stays in the unlocked controller; this screen saves no private state. */
@Composable
internal fun AssistantChatScreen(
    state: AssistantChatUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onSelectDocument: (VaultDocument?) -> Unit,
    onViewSource: (AssistantCitationUi) -> Unit,
    onOpenModels: () -> Unit,
    onRefreshModel: () -> Unit,
) {
    var choosingSource by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selected = state.selectedDocument
    val canSend = !state.busy && state.draft.isNotBlank() && state.draft.length <= MAX_DRAFT_UTF16 &&
        (selected != null || state.modelReady == true)

    LaunchedEffect(Unit) { onRefreshModel() }
    LaunchedEffect(state.messages.size, state.busy, state.message) {
        if (state.messages.isNotEmpty()) {
            val trailing = (if (state.busy) 1 else 0) + (if (state.message != null) 1 else 0)
            listState.animateScrollToItem(state.messages.lastIndex + trailing)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClear, enabled = state.messages.isNotEmpty() || state.draft.isNotEmpty()) {
                    Text(stringResource(R.string.chat_clear))
                }
            }
            OutlinedButton(onClick = { choosingSource = true }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(if (selected == null) R.string.chat_general_mode else R.string.chat_document_mode),
                        style = MaterialTheme.typography.labelSmall)
                    Text(selected?.displayName ?: stringResource(R.string.chat_general_source),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(stringResource(if (selected == null) R.string.chat_general_note else R.string.chat_document_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty()) item(key = "welcome") {
                if (selected == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.chat_welcome), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.chat_welcome_body), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.chat_session_note), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.chat_document_examples), style = MaterialTheme.typography.titleSmall)
                        listOf(R.string.chat_example_premium, R.string.chat_example_policy_number,
                            R.string.chat_example_summary).forEach { exampleId ->
                            val example = stringResource(exampleId)
                            OutlinedButton(onClick = { onDraft(example) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(example, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Text(stringResource(R.string.chat_document_scope), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                ChatMessageCard(message, onViewSource = onViewSource, sourcesEnabled = !state.busy)
            }
            if (state.busy) item(key = "working") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.chat_working), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.chat_cancel)) }
                }
            }
            state.message?.let { message -> item(key = "status") {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected == null && state.modelReady != true) {
                if (state.modelReady == null) {
                    Text(stringResource(R.string.chat_checking_model), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.chat_model_needed), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenModels, enabled = !state.busy) {
                            Text(stringResource(R.string.chat_setup_model))
                        }
                        TextButton(onClick = onRefreshModel, enabled = !state.busy) {
                            Text(stringResource(R.string.chat_check_again))
                        }
                    }
                }
            }
            OutlinedTextField(value = state.draft, onValueChange = onDraft,
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                label = { Text(stringResource(R.string.chat_question)) },
                placeholder = { Text(stringResource(if (selected == null)
                    R.string.chat_general_hint else R.string.chat_document_hint)) },
                isError = state.draft.length > MAX_DRAFT_UTF16,
                supportingText = { Text(stringResource(R.string.chat_input_count, state.draft.length, MAX_DRAFT_UTF16)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.busy) TextButton(onClick = onCancel) { Text(stringResource(R.string.chat_cancel)) }
                else Button(onClick = onSend, enabled = canSend) { Text(stringResource(R.string.chat_send)) }
            }
        }
    }

    if (choosingSource) AlertDialog(
        onDismissRequest = { choosingSource = false },
        title = { Text(stringResource(R.string.chat_choose_source)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chat_source_change_note), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { choosingSource = false; onSelectDocument(null) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_general_mode), modifier = Modifier.fillMaxWidth())
                }
                if (state.documents.isEmpty()) Text(stringResource(R.string.chat_no_documents))
                state.documents.forEach { document ->
                    TextButton(onClick = { choosingSource = false; onSelectDocument(document) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(document.displayName, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { choosingSource = false }) { Text(stringResource(R.string.chat_done)) }
        },
    )
}

@Composable
private fun ChatMessageCard(message: AssistantMessageUi, onViewSource: (AssistantCitationUi) -> Unit,
    sourcesEnabled: Boolean,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.user) Alignment.TopEnd else Alignment.TopStart) {
        Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(0.94f),
            color = if (message.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when (message.kind) {
                    AssistantMessageKind.USER -> R.string.chat_you
                    AssistantMessageKind.MODEL -> R.string.chat_model_reply
                    AssistantMessageKind.DOCUMENT -> R.string.chat_document_reply
                    AssistantMessageKind.NOTICE -> R.string.chat_notice
                }), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                message.citations.forEach { citation ->
                    Spacer(Modifier.height(2.dp))
                    Text(citation.quote, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onViewSource(citation) }, enabled = sourcesEnabled) {
                        Text(stringResource(R.string.chat_citation, citation.label, citation.pageNumber))
                    }
                }
            }
        }
    }
}

private const val MAX_DRAFT_UTF16 = 400
