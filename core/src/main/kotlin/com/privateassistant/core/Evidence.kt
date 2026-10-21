package com.privateassistant.core

import java.math.BigDecimal

/** Quotes are untrusted until checked against the stored revision by [EvidenceValidator]. */
data class EvidenceSpan(
    val ref: RevisionRef,
    val pageNumber: Int,
    val startOffset: Int,
    val endOffset: Int,
    val quote: String,
)

class EvidenceValidator {
    fun validate(span: EvidenceSpan, source: RevisionText): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (span.ref != source.ref) {
            issues += ValidationIssue("evidence_revision", "Evidence must cite the pinned source revision.")
        }
        val page = source.page(span.pageNumber)
        if (page == null) {
            issues += ValidationIssue("evidence_page", "The cited page does not exist.")
        } else if (span.startOffset < 0 || span.endOffset <= span.startOffset || span.endOffset > page.text.length) {
            issues += ValidationIssue("evidence_offsets", "Evidence offsets must identify a nonempty source span.")
        } else if (page.text.substring(span.startOffset, span.endOffset) != span.quote) {
            issues += ValidationIssue("evidence_quote", "The evidence quote does not match the source text.")
        }
        return ValidationReport(issues)
    }
}

/** A base-ten value constructed only from plain decimal text; no floating point conversion. */
class ExactDecimal private constructor(val plainText: String) {
    val value: BigDecimal get() = BigDecimal(plainText)

    override fun equals(other: Any?): Boolean = other is ExactDecimal && plainText == other.plainText
    override fun hashCode(): Int = plainText.hashCode()
    override fun toString(): String = plainText

    companion object {
        private val plainDecimal = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")

        fun parse(text: String): ExactDecimal {
            require(text.length <= 128 && plainDecimal.matches(text)) { "Use an ungrouped plain decimal string" }
            return ExactDecimal(text)
        }

        fun parseOrNull(text: String): ExactDecimal? = runCatching { parse(text) }.getOrNull()
    }
}

/** Checks numeric/span integrity. Field meaning and currency still require separately verified extraction. */
class EvidenceBackedDecimalField(
    val name: String,
    val value: ExactDecimal,
    evidence: List<EvidenceSpan>,
    val unit: String? = null,
    val rawValue: String = value.plainText,
) {
    val evidence: List<EvidenceSpan> = frozenList(evidence)

    init {
        require(name.isNotBlank() && name.length <= 128)
        require(unit == null || (unit.isNotBlank() && unit.length <= 32))
        require(rawValue.isNotBlank() && rawValue.length <= 128)
    }

    /** Results without evidence, with stale evidence, or with an invented number fail closed. */
    fun validate(source: RevisionText): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (evidence.isEmpty()) issues += ValidationIssue("missing_evidence", "A numeric field requires source evidence.")
        val validator = EvidenceValidator()
        evidence.forEach { issues += validator.validate(it, source).issues }
        if (normalizeSourceDecimal(rawValue) != value.plainText) {
            issues += ValidationIssue("decimal_format", "The preserved source number must represent the exact decimal.")
        }
        if (evidence.isNotEmpty() && evidence.none { hasSupportedSourceNumber(it, source, rawValue) }) {
            issues += ValidationIssue("unsupported_decimal", "The exact decimal must appear in its evidence.")
        }
        return ValidationReport(issues)
    }
}

private fun hasSupportedSourceNumber(span: EvidenceSpan, source: RevisionText, rawValue: String): Boolean {
    if (!EvidenceValidator().validate(span, source).isValid) return false
    val text = source.page(span.pageNumber)?.text ?: return false
    // Match in the complete page, so trimming the citation cannot hide a sign or larger number.
    val token = Regex("(?<![\\p{L}\\p{N}.,+\\p{Pd}\\u2212(])${Regex.escape(rawValue)}(?![\\p{L}\\p{N}]|[.,][\\p{N}]|\\))")
    var match = token.find(text, span.startOffset)
    while (match != null && match.range.last < span.endOffset) {
        var prefix = match.range.first - 1
        while (prefix >= 0 && text[prefix].isNumberDecoration()) prefix--
        val prefixUnsafe = prefix >= 0 && (text[prefix].isDigit() || text[prefix] == '(' ||
            text[prefix] == '+' || text[prefix] == '\u2212' || Character.getType(text[prefix]) == Character.DASH_PUNCTUATION.toInt())
        var suffix = match.range.last + 1
        while (suffix < text.length && text[suffix].isNumberDecoration()) suffix++
        val suffixUnsafe = suffix < text.length && (text[suffix] == ')' || text[suffix] == '+' ||
            text[suffix] == '\u2212' || Character.getType(text[suffix]) == Character.DASH_PUNCTUATION.toInt())
        if (!prefixUnsafe && !suffixUnsafe) return true
        match = match.next()
    }
    return false
}

private fun Char.isNumberDecoration(): Boolean = isWhitespace() || when (Character.getType(this)) {
    Character.CURRENCY_SYMBOL.toInt(), Character.FORMAT.toInt(), Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

/** Support explicit dot decimals with either Western or Indian digit grouping. */
private fun normalizeSourceDecimal(raw: String): String? {
    if (ExactDecimal.parseOrNull(raw) != null) return raw
    val grouped = Regex("-?(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]?(?:,[0-9]{2})+,[0-9]{3})(?:\\.[0-9]+)?")
    return raw.takeIf { grouped.matches(it) }?.replace(",", "")
}
