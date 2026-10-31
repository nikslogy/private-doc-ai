package com.privateassistant.app.ui

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privateassistant.app.R
import com.privateassistant.platform.vault.VaultDocument
import com.privateassistant.platform.vault.VaultFolder

/** Everything the home and folder screens can ask the controller to do; all are direct user taps. */
internal class HomeActions(
    val onQuery: (String) -> Unit,
    val onImport: () -> Unit,
    val onOpen: (VaultDocument, Int?) -> Unit,
    val onOpenFolder: (VaultFolder?) -> Unit,
    val onCreateFolder: (String, VaultDocument?) -> Unit,
    val onRenameFolder: (VaultFolder, String) -> Unit,
    val onDeleteFolder: (VaultFolder) -> Unit,
    val onMove: (VaultDocument, VaultFolder?) -> Unit,
    val onRename: (VaultDocument, String) -> Unit,
    val onDelete: (VaultDocument) -> Unit,
    val onExport: (VaultDocument) -> Unit,
    val onAsk: (VaultDocument) -> Unit,
    val onOpenAssistant: () -> Unit,
)

private sealed interface NameDialog {
    data class NewFolder(val moveAfter: VaultDocument? = null) : NameDialog
    data class RenameFolder(val folder: VaultFolder) : NameDialog
    data class RenameDocument(val document: VaultDocument) : NameDialog
}

@Composable
internal fun HomeScreen(state: VaultUiState, actions: HomeActions) {
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var moveDialog by remember { mutableStateOf<VaultDocument?>(null) }
    var deleteDocument by remember { mutableStateOf<VaultDocument?>(null) }
    var deleteFolder by remember { mutableStateOf<VaultFolder?>(null) }
    val openFolder = state.openFolderId?.let { id -> state.folders.firstOrNull { it.id == id } }
    val folderNames = remember(state.folders) { state.folders.associate { it.id to it.name } }
    val searching = state.query.isNotBlank()
    val visible = remember(state.documents, state.visibleDocumentIds, state.query, openFolder) {
        val base = if (searching) {
            val byId = state.documents.associateBy { it.id }
            state.visibleDocumentIds.orEmpty().mapNotNull { byId[it] }
        } else state.documents
        if (openFolder != null && !searching) base.filter { it.folderId == openFolder.id } else base
    }
    val rowMenu = DocumentMenu(
        onOpen = { actions.onOpen(it, null) },
        onAsk = actions.onAsk,
        onMove = { moveDialog = it },
        onRename = { nameDialog = NameDialog.RenameDocument(it) },
        onExport = actions.onExport,
        onDelete = { deleteDocument = it },
    )

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (openFolder == null) {
            item(key = "hero") { HomeHeader() }
        } else {
            item(key = "folder-header") {
                FolderHeader(openFolder, visible.size, state.busy,
                    onBack = { actions.onOpenFolder(null) },
                    onRename = { nameDialog = NameDialog.RenameFolder(openFolder) },
                    onDelete = { deleteFolder = openFolder })
            }
        }
        item(key = "search") {
            SearchField(state.query, actions.onQuery)
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (state.searchFailed) Text(stringResource(R.string.search_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (openFolder == null && !searching) {
            item(key = "actions") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction(R.drawable.ic_add, R.string.action_import, Modifier.weight(1f), !state.busy, actions.onImport)
                    QuickAction(R.drawable.ic_sparkle, R.string.action_ask, Modifier.weight(1f), true, actions.onOpenAssistant)
                    QuickAction(R.drawable.ic_folder_add, R.string.action_new_folder, Modifier.weight(1f), !state.busy) {
                        nameDialog = NameDialog.NewFolder()
                    }
                }
            }
            item(key = "folders-title") { SectionTitle(R.string.folders_title) }
            item(key = "folders") {
                if (state.folders.isEmpty()) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { nameDialog = NameDialog.NewFolder() }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(R.drawable.ic_folder_add, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.folders_empty), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.folders, key = { it.id }) { folder ->
                            FolderCard(folder, state.documents.count { it.folderId == folder.id }) { actions.onOpenFolder(folder) }
                        }
                    }
                }
            }
            item(key = "recent-title") {
                SectionTitle(R.string.recent_documents, trailing = pluralStringResource(R.plurals.documents_in_vault,
                    state.documents.size, state.documents.size))
            }
        }
        if (state.documents.isEmpty()) {
            item(key = "empty") { EmptyVault(state.busy, actions.onImport) }
        } else if (visible.isEmpty() && searching && !state.searching && !state.searchFailed) {
            item(key = "no-results") {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(R.drawable.ic_search, null, Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.no_search_results), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { actions.onQuery("") }) { Text(stringResource(R.string.clear_search)) }
                }
            }
        } else if (visible.isEmpty() && openFolder != null) {
            item(key = "folder-empty") { EmptyFolder(state.busy, actions.onImport) }
        } else {
            items(visible, key = { it.id }) { document ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentCard(document, folderNames[document.folderId].takeIf { openFolder == null }, !state.busy, rowMenu)
                    if (searching) state.searchMatches.filter { it.documentId == document.id }.take(3).forEach { match ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(start = 20.dp)) {
                            Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { actions.onOpen(document, match.pageNumber) }
                                .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.search_source_page, match.pageNumber),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(match.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item(key = "formats") {
            Text(stringResource(R.string.supported_formats), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }

    nameDialog?.let { dialog ->
        val (title, label, initial) = when (dialog) {
            is NameDialog.NewFolder -> Triple(R.string.create_folder, R.string.folder_name, "")
            is NameDialog.RenameFolder -> Triple(R.string.rename_folder, R.string.folder_name, dialog.folder.name)
            is NameDialog.RenameDocument -> Triple(R.string.rename_document, R.string.document_name, dialog.document.displayName)
        }
        NameInputDialog(title, label, initial, confirm = if (dialog is NameDialog.NewFolder) R.string.create else R.string.save,
            onDismiss = { nameDialog = null }) { name ->
            nameDialog = null
            when (dialog) {
                is NameDialog.NewFolder -> actions.onCreateFolder(name, dialog.moveAfter)
                is NameDialog.RenameFolder -> actions.onRenameFolder(dialog.folder, name)
                is NameDialog.RenameDocument -> actions.onRename(dialog.document, name)
            }
        }
    }
    moveDialog?.let { document ->
        AlertDialog(onDismissRequest = { moveDialog = null },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.folders.forEach { folder ->
                        MenuRow(R.drawable.ic_folder, folder.name, selected = document.folderId == folder.id) {
                            moveDialog = null; actions.onMove(document, folder)
                        }
                    }
                    if (document.folderId != null) MenuRow(R.drawable.ic_documents, stringResource(R.string.remove_from_folder)) {
                        moveDialog = null; actions.onMove(document, null)
                    }
                    MenuRow(R.drawable.ic_folder_add, stringResource(R.string.move_to_new_folder)) {
                        moveDialog = null; nameDialog = NameDialog.NewFolder(moveAfter = document)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveDialog = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleteDocument?.let { document ->
        AlertDialog(onDismissRequest = { deleteDocument = null },
            icon = { AppIcon(R.drawable.ic_delete, null) },
            title = { Text(stringResource(R.string.delete_document_title)) },
            text = { Text(stringResource(R.string.delete_document_body, document.displayName)) },
            confirmButton = { TextButton(onClick = { deleteDocument = null; actions.onDelete(document) }, enabled = !state.busy) {
                Text(stringResource(R.string.delete_document), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteDocument = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleteFolder?.let { folder ->
        AlertDialog(onDismissRequest = { deleteFolder = null },
            icon = { AppIcon(R.drawable.ic_folder, null) },
            title = { Text(stringResource(R.string.delete_folder_title)) },
            text = { Text(stringResource(R.string.delete_folder_body, folder.name)) },
            confirmButton = { TextButton(onClick = { deleteFolder = null; actions.onDeleteFolder(folder) }, enabled = !state.busy) {
                Text(stringResource(R.string.delete_folder), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun HomeHeader() {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderHeader(folder: VaultFolder, count: Int, busy: Boolean, onBack: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back, R.string.back) }
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.folder_documents, count, count), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { AppIcon(R.drawable.ic_more, R.string.more_options) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rename_folder)) },
                    leadingIcon = { AppIcon(R.drawable.ic_edit, null, Modifier.size(20.dp)) },
                    onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_folder), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { AppIcon(R.drawable.ic_delete, null, Modifier.size(20.dp)) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
        leadingIcon = { AppIcon(R.drawable.ic_search, null, Modifier.size(20.dp)) },
        trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { onQuery("") }) {
            AppIcon(R.drawable.ic_close, R.string.clear_search, Modifier.size(18.dp)) } } } else null,
        placeholder = { Text(stringResource(R.string.home_search)) },
        shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = MaterialTheme.colorScheme.primary,
        ))
}

@Composable
private fun QuickAction(@DrawableRes icon: Int, @StringRes label: Int, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun FolderCard(folder: VaultFolder, count: Int, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.width(132.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(folder.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.folder_documents, count, count), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal class DocumentMenu(
    val onOpen: (VaultDocument) -> Unit,
    val onAsk: (VaultDocument) -> Unit,
    val onMove: (VaultDocument) -> Unit,
    val onRename: (VaultDocument) -> Unit,
    val onExport: (VaultDocument) -> Unit,
    val onDelete: (VaultDocument) -> Unit,
)

@Composable
private fun DocumentCard(document: VaultDocument, folderName: String?, enabled: Boolean, menu: DocumentMenu) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = documentVisual(document.mimeType)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { menu.onOpen(document) }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(document.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val meta = buildList {
                    add(fileType(document.mimeType))
                    add(Formatter.formatShortFileSize(context, document.sizeBytes))
                    folderName?.let { add(stringResource(R.string.in_folder, it)) }
                    if (document.parentDocumentId != null) add(stringResource(R.string.derived_copy))
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = document.indexSummary().status
                if (status != IndexStatusUi.READY) IndexStatusBadge(status)
            }
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) { AppIcon(R.drawable.ic_more, R.string.more_options) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MenuItem(R.drawable.ic_sparkle, R.string.ask_about_document) { expanded = false; menu.onAsk(document) }
                    MenuItem(R.drawable.ic_documents, R.string.view_details) { expanded = false; menu.onOpen(document) }
                    MenuItem(R.drawable.ic_folder, R.string.move_to_folder) { expanded = false; menu.onMove(document) }
                    MenuItem(R.drawable.ic_edit, R.string.rename) { expanded = false; menu.onRename(document) }
                    MenuItem(R.drawable.ic_save, R.string.save_to_phone) { expanded = false; menu.onExport(document) }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_document), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { AppIcon(R.drawable.ic_delete, null, Modifier.size(20.dp)) },
                        onClick = { expanded = false; menu.onDelete(document) })
                }
            }
        }
    }
}

@Composable
private fun MenuItem(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { AppIcon(icon, null, Modifier.size(20.dp)) },
        onClick = onClick)
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(22.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyVault(busy: Boolean, onImport: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_vault), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.empty_documents_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_documents_body), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onImport, enabled = !busy) {
                AppIcon(R.drawable.ic_add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.choose_documents))
            }
        }
    }
}

@Composable
private fun EmptyFolder(busy: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(R.drawable.ic_folder, null, Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.folder_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.folder_empty_body), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onImport, enabled = !busy) {
            AppIcon(R.drawable.ic_add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.import_here))
        }
    }
}

@Composable
internal fun NameInputDialog(@StringRes title: Int, @StringRes label: Int, initial: String, @StringRes confirm: Int,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it.take(120) }, singleLine = true,
                label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Distinct, theme-independent hues help scan a long list; meaning is never conveyed by colour alone. */
@Composable
internal fun documentVisual(mimeType: String): Pair<Int, Color> = when (mimeType) {
    "application/pdf" -> R.drawable.ic_pdf to Color(0xFFD1453B)
    "image/jpeg", "image/png" -> R.drawable.ic_image to Color(0xFF2F7DD1)
    else -> R.drawable.ic_file to MaterialTheme.colorScheme.primary
}
