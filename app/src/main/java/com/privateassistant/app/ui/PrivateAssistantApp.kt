package com.privateassistant.app.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateassistant.app.R
import com.privateassistant.platform.vault.VaultDocument

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateAssistantApp(
    model: VaultUiModel,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onImport: () -> Unit,
    onExport: (VaultDocument) -> Unit,
    onDeviceSecurity: () -> Unit,
    onImportModel: (String, Boolean) -> Unit,
    onOpenModelLink: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    val modelState by model.models.state.collectAsState()
    val chatState by model.assistant.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    BackHandler(enabled = state.unlocked && state.selectedDocument != null) { model.closeDocument() }
    BackHandler(enabled = state.unlocked && state.modelManagerOpen) { model.closeModels() }
    LaunchedEffect(state.message, state.importCount) {
        val message = state.message?.let(context::getString)
            ?: state.importCount?.let { context.resources.getQuantityString(R.plurals.documents_imported, it, it) }
        if (message != null) {
            snackbar.showSnackbar(message)
            model.clearMessage()
        }
    }
    PrivateAssistantTheme {
        Scaffold(
            topBar = {
                if (state.unlocked) TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.vault_unlocked), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        IconButton(onClick = onLock) {
                            AppIcon(R.drawable.ic_lock, R.string.lock_vault)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                if (state.unlocked) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    AppPage.entries.forEach { page ->
                        val label = when (page) {
                            AppPage.DOCUMENTS -> R.string.tab_documents
                            AppPage.ASSISTANT -> R.string.tab_assistant
                            AppPage.PRIVACY -> R.string.tab_privacy
                        }
                        val icon = when (page) {
                            AppPage.DOCUMENTS -> R.drawable.ic_documents
                            AppPage.ASSISTANT -> R.drawable.ic_sparkle
                            AppPage.PRIVACY -> R.drawable.ic_shield
                        }
                        NavigationBarItem(
                            selected = state.page == page,
                            onClick = { model.setPage(page) },
                            icon = { AppIcon(icon, null) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = if (state.unlocked) 840.dp else 480.dp).fillMaxSize()) {
                    if (!state.unlocked) LockedScreen(state, onUnlock, onDeviceSecurity)
                    else when (state.page) {
                        AppPage.DOCUMENTS -> state.selectedDocument?.let { document ->
                            DocumentEvidenceScreen(state, document, onBack = model::closeDocument,
                                onRetry = { model.openDocument(document, state.selectedPage) },
                                onRead = { model.readDocument(document) }, onCancelRead = model::cancelReading,
                                onOcr = { script -> model.readDocument(document, script) },
                                onExport = { model.closeDocument(); onExport(document) },
                                onDelete = { model.deleteDocument(document) })
                        } ?: DocumentsScreen(state, model::setQuery, onImport, model::openDocument)
                        AppPage.ASSISTANT -> if (state.modelManagerOpen) ModelManagerScreen(modelState,
                            onBack = model::closeModels, onImport = onImportModel,
                            onDiagnostic = model.models::diagnostic, onRemove = model.models::remove,
                            onCancel = model.models::cancel, onRefresh = model.models::refresh,
                            onOpenLink = onOpenModelLink)
                        else AssistantChatScreen(chatState,
                            onDraft = model.assistant::setDraft, onSend = model.assistant::send,
                            onCancel = model.assistant::cancel, onClear = model.assistant::clearConversation,
                            onSelectDocument = model.assistant::selectDocument,
                            onSelectAllDocuments = model.assistant::selectAllDocuments,
                            onViewSource = model::viewChatSource, onOpenModels = model::openModels,
                            onRefreshModel = model.assistant::refreshModel)
                        AppPage.PRIVACY -> PrivacyScreen(onLock)
                    }
                }
                if (state.busy && state.unlocked) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LockedScreen(state: VaultUiState, onUnlock: () -> Unit, onDeviceSecurity: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(painterResource(R.drawable.ic_vault), null, Modifier.padding(24.dp).size(64.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.welcome_tagline), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AssuranceRow(R.drawable.ic_shield, R.string.welcome_encryption)
                AssuranceRow(R.drawable.ic_device, R.string.welcome_local)
                AssuranceRow(R.drawable.ic_lock, R.string.welcome_authentication)
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onUnlock, enabled = !state.busy && !state.authenticating,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
            if (state.busy || state.authenticating) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else AppIcon(R.drawable.ic_lock, null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (state.busy) R.string.opening_vault else R.string.unlock_vault))
        }
        if (state.requiresDeviceSecurity) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.device_security_required), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center)
            TextButton(onClick = onDeviceSecurity) { Text(stringResource(R.string.setup_device_security)) }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.welcome_recovery_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DocumentsScreen(
    state: VaultUiState,
    onQuery: (String) -> Unit,
    onImport: () -> Unit,
    onDocument: (VaultDocument, Int?) -> Unit,
) {
    val filtered = remember(state.documents, state.visibleDocumentIds, state.query) {
        if (state.query.isBlank()) state.documents
        else {
            val byId = state.documents.associateBy { it.id }
            state.visibleDocumentIds.orEmpty().mapNotNull { byId[it] }
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.documents_title), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(pluralStringResource(R.plurals.documents_in_vault, state.documents.size, state.documents.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onImport, enabled = !state.busy) {
                    AppIcon(R.drawable.ic_add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.import_document))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.documents_storage_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (state.documents.isNotEmpty()) item {
            OutlinedTextField(value = state.query, onValueChange = onQuery, singleLine = true,
                leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                label = { Text(stringResource(R.string.search_documents)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.search_scope), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            if (state.searching) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.search_in_progress), style = MaterialTheme.typography.labelSmall)
            }
            if (state.searchFailed) Text(stringResource(R.string.search_failed),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.documents.isEmpty()) item {
            EmptyDocuments(state.busy, onImport)
        } else if (filtered.isEmpty() && !state.searching && !state.searchFailed) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(R.drawable.ic_search, null, Modifier.size(36.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.no_search_results), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onQuery("") }) { Text(stringResource(R.string.clear_search)) }
            }
        } else items(filtered, key = { it.id }) { document ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DocumentRow(document, enabled = !state.busy, onClick = { onDocument(document, null) })
                state.searchMatches.filter { it.documentId == document.id }.take(3).forEach { match ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy,
                            onClick = { onDocument(document, match.pageNumber) }).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(stringResource(R.string.search_source_page, match.pageNumber),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(match.excerpt, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.supported_formats), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EmptyDocuments(busy: Boolean, onImport: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(22.dp)) {
                AppIcon(R.drawable.ic_documents, null, Modifier.padding(20.dp).size(38.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.empty_documents_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_documents_body), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            FilledTonalButton(onClick = onImport, enabled = !busy) { Text(stringResource(R.string.choose_documents)) }
        }
    }
}

@Composable
private fun DocumentRow(document: VaultDocument, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(if (document.mimeType.startsWith("image/")) R.drawable.ic_image else R.drawable.ic_file),
                    null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(document.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(stringResource(R.string.document_summary, fileType(document.mimeType),
                    Formatter.formatShortFileSize(context, document.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                IndexStatusBadge(document.indexSummary().status)
            }
            Spacer(Modifier.width(8.dp))
            AppIcon(R.drawable.ic_chevron, R.string.document_details, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PrivacyScreen(onLock: () -> Unit) {
    val connected = booleanResource(R.bool.connected_build)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_shield, null, Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(if (connected) R.string.connected_build_title else R.string.offline_build_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(if (connected) R.string.connected_build_body else R.string.offline_build_body),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        InfoBlock(R.drawable.ic_lock, R.string.privacy_encryption_title, R.string.privacy_encryption_body)
        InfoBlock(R.drawable.ic_device, R.string.privacy_backup_title, R.string.privacy_backup_body)
        InfoBlock(R.drawable.ic_shield, R.string.privacy_screen_title, R.string.privacy_screen_body)
        InfoBlock(R.drawable.ic_export, R.string.privacy_export_title, R.string.privacy_export_body)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.privacy_limitations), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onLock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            AppIcon(R.drawable.ic_lock, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lock_vault))
        }
    }
}

@Composable
internal fun DetailLine(@StringRes label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AssuranceRow(@DrawableRes icon: Int, @StringRes text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoBlock(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(15.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AppIcon(@DrawableRes icon: Int, @StringRes description: Int?, modifier: Modifier = Modifier.size(24.dp)) {
    Icon(painterResource(icon), description?.let { stringResource(it) }, modifier)
}

@Composable
internal fun fileType(mimeType: String): String = stringResource(when (mimeType) {
    "application/pdf" -> R.string.file_type_pdf
    "image/jpeg" -> R.string.file_type_jpeg
    "image/png" -> R.string.file_type_png
    "text/plain" -> R.string.file_type_text
    else -> R.string.file_type_document
})
