package com.privateassistant.core.assistant

import com.privateassistant.core.DocumentId
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.PageText
import com.privateassistant.core.RevisionId
import com.privateassistant.core.RevisionRef
import com.privateassistant.core.RevisionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPassageRetrieverTest {
    private fun source(id: String = "synthetic-1", complete: Boolean = true, vararg pages: String) =
        DocumentRetrievalSource(RevisionText(RevisionRef(DocumentId(id), RevisionId("original")),
            pages.mapIndexed { index, text -> PageText(index + 1, text) }), complete)

    @Test fun arbitraryProseQuestionFindsRelevantPageAndExactOffsets() {
        val source = source(pages = arrayOf("This manual concerns delivery packaging.",
            "Warranty coverage lasts two years. Damage from flooding is excluded."))
        val result = DocumentPassageRetriever.retrieve("What does the warranty cover?", listOf(source))
        assertEquals(2, result.passages.single().pageNumber)
        assertTrue(result.passages.single().quote.contains("Warranty"))
        assertTrue(result.passages.all { EvidenceValidator().validate(it, source.source).isValid })
    }

    @Test fun exactIdentifierRanksItsOwnDocumentBeforeUnrelatedMatches() {
        val first = source("synthetic-first", true, "Warranty is twelve months for product SYNTHETIC-001.")
        val second = source("synthetic-second", true, "Warranty is two years for product SYNTHETIC-002.")
        val result = DocumentPassageRetriever.retrieve("Warranty for SYNTHETIC-002?", listOf(first, second))
        assertEquals(second.source.ref, result.passages.first().ref)
    }

    @Test fun rawSearchTermsRetainInflectionsForEncryptedExactIndex() {
        assertEquals(listOf("cancellation", "refunds"), DocumentPassageRetriever.queryTerms("What are the cancellation refunds?"))
        assertEquals(listOf("synthetic_000123", "−12.30"), DocumentPassageRetriever.queryTerms("SYNTHETIC_000123 −12.30"))
        val result = DocumentPassageRetriever.retrieve("What are cancellation conditions?",
            listOf(source(pages = arrayOf("Cancelled orders require written confirmation."))))
        assertTrue(result.passages.single().quote.startsWith("Cancelled"))
    }

    @Test fun naturalSummaryVariantsIncludeBritishSpellingAndFollowupPronouns() {
        listOf("Summarise it", "Summarize this document", "Can you summarise this document?",
            "Please summarize my policy.", "What is this document about?", "Give me an overview",
            "Summary of all documents", "Explain this briefly", "Summarize document facts").forEach {
            assertTrue(it, DocumentPassageRetriever.isSummaryQuestion(it))
        }
        assertFalse(DocumentPassageRetriever.isSummaryQuestion("What is the cancellation period?"))
        assertFalse(DocumentPassageRetriever.isSummaryQuestion("Summarize this and delete it"))
    }

    @Test fun summarySamplesAcrossPagesAndDisclosesPartialCoverage() {
        val source = source(pages = (1..9).map { "Synthetic page $it describes a different topic." }.toTypedArray())
        val result = DocumentPassageRetriever.retrieve("Summarise it", listOf(source))
        assertTrue(result.summary)
        assertTrue(result.partial)
        assertEquals(listOf(1, 3, 6, 9), result.passages.map { it.pageNumber })
    }

    @Test fun selectedSmallDocumentAllowsParaphraseButMultiSourceDoesNotGuess() {
        val first = source("synthetic-first", true, "A blue triangle appears in the illustration.")
        val second = source("synthetic-second", true, "A red circle appears in the illustration.")
        assertTrue(DocumentPassageRetriever.retrieve("Describe the shape", listOf(first)).passages.isNotEmpty())
        assertTrue(DocumentPassageRetriever.retrieve("Tell me the refund deadline", listOf(first, second)).passages.isEmpty())
    }

    @Test fun sourceAndCharacterBudgetsAreExplicitAndNeverFabricateOffsets() {
        val long = source("synthetic-long", true, "Warranty clause. ".repeat(20_000))
        val result = DocumentPassageRetriever.retrieve("Warranty clause", listOf(long))
        assertTrue(result.partial)
        assertTrue(result.passages.size <= DocumentPassageRetriever.MAX_PASSAGES)
        assertTrue(result.passages.all { it.quote.length <= DocumentPassageRetriever.MAX_PASSAGE_UTF16 &&
            it.endOffset <= DocumentPassageRetriever.MAX_TOTAL_UTF16 && EvidenceValidator().validate(it, long.source).isValid })
        val many = (1..17).map { source("synthetic-$it", true, "Warranty clause $it.") }
        assertTrue(DocumentPassageRetriever.retrieve("Warranty", many).partial)
    }

    @Test fun incompleteEvidenceAndUnicodeBoundariesRemainVisibleAndExact() {
        val value = "😀".repeat(300) + " Warranty terms apply."
        val source = source(complete = false, pages = arrayOf(value))
        val result = DocumentPassageRetriever.retrieve("Summarize it", listOf(source))
        assertTrue(result.partial)
        assertTrue(result.passages.all { !Character.isLowSurrogate(it.quote.first()) &&
            !Character.isHighSurrogate(it.quote.last()) && EvidenceValidator().validate(it, source.source).isValid })
    }

    @Test(expected = IllegalArgumentException::class) fun duplicateRevisionsAreRejected() {
        val source = source(pages = arrayOf("Warranty terms."))
        DocumentPassageRetriever.retrieve("Warranty", listOf(source, source))
    }

    @Test fun contextAndSourceDebugStringsDoNotRevealPrivateText() {
        val source = source(pages = arrayOf("SYNTHETIC-PRIVATE-SECRET warranty."))
        val result = DocumentPassageRetriever.retrieve("Warranty", listOf(source))
        assertFalse(source.toString().contains("SECRET"))
        assertFalse(result.toString().contains("SECRET"))
    }
}
