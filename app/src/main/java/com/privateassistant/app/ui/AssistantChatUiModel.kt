package com.privateassistant.app.ui

import android.content.Context
import androidx.annotation.StringRes
import com.privateassistant.app.R
import com.privateassistant.core.DocumentId
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.PageText
import com.privateassistant.core.RevisionId
import com.privateassistant.core.RevisionRef
import com.privateassistant.core.RevisionText
import com.privateassistant.core.assistant.AssistantCommand
import com.privateassistant.core.assistant.AssistantCommandParser
import com.privateassistant.core.assistant.ChatRole
import com.privateassistant.core.assistant.DocumentDescriptor
import com.privateassistant.core.assistant.DocumentHint
import com.privateassistant.core.assistant.DocumentResolution
import com.privateassistant.core.assistant.DocumentResolver
import com.privateassistant.core.assistant.PhotoSpec
import com.privateassistant.core.assistant.SourceRequirement
import com.privateassistant.core.identity.IdentifierKind
import com.privateassistant.core.identity.PersonalIdentifierExtractor
import com.privateassistant.core.tools.ByteSizes
import com.privateassistant.core.assistant.ChatTurn
import com.privateassistant.core.assistant.DocumentChatAnswer
import com.privateassistant.core.assistant.DocumentChatEngine
import com.privateassistant.core.assistant.DocumentAnswerValidator
import com.privateassistant.core.assistant.DocumentAnswerValidationReason
import com.privateassistant.core.assistant.DocumentPassageRetriever
import com.privateassistant.core.assistant.DocumentQuestionPrompt
import com.privateassistant.core.assistant.DocumentRetrievalSource
import com.privateassistant.core.assistant.RetrievedDocumentContext
import com.privateassistant.core.assistant.GeneralChatRequestPolicy
import com.privateassistant.core.assistant.GeneralChatRoute
import com.privateassistant.core.assistant.LocalChatPrompt
import com.privateassistant.core.assistant.LocalChatRequest
import com.privateassistant.platform.inference.LocalChatException
import com.privateassistant.platform.inference.LocalChatFailure
import com.privateassistant.platform.inference.LocalChatGateway
import com.privateassistant.platform.inference.LocalInferenceResult
import com.privateassistant.platform.vault.VaultDocument
import com.privateassistant.platform.vault.VaultIndexState
import com.privateassistant.platform.vault.VaultPageEvidence
import com.privateassistant.platform.vault.VaultPageState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AssistantMessageKind { USER, MODEL, DOCUMENT, DOCUMENT_MODEL, NOTICE, ACTION }
class AssistantCitationUi(val label: String, val pageNumber: Int, val documentId: String,
    val revisionId: String, val quote: String) {
    override fun toString(): String = "AssistantCitationUi"
}
class AssistantMessageUi(val id: Long, val user: Boolean, val text: String,
    val kind: AssistantMessageKind, val citations: List<AssistantCitationUi> = emptyList(),
    val action: AssistantActionUi? = null,
    val identifiers: List<IdentifierAnswerUi> = emptyList(),
    /** App-resolved vault documents offered with explicit Open/Save controls. */
    val documents: List<VaultDocument> = emptyList()) {
    override fun toString(): String = "AssistantMessageUi(kind=$kind)"
}
data class AssistantChatUiState(val draft: String = "", val messages: List<AssistantMessageUi> = emptyList(),
    val busy: Boolean = false, val modelReady: Boolean? = null, val selectedDocument: VaultDocument? = null,
    val allDocuments: Boolean = false,
    val documents: List<VaultDocument> = emptyList(), @StringRes val message: Int? = null) {
    override fun toString(): String = "AssistantChatUiState(busy=$busy)"
}

/** Unlocked-memory-only chat. No persistence, URI grants, action executor or generated tool calls. */
internal interface AssistantChatBackend {
    suspend fun isReady(): Boolean
    suspend fun generate(request: LocalChatRequest, cancellationRequested: () -> Boolean): LocalInferenceResult
}

private class DeviceChatBackend(context: Context) : AssistantChatBackend {
    private val gateway = LocalChatGateway(context)
    override suspend fun isReady() = gateway.isReady()
    override suspend fun generate(request: LocalChatRequest, cancellationRequested: () -> Boolean) =
        gateway.generate(request, cancellationRequested)
}

class AssistantChatUiModel internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loadSource: suspend (VaultDocument) -> List<VaultPageEvidence>,
    private val gateway: AssistantChatBackend,
    private val searchSources: suspend (String) -> List<VaultDocument> = { emptyList() },
    private val tools: AssistantToolHost? = null,
) {
    constructor(context: Context, scope: CoroutineScope,
        loadSource: suspend (VaultDocument) -> List<VaultPageEvidence>,
        searchSources: suspend (String) -> List<VaultDocument> = { emptyList() },
        tools: AssistantToolHost? = null,
    ) : this(context, scope, loadSource, DeviceChatBackend(context), searchSources, tools)
    private val mutableState = MutableStateFlow(AssistantChatUiState())
    val state = mutableState.asStateFlow()
    private var unlocked = false
    private var epoch = 0L
    private var nextMessageId = 0L
    private var operation: Job? = null
    private var readiness: Job? = null
    private var readinessEpoch = 0L
    private var token = AtomicBoolean(true)
    private val history = ArrayList<ChatTurn>()
    /** Which identifier kinds each scanned revision contains; derived plaintext metadata, memory only. */
    private val identifierIndex = HashMap<Pair<String, String>, Set<IdentifierKind>>()
    /** Documents this chat created; their arrival does not invalidate an all-documents scope. */
    private val ownCreated = HashSet<String>()

    fun unlock(documents: List<VaultDocument>) {
        lock()
        unlocked = true
        mutableState.value = AssistantChatUiState(documents = documents.toList())
        refreshModel()
    }

    fun lock() {
        unlocked = false
        revoke()
        readiness?.cancel(); readiness = null
        ++readinessEpoch
        history.clear()
        identifierIndex.clear()
        ownCreated.clear()
        mutableState.value = AssistantChatUiState()
    }

    fun setDocuments(documents: List<VaultDocument>) {
        if (!unlocked) return
        val before = mutableState.value
        val selected = before.selectedDocument
        val oldRefs = before.documents.map { it.id to it.revisionId }.toSet()
        val newRefs = documents.map { it.id to it.revisionId }.toSet()
        identifierIndex.keys.retainAll(newRefs)
        // Renames/moves keep the same revisions; only removed/changed sources or others' imports revoke scope.
        val scopeChanged = !newRefs.containsAll(oldRefs) || (newRefs - oldRefs).any { it.first !in ownCreated }
        if (before.allDocuments && scopeChanged) {
            clearConversation()
            mutableState.update { it.copy(message = R.string.chat_source_changed) }
        }
        if (selected != null && documents.none { it.id == selected.id && it.revisionId == selected.revisionId }) {
            clearConversation()
            mutableState.update { it.copy(selectedDocument = null, message = R.string.chat_source_unavailable) }
        }
        mutableState.update { it.copy(documents = documents.toList()) }
    }

    /** OCR can change evidence even though the original revision ID stays immutable. */
    fun invalidateDocument(documentId: String) {
        identifierIndex.keys.removeAll { it.first == documentId }
        if (!unlocked || (!mutableState.value.allDocuments && mutableState.value.selectedDocument?.id != documentId)) return
        clearConversation()
        mutableState.update { it.copy(selectedDocument = null, message = R.string.chat_source_changed) }
    }

    fun setDraft(text: String) {
        if (!unlocked || mutableState.value.busy) return
        var end = minOf(text.length, LocalChatPrompt.MAX_QUESTION_UTF16)
        if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        mutableState.update { it.copy(draft = text.substring(0, end), message = null) }
    }

    fun selectDocument(document: VaultDocument?) {
        if (!unlocked) return
        if (document != null && mutableState.value.documents.none {
                it.id == document.id && it.revisionId == document.revisionId }) return
        clearConversation()
        mutableState.update { it.copy(selectedDocument = document, allDocuments = false) }
    }

    fun selectAllDocuments() {
        if (!unlocked) return
        clearConversation()
        mutableState.update { it.copy(selectedDocument = null, allDocuments = true) }
    }

    fun clearConversation() {
        if (!unlocked) return
        revoke()
        history.clear()
        mutableState.update { it.copy(draft = "", messages = emptyList(), busy = false, message = null) }
    }

    fun cancel() {
        if (!unlocked || !mutableState.value.busy) return
        revoke()
        mutableState.update { it.copy(busy = false, message = R.string.chat_reply_cancelled) }
    }

    fun refreshModel() {
        if (!unlocked) return
        readiness?.cancel()
        val expectedEpoch = ++readinessEpoch
        mutableState.update { it.copy(modelReady = null) }
        readiness = scope.launch {
            val ready = gateway.isReady()
            if (unlocked && readinessEpoch == expectedEpoch) mutableState.update { it.copy(modelReady = ready) }
        }
    }

    fun send() {
        val snapshot = mutableState.value
        if (!unlocked || snapshot.busy) return
        val question = snapshot.draft.trim()
        if (question.isBlank()) return
        if (question.length > LocalChatPrompt.MAX_QUESTION_UTF16) return
        val selected = snapshot.selectedDocument
        val oldHistory = history.toList()
        val expectedEpoch = ++epoch
        val request = AtomicBoolean(false)
        token = request
        append(question, AssistantMessageKind.USER)
        mutableState.update { it.copy(draft = "", busy = true, message = null) }
        fun current() = unlocked && epoch == expectedEpoch && !request.get()
        val command = if (tools != null) AssistantCommandParser.parse(question) else null
        operation = scope.launch {
            try {
                if (command != null) {
                    handleCommand(command, snapshot.documents, selected, request, ::current)
                } else if (selected == null && !snapshot.allDocuments) {
                    when (GeneralChatRequestPolicy.route(question)) {
                        GeneralChatRoute.CHOOSE_DOCUMENT -> if (current()) notice(R.string.chat_choose_document_notice)
                        GeneralChatRoute.USE_DOCUMENT_CONTROLS -> if (current()) notice(R.string.chat_use_controls_notice)
                        GeneralChatRoute.GENERATE -> generateGeneral(question, oldHistory, request, ::current)
                    }
                } else {
                    if (GeneralChatRequestPolicy.route(question) == GeneralChatRoute.USE_DOCUMENT_CONTROLS) {
                        if (current()) notice(R.string.chat_use_controls_notice)
                        return@launch
                    }
                    val documents = if (selected == null) rankDocuments(question, snapshot.documents, ::current) else listOf(selected)
                    val sources = readSources(documents, request, ::current)
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@launch
                    val generalFallback = selected == null &&
                        GeneralChatRequestPolicy.route(question) == GeneralChatRoute.GENERATE
                    if (sources.isEmpty() || sources.all { it.source.pages.all { page -> page.text.isBlank() } }) {
                        if (generalFallback) generateGeneral(question, oldHistory, request, ::current)
                        else notice(R.string.chat_document_incomplete)
                    } else {
                        val exact = if (selected != null && !DocumentPassageRetriever.isSummaryQuestion(question)) {
                            withContext(Dispatchers.Default) {
                                val source = sources.single()
                                DocumentChatEngine().answer(question, source.source, source.complete).also { answer ->
                                    val facts = when (answer) {
                                        is DocumentChatAnswer.Facts -> answer.facts
                                        is DocumentChatAnswer.Conflict -> answer.facts
                                        else -> emptyList()
                                    }
                                    check(facts.all { EvidenceValidator().validate(it.evidence, source.source).isValid })
                                }
                            }
                        } else DocumentChatAnswer.Unsupported
                        if (!current()) return@launch
                        when (exact) {
                            is DocumentChatAnswer.Facts -> documentFacts(exact.facts, requireNotNull(selected),
                                when {
                                    exact.hasConflicts -> R.string.chat_document_summary_conflicts
                                    exact.partial -> R.string.chat_document_summary_partial
                                    exact.summary -> R.string.chat_document_summary_title
                                    else -> R.string.chat_document_fact_title
                                })
                            is DocumentChatAnswer.Conflict -> documentFacts(exact.facts, requireNotNull(selected), R.string.chat_document_conflict)
                            DocumentChatAnswer.Missing -> notice(R.string.chat_document_missing)
                            DocumentChatAnswer.Clarify -> notice(R.string.chat_document_clarify)
                            DocumentChatAnswer.Incomplete -> notice(R.string.chat_document_incomplete)
                            DocumentChatAnswer.Unsupported -> answerPassages(question, sources, documents, request, ::current) {
                                if (generalFallback) generateGeneral(question, oldHistory, request, ::current) else null
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LocalChatException) {
                if (current()) {
                    val message = when (failure.reason) {
                        LocalChatFailure.MODEL_REQUIRED -> R.string.chat_model_required_notice
                        LocalChatFailure.MEMORY -> R.string.chat_memory_notice
                        LocalChatFailure.GENERATION -> R.string.chat_generation_failed
                    }
                    notice(message)
                    if (failure.reason == LocalChatFailure.MODEL_REQUIRED) mutableState.update { it.copy(modelReady = false) }
                }
            } catch (_: Exception) {
                if (current()) notice(R.string.chat_request_failed)
            } finally {
                if (current()) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** A direct tap on an assistant card proposes [tool] for an explicit, currently authorized document. */
    fun proposeTool(tool: ToolKind, document: VaultDocument) {
        if (!unlocked || tools == null || mutableState.value.busy || !isAuthorized(document)) return
        proposeFor(tool, document, 0L, PhotoSpec.PASSPORT_35X45)
    }

    fun chooseActionDocument(messageId: Long, document: VaultDocument) {
        val message = mutableState.value.messages.firstOrNull { it.id == messageId } ?: return
        val action = message.action ?: return
        if (!unlocked || mutableState.value.busy || action.status != ActionStatus.CHOOSE ||
            action.choices.none { it.id == document.id && it.revisionId == document.revisionId } || !isAuthorized(document)) return
        replaceAction(messageId, action.with(ActionStatus.CANCELLED))
        proposeFor(action.tool, document, action.targetBytes, action.spec)
    }

    fun cancelAction(messageId: Long) {
        val action = mutableState.value.messages.firstOrNull { it.id == messageId }?.action ?: return
        if (!unlocked || action.status !in setOf(ActionStatus.CHOOSE, ActionStatus.PROPOSED)) return
        replaceAction(messageId, action.with(ActionStatus.CANCELLED))
    }

    /**
     * The only path that runs a tool: the user's tap on this card's confirm button. The document
     * is rechecked against the current authorized list; model or document text cannot reach here.
     */
    fun confirmAction(messageId: Long) {
        val host = tools ?: return
        val snapshot = mutableState.value
        val action = snapshot.messages.firstOrNull { it.id == messageId }?.action ?: return
        val document = action.document ?: return
        if (!unlocked || snapshot.busy || action.status != ActionStatus.PROPOSED || !isAuthorized(document)) return
        val expectedEpoch = ++epoch
        val request = AtomicBoolean(false)
        token = request
        fun current() = unlocked && epoch == expectedEpoch && !request.get()
        replaceAction(messageId, action.with(ActionStatus.RUNNING))
        mutableState.update { it.copy(busy = true, message = null) }
        operation = scope.launch {
            try {
                val outcome = host.run(AssistantToolRequest(action.tool, document, action.targetBytes, action.spec), request::get)
                currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                replaceAction(messageId, action.with(ActionStatus.DONE))
                when (outcome) {
                    is AssistantToolOutcome.Created -> {
                        ownCreated += outcome.documents.map { it.id }
                        host.documentsCreated()
                        if (!current()) return@launch
                        val names = outcome.documents.joinToString("\n") { "• " + it.displayName + " (" + ByteSizes.format(it.sizeBytes) + ")" }
                        val text = buildString {
                            append(context.getString(R.string.tool_done, names))
                            if (action.tool == ToolKind.COMPRESS) {
                                append("\n").append(context.getString(R.string.tool_done_size,
                                    ByteSizes.format(document.sizeBytes), ByteSizes.format(action.targetBytes)))
                            }
                            if (outcome.faceFound == false) append("\n").append(context.getString(R.string.tool_no_face))
                            if (action.tool == ToolKind.PASSPORT_PHOTO) append("\n").append(context.getString(R.string.tool_passport_check))
                            append("\n").append(context.getString(R.string.tool_original_unchanged))
                        }
                        append(text, AssistantMessageKind.DOCUMENT, documents = outcome.documents)
                    }
                    is AssistantToolOutcome.AlreadyFits -> append(context.getString(R.string.tool_already_fits,
                        ByteSizes.format(outcome.bytes), ByteSizes.format(action.targetBytes)), AssistantMessageKind.DOCUMENT,
                        documents = listOf(document))
                    is AssistantToolOutcome.Unreachable -> notice(context.getString(R.string.tool_unreachable,
                        ByteSizes.format(action.targetBytes), ByteSizes.format(outcome.smallestBytes)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current()) {
                    replaceAction(messageId, action.with(ActionStatus.FAILED))
                    notice(R.string.tool_failed)
                }
            } finally {
                if (current()) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun handleCommand(command: AssistantCommand, documents: List<VaultDocument>, focused: VaultDocument?,
        request: AtomicBoolean, current: () -> Boolean,
    ) {
        if (documents.isEmpty()) { notice(R.string.chat_no_documents); return }
        when (command) {
            is AssistantCommand.LookupIdentifier -> lookupIdentifier(command.kind, documents, request, current)
            is AssistantCommand.FindDocument -> {
                command.hint.identifier?.let { scanIdentifiers(documents, request, current) }
                if (!current()) return
                when (val resolution = DocumentResolver.resolve(command.hint, descriptors(documents), SourceRequirement.ANY, focused?.id)) {
                    is DocumentResolution.Single -> append(context.getString(R.string.find_single),
                        AssistantMessageKind.DOCUMENT, documents = documentsById(documents, listOf(resolution.id)))
                    is DocumentResolution.Choose -> append(context.getString(R.string.find_several),
                        AssistantMessageKind.DOCUMENT, documents = documentsById(documents, resolution.ids))
                    DocumentResolution.NoCompatible -> notice(R.string.find_none)
                }
            }
            is AssistantCommand.CompressToSize -> resolveTool(ToolKind.COMPRESS, command.hint, command.targetBytes,
                PhotoSpec.PASSPORT_35X45, documents, focused, request, current)
            is AssistantCommand.ConvertToImage -> resolveTool(ToolKind.TO_IMAGE, command.hint, 0L,
                PhotoSpec.PASSPORT_35X45, documents, focused, request, current)
            is AssistantCommand.ConvertToPdf -> resolveTool(ToolKind.TO_PDF, command.hint, 0L,
                PhotoSpec.PASSPORT_35X45, documents, focused, request, current)
            is AssistantCommand.PassportPhoto -> resolveTool(ToolKind.PASSPORT_PHOTO, command.hint, 0L,
                command.spec, documents, focused, request, current)
        }
    }

    private suspend fun resolveTool(tool: ToolKind, hint: DocumentHint, targetBytes: Long, spec: PhotoSpec,
        documents: List<VaultDocument>, focused: VaultDocument?, request: AtomicBoolean, current: () -> Boolean,
    ) {
        if (hint.identifier != null) scanIdentifiers(documents, request, current)
        if (!current()) return
        val requirement = if (tool == ToolKind.PASSPORT_PHOTO) SourceRequirement.IMAGE else SourceRequirement.IMAGE_OR_PDF
        when (val resolution = DocumentResolver.resolve(hint, descriptors(documents), requirement, focused?.id)) {
            is DocumentResolution.Single -> proposeFor(tool, documentsById(documents, listOf(resolution.id)).single(), targetBytes, spec)
            is DocumentResolution.Choose -> append(context.getString(R.string.tool_choose_document), AssistantMessageKind.ACTION,
                action = AssistantActionUi(tool, ActionStatus.CHOOSE, null, documentsById(documents, resolution.ids), targetBytes, spec))
            DocumentResolution.NoCompatible -> notice(if (tool == ToolKind.PASSPORT_PHOTO) R.string.tool_no_photo else R.string.tool_no_compatible)
        }
    }

    private fun proposeFor(tool: ToolKind, document: VaultDocument, targetBytes: Long, spec: PhotoSpec) {
        val image = document.mimeType == "image/jpeg" || document.mimeType == "image/png"
        when {
            tool == ToolKind.TO_IMAGE && document.mimeType == "image/jpeg" ->
                append(context.getString(R.string.tool_already_image, document.displayName), AssistantMessageKind.DOCUMENT,
                    documents = listOf(document))
            tool == ToolKind.TO_PDF && document.mimeType == "application/pdf" ->
                append(context.getString(R.string.tool_already_pdf, document.displayName), AssistantMessageKind.DOCUMENT,
                    documents = listOf(document))
            tool == ToolKind.COMPRESS && targetBytes > 0 && document.sizeBytes <= targetBytes ->
                append(context.getString(R.string.tool_already_fits, ByteSizes.format(document.sizeBytes),
                    ByteSizes.format(targetBytes)), AssistantMessageKind.DOCUMENT, documents = listOf(document))
            tool == ToolKind.COMPRESS && targetBytes <= 0 -> notice(R.string.tool_need_size)
            tool == ToolKind.PASSPORT_PHOTO && !image -> notice(R.string.tool_no_photo)
            !DocumentResolver.compatible(document.mimeType, SourceRequirement.IMAGE_OR_PDF) -> notice(R.string.tool_no_compatible)
            else -> append(context.getString(toolTitle(tool)), AssistantMessageKind.ACTION,
                action = AssistantActionUi(tool, ActionStatus.PROPOSED, document, emptyList(), targetBytes, spec))
        }
    }

    private suspend fun lookupIdentifier(kind: IdentifierKind, documents: List<VaultDocument>, request: AtomicBoolean,
        current: () -> Boolean,
    ) {
        val found = scanIdentifiers(documents, request, current, kind)
        if (!current()) return
        val label = context.getString(identifierLabel(kind))
        if (found.isEmpty()) {
            val unread = documents.count { it.indexState != VaultIndexState.INDEXED && it.mimeType != "text/plain" }
            notice(context.getString(R.string.id_not_found, label) +
                if (unread > 0) "\n" + context.resources.getQuantityString(R.plurals.id_unread_documents, unread, unread) else "")
            return
        }
        val distinct = found.distinctBy { it.value.filter(Char::isLetterOrDigit).uppercase() to it.masked }
            .take(MAX_IDENTIFIER_ANSWERS)
        val text = when {
            distinct.size == 1 && distinct.single().masked -> context.getString(R.string.id_found_masked, label)
            distinct.size == 1 -> context.getString(R.string.id_found_single, label)
            else -> context.getString(R.string.id_found_several, distinct.size, label)
        }
        append(text, AssistantMessageKind.DOCUMENT, citations = distinct.map {
            AssistantCitationUi(it.document.displayName, it.pageNumber, it.document.id, it.document.revisionId, it.contextLine)
        }, identifiers = distinct)
    }

    /**
     * Reads a bounded set of authorized sources and extracts printed identifiers. Name matches and
     * previously seen kinds are read first. Every returned value is re-validated against its page.
     */
    private suspend fun scanIdentifiers(documents: List<VaultDocument>, request: AtomicBoolean, current: () -> Boolean,
        kind: IdentifierKind? = null,
    ): List<IdentifierAnswerUi> {
        val ordered = documents.sortedByDescending { document ->
            val known = identifierIndex[document.id to document.revisionId]
            val nameHit = kind != null && AssistantCommandParser.identifierKind(document.displayName) == kind
            (if (nameHit) 4 else 0) + (if (kind != null && known?.contains(kind) == true) 2 else 0) +
                (if (known == null) 1 else 0)
        }.filter { it.indexState != VaultIndexState.NOT_INDEXED || it.mimeType == "text/plain" }
        if (ordered.isEmpty()) return emptyList()
        val sources = readSources(ordered, request, current)
        currentCoroutineContext().ensureActive()
        if (!current()) throw CancellationException("Document scope revoked")
        val byRef = documents.associateBy { it.id to it.revisionId }
        return withContext(Dispatchers.Default) {
            val extractor = PersonalIdentifierExtractor()
            val validator = EvidenceValidator()
            sources.flatMap { source ->
                val key = source.source.ref.documentId.value to source.source.ref.revisionId.value
                val document = checkNotNull(byRef[key])
                val candidates = extractor.extract(source.source).candidates
                check(candidates.all { validator.validate(it.evidence, source.source).isValid })
                identifierIndex[key] = candidates.map { it.kind }.toSet()
                candidates.filter { it.kind == kind }.map {
                    IdentifierAnswerUi(it.kind, it.rawValue, it.masked, document, it.evidence.pageNumber, it.contextLine)
                }
            }
        }
    }

    private fun descriptors(documents: List<VaultDocument>) = documents.map {
        DocumentDescriptor(it.id, it.displayName, it.mimeType, tools?.folderName(it.folderId),
            identifierIndex[it.id to it.revisionId].orEmpty())
    }

    private fun documentsById(documents: List<VaultDocument>, ids: List<String>): List<VaultDocument> {
        val byId = documents.associateBy { it.id }
        return ids.map { checkNotNull(byId[it]) }
    }

    private fun isAuthorized(document: VaultDocument) =
        mutableState.value.documents.any { it.id == document.id && it.revisionId == document.revisionId }

    private fun replaceAction(messageId: Long, action: AssistantActionUi) {
        mutableState.update { state ->
            state.copy(messages = state.messages.map { message ->
                if (message.id != messageId) message else AssistantMessageUi(message.id, message.user, message.text,
                    message.kind, message.citations, action, message.identifiers, message.documents)
            })
        }
    }

    private suspend fun rankDocuments(question: String, documents: List<VaultDocument>, current: () -> Boolean): List<VaultDocument> {
        if (documents.size <= DocumentPassageRetriever.MAX_SOURCES || DocumentPassageRetriever.isSummaryQuestion(question)) {
            return documents
        }
        val scores = HashMap<Pair<String, String>, Int>()
        val authorized = documents.associateBy { it.id to it.revisionId }
        for (term in DocumentPassageRetriever.queryTerms(question).take(8)) {
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("Document scope revoked")
            val matches = searchSources(term)
            for (document in matches.distinctBy { it.id to it.revisionId }) {
                val key = document.id to document.revisionId
                if (key in authorized) scores[key] = (scores[key] ?: 0) + 1
            }
        }
        return documents.sortedByDescending { scores[it.id to it.revisionId] ?: 0 }
    }

    /** Sequential reads retain a bounded prefix; truncation is explicit and never proves absence. */
    private suspend fun readSources(documents: List<VaultDocument>, request: AtomicBoolean,
        current: () -> Boolean,
    ): List<DocumentRetrievalSource> {
        val sources = ArrayList<DocumentRetrievalSource>()
        var remaining = DocumentPassageRetriever.MAX_TOTAL_UTF16
        var scopePartial = documents.size > DocumentPassageRetriever.MAX_SOURCES
        for (document in documents.take(DocumentPassageRetriever.MAX_SOURCES)) {
            currentCoroutineContext().ensureActive()
            if (!current() || request.get()) throw CancellationException("Document scope revoked")
            if (remaining == 0) { scopePartial = true; break }
            val pages = loadSource(document)
            currentCoroutineContext().ensureActive()
            if (!current()) throw CancellationException("Document scope revoked")
            check(pages.all { it.documentId == document.id && it.revisionId == document.revisionId })
            check(pages.map { it.pageNumber }.distinct().size == pages.size)
            check(pages.all { it.pageNumber in 1..document.pageCount })
            val retained = ArrayList<PageText>()
            var truncated = pages.size > 50
            for (page in pages.sortedBy { it.pageNumber }.take(50)) {
                if (remaining == 0) { truncated = true; break }
                var end = minOf(page.text.length, remaining)
                if (end < page.text.length && end > 0 && Character.isHighSurrogate(page.text[end - 1])) end--
                retained += PageText(page.pageNumber, page.text.substring(0, end))
                remaining -= end
                if (end < page.text.length) truncated = true
            }
            if (retained.isEmpty()) { scopePartial = true; continue }
            val complete = !truncated && document.indexState == VaultIndexState.INDEXED && document.pageCount > 0 &&
                pages.size == document.pageCount &&
                pages.map { it.pageNumber }.toSet() == (1..document.pageCount).toSet() &&
                pages.all { it.state == VaultPageState.READY || it.state == VaultPageState.EMPTY }
            val source = RevisionText(RevisionRef(DocumentId(document.id), RevisionId(document.revisionId)), retained)
            sources += DocumentRetrievalSource(source, complete)
        }
        return if (scopePartial) sources.map { DocumentRetrievalSource(it.source, false) } else sources
    }

    /** Plain conversation through the local model; never receives vault text. */
    private suspend fun generateGeneral(question: String, oldHistory: List<ChatTurn>, request: AtomicBoolean,
        current: () -> Boolean,
    ) {
        val prompt = LocalChatPrompt.build(question, oldHistory)
        val result = gateway.generate(prompt, request::get)
        currentCoroutineContext().ensureActive()
        if (!current()) return
        // Validate before adding either turn; cancelled/failed requests never enter model history.
        val userTurn = ChatTurn(ChatRole.USER, question)
        val assistantTurn = ChatTurn(ChatRole.ASSISTANT, result.text)
        history.addAll(listOf(userTurn, assistantTurn))
        while (history.size > MAX_HISTORY_TURNS) history.removeAt(0)
        append(result.text, AssistantMessageKind.MODEL)
        mutableState.update { it.copy(modelReady = true) }
    }

    /** [noPassages] may answer a non-private all-documents question generally; null keeps the notice. */
    private suspend fun answerPassages(question: String, sources: List<DocumentRetrievalSource>,
        documents: List<VaultDocument>, request: AtomicBoolean, current: () -> Boolean,
        noPassages: suspend () -> Unit? = { null },
    ) {
        val retrieved = withContext(Dispatchers.Default) {
            DocumentPassageRetriever.retrieve(question, sources).also { result ->
                val byRef = sources.associateBy { it.source.ref }
                check(result.passages.all { span ->
                    byRef[span.ref]?.let { EvidenceValidator().validate(span, it.source).isValid } == true
                })
            }
        }
        if (!current()) return
        if (retrieved.passages.isEmpty()) {
            if (noPassages() == null) {
                notice(if (sources.any { !it.complete }) R.string.chat_document_incomplete else R.string.chat_no_passages)
            }
            return
        }
        val allCitations = passageCitations(retrieved.passages, documents)
        try {
            val result = gateway.generate(DocumentQuestionPrompt.build(question, retrieved), request::get)
            currentCoroutineContext().ensureActive()
            if (!current()) return
            val validation = DocumentAnswerValidator.validate(result.text, retrieved)
            if (validation.accepted) {
                val heading = context.getString(when {
                    validation.reason == DocumentAnswerValidationReason.UNCITED_CONTEXT -> R.string.chat_ai_uncited_context_note
                    retrieved.partial -> R.string.chat_ai_passages_partial
                    else -> R.string.chat_ai_passages_note
                }) + if (retrieved.partial && validation.reason == DocumentAnswerValidationReason.UNCITED_CONTEXT) {
                    "\n" + context.getString(R.string.chat_passages_partial)
                } else ""
                append(result.text + "\n\n" + heading, AssistantMessageKind.DOCUMENT_MODEL,
                    passageCitations(validation.citations, documents, retrieved.passages))
            } else {
                showPassages(retrieved, allCitations, R.string.chat_answer_unverified)
            }
            mutableState.update { it.copy(modelReady = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LocalChatException) {
            if (!current()) return
            if (failure.reason == LocalChatFailure.MODEL_REQUIRED) mutableState.update { it.copy(modelReady = false) }
            showPassages(retrieved, allCitations, when (failure.reason) {
                LocalChatFailure.MODEL_REQUIRED -> R.string.chat_passages_model_needed
                LocalChatFailure.MEMORY -> R.string.chat_passages_memory
                LocalChatFailure.GENERATION -> R.string.chat_passages_generation_failed
            })
        }
    }

    private fun passageCitations(passages: List<com.privateassistant.core.EvidenceSpan>, documents: List<VaultDocument>,
        numberedPassages: List<com.privateassistant.core.EvidenceSpan> = passages,
    ): List<AssistantCitationUi> {
        val authorized = documents.associateBy { it.id to it.revisionId }
        return passages.map { span ->
            val document = checkNotNull(authorized[span.ref.documentId.value to span.ref.revisionId.value])
            val index = numberedPassages.indexOf(span).also { check(it >= 0) }
            AssistantCitationUi("[${index + 1}] ${document.displayName}", span.pageNumber,
                document.id, document.revisionId, span.quote)
        }
    }

    private fun showPassages(retrieved: RetrievedDocumentContext, citations: List<AssistantCitationUi>, @StringRes explanation: Int) {
        val partial = if (retrieved.partial) "\n" + context.getString(R.string.chat_passages_partial) else ""
        append(context.getString(explanation) + partial, AssistantMessageKind.DOCUMENT, citations)
    }

    private fun documentFacts(facts: List<com.privateassistant.core.assistant.ChatDocumentFact>,
        document: VaultDocument, @StringRes heading: Int) {
        val selected = facts.take(8)
        val text = buildString {
            append(context.getString(heading))
            selected.forEach { append("\n").append(context.getString(fieldLabel(it.key))).append(": ").append(it.rawValue) }
            if (facts.size > selected.size) append("\n").append(context.getString(R.string.chat_document_more_matches))
        }
        append(text, AssistantMessageKind.DOCUMENT, selected.map {
            AssistantCitationUi(context.getString(fieldLabel(it.key)), it.evidence.pageNumber,
                document.id, document.revisionId, it.evidence.quote)
        })
    }

    private fun notice(@StringRes message: Int) = append(context.getString(message), AssistantMessageKind.NOTICE)
    private fun notice(message: String) = append(message, AssistantMessageKind.NOTICE)

    private fun append(text: String, kind: AssistantMessageKind, citations: List<AssistantCitationUi> = emptyList(),
        action: AssistantActionUi? = null, identifiers: List<IdentifierAnswerUi> = emptyList(),
        documents: List<VaultDocument> = emptyList(),
    ) {
        val message = AssistantMessageUi(++nextMessageId, kind == AssistantMessageKind.USER, text, kind, citations,
            action, identifiers, documents)
        mutableState.update { it.copy(messages = (it.messages + message).takeLast(MAX_VISIBLE_MESSAGES)) }
    }

    private fun revoke() {
        ++epoch
        token.set(true)
        operation?.cancel(); operation = null
    }

    companion object {
        private const val MAX_VISIBLE_MESSAGES = 20
        private const val MAX_HISTORY_TURNS = 8
        private const val MAX_IDENTIFIER_ANSWERS = 6
    }
}
