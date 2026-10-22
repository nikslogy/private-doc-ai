package com.privateassistant.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.privateassistant.app.R
import com.privateassistant.core.facts.FieldKey
import com.privateassistant.platform.vault.VaultDocument

/** Direct field inspection: no generated answer, implicit source selection, or private saved state. */
@Composable
internal fun DocumentFactsScreen(
    state: VaultUiState,
    onSelectDocument: (VaultDocument) -> Unit,
    onClearDocument: () -> Unit,
    onViewSource: (Int) -> Unit,
    onOpenModelManager: () -> Unit,
) {
    val source = state.factsDocument
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.facts_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.facts_description), style = MaterialTheme.typography.bodyMedium)
        }
        if (source == null) {
            item {
                Text(stringResource(R.string.facts_choose_source), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.facts_selection_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.documents.isEmpty()) item {
                Text(stringResource(R.string.facts_no_documents), style = MaterialTheme.typography.bodyMedium)
            }
            items(state.documents, key = { "source-${it.id}-${it.revisionId}" }) { document ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy,
                        onClick = { onSelectDocument(document) }).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(document.displayName, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(fileType(document.mimeType), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.facts_revision, document.revisionId),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.facts_selected_source), style = MaterialTheme.typography.labelMedium)
                        Text(source.displayName, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.facts_revision, source.revisionId),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onClearDocument, enabled = !state.busy) {
                            Text(stringResource(R.string.facts_change_source))
                        }
                    }
                }
            }
            when {
                state.factsLoading -> item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.facts_reading), style = MaterialTheme.typography.bodySmall)
                }
                state.factsFailed -> item {
                    Text(stringResource(R.string.facts_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onSelectDocument(source) }, enabled = !state.busy) {
                        Text(stringResource(R.string.retry))
                    }
                }
                else -> {
                    val facts = state.facts
                    if (facts?.incomplete == true) item {
                        Text(stringResource(R.string.facts_incomplete), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (facts?.truncated == true) item {
                        Text(stringResource(R.string.facts_partial), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (facts?.groups.isNullOrEmpty()) item {
                        Text(stringResource(R.string.facts_no_matches), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onViewSource(1) }, enabled = !state.busy) {
                            Text(stringResource(R.string.facts_review_text))
                        }
                    }
                    items(facts?.groups.orEmpty(), key = { it.key.name }) { group ->
                        FieldGroupCard(group, enabled = !state.busy, onViewSource = onViewSource)
                    }
                }
            }
            item {
                Text(stringResource(R.string.facts_accuracy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(stringResource(R.string.facts_supported_labels), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.assistant_unavailable_title),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.assistant_unavailable_body), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.assistant_privacy_body), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenModelManager, enabled = !state.busy) {
                        Text(stringResource(R.string.models_open))
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldGroupCard(group: FieldGroupUi, enabled: Boolean, onViewSource: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(fieldLabel(group.key)), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (group.conflicting) Text(stringResource(R.string.facts_conflict),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            group.candidates.forEach { candidate ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(candidate.rawValue, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.facts_source_quote), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(candidate.quote, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onViewSource(candidate.pageNumber) }, enabled = enabled) {
                            Text(stringResource(R.string.facts_view_source_page, candidate.pageNumber))
                        }
                    }
                }
            }
        }
    }
}

@StringRes
private fun fieldLabel(key: FieldKey): Int = when (key) {
    FieldKey.POLICY_NUMBER -> R.string.facts_policy_number
    FieldKey.VEHICLE_REGISTRATION -> R.string.facts_vehicle_registration
    FieldKey.POLICY_HOLDER -> R.string.facts_policy_holder
    FieldKey.COVERAGE_START -> R.string.facts_coverage_start
    FieldKey.COVERAGE_END -> R.string.facts_coverage_end
    FieldKey.PREMIUM_TOTAL -> R.string.facts_premium_total
    FieldKey.PREMIUM_BEFORE_TAX -> R.string.facts_premium_before_tax
    FieldKey.TAX -> R.string.facts_tax
    FieldKey.INSURED_VALUE -> R.string.facts_insured_value
    FieldKey.SUM_INSURED -> R.string.facts_sum_insured
    FieldKey.DEDUCTIBLE -> R.string.facts_deductible
    FieldKey.INVOICE_NUMBER -> R.string.facts_invoice_number
    FieldKey.INVOICE_TOTAL -> R.string.facts_invoice_total
    FieldKey.INVOICE_SUBTOTAL -> R.string.facts_invoice_subtotal
    FieldKey.INVOICE_DATE -> R.string.facts_invoice_date
    FieldKey.TOTAL -> R.string.facts_total
    FieldKey.OWNER -> R.string.facts_owner
    FieldKey.SUBTOTAL -> R.string.facts_subtotal
}
