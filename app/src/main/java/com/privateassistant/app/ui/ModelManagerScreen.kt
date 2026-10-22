package com.privateassistant.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privateassistant.app.R

/** Public model setup and a synthetic runtime diagnostic; no document-question input. */
@Composable
internal fun ModelManagerScreen(
    state: ModelManagerUiState,
    onBack: () -> Unit,
    onImport: (String, Boolean) -> Unit,
    onDiagnostic: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val context = LocalContext.current
    // These dialogs contain only public catalogue metadata. Licence acknowledgement
    // is transient and never restored into a later import or unlocked session.
    var importCandidate by remember { mutableStateOf<ModelEntryUi?>(null) }
    var removeCandidate by remember { mutableStateOf<ModelEntryUi?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.models_back)) }
            Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.models_description), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.models_experimental_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.models_experimental_body), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.models_download_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.message?.let { message -> item {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        } }
        state.memoryCheck?.let { memory -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.models_memory_total,
                        Formatter.formatShortFileSize(context, memory.totalBytes),
                        Formatter.formatShortFileSize(context, memory.minimumTotalBytes)),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.models_memory_available,
                        Formatter.formatShortFileSize(context, memory.availableBytes),
                        Formatter.formatShortFileSize(context, memory.minimumAvailableBytes)),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.models_memory_profile_note),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        if (state.loading || state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val activeName = state.entries.firstOrNull { it.id == state.activeModelId }?.displayName
            if (activeName != null) Text(activeName, style = MaterialTheme.typography.labelMedium)
            val copied = state.progressBytes
            val total = state.progressTotal
            if (copied != null && total != null && total > 0) {
                Text(stringResource(R.string.models_copy_progress,
                    Formatter.formatShortFileSize(context, copied.coerceIn(0, total)),
                    Formatter.formatShortFileSize(context, total)), style = MaterialTheme.typography.bodySmall)
            } else Text(stringResource(if (state.busy) R.string.models_working else R.string.models_loading),
                style = MaterialTheme.typography.bodySmall)
            if (state.busy) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        if (!state.loading && state.entries.isEmpty()) item {
            Text(stringResource(R.string.models_empty), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
        }
        items(state.entries, key = { it.id }) { model ->
            ModelEntryCard(model, enabled = !state.busy && !state.loading,
                onDownload = { onOpenLink(model.downloadUrl) },
                onImport = { importCandidate = model },
                onDiagnostic = { onDiagnostic(model.id) },
                onRemove = { removeCandidate = model })
        }
        if (state.diagnosticFailed) item {
            Text(stringResource(R.string.models_diagnostic_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        state.diagnosticText?.let { output -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.models_diagnostic_output),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.models_diagnostic_output_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(output, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } }
        item {
            Text(stringResource(R.string.models_session_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    importCandidate?.let { candidate ->
        var acknowledged by remember(candidate.id, candidate.sha256) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { importCandidate = null },
            title = { Text(stringResource(R.string.models_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(candidate.displayName, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.models_import_body,
                        Formatter.formatShortFileSize(context, candidate.byteCount)))
                    TextButton(onClick = { onOpenLink(candidate.licenseTermsUrl) }) {
                        Text(stringResource(R.string.models_license_link, candidate.licenseIdentifier))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !state.busy)
                        Text(stringResource(R.string.models_license_acknowledgement),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    importCandidate = null
                    onImport(candidate.id, true)
                }, enabled = acknowledged && !state.busy && !state.loading) {
                    Text(stringResource(R.string.models_choose_file))
                }
            },
            dismissButton = { TextButton(onClick = { importCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    removeCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { removeCandidate = null },
            title = { Text(stringResource(R.string.models_remove_title)) },
            text = { Text(stringResource(R.string.models_remove_body, candidate.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    removeCandidate = null
                    onRemove(candidate.id)
                }, enabled = !state.busy && !state.loading) {
                    Text(stringResource(R.string.models_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removeCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ModelEntryCard(
    model: ModelEntryUi,
    enabled: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDiagnostic: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(Formatter.formatShortFileSize(context, model.byteCount), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (model.installed) R.string.models_installed else R.string.models_not_installed),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (model.experimental) Text(stringResource(R.string.models_experimental_label),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.invalid) Text(stringResource(R.string.models_invalid), color = MaterialTheme.colorScheme.error)
            if (!model.installed && !model.invalid) {
                TextButton(onClick = onDownload, enabled = enabled) { Text(stringResource(R.string.models_get_file)) }
                Button(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.models_import))
                }
            } else {
                if (model.installed) {
                FilledTonalButton(onClick = onDiagnostic, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.models_test))
                }
                Text(stringResource(R.string.models_test_description), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text(stringResource(R.string.models_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
