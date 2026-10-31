package com.privateassistant.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privateassistant.app.R
import com.privateassistant.core.tools.ByteSizes
import com.privateassistant.platform.vault.VaultDocument

/** Every callback is a direct user tap; message text from a model or document never calls these. */
internal class AssistantCallbacks(
    val onDraft: (String) -> Unit,
    val onSend: () -> Unit,
    val onCancel: () -> Unit,
    val onClear: () -> Unit,
    val onSelectDocument: (VaultDocument?) -> Unit,
    val onSelectAllDocuments: () -> Unit,
    val onViewSource: (AssistantCitationUi) -> Unit,
    val onOpenModels: () -> Unit,
    val onRefreshModel: () -> Unit,
    val onConfirmAction: (Long) -> Unit,
    val onCancelAction: (Long) -> Unit,
    val onChooseActionDocument: (Long, VaultDocument) -> Unit,
    val onProposeTool: (ToolKind, VaultDocument) -> Unit,
    val onOpenDocument: (VaultDocument) -> Unit,
    val onSaveDocument: (VaultDocument) -> Unit,
)

/** Chat text stays in the unlocked controller; this screen saves no private state. */
@Composable
internal fun AssistantChatScreen(state: AssistantChatUiState, callbacks: AssistantCallbacks) {
    var choosingSource by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selected = state.selectedDocument
    val canSend = !state.busy && state.draft.isNotBlank() && state.draft.length <= MAX_DRAFT_UTF16

    LaunchedEffect(Unit) { callbacks.onRefreshModel() }
    LaunchedEffect(state.messages.size, state.busy, state.message) {
        if (state.messages.isNotEmpty()) {
            val trailing = (if (state.busy) 1 else 0) + (if (state.message != null) 1 else 0)
            listState.animateScrollToItem(state.messages.size + trailing)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.weight(1f).clickable(enabled = !state.busy) { choosingSource = true }) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (selected != null) R.drawable.ic_file else if (state.allDocuments) R.drawable.ic_documents
                        else R.drawable.ic_sparkle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        selected != null -> stringResource(R.string.assistant_scope_document, selected.displayName)
                        state.allDocuments -> stringResource(R.string.assistant_scope_documents)
                        else -> stringResource(R.string.assistant_scope_general)
                    }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    AppIcon(R.drawable.ic_chevron, null, Modifier.size(16.dp))
                }
            }
            IconButton(onClick = callbacks.onClear, enabled = state.messages.isNotEmpty() || state.draft.isNotEmpty()) {
                AppIcon(R.drawable.ic_delete, R.string.chat_clear, Modifier.size(22.dp))
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "welcome") {
                if (state.messages.isEmpty()) WelcomePanel(state, callbacks.onDraft)
            }
            items(state.messages, key = { it.id }) { message ->
                ChatMessage(message, enabled = !state.busy, callbacks)
            }
            if (state.busy) item(key = "working") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        TextButton(onClick = callbacks.onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                    Text(stringResource(R.string.chat_working), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.message?.let { message -> item(key = "status") {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }

        if (state.modelReady == false) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.assistant_model_missing), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = callbacks.onOpenModels, enabled = !state.busy) { Text(stringResource(R.string.chat_setup_model)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(value = state.draft, onValueChange = callbacks.onDraft,
                modifier = Modifier.weight(1f), minLines = 1, maxLines = 5, shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(R.string.assistant_message_hint)) },
                isError = state.draft.length > MAX_DRAFT_UTF16,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) callbacks.onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    unfocusedIndicatorColor = Color.Transparent))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = callbacks.onSend, enabled = canSend, modifier = Modifier.size(52.dp)) {
                AppIcon(R.drawable.ic_send, R.string.chat_send, Modifier.size(22.dp))
            }
        }
    }

    if (choosingSource) AlertDialog(
        onDismissRequest = { choosingSource = false },
        title = { Text(stringResource(R.string.chat_choose_source)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.chat_source_change_note), style = MaterialTheme.typography.bodySmall)
                SourceRow(R.drawable.ic_documents, stringResource(R.string.chat_all_documents_mode), state.allDocuments,
                    enabled = state.documents.isNotEmpty()) { choosingSource = false; callbacks.onSelectAllDocuments() }
                SourceRow(R.drawable.ic_sparkle, stringResource(R.string.chat_general_mode), !state.allDocuments && selected == null) {
                    choosingSource = false; callbacks.onSelectDocument(null)
                }
                if (state.documents.isEmpty()) Text(stringResource(R.string.chat_no_documents), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                state.documents.forEach { document ->
                    SourceRow(documentVisual(document.mimeType).first, document.displayName, selected?.id == document.id) {
                        choosingSource = false; callbacks.onSelectDocument(document)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingSource = false }) { Text(stringResource(R.string.chat_done)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomePanel(state: AssistantChatUiState, onDraft: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.assistant_hero_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.assistant_hero_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(stringResource(R.string.assistant_try), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val suggestions = if (state.selectedDocument != null) listOf(R.string.chat_example_summary, R.string.chat_example_premium,
            R.string.suggest_compress, R.string.suggest_pan_image)
        else listOf(R.string.suggest_pan, R.string.suggest_aadhaar, R.string.suggest_pan_image, R.string.suggest_compress,
            R.string.suggest_passport, R.string.suggest_to_pdf, R.string.suggest_summary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            suggestions.forEach { id ->
                val text = stringResource(id)
                SuggestionChip(onClick = { onDraft(text) }, label = { Text(text) }, enabled = !state.busy)
            }
        }
        Text(stringResource(R.string.chat_session_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceRow(icon: Int, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ChatMessage(message: AssistantMessageUi, enabled: Boolean, callbacks: AssistantCallbacks) {
    if (message.user) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (message.kind) {
                AssistantMessageKind.MODEL -> R.string.chat_model_reply
                AssistantMessageKind.DOCUMENT -> R.string.chat_document_reply
                AssistantMessageKind.DOCUMENT_MODEL -> R.string.chat_document_model_reply
                else -> R.string.chat_notice
            }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.action == null) {
                Surface(shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            message.identifiers.forEach { IdentifierCard(it, enabled, callbacks) }
            message.action?.let { ActionCard(message, it, enabled, callbacks) }
            message.documents.forEach { ResultDocument(it, enabled, callbacks) }
            if (message.identifiers.isEmpty()) message.citations.forEach { citation ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("“" + citation.quote + "”", style = MaterialTheme.typography.bodySmall, maxLines = 6,
                            overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { callbacks.onViewSource(citation) }, enabled = enabled,
                            contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.chat_citation, citation.label, citation.pageNumber),
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentifierCard(answer: IdentifierAnswerUi, enabled: Boolean, callbacks: AssistantCallbacks) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_badge, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(identifierLabel(answer.kind)), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(answer.value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (answer.masked) Text(stringResource(R.string.masked_value), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.chat_citation, answer.document.displayName, answer.pageNumber),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { copySensitive(context, answer.value) }, label = { Text(stringResource(R.string.copy)) },
                    leadingIcon = { AppIcon(R.drawable.ic_copy, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { callbacks.onOpenDocument(answer.document) }, enabled = enabled,
                    label = { Text(stringResource(R.string.view_source)) },
                    leadingIcon = { AppIcon(R.drawable.ic_open, null, Modifier.size(16.dp)) })
                if (answer.document.mimeType == "application/pdf" || answer.document.mimeType == "image/png" ||
                    answer.document.mimeType == "image/jpeg") {
                    AssistChip(onClick = { callbacks.onProposeTool(ToolKind.TO_IMAGE, answer.document) }, enabled = enabled,
                        label = { Text(stringResource(R.string.as_image)) },
                        leadingIcon = { AppIcon(R.drawable.ic_image, null, Modifier.size(16.dp)) })
                }
            }
        }
    }
}

@Composable
private fun ActionCard(message: AssistantMessageUi, action: AssistantActionUi, enabled: Boolean, callbacks: AssistantCallbacks) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    AppIcon(when (action.tool) {
                        ToolKind.COMPRESS -> R.drawable.ic_compress
                        ToolKind.TO_IMAGE -> R.drawable.ic_image
                        ToolKind.TO_PDF -> R.drawable.ic_pdf
                        ToolKind.PASSPORT_PHOTO -> R.drawable.ic_person
                    }, null, Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(toolTitle(action.tool)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (action.status == ActionStatus.CHOOSE) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                action.choices.forEach { document ->
                    OutlinedButton(onClick = { callbacks.onChooseActionDocument(message.id, document) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth()) {
                        AppIcon(documentVisual(document.mimeType).first, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(document.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { callbacks.onCancelAction(message.id) }, enabled = enabled) { Text(stringResource(R.string.cancel)) }
                return@Column
            }
            action.document?.let { document ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(documentVisual(document.mimeType).first, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(document.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(toolDetail(action, document), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action.tool == ToolKind.COMPRESS && document.mimeType == "application/pdf") {
                    Text(stringResource(R.string.tool_compress_pdf_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
            when (action.status) {
                ActionStatus.PROPOSED -> {
                    Text(stringResource(R.string.tool_private_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { callbacks.onConfirmAction(message.id) }, enabled = enabled) { Text(stringResource(R.string.tool_create)) }
                        TextButton(onClick = { callbacks.onCancelAction(message.id) }, enabled = enabled) { Text(stringResource(R.string.cancel)) }
                    }
                }
                ActionStatus.RUNNING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.tool_running), style = MaterialTheme.typography.bodySmall)
                }
                ActionStatus.DONE -> StatusLine(R.string.tool_completed)
                ActionStatus.CANCELLED -> StatusLine(R.string.tool_cancelled)
                ActionStatus.FAILED -> StatusLine(R.string.tool_failed_status)
                ActionStatus.CHOOSE -> Unit
            }
        }
    }
}

@Composable
private fun StatusLine(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun toolDetail(action: AssistantActionUi, document: VaultDocument): String = when (action.tool) {
    ToolKind.COMPRESS -> stringResource(R.string.tool_compress_detail, ByteSizes.format(action.targetBytes), ByteSizes.format(document.sizeBytes))
    ToolKind.TO_IMAGE -> stringResource(R.string.tool_to_image_detail)
    ToolKind.TO_PDF -> stringResource(R.string.tool_to_pdf_detail)
    ToolKind.PASSPORT_PHOTO -> stringResource(R.string.tool_passport_detail, action.spec.widthMm, action.spec.heightMm,
        action.spec.widthPx, action.spec.heightPx)
}

@Composable
private fun ResultDocument(document: VaultDocument, enabled: Boolean, callbacks: AssistantCallbacks) {
    val (icon, tint) = documentVisual(document.mimeType)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tint)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(document.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fileType(document.mimeType) + " · " + ByteSizes.format(document.sizeBytes),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { callbacks.onOpenDocument(document) }, enabled = enabled,
                    label = { Text(stringResource(R.string.open_document)) },
                    leadingIcon = { AppIcon(R.drawable.ic_open, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { callbacks.onSaveDocument(document) }, enabled = enabled,
                    label = { Text(stringResource(R.string.save_to_phone)) },
                    leadingIcon = { AppIcon(R.drawable.ic_save, null, Modifier.size(16.dp)) })
            }
        }
    }
}

/** Marks the clip sensitive so Android 13+ hides it from clipboard previews. */
private fun copySensitive(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", value).apply {
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions ignore the extra.
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

private const val MAX_DRAFT_UTF16 = 400
