package com.privateassistant.core.search

import com.privateassistant.core.EvidenceSpan
import com.privateassistant.core.RevisionRef
import com.privateassistant.core.RevisionText
import com.privateassistant.core.frozenList

/** A source quote, never generated text. Matching a page does not establish field meaning. */
class GroundedSearchHit internal constructor(
    val evidence: EvidenceSpan,
    matchedTerms: List<String>,
    /** Bounded literal frequency only; it does not select a current policy or resolve identity. */
    val score: Int,
) {
    val matchedTerms: List<String> = frozenList(matchedTerms)
    val ref: RevisionRef get() = evidence.ref
    val pageNumber: Int get() = evidence.pageNumber
    val startOffset: Int get() = evidence.startOffset
    val endOffset: Int get() = evidence.endOffset
    val quote: String get() = evidence.quote
}

object GroundedLexicalSearch {
    const val MAX_SNIPPET_UTF16 = 240
    private const val MAX_SCORED_OCCURRENCES_PER_TERM = 32

    /**
     * Verifies every literal against this exact revision/page, independently of FTS.
     * A bounded quote surrounds the shortest window covering the query terms. If
     * that window exceeds the snippet budget, the quote shows its beginning;
     * all terms still matched the page, but may not all appear in the quote.
     */
    fun findPage(
        query: LiteralSearchQuery,
        source: RevisionText,
        pageNumber: Int,
        maxSnippetUtf16: Int = MAX_SNIPPET_UTF16,
    ): GroundedSearchHit? {
        require(maxSnippetUtf16 in 32..MAX_SNIPPET_UTF16) { "Invalid snippet bound" }
        if (query.canonicalTerms.isEmpty()) return null
        val page = source.page(pageNumber) ?: return null
        require(page.text.length <= LiteralSearchQuery.MAX_PAGE_UTF16) { "Search page is too long" }
        val termIndexes = query.canonicalTerms.withIndex().associate { it.value to it.index }
        val latest = arrayOfNulls<LexicalToken>(query.canonicalTerms.size)
        val counts = IntArray(query.canonicalTerms.size)
        var found = 0
        var bestStart = -1
        var bestEnd = -1
        for (token in lexicalTokens(page.text)) {
            val index = termIndexes[token.canonical] ?: continue
            if (latest[index] == null) found++
            latest[index] = token
            counts[index] = (counts[index] + 1).coerceAtMost(MAX_SCORED_OCCURRENCES_PER_TERM)
            if (found == latest.size) {
                val start = latest.minOf { it!!.start }
                if (bestStart < 0 || token.end - start < bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = token.end
                }
            }
        }
        if (found != latest.size) return null
        val context = ((maxSnippetUtf16 - (bestEnd - bestStart)).coerceAtLeast(0)) / 2
        var start = (bestStart - context).coerceAtLeast(0)
        var end = (start + maxSnippetUtf16).coerceAtMost(page.text.length)
        if (end == page.text.length) start = (end - maxSnippetUtf16).coerceAtLeast(0)
        // Trim inward: quotes never split UTF-16 surrogate pairs or leave an
        // attached combining mark/joiner at a boundary. No source text is rewritten.
        if (start > 0 && Character.isLowSurrogate(page.text[start])) start++
        while (start < end && unsafeBoundary(page.text, start)) {
            start += Character.charCount(page.text.codePointAt(start))
        }
        if (end < page.text.length && Character.isLowSurrogate(page.text[end])) end--
        while (end > start && unsafeBoundary(page.text, end)) {
            end -= Character.charCount(page.text.codePointBefore(end))
        }
        if (end <= start) return null
        val evidence = EvidenceSpan(source.ref, pageNumber, start, end, page.text.substring(start, end))
        return GroundedSearchHit(evidence, query.literals, counts.sum())
    }

    private fun unsafeBoundary(text: String, boundary: Int): Boolean =
        (boundary < text.length && (isMark(text.codePointAt(boundary)) || isJoiner(text.codePointAt(boundary)))) ||
            (boundary > 0 && isJoiner(text.codePointBefore(boundary)))
}
