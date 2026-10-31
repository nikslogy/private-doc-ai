package com.privateassistant.core.identity

import com.privateassistant.core.EvidenceSpan
import com.privateassistant.core.RevisionText

/**
 * Common Indian identity/finance identifiers printed in a user's own documents.
 * Patterns are deterministic and conservative; a match proves only that the exact text
 * is present on the cited page, not that it belongs to the user or is currently valid.
 */
enum class IdentifierKind {
    PAN, AADHAAR, PASSPORT, VOTER_ID, DRIVING_LICENCE, GSTIN, IFSC, VEHICLE_REGISTRATION,
    BANK_ACCOUNT, UAN, EMAIL, MOBILE, DATE_OF_BIRTH,
}

/** The span covers exactly [rawValue]; [contextLine] is the surrounding printed line for display. */
data class IdentifierCandidate(
    val kind: IdentifierKind,
    val rawValue: String,
    val normalized: String,
    val evidence: EvidenceSpan,
    val contextLine: String,
    val masked: Boolean = false,
)

data class IdentifierExtraction(val candidates: List<IdentifierCandidate>, val truncated: Boolean)

class PersonalIdentifierExtractor {
    fun extract(source: RevisionText, kinds: Set<IdentifierKind> = IdentifierKind.entries.toSet()): IdentifierExtraction {
        val result = ArrayList<IdentifierCandidate>()
        var truncated = false
        var scanned = 0
        pages@ for (page in source.pages.sortedBy { it.pageNumber }) {
            if (scanned + page.text.length > MAX_TEXT_UTF16) { truncated = true; break }
            scanned += page.text.length
            val text = page.text
            val lines = lines(text)
            for (rule in RULES) {
                if (rule.kind !in kinds) continue
                if (rule.pageLabel != null && !rule.pageLabel.containsMatchIn(text)) continue
                for ((index, line) in lines.withIndex()) {
                    if (rule.lineLabel != null) {
                        val previous = (index - 1 downTo 0).firstOrNull { lines[it].text.isNotBlank() }?.let { lines[it].text }
                        val labelled = rule.lineLabel.containsMatchIn(line.text) ||
                            (previous != null && rule.lineLabel.containsMatchIn(previous) && !rule.pattern.containsMatchIn(previous))
                        if (!labelled) continue
                    }
                    for (match in rule.pattern.findAll(line.text)) {
                        val raw = match.value
                        if (!rule.accept(raw)) continue
                        val start = line.start + match.range.first
                        val end = line.start + match.range.last + 1
                        result += IdentifierCandidate(
                            kind = rule.kind,
                            rawValue = raw,
                            normalized = rule.normalize(raw),
                            evidence = EvidenceSpan(source.ref, page.pageNumber, start, end, text.substring(start, end)),
                            contextLine = line.text.trim().take(MAX_CONTEXT_UTF16),
                            masked = rule.masked,
                        )
                        if (result.size >= MAX_CANDIDATES) { truncated = true; break@pages }
                    }
                }
            }
        }
        // A masked Aadhaar is reported only when no full number with the same visible suffix exists.
        val full = result.filter { it.kind == IdentifierKind.AADHAAR && !it.masked }.map { it.normalized.takeLast(4) }.toSet()
        val filtered = result.filterNot { it.masked && it.normalized.takeLast(4) in full }
        return IdentifierExtraction(filtered.distinctBy { Triple(it.kind, it.evidence.pageNumber, it.evidence.startOffset) }, truncated)
    }

    private data class Line(val start: Int, val text: String)

    private fun lines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            lines += Line(start, text.substring(start, newline).take(MAX_LINE_UTF16))
            start = newline + 1
        }
        return lines
    }

    private class Rule(
        val kind: IdentifierKind,
        val pattern: Regex,
        val pageLabel: Regex? = null,
        val lineLabel: Regex? = null,
        val masked: Boolean = false,
        val accept: (String) -> Boolean = { true },
        val normalize: (String) -> String = { it.filterNot(Char::isWhitespace).filterNot { c -> c == '-' }.uppercase() },
    )

    companion object {
        const val MAX_CANDIDATES = 200
        private const val MAX_TEXT_UTF16 = 1_000_000
        private const val MAX_LINE_UTF16 = 2_000
        private const val MAX_CONTEXT_UTF16 = 160
        private const val B = "(?<![A-Za-z0-9])"
        private const val E = "(?![A-Za-z0-9])"
        private val I = setOf(RegexOption.IGNORE_CASE)

        private val RULES = listOf(
            Rule(IdentifierKind.PAN, Regex("$B[A-Z]{3}[ABCFGHLJPTK][A-Z][0-9]{4}[A-Z]$E")),
            Rule(IdentifierKind.AADHAAR, Regex("(?<![0-9])[2-9][0-9]{3}([ -]?)[0-9]{4}\\1[0-9]{4}(?![0-9])"),
                accept = { Verhoeff.isValid(it.filter(Char::isDigit)) }),
            Rule(IdentifierKind.AADHAAR, Regex("(?<![A-Za-z0-9*])(?:[Xx]{4}|\\*{4})([ -]?)(?:[Xx]{4}|\\*{4})\\1[0-9]{4}(?![0-9])"),
                masked = true, normalize = { "XXXXXXXX" + it.takeLast(4) }),
            Rule(IdentifierKind.GSTIN, Regex("$B[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$E")),
            Rule(IdentifierKind.PASSPORT, Regex("$B[A-Z][0-9]{7}$E"), pageLabel = Regex("passport|पासपोर्ट", I)),
            Rule(IdentifierKind.VOTER_ID, Regex("$B[A-Z]{3}[0-9]{7}$E"),
                pageLabel = Regex("\\bepic\\b|voter|elector|निर्वाचक|मतदाता", I)),
            Rule(IdentifierKind.DRIVING_LICENCE,
                Regex("$B[A-Z]{2}[0-9]{2}[ -]?(?:19|20)[0-9]{2}[ -]?[0-9]{7}$E"),
                pageLabel = Regex("driving|licen[cs]e|\\bD\\.?L\\.?\\b", I)),
            Rule(IdentifierKind.IFSC, Regex("$B[A-Z]{4}0[A-Z0-9]{6}$E"), pageLabel = Regex("\\bIFS(?:C| code)", I)),
            Rule(IdentifierKind.VEHICLE_REGISTRATION,
                Regex("$B[A-Z]{2}[ -]?[0-9]{1,2}[ -]?[A-Z]{1,3}[ -]?[0-9]{4}$E"),
                pageLabel = Regex("registration|\\bregn?\\.?\\s*no|vehicle|\\bR\\.?C\\.?\\b", I)),
            Rule(IdentifierKind.BANK_ACCOUNT, Regex("(?<![0-9])[0-9]{9,18}(?![0-9])"),
                lineLabel = Regex("\\b(?:a/?c|acct?|account)\\.?\\s*(?:no|num|number|#)\\b", I)),
            Rule(IdentifierKind.UAN, Regex("(?<![0-9])[0-9]{12}(?![0-9])"), lineLabel = Regex("\\bUAN\\b", I)),
            Rule(IdentifierKind.EMAIL, Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,24}(?![A-Za-z0-9])"),
                normalize = { it.lowercase() }),
            Rule(IdentifierKind.MOBILE, Regex("(?<![0-9+])(?:\\+91[ -]?|0)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![0-9])"),
                lineLabel = Regex("mobile|phone|\\bmob\\b|contact|\\btel\\b|मोबाइल", I),
                normalize = { it.filter(Char::isDigit).takeLast(10) }),
            Rule(IdentifierKind.DATE_OF_BIRTH,
                Regex("(?<![0-9])(?:[0-9]{1,2}[/.-][0-9]{1,2}[/.-](?:19|20)[0-9]{2}|[0-9]{1,2} [A-Za-z]{3,9},? (?:19|20)[0-9]{2})(?![0-9])"),
                lineLabel = Regex("\\bd\\.?\\s?o\\.?\\s?b\\b|date of birth|birth ?date|\\bborn\\b|जन्म", I),
                normalize = { it }),
        )
    }
}

/** Verhoeff check digit validation used by Aadhaar numbers; rejects arbitrary 12-digit numbers. */
object Verhoeff {
    private val d = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val p = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    fun isValid(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return false
        var c = 0
        digits.reversed().forEachIndexed { index, ch -> c = d[c][p[index % 8][ch - '0']] }
        return c == 0
    }

    /** Test/fixture helper: the digit that makes [digits] + result valid. */
    fun checkDigit(digits: String): Int = (0..9).first { isValid(digits + it) }
}
