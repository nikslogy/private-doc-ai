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
import com.privateassistant.core.assistant.ChatRole
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

enum class AssistantMessageKind { USER, MODEL, DOCUMENT, DOCUMENT_MODEL, NOTICE }
class AssistantCitationUi(val label: String, val pageNumber: Int, val documentId: String,
    val revisionId: String, val quote: String) {
    override fun toString(): String = "AssistantCitationUi"
}
class AssistantMessageUi(val id: Long, val user: Boolean, val text: String,
    val kind: AssistantMessageKind, val citations: List<AssistantCitationUi> = emptyList()) {
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
) {
    constructor(context: Context, scope: CoroutineScope,
        loadSource: suspend (VaultDocument) -> List<VaultPageEvidence>,
        searchSources: suspend (String) -> List<VaultDocument> = { emptyList() },
    ) : this(context, scope, loadSource, DeviceChatBackend(context), searchSources)
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
        mutableState.value = AssistantChatUiState()
    }

    fun setDocuments(documents: List<VaultDocument>) {
        if (!unlocked) return
        val before = mutableState.value
        val selected = before.selectedDocument
        if (before.allDocuments && before.documents != documents) {
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
        operation = scope.launch {
            try {
                if (selected == null && !snapshot.allDocuments) {
                    when (GeneralChatRequestPolicy.route(question)) {
                        GeneralChatRoute.CHOOSE_DOCUMENT -> if (current()) notice(R.string.chat_choose_document_notice)
                        GeneralChatRoute.USE_DOCUMENT_CONTROLS -> if (current()) notice(R.string.chat_use_controls_notice)
                        GeneralChatRoute.GENERATE -> {
                            val prompt = LocalChatPrompt.build(question, oldHistory)
                            val result = gateway.generate(prompt, request::get)
                            currentCoroutineContext().ensureActive()
                            if (!current()) return@launch
                            // Validate before adding either turn; cancelled/failed requests never enter model history.
                            val userTurn = ChatTurn(ChatRole.USER, question)
                            val assistantTurn = ChatTurn(ChatRole.ASSISTANT, result.text)
                            history.addAll(listOf(userTurn, assistantTurn))
                            while (history.size > MAX_HISTORY_TURNS) history.removeAt(0)
                            append(result.text, AssistantMessageKind.MODEL)
                            mutableState.update { it.copy(modelReady = true) }
                        }
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
                    if (sources.isEmpty() || sources.all { it.source.pages.all { page -> page.text.isBlank() } }) {
                        notice(R.string.chat_document_incomplete)
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
                            DocumentChatAnswer.Unsupported -> answerPassages(question, sources, documents, request, ::current)
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

    private suspend fun answerPassages(question: String, sources: List<DocumentRetrievalSource>,
        documents: List<VaultDocument>, request: AtomicBoolean, current: () -> Boolean,
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
            notice(if (sources.any { !it.complete }) R.string.chat_document_incomplete else R.string.chat_no_passages)
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

    private fun append(text: String, kind: AssistantMessageKind, citations: List<AssistantCitationUi> = emptyList()) {
        val message = AssistantMessageUi(++nextMessageId, kind == AssistantMessageKind.USER, text, kind, citations)
        mutableState.update { it.copy(messages = (it.messages + message).takeLast(MAX_VISIBLE_MESSAGES)) }
    }

    private fun revoke() {
        ++epoch
        token.set(true)
        operation?.cancel(); operation = null
    }

    companion object { private const val MAX_VISIBLE_MESSAGES = 20; private const val MAX_HISTORY_TURNS = 8 }
}
