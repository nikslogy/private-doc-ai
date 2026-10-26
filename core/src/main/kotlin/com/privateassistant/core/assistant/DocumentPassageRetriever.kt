package com.privateassistant.core.assistant

import com.privateassistant.core.EvidenceSpan
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.RevisionText
import com.privateassistant.core.frozenList
import com.privateassistant.core.search.lexicalTokens
import java.text.Normalizer
import java.util.Locale

/** An authorized, transient snapshot. No file names, paths, tools or repository access. */
class DocumentRetrievalSource(val source: RevisionText, val complete: Boolean) {
    override fun toString(): String = "DocumentRetrievalSource"
}

class RetrievedDocumentContext(passages: List<EvidenceSpan>, val summary: Boolean, val partial: Boolean) {
    val passages: List<EvidenceSpan> = frozenList(passages)
    override fun toString(): String = "RetrievedDocumentContext(count=${passages.size},summary=$summary,partial=$partial)"
}

/**
 * Bounded lexical retrieval over any readable document, independent of printed field labels.
 * Ranking is a relevance heuristic; exact span verification is not a proof of relevance or entailment.
 * All offsets refer to the original page text, never the normalized matching representation.
 */
object DocumentPassageRetriever {
    const val MAX_SOURCES = 16
    const val MAX_PAGES_PER_SOURCE = 50
    const val MAX_TOTAL_UTF16 = 200_000
    const val MAX_PASSAGES = 4
    const val MAX_PASSAGE_UTF16 = 480
    private const val MAX_QUERY_TERMS = 48
    private val summaryTarget = "(?:it|this|that|everything|these|all|document facts|(?:(?:the|my|this|selected) )?(?:document|policy|invoice)|(?:(?:my|all|these|the) )?documents)"
    private val summary = Regex("(?:(?:can|could|would) you )?(?:please )?(?:summari[sz]e(?: $summaryTarget)?|summary(?: of $summaryTarget)?|(?:give|show)(?: me)? (?:a |an |the )?(?:summary|overview|key points|key facts)(?: of $summaryTarget)?|(?:what is|what's) $summaryTarget about|(?:explain|describe) $summaryTarget(?: briefly)?)(?: please)?")
    private val stopWords = setOf("a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "do", "does", "did",
        "i", "me", "my", "you", "your", "it", "its", "this", "that", "these", "those", "can", "could", "would", "should",
        "what", "which", "who", "whom", "when", "where", "why", "how", "of", "in", "on", "at", "to", "for", "from", "and", "or",
        "with", "about", "please", "tell", "show", "give", "find", "read", "say", "says", "said", "document", "documents", "selected")

    fun isSummaryQuestion(question: String): Boolean = summary.matches(normalizeQuestion(question))

    /** Literal terms for the existing encrypted lexical index; stemming is only for ranking. */
    fun queryTerms(question: String): List<String> {
        require(question.isNotBlank() && question.length <= LocalChatPrompt.MAX_QUESTION_UTF16)
        return lexicalTokens(question).map { it.canonical }
            .filterNot { stem(it) in stopWords }.distinct().take(MAX_QUERY_TERMS).toList()
    }

    fun retrieve(question: String, sources: List<DocumentRetrievalSource>): RetrievedDocumentContext {
        require(question.isNotBlank() && question.length <= LocalChatPrompt.MAX_QUESTION_UTF16)
        require(sources.map { it.source.ref }.distinct().size == sources.size) { "Duplicate source revision" }
        val isSummary = isSummaryQuestion(question)
        val query = tokens(question).filterNot { it in stopWords }.distinct().take(MAX_QUERY_TERMS)
        var consumed = 0
        var truncated = sources.size > MAX_SOURCES || sources.any { !it.complete }
        val candidates = ArrayList<Candidate>()
        sourceLoop@ for ((sourceIndex, selected) in sources.take(MAX_SOURCES).withIndex()) {
            val pages = selected.source.pages.sortedBy { it.pageNumber }
            if (pages.size > MAX_PAGES_PER_SOURCE) truncated = true
            for (page in pages.take(MAX_PAGES_PER_SOURCE)) {
                if (consumed >= MAX_TOTAL_UTF16) { truncated = true; break@sourceLoop }
                val limit = minOf(page.text.length, MAX_TOTAL_UTF16 - consumed).safeEnd(page.text)
                if (limit < page.text.length) truncated = true
                consumed += limit
                var start = 0
                while (start < limit) {
                    while (start < limit && page.text[start].isWhitespace()) start++
                    if (start == limit) break
                    var end = minOf(limit, start + MAX_PASSAGE_UTF16).safeEnd(page.text)
                    if (end < limit) {
                        val lower = start + MAX_PASSAGE_UTF16 / 2
                        val sentence = (end - 1 downTo lower).firstOrNull { index ->
                            page.text[index] == '\n' || (page.text[index] in ".!?" &&
                                index + 1 < page.text.length && page.text[index + 1].isWhitespace())
                        }
                        val space = if (sentence == null) (end - 1 downTo lower).firstOrNull { page.text[it].isWhitespace() } else null
                        end = sentence?.plus(1) ?: space ?: end
                    }
                    var quoteEnd = end
                    while (quoteEnd > start && page.text[quoteEnd - 1].isWhitespace()) quoteEnd--
                    if (quoteEnd > start) {
                        val span = EvidenceSpan(selected.source.ref, page.pageNumber, start, quoteEnd, page.text.substring(start, quoteEnd))
                        check(EvidenceValidator().validate(span, selected.source).isValid)
                        val terms = tokens(span.quote).toSet()
                        val matched = query.count { it in terms }
                        val score = query.sumOf { term -> if (term in terms) { if (term.any(Char::isDigit)) 6 else 3 } else 0 } +
                            if (query.size > 1 && matched == query.size) 4 else 0
                        candidates += Candidate(span, sourceIndex, score)
                    }
                    start = end
                }
            }
        }
        val selected = when {
            isSummary -> diverseSummary(candidates)
            candidates.any { it.score > 0 } -> candidates.filter { it.score > 0 }
                .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.sourceIndex }
                    .thenBy { it.span.pageNumber }.thenBy { it.span.startOffset }).take(MAX_PASSAGES)
            // A small explicitly selected source can be read in full even when paraphrased query
            // terms do not overlap. Across sources, do not guess which document the user meant.
            sources.size == 1 && candidates.size <= MAX_PASSAGES -> candidates
            else -> emptyList()
        }
        return RetrievedDocumentContext(selected.map { it.span }, isSummary,
            truncated || (isSummary && selected.size < candidates.size))
    }

    private fun diverseSummary(candidates: List<Candidate>): List<Candidate> {
        if (candidates.size <= MAX_PASSAGES) return candidates
        val pages = candidates.groupBy { it.sourceIndex to it.span.pageNumber }.values.toList()
        // Sample across the selected source(s), then fill with later passages. This is explicitly
        // a partial overview when all content cannot fit, never a claim of complete coverage.
        val pageIndexes = if (pages.size <= MAX_PASSAGES) pages.indices.toList() else
            (0 until MAX_PASSAGES).map { it * (pages.size - 1) / (MAX_PASSAGES - 1) }
        val result = pageIndexes.map { pages[it].first() }.toMutableList()
        var depth = 1
        while (result.size < MAX_PASSAGES) {
            var added = false
            for (index in pageIndexes) {
                pages[index].getOrNull(depth)?.let { if (result.size < MAX_PASSAGES) { result += it; added = true } }
            }
            if (!added) break
            depth++
        }
        return result
    }

    private fun tokens(text: String): List<String> = lexicalTokens(Normalizer.normalize(text, Normalizer.Form.NFKC))
        .map { stem(it.canonical) }.toList()

    private fun stem(word: String): String = when (word) {
        "cancellation", "cancellations", "cancelled", "canceled", "cancelling", "canceling" -> "cancel"
        "expiry", "expiration", "expires", "expired", "expire" -> "expire"
        "exclusions", "excluded", "exclude" -> "exclusion"
        "refunds", "refunded", "refunding" -> "refund"
        else -> if (word.length >= 5 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word
    }

    private fun normalizeQuestion(question: String): String = question.trim().lowercase(Locale.ROOT)
        .replace('\u2019', '\'').trimEnd('?', '!', '.').trim().replace(Regex("\\s+"), " ")

    private fun Int.safeEnd(text: String): Int = if (this > 0 && this < text.length && Character.isHighSurrogate(text[this - 1])) this - 1 else this
    private class Candidate(val span: EvidenceSpan, val sourceIndex: Int, val score: Int)
}
