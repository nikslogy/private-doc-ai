package com.privateassistant.core.search

import com.privateassistant.core.frozenList
import java.text.Normalizer
import java.util.Locale

/**
 * A bounded, literal, all-terms lexical query. Input is never SQLite MATCH syntax.
 *
 * FTS indexes ASCII encodings from [indexTerms], not raw document text. This keeps
 * SQLite tokenizer versions from discarding Devanagari marks or punctuation in
 * identifiers. NFC/case matching does not change the source used for citations;
 * numbers, their leading zeroes, separators and signs are never canonicalized.
 */
class LiteralSearchQuery private constructor(literals: List<String>, canonicalTerms: List<String>) {
    val literals: List<String> = frozenList(literals)
    internal val canonicalTerms: List<String> = frozenList(canonicalTerms)

    /** Null means no searchable literal; callers must return no results, not all pages. */
    val ftsMatch: String? = this.canonicalTerms.takeIf { it.isNotEmpty() }
        ?.joinToString(" AND ") { "\"${encodeTerm(it)}\"" }

    companion object {
        const val MAX_INPUT_UTF16 = 512
        const val MAX_TERMS = 24
        const val MAX_PAGE_UTF16 = 1_000_000

        /** Reject bounds instead of silently dropping a part of the user's request. */
        fun parse(raw: String): LiteralSearchQuery {
            require(raw.length <= MAX_INPUT_UTF16) { "Search input is too long" }
            val terms = linkedMapOf<String, String>()
            var count = 0
            for (token in lexicalTokens(raw)) {
                count++
                require(count <= MAX_TERMS) { "Search has too many terms" }
                terms.putIfAbsent(token.canonical, token.raw)
            }
            return LiteralSearchQuery(terms.values.toList(), terms.keys.toList())
        }

        /**
         * Store this generated string in the encrypted page FTS column. Its only
         * characters are ASCII letters, hexadecimal digits and token separators.
         * Long tokens cannot fit any accepted query and are not indexed.
         */
        fun indexTerms(text: String): String {
            require(text.length <= MAX_PAGE_UTF16) { "Search page is too long" }
            val terms = linkedSetOf<String>()
            for (token in lexicalTokens(text)) {
                if (token.raw.length <= MAX_INPUT_UTF16) terms += encodeTerm(token.canonical)
            }
            return terms.joinToString(" ")
        }
    }
}

internal data class LexicalToken(val raw: String, val canonical: String, val start: Int, val end: Int)

/** Offsets always address the original Kotlin UTF-16 string, before normalization. */
internal fun lexicalTokens(text: String): Sequence<LexicalToken> = sequence {
    var offset = 0
    while (offset < text.length) {
        val first = text.codePointAt(offset)
        val firstSize = Character.charCount(first)
        val signedNumber = isNumberSign(first) && offset + firstSize < text.length &&
            isNumber(text.codePointAt(offset + firstSize))
        if (!isBase(first) && !signedNumber) {
            offset += firstSize
            continue
        }
        val start = offset
        offset += firstSize
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val size = Character.charCount(codePoint)
            if (isBase(codePoint) || isMark(codePoint)) {
                offset += size
            } else if ((isInternalSeparator(codePoint) || isJoiner(codePoint)) &&
                offset + size < text.length && isBase(text.codePointAt(offset + size))) {
                offset += size
            } else {
                break
            }
        }
        val raw = text.substring(start, offset)
        val canonical = Normalizer.normalize(
            Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT),
            Normalizer.Form.NFC,
        )
        yield(LexicalToken(raw, canonical, start, offset))
    }
}

private fun encodeTerm(term: String): String = buildString {
    append('t')
    val hex = "0123456789abcdef"
    for (byte in term.toByteArray(Charsets.UTF_8)) {
        val unsigned = byte.toInt() and 0xff
        append(hex[unsigned ushr 4])
        append(hex[unsigned and 15])
    }
}

private fun isBase(codePoint: Int): Boolean = Character.isLetter(codePoint) || isNumber(codePoint)

private fun isNumber(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
    Character.OTHER_NUMBER.toInt() -> true
    else -> false
}

internal fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

internal fun isJoiner(codePoint: Int): Boolean = codePoint == 0x200c || codePoint == 0x200d

private fun isNumberSign(codePoint: Int): Boolean = codePoint == '+'.code || codePoint == '-'.code || codePoint == 0x2212

private fun isInternalSeparator(codePoint: Int): Boolean = when (codePoint) {
    '.'.code, ','.code, '/'.code, ':'.code, '-'.code, '_'.code, 0x2010, 0x2011 -> true
    else -> false
}
