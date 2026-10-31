package com.privateassistant.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privateassistant.app.ui.ActionStatus
import com.privateassistant.app.ui.AssistantChatUiModel
import com.privateassistant.app.ui.AssistantToolHost
import com.privateassistant.app.ui.AssistantToolOutcome
import com.privateassistant.app.ui.AssistantToolRequest
import com.privateassistant.app.ui.ToolKind
import com.privateassistant.app.ui.AssistantChatBackend
import com.privateassistant.app.ui.AssistantChatUiState
import com.privateassistant.app.ui.AssistantMessageKind
import com.privateassistant.app.ui.AssistantMessageUi
import com.privateassistant.core.assistant.ChatRole
import com.privateassistant.core.assistant.LocalChatProfile
import com.privateassistant.core.assistant.LocalChatRequest
import com.privateassistant.platform.inference.LocalChatException
import com.privateassistant.platform.inference.LocalChatFailure
import com.privateassistant.platform.inference.LocalInferenceResult
import com.privateassistant.platform.documents.ExtractionMethod
import com.privateassistant.platform.documents.SourceSpanKind
import com.privateassistant.platform.documents.SourceTextSpan
import com.privateassistant.platform.vault.VaultDocument
import com.privateassistant.platform.vault.VaultIndexState
import com.privateassistant.platform.vault.VaultPageEvidence
import com.privateassistant.platform.vault.VaultPageState
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller/session tests with synthetic text only; no native model, vault or key is opened. */
@RunWith(AndroidJUnit4::class)
class AssistantChatLifecycleTest {
    @Test
    fun selectedDocumentQuestionReturnsExactCitedPremium() = runBlocking {
        val document = document("exact")
        val quote = "Total premium: INR 1,23,456.70"
        withModel({ pages(it, "Policy number: SYNTHETIC-000123\n$quote") }) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("What is the total premium?")
            val answer = response()
            assertEquals(AssistantMessageKind.DOCUMENT, answer.kind)
            assertTrue(answer.text.contains("INR 1,23,456.70"))
            val citation = answer.citations.single()
            assertEquals(document.id, citation.documentId)
            assertEquals(document.revisionId, citation.revisionId)
            assertEquals(1, citation.pageNumber)
            assertEquals(quote, citation.quote)
            assertTrue(model.state.value.messages.first().user)
            assertEquals(2, model.state.value.messages.size)
        }
    }

    @Test
    fun missingConflictingAndPartialFactsNeverInventAUniqueValue() = runBlocking {
        val missing = document("missing")
        val conflicting = document("conflicting")
        val partial = document("partial").copy(indexState = VaultIndexState.PARTIAL)
        withModel({ selected ->
            when (selected.id) {
                missing.id -> pages(selected, "Policy number: SYNTHETIC-000001")
                conflicting.id -> pages(selected, "Total premium: INR 100.00\nTotal premium: INR 200.00")
                else -> pages(selected, "Total premium: INR 300.00")
            }
        }) {
            unlock(missing, conflicting, partial)
            onMain { model.selectDocument(missing) }
            ask("What is the tax?")
            val missingAnswer = response()
            assertEquals(AssistantMessageKind.NOTICE, missingAnswer.kind)
            assertEquals(context.getString(R.string.chat_document_missing), missingAnswer.text)
            assertTrue(missingAnswer.citations.isEmpty())

            onMain { model.selectDocument(conflicting) }
            ask("What is the total premium?")
            val conflictAnswer = response()
            assertEquals(AssistantMessageKind.DOCUMENT, conflictAnswer.kind)
            assertTrue(conflictAnswer.text.startsWith(context.getString(R.string.chat_document_conflict)))
            assertTrue(conflictAnswer.text.contains("INR 100.00"))
            assertTrue(conflictAnswer.text.contains("INR 200.00"))
            assertEquals(2, conflictAnswer.citations.size)

            onMain { model.selectDocument(partial) }
            ask("What is the total premium?")
            val partialAnswer = response()
            assertEquals(AssistantMessageKind.NOTICE, partialAnswer.kind)
            assertEquals(context.getString(R.string.chat_document_incomplete), partialAnswer.text)
            assertTrue(partialAnswer.citations.isEmpty())
            assertFalse(partialAnswer.text.contains("300.00"))
        }
    }

    @Test
    fun clearConversationRevokesInFlightSourceAndClearsPrivateDraft() = runBlocking {
        val document = document("clear")
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        try {
            withModel({ selected ->
                entered.complete(requireNotNull(currentCoroutineContext()[Job]))
                withContext(NonCancellable) { release.await() }
                pages(selected, "Total premium: INR 444.44")
            }) {
                releaseOnClose(release)
                unlock(document)
                onMain {
                    model.selectDocument(document)
                    model.setDraft("SYNTHETIC-PRIVATE-DRAFT-CLEAR")
                    model.clearConversation()
                }
                assertEquals("", model.state.value.draft)
                ask("What is the total premium?")
                val oldJob = withTimeout(TIMEOUT_MILLIS) { entered.await() }
                onMain { model.clearConversation() }
                assertCleared(model.state.value)
                release.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { oldJob.join() }
                assertCleared(model.state.value)
                assertEquals(document.id, model.state.value.selectedDocument?.id)
            }
        } finally { release.complete(Unit) }
    }

    @Test
    fun sourceChangeRejectsLateCompletionFromPreviousDocument() = runBlocking {
        val old = document("source-old")
        val fresh = document("source-new")
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        try {
            withModel({ selected ->
                if (selected.id == old.id) {
                    entered.complete(requireNotNull(currentCoroutineContext()[Job]))
                    withContext(NonCancellable) { release.await() }
                    pages(selected, "Total premium: INR 111.11")
                } else pages(selected, "Total premium: INR 222.22")
            }) {
                releaseOnClose(release)
                unlock(old, fresh)
                onMain { model.selectDocument(old) }
                ask("What is the total premium?")
                val oldJob = withTimeout(TIMEOUT_MILLIS) { entered.await() }
                onMain { model.selectDocument(fresh) }
                ask("What is the total premium?")
                assertTrue(response().text.contains("INR 222.22"))
                release.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { oldJob.join() }
                val current = model.state.value
                assertEquals(2, current.messages.size)
                assertFalse(current.busy)
                assertEquals(fresh.revisionId, current.selectedDocument?.revisionId)
                assertFalse(current.messages.any { it.text.contains("111.11") })
                assertEquals(fresh.revisionId, current.messages.last().citations.single().revisionId)
            }
        } finally { release.complete(Unit) }
    }

    @Test
    fun lockRejectsOldCompletionInNewUnlockedSession() = runBlocking {
        val old = document("session-old")
        val fresh = document("session-new")
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        try {
            withModel({ selected ->
                if (selected.id == old.id) {
                    entered.complete(requireNotNull(currentCoroutineContext()[Job]))
                    withContext(NonCancellable) { release.await() }
                    pages(selected, "Total premium: INR 555.55")
                } else pages(selected, "Total premium: INR 666.66")
            }) {
                releaseOnClose(release)
                unlock(old)
                onMain { model.selectDocument(old) }
                ask("What is the total premium?")
                val oldJob = withTimeout(TIMEOUT_MILLIS) { entered.await() }
                onMain { model.lock() }
                assertCleared(model.state.value)
                assertTrue(model.state.value.documents.isEmpty())
                assertEquals(null, model.state.value.selectedDocument)
                unlock(fresh)
                onMain { model.selectDocument(fresh) }
                ask("What is the total premium?")
                assertTrue(response().text.contains("INR 666.66"))
                release.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { oldJob.join() }
                val current = model.state.value
                assertEquals(2, current.messages.size)
                assertFalse(current.messages.any { it.text.contains("555.55") })
                assertEquals(fresh.id, current.messages.last().citations.single().documentId)
                assertFalse(current.busy)
            }
        } finally { release.complete(Unit) }
    }

    @Test
    fun unselectedPrivateQuestionsAndActionRequestsUseNoticesWithoutNativeModel() = runBlocking {
        val sourceCalls = AtomicInteger()
        withModel({ selected -> sourceCalls.incrementAndGet(); pages(selected, "Total premium: INR 777.77") }) {
            unlock(document("not-selected"))
            withTimeout(TIMEOUT_MILLIS) { model.state.first { it.modelReady == false } }
            val requests = listOf(
                "What is my total premium?" to R.string.chat_choose_document_notice,
                "Delete this policy" to R.string.chat_use_controls_notice,
                "Email my invoice to someone" to R.string.chat_use_controls_notice,
            )
            for ((question, notice) in requests) {
                onMain { model.clearConversation() }
                ask(question)
                val answer = response()
                assertEquals(AssistantMessageKind.NOTICE, answer.kind)
                assertEquals(context.getString(notice), answer.text)
                assertTrue(answer.citations.isEmpty())
                assertFalse(model.state.value.messages.any { it.kind == AssistantMessageKind.MODEL })
            }
            assertEquals(0, sourceCalls.get())
            assertEquals(null, model.state.value.selectedDocument)
        }
    }

    @Test
    fun reindexInvalidationClearsCitationsAndRevokesSourceRead() = runBlocking {
        val document = document("reindex")
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        try {
            withModel({ selected ->
                if (calls.incrementAndGet() == 2) {
                    entered.complete(requireNotNull(currentCoroutineContext()[Job]))
                    withContext(NonCancellable) { release.await() }
                }
                pages(selected, "Total premium: INR 888.88")
            }) {
                releaseOnClose(release)
                unlock(document)
                onMain { model.selectDocument(document) }
                ask("What is the total premium?")
                assertEquals(1, response().citations.size)
                ask("What is the total premium?")
                val oldJob = withTimeout(TIMEOUT_MILLIS) { entered.await() }
                onMain { model.invalidateDocument(document.id) }
                assertCleared(model.state.value)
                assertEquals(null, model.state.value.selectedDocument)
                assertEquals(R.string.chat_source_changed, model.state.value.message)
                release.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { oldJob.join() }
                assertCleared(model.state.value)
                assertEquals(null, model.state.value.selectedDocument)
            }
        } finally { release.complete(Unit) }
    }

    @Test
    fun mismatchedSourceRevisionCannotPublishCitations() = runBlocking {
        val document = document("mismatch")
        withModel({ pages(it, "Total premium: INR 999.99").map { page -> page.copy(revisionId = "other-revision") } }) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("What is the total premium?")
            val answer = response()
            assertEquals(AssistantMessageKind.NOTICE, answer.kind)
            assertEquals(context.getString(R.string.chat_request_failed), answer.text)
            assertTrue(answer.citations.isEmpty())
            assertFalse(answer.text.contains("999.99"))
        }
    }

    @Test
    fun removedSourceClearsChatAndSelection() = runBlocking {
        val document = document("removed")
        withModel({ pages(it, "Total premium: INR 123.45") }) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("What is the total premium?")
            assertEquals(1, response().citations.size)
            onMain {
                model.setDraft("SYNTHETIC-PRIVATE-DRAFT-REMOVED")
                model.setDocuments(listOf(document.copy(revisionId = "replacement-revision")))
            }
            assertCleared(model.state.value)
            assertEquals(null, model.state.value.selectedDocument)
            assertEquals(R.string.chat_source_unavailable, model.state.value.message)
            assertEquals("replacement-revision", model.state.value.documents.single().revisionId)
        }
    }

    @Test
    fun actualResourcePresetsReturnSourceAnswersInsteadOfUnsupportedNotice() = runBlocking {
        val document = document("resource-presets")
        val source = "Policy number: SYNTHETIC-000123\nTotal premium: INR 456.70\nCancellation requires written notice."
        val backend = RecordingBackend { "The document describes a policy and cancellation by written notice. [1]" }
        withModel({ pages(it, source) }, backend = backend) {
            unlock(document)
            onMain { model.selectDocument(document) }
            for (resource in listOf(R.string.chat_example_premium, R.string.chat_example_policy_number,
                    R.string.chat_example_summary)) {
                onMain { model.clearConversation() }
                ask(context.getString(resource))
                val answer = response()
                assertFalse(answer.kind == AssistantMessageKind.NOTICE)
                assertTrue(answer.citations.isNotEmpty())
                assertTrue(answer.citations.all { source.contains(it.quote) })
                assertFalse(answer.text == context.getString(R.string.chat_document_unsupported))
            }
            assertEquals(1, backend.requests.size)
            assertEquals(LocalChatProfile.DOCUMENT, backend.requests.single().profile)
        }
    }

    @Test
    fun typedGeneralQuestionsKeepCurrentInputAndStructuredHistory() = runBlocking {
        val backend = RecordingBackend { request ->
            if (request.question == "Which animal says meow?") "A cat says meow." else "Blue is a primary colour."
        }
        withModel({ pages(it, "Unused synthetic source.") }, backend = backend) {
            unlock(document("general-input"))
            ask("Which animal says meow?")
            assertEquals("A cat says meow.", response().text)
            ask("Name a primary colour.")
            assertEquals("Blue is a primary colour.", response().text)
            assertEquals(listOf("Which animal says meow?", "Name a primary colour."), backend.requests.map { it.question })
            val second = backend.requests.last()
            assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), second.history.map { it.role })
            assertEquals(listOf("Which animal says meow?", "A cat says meow."), second.history.map { it.text })
            assertTrue(second.systemInstruction.isNotBlank())
            assertTrue(second.profile == LocalChatProfile.GENERAL)
        }
    }

    @Test
    fun broaderDocumentQuestionUsesRetrievedTextAndCheckedSource() = runBlocking {
        val document = document("narrative")
        val source = "To cancel a booking, send written notice to the booking office. A refund is issued after review."
        val backend = RecordingBackend { "Send written notice to the booking office to cancel. [1]" }
        withModel({ pages(it, source) }, backend = backend) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("How can I cancel my booking?")
            val answer = response()
            assertEquals(AssistantMessageKind.DOCUMENT_MODEL, answer.kind)
            assertTrue(answer.text.contains("written notice"))
            assertEquals(source, answer.citations.single().quote)
            val request = backend.requests.single()
            assertEquals(LocalChatProfile.DOCUMENT, request.profile)
            assertTrue(request.question.contains(source))
            assertTrue(request.question.endsWith("How can I cancel my booking?"))
            assertTrue(request.history.isEmpty())
        }
    }

    @Test
    fun invalidGeneratedCitationsAndNumbersFallBackToActualExcerpts() = runBlocking {
        val document = document("invalid-generation")
        val source = "Cancellation requires written notice. The refund is INR 100.00."
        var generated = "The refund is INR 999.99. [1]"
        val backend = RecordingBackend { generated }
        withModel({ pages(it, source) }, backend = backend) {
            unlock(document)
            onMain { model.selectDocument(document) }
            for (invalid in listOf("The refund is INR 999.99. [1]", "Send written notice. [9]",
                    "Hello! I am your assistant and how can I help you?")) {
                generated = invalid
                onMain { model.clearConversation() }
                ask("How does cancellation work?")
                val answer = response()
                assertEquals(AssistantMessageKind.DOCUMENT, answer.kind)
                assertTrue(answer.text.startsWith(context.getString(R.string.chat_answer_unverified)))
                assertEquals(source, answer.citations.single().quote)
                assertFalse(answer.text.contains(invalid))
            }
        }
    }

    @Test
    fun allDocumentsSearchFindsMatchingSourceBeyondInitialReadLimit() = runBlocking {
        val documents = (0 until 20).map { document("all-documents-$it") }
        val target = documents.last()
        val reads = ArrayList<String>()
        val searches = ArrayList<String>()
        val source = "Cancellation requires written notice to the booking office."
        val backend = RecordingBackend { "Send written notice to the booking office. [1]" }
        withModel({ selected ->
            reads += selected.id
            pages(selected, if (selected.id == target.id) source else "Gardening notes about flowers and sunny weather.")
        }, backend = backend, searchSources = { query ->
            searches += query
            if (query == "cancellation") listOf(target) else emptyList()
        }) {
            unlock(*documents.toTypedArray())
            onMain { model.selectAllDocuments() }
            ask("What are the cancellation requirements?")
            val answer = response()
            assertTrue(model.state.value.allDocuments)
            assertEquals(AssistantMessageKind.DOCUMENT_MODEL, answer.kind)
            assertEquals(target.id, answer.citations.single().documentId)
            assertEquals(target.id, reads.first())
            assertTrue(reads.size <= 16)
            assertTrue(searches.contains("cancellation"))
            assertTrue(answer.text.contains(context.getString(R.string.chat_ai_passages_partial)))
        }
    }

    @Test
    fun allDocumentsInvalidationRevokesPendingGeneratedReply() = runBlocking {
        val document = document("all-documents-revoke")
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val backend = RecordingBackend {
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            withContext(NonCancellable) { release.await() }
            "Cancellation requires written notice. [1]"
        }
        try {
            withModel({ pages(it, "Cancellation requires written notice.") }, backend = backend) {
                releaseOnClose(release)
                unlock(document)
                onMain { model.selectAllDocuments() }
                ask("How does cancellation work?")
                val oldJob = withTimeout(TIMEOUT_MILLIS) { entered.await() }
                onMain { model.invalidateDocument(document.id) }
                assertCleared(model.state.value)
                release.complete(Unit)
                withTimeout(TIMEOUT_MILLIS) { oldJob.join() }
                assertCleared(model.state.value)
                assertTrue(model.state.value.allDocuments)
            }
        } finally { release.complete(Unit) }
    }

    @Test
    fun unavailableModelStillShowsRelevantDocumentExcerpts() = runBlocking {
        val document = document("no-model-excerpts")
        val source = "Cancellation requires written notice to the booking office."
        val backend = RecordingBackend { throw LocalChatException(LocalChatFailure.MODEL_REQUIRED) }
        withModel({ pages(it, source) }, backend = backend) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("How does cancellation work?")
            val answer = response()
            assertEquals(AssistantMessageKind.DOCUMENT, answer.kind)
            assertTrue(answer.text.startsWith(context.getString(R.string.chat_passages_model_needed)))
            assertEquals(source, answer.citations.single().quote)
            assertFalse(model.state.value.modelReady == true)
        }
    }

    @Test
    fun generatedSubsetKeepsOriginalSourceReferenceNumber() = runBlocking {
        val document = document("citation-subset").copy(pageCount = 2)
        val backend = RecordingBackend { "Cancellation requires written notice. [2]" }
        withModel({ selected ->
            pages(selected, "Cancellation is covered by the booking terms.") +
                pages(selected, "Cancellation requires written notice.").map { it.copy(pageNumber = 2) }
        }, backend = backend) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("How does cancellation work?")
            val answer = response()
            assertEquals(AssistantMessageKind.DOCUMENT_MODEL, answer.kind)
            assertEquals(2, answer.citations.single().pageNumber)
            assertTrue(answer.citations.single().label.startsWith("[2]"))
        }
    }

    @Test
    fun uncitedExplanationShowsAllSuppliedContextWithoutClaimingVerifiedMeaning() = runBlocking {
        val document = document("uncited-context")
        val source = "Cancellation requires written notice to the booking office."
        val backend = RecordingBackend { "Send written notice to the booking office to cancel." }
        withModel({ pages(it, source) }, backend = backend) {
            unlock(document)
            onMain { model.selectDocument(document) }
            ask("How does cancellation work?")
            val answer = response()
            assertEquals(AssistantMessageKind.DOCUMENT_MODEL, answer.kind)
            assertTrue(answer.text.contains(context.getString(R.string.chat_ai_uncited_context_note)))
            assertEquals(source, answer.citations.single().quote)
            assertFalse(answer.text.contains(context.getString(R.string.chat_ai_passages_note)))
        }
    }

    @Test
    fun personalIdentifierLookupIsCitedAndNeedsNoModel() = runBlocking {
        val card = document("pan-card")
        val other = document("notes")
        val text = "INCOME TAX DEPARTMENT\nPermanent Account Number\nABCPE1234F"
        val tools = RecordingTools()
        withModel({ if (it.id == card.id) pages(it, text) else pages(it, "Shopping list") }, tools = tools) {
            unlock(card, other)
            ask("What is my PAN number?")
            val answer = response()
            assertEquals(AssistantMessageKind.DOCUMENT, answer.kind)
            val value = answer.identifiers.single()
            assertEquals("ABCPE1234F", value.value)
            assertEquals(card.id, value.document.id)
            assertEquals("ABCPE1234F", answer.citations.single().quote)
            assertTrue(tools.requests.isEmpty())
        }
    }

    @Test
    fun toolRequestsRunOnlyAfterExplicitConfirmation() = runBlocking {
        val resume = document("resume").copy(displayName = "Resume.pdf", mimeType = "application/pdf", sizeBytes = 900_000)
        val photo = document("photo").copy(displayName = "Holiday.jpg", mimeType = "image/jpeg", sizeBytes = 50_000)
        val tools = RecordingTools()
        withModel({ pages(it, "Synthetic text") }, tools = tools) {
            unlock(resume, photo)
            ask("compress my resume to under 200 kb")
            val proposal = response()
            val action = requireNotNull(proposal.action)
            assertEquals(ToolKind.COMPRESS, action.tool)
            assertEquals(ActionStatus.PROPOSED, action.status)
            assertEquals(resume.id, action.document?.id)
            assertEquals(200_000L, action.targetBytes)
            assertTrue(tools.requests.isEmpty())
            onMain { model.confirmAction(proposal.id) }
            val result = response()
            assertEquals(1, tools.requests.size)
            assertEquals(resume.id, tools.requests.single().document.id)
            assertEquals("Resume - compressed.pdf", result.documents.single().displayName)
            assertEquals(ActionStatus.DONE, model.state.value.messages.first { it.id == proposal.id }.action?.status)
            // A finished card cannot be confirmed again.
            onMain { model.confirmAction(proposal.id) }
            assertEquals(1, tools.requests.size)
        }
    }

    @Test
    fun clearingOrLockingDropsUnconfirmedToolProposals() = runBlocking {
        val photo = document("selfie").copy(displayName = "my photo.jpg", mimeType = "image/jpeg")
        val tools = RecordingTools()
        withModel({ pages(it, "") }, tools = tools) {
            unlock(photo)
            ask("make my photo passport size")
            val proposal = response()
            assertEquals(ToolKind.PASSPORT_PHOTO, proposal.action?.tool)
            onMain { model.clearConversation() }
            onMain { model.confirmAction(proposal.id) }
            assertTrue(tools.requests.isEmpty())
            ask("make my photo passport size")
            val second = response()
            onMain { model.lock() }
            onMain { model.confirmAction(second.id) }
            assertTrue(tools.requests.isEmpty())
        }
    }

    private class RecordingTools : AssistantToolHost {
        val requests = ArrayList<AssistantToolRequest>()
        override suspend fun run(request: AssistantToolRequest, cancelled: () -> Boolean): AssistantToolOutcome {
            requests += request
            val name = request.document.displayName.substringBeforeLast('.') + " - compressed.pdf"
            return AssistantToolOutcome.Created(listOf(request.document.copy(id = "created-${requests.size}",
                revisionId = "created-revision-${requests.size}", displayName = name, sizeBytes = 150_000,
                parentDocumentId = request.document.id)))
        }
        override suspend fun documentsCreated() = Unit
        override fun folderName(folderId: String?): String? = null
    }

    private class RecordingBackend(private val respond: suspend (LocalChatRequest) -> String) : AssistantChatBackend {
        val requests = ArrayList<LocalChatRequest>()
        override suspend fun isReady() = true
        override suspend fun generate(request: LocalChatRequest, cancellationRequested: () -> Boolean): LocalInferenceResult {
            requests += request
            return LocalInferenceResult(respond(request), "synthetic-controller-only", "TEST")
        }
    }

    private suspend fun withModel(loader: suspend (VaultDocument) -> List<VaultPageEvidence>,
        backend: AssistantChatBackend? = null,
        searchSources: suspend (String) -> List<VaultDocument> = { emptyList() },
        tools: AssistantToolHost? = null,
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(loader, backend, searchSources, tools)
        try { fixture.block() } finally { withContext(NonCancellable) { fixture.close() } }
    }

    private class Fixture(loader: suspend (VaultDocument) -> List<VaultPageEvidence>, backend: AssistantChatBackend?,
        searchSources: suspend (String) -> List<VaultDocument>, tools: AssistantToolHost?,
    ) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val root = File(instrumentation.targetContext.cacheDir,
            "synthetic-chat-lifecycle-${UUID.randomUUID()}").apply { check(mkdir()) }
        // App resources supply the real controller strings, while any readiness
        // probe uses only test assets and this new synthetic public-model root.
        // The application's real model store, vault and Keystore are untouched.
        val context: Context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getAssets(): AssetManager = instrumentation.context.assets
            override fun getNoBackupFilesDir(): File = root
        }
        private val lifetime = SupervisorJob()
        private val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
        private val releases = ArrayList<CompletableDeferred<Unit>>()
        val model = if (backend == null) AssistantChatUiModel(context, scope, loader, searchSources, tools)
            else AssistantChatUiModel(context, scope, loader, backend, searchSources, tools)

        fun releaseOnClose(release: CompletableDeferred<Unit>) { releases.add(release) }
        suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
        suspend fun unlock(vararg documents: VaultDocument) = onMain { model.unlock(documents.toList()) }
        suspend fun ask(question: String) = onMain { model.setDraft(question); model.send() }
        suspend fun response(): AssistantMessageUi = withTimeout(TIMEOUT_MILLIS) {
            model.state.first { !it.busy && it.messages.lastOrNull()?.user == false }.messages.last()
        }
        suspend fun close() {
            releases.forEach { it.complete(Unit) }
            onMain { model.lock(); scope.cancel() }
            withTimeout(TIMEOUT_MILLIS) { lifetime.join() }
            check(root.deleteRecursively())
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000L

        private fun document(name: String) = VaultDocument(
            id = "synthetic-$name", displayName = "Synthetic policy $name", mimeType = "text/plain",
            sizeBytes = 64, importedAtEpochMillis = 0, sha256 = "0".repeat(64),
            revisionId = "revision-$name", pageCount = 1, indexState = VaultIndexState.INDEXED,
        )

        private fun pages(document: VaultDocument, text: String) = listOf(VaultPageEvidence(
            document.id, document.revisionId, 1, text, null, ExtractionMethod.UTF8_TEXT,
            "synthetic-chat-lifecycle-v1", VaultPageState.READY,
            listOf(SourceTextSpan(0, text.length, null, SourceSpanKind.BLOCK)),
        ))

        private fun assertCleared(state: AssistantChatUiState) {
            assertEquals("", state.draft)
            assertTrue(state.messages.isEmpty())
            assertFalse(state.busy)
        }
    }
}
