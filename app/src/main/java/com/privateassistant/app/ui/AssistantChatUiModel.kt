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
import com.privateassistant.core.assistant.GeneralChatRequestPolicy
import com.privateassistant.core.assistant.GeneralChatRoute
import com.privateassistant.core.assistant.LocalChatPrompt
import com.privateassistant.platform.inference.LocalChatException
import com.privateassistant.platform.inference.LocalChatFailure
import com.privateassistant.platform.inference.LocalChatGateway
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

enum class AssistantMessageKind { USER, MODEL, DOCUMENT, NOTICE }
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
    val documents: List<VaultDocument> = emptyList(), @StringRes val message: Int? = null) {
    override fun toString(): String = "AssistantChatUiState(busy=$busy)"
}

/** Unlocked-memory-only chat. No persistence, URI grants, action executor or generated tool calls. */
class AssistantChatUiModel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loadSource: suspend (VaultDocument) -> List<VaultPageEvidence>,
) {
    private val gateway = LocalChatGateway(context)
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
        val selected = mutableState.value.selectedDocument
        if (selected != null && documents.none { it.id == selected.id && it.revisionId == selected.revisionId }) {
            clearConversation()
            mutableState.update { it.copy(selectedDocument = null, message = R.string.chat_source_unavailable) }
        }
        mutableState.update { it.copy(documents = documents.toList()) }
    }

    /** OCR can change evidence even though the original revision ID stays immutable. */
    fun invalidateDocument(documentId: String) {
        if (!unlocked || mutableState.value.selectedDocument?.id != documentId) return
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
        mutableState.update { it.copy(selectedDocument = document) }
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
                if (selected == null) {
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
                    val pages = loadSource(selected)
                    currentCoroutineContext().ensureActive()
                    if (!current()) return@launch
                    if (pages.any { it.documentId != selected.id || it.revisionId != selected.revisionId }) {
                        throw IllegalStateException("Source identity mismatch")
                    }
                    if (pages.isEmpty()) {
                        notice(R.string.chat_document_incomplete)
                    } else {
                        val answer = withContext(Dispatchers.Default) {
                            val source = RevisionText(RevisionRef(DocumentId(selected.id), RevisionId(selected.revisionId)),
                                pages.map { PageText(it.pageNumber, it.text) })
                            val complete = selected.indexState == VaultIndexState.INDEXED && selected.pageCount > 0 &&
                                pages.size == selected.pageCount &&
                                pages.map { it.pageNumber }.toSet() == (1..selected.pageCount).toSet() &&
                                pages.all { it.state == VaultPageState.READY || it.state == VaultPageState.EMPTY }
                            val answer = DocumentChatEngine().answer(question, source, complete)
                            val facts = when (answer) {
                                is DocumentChatAnswer.Facts -> answer.facts
                                is DocumentChatAnswer.Conflict -> answer.facts
                                else -> emptyList()
                            }
                            check(facts.all { EvidenceValidator().validate(it.evidence, source).isValid })
                            answer
                        }
                        if (!current()) return@launch
                        when (answer) {
                            is DocumentChatAnswer.Facts -> documentFacts(answer.facts, selected,
                                when {
                                    answer.hasConflicts -> R.string.chat_document_summary_conflicts
                                    answer.partial -> R.string.chat_document_summary_partial
                                    answer.summary -> R.string.chat_document_summary_title
                                    else -> R.string.chat_document_fact_title
                                })
                            is DocumentChatAnswer.Conflict -> documentFacts(answer.facts, selected, R.string.chat_document_conflict)
                            DocumentChatAnswer.Missing -> notice(R.string.chat_document_missing)
                            DocumentChatAnswer.Clarify -> notice(R.string.chat_document_clarify)
                            DocumentChatAnswer.Incomplete -> notice(R.string.chat_document_incomplete)
                            DocumentChatAnswer.Unsupported -> notice(R.string.chat_document_unsupported)
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
