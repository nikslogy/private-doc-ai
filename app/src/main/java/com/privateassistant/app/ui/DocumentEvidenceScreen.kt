package com.privateassistant.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privateassistant.app.R
import com.privateassistant.platform.documents.OcrScript
import com.privateassistant.platform.vault.VaultDocument
import java.text.DateFormat
import java.util.Date

/** Shows stored page text and provenance. It does not stand in for a rendered original. */
@Composable
internal fun DocumentEvidenceScreen(
    state: VaultUiState,
    document: VaultDocument,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRead: () -> Unit,
    onOcr: (OcrScript) -> Unit,
    onCancelRead: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var deleteDialog by remember(document.revisionId) { mutableStateOf(false) }
    val evidence = state.evidence
    val supportsNativeText = document.mimeType == "application/pdf" || document.mimeType == "text/plain"
    val supportsOcr = document.mimeType in setOf("application/pdf", "image/jpeg", "image/png")
    LaunchedEffect(document.revisionId, evidence, state.selectedPage) {
        val pageIndex = evidence?.pages?.indexOfFirst { it.pageNumber == state.selectedPage } ?: -1
        if (pageIndex >= 0) listState.scrollToItem(pageIndex + 1)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "source") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back_to_documents)) }
                Text(document.displayName, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.evidence_view_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailLine(R.string.detail_type, fileType(document.mimeType))
                        DetailLine(R.string.detail_size, Formatter.formatShortFileSize(context, document.sizeBytes))
                        DetailLine(R.string.detail_imported, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(document.importedAtEpochMillis)))
                        DetailLine(R.string.evidence_revision, document.revisionId)
                        HorizontalDivider()
                        IndexStatusBadge(document.indexSummary().status)
                        if (evidence != null && evidence.summary.pageCount > 0) {
                            Text(stringResource(R.string.index_page_count,
                                evidence.summary.searchablePages, evidence.summary.pageCount),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.indexing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.indexing_in_progress), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCancelRead) { Text(stringResource(R.string.cancel_reading)) }
                } else {
                    if (supportsNativeText) {
                        Button(onClick = onRead, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.read_native_text))
                        }
                    }
                    if (supportsOcr && state.ocrAvailable) {
                        Text(stringResource(R.string.ocr_language_prompt), style = MaterialTheme.typography.titleSmall)
                        FilledTonalButton(onClick = { onOcr(OcrScript.LATIN) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.read_ocr_english))
                        }
                        FilledTonalButton(onClick = { onOcr(OcrScript.DEVANAGARI) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.read_ocr_devanagari))
                        }
                    }
                }
                if (supportsNativeText) {
                    Text(stringResource(R.string.native_text_description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (supportsOcr) {
                    Text(stringResource(if (state.ocrAvailable) R.string.ocr_description else R.string.ocr_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.ocr_accuracy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(R.drawable.ic_export, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_original))
                }
                Text(stringResource(R.string.export_warning), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { deleteDialog = true }, enabled = !state.busy) {
                    AppIcon(R.drawable.ic_delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_document), color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Text(stringResource(R.string.evidence_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                if (state.evidenceLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.evidence_loading), style = MaterialTheme.typography.bodySmall)
                } else if (state.evidenceFailed) {
                    Text(stringResource(R.string.evidence_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
                } else if (evidence == null || evidence.pages.isEmpty()) {
                    Text(stringResource(R.string.evidence_not_indexed), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!state.evidenceLoading && !state.evidenceFailed && evidence != null) {
            items(evidence.pages, key = { it.pageNumber }) { page -> PageEvidenceCard(page) }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false },
        icon = { AppIcon(R.drawable.ic_delete, null) },
        title = { Text(stringResource(R.string.delete_document_title)) },
        text = { Text(stringResource(R.string.delete_document_body, document.displayName)) },
        confirmButton = { TextButton(onClick = { deleteDialog = false; onDelete() }, enabled = !state.busy) {
            Text(stringResource(R.string.delete_document), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PageEvidenceCard(page: EvidencePageUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.evidence_source_page, page.pageNumber),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            page.method?.let { method ->
                Text(stringResource(when (method) {
                    EvidenceMethodUi.UTF8_TEXT -> R.string.evidence_method_utf8
                    EvidenceMethodUi.PDF_NATIVE -> R.string.evidence_method_native
                    EvidenceMethodUi.OCR_LATIN -> R.string.evidence_method_ocr_latin
                    EvidenceMethodUi.OCR_DEVANAGARI -> R.string.evidence_method_ocr_devanagari
                }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (page.status != EvidencePageStatusUi.READY) {
                Text(stringResource(when (page.status) {
                    EvidencePageStatusUi.EMPTY -> R.string.evidence_page_empty
                    EvidencePageStatusUi.NEEDS_OCR -> R.string.evidence_page_needs_ocr
                    EvidencePageStatusUi.FAILED -> R.string.evidence_page_failed
                    EvidencePageStatusUi.READY -> R.string.evidence_page_empty
                }), style = MaterialTheme.typography.bodySmall,
                    color = if (page.status == EvidencePageStatusUi.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (page.text.isNotEmpty()) {
                HorizontalDivider()
                Text(page.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun IndexStatusBadge(status: IndexStatusUi) {
    Surface(shape = RoundedCornerShape(8.dp), color = when (status) {
        IndexStatusUi.READY -> MaterialTheme.colorScheme.primaryContainer
        IndexStatusUi.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }) {
        Text(stringResource(when (status) {
            IndexStatusUi.NOT_INDEXED -> R.string.index_not_indexed
            IndexStatusUi.NEEDS_OCR -> R.string.index_needs_ocr
            IndexStatusUi.PARTIAL -> R.string.index_partial
            IndexStatusUi.READY -> R.string.index_ready
            IndexStatusUi.FAILED -> R.string.index_failed
            IndexStatusUi.INDEXING -> R.string.index_indexing
        }), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
