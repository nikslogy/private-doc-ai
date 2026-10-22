package com.privateassistant.core.facts

import com.privateassistant.core.EvidenceBackedDecimalField
import com.privateassistant.core.EvidenceSpan
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.ExactDecimal
import com.privateassistant.core.RevisionText
import com.privateassistant.core.frozenList
import java.util.Locale

/** Explicit source labels only. These names do not establish policy identity or validity. */
enum class FieldKey {
    POLICY_NUMBER, VEHICLE_REGISTRATION, POLICY_HOLDER, OWNER, COVERAGE_START, COVERAGE_END,
    PREMIUM_TOTAL, PREMIUM_BEFORE_TAX, TAX, INSURED_VALUE, SUM_INSURED, DEDUCTIBLE,
    INVOICE_NUMBER, INVOICE_TOTAL, INVOICE_SUBTOTAL, INVOICE_DATE, TOTAL, SUBTOTAL,
}

/** A literal labelled value, never an instruction or a generated answer. Dates remain raw text. */
class LabelledField internal constructor(
    val key: FieldKey,
    val rawValue: String,
    val evidence: EvidenceSpan,
    val decimal: ExactDecimal? = null,
    /** A printed code/symbol only; an ambiguous dollar symbol is not converted into USD. */
    val currency: String? = null,
)

sealed interface FieldResolution {
    data object Missing : FieldResolution
    data class Unique(val field: LabelledField) : FieldResolution
    class Conflict internal constructor(candidates: List<LabelledField>) : FieldResolution {
        val candidates: List<LabelledField> = frozenList(candidates)
    }
}

class FieldExtraction internal constructor(candidates: List<LabelledField>, val truncated: Boolean) {
    val candidates: List<LabelledField> = frozenList(candidates)

    /** Resolves returned candidates only; callers must also check [truncated] and source coverage. */
    fun resolve(key: FieldKey): FieldResolution {
        val matching = candidates.filter { it.key == key }
        if (matching.isEmpty()) return FieldResolution.Missing
        return if (matching.map { it.rawValue }.distinct().size == 1) {
            FieldResolution.Unique(matching.first())
        } else {
            FieldResolution.Conflict(matching)
        }
    }
}

/**
 * A deliberately narrow, bounded form reader for English label:value text. It
 * does not answer free-form questions, infer omitted currencies, resolve dates,
 * choose a current policy, add amounts, or obey instructions in source text.
 *
 * Each accepted physical line starts with a supported label. Additional labels
 * on that same line may follow a period and whitespace (the fixture format).
 * Evidence preserves the complete physical line and its exact UTF-16 offsets.
 * Unsupported prose/number formats are omitted rather than guessed.
 */
class LabelledFieldExtractor {
    fun extract(source: RevisionText): FieldExtraction {
        val fields = mutableListOf<LabelledField>()
        var truncated = false
        var consumed = 0
        for ((pageIndex, page) in source.pages.withIndex()) {
            if (pageIndex >= MAX_PAGES || page.text.length > MAX_PAGE_UTF16 ||
                page.text.length > MAX_TOTAL_UTF16 - consumed
            ) {
                truncated = true
                break
            }
            consumed += page.text.length
            var lineStart = 0
            while (lineStart < page.text.length) {
                if (fields.size == MAX_CANDIDATES) return FieldExtraction(fields, true)
                val newline = page.text.indexOf('\n', lineStart).let { if (it < 0) page.text.length else it }
                val lineEnd = if (newline > lineStart && page.text[newline - 1] == '\r') newline - 1 else newline
                if (lineEnd - lineStart > MAX_LINE_UTF16) {
                    truncated = true
                } else {
                    val line = page.text.substring(lineStart, lineEnd)
                    val initial = labelled.matchEntire(line.substringBeforeInlineLabel())
                    if (initial != null && keyFor(initial.groupValues[1]) != null) {
                        val evidence = EvidenceSpan(source.ref, page.pageNumber, lineStart, lineEnd, line)
                        if (EvidenceValidator().validate(evidence, source).isValid) {
                            for (part in inlineBoundary.split(line)) {
                                if (fields.size == MAX_CANDIDATES) return FieldExtraction(fields, true)
                                val match = labelled.matchEntire(part) ?: continue
                                val key = keyFor(match.groupValues[1]) ?: continue
                                val rawValue = match.groupValues[2].trim()
                                parseField(key, rawValue, evidence, source)?.let(fields::add)
                            }
                        }
                    }
                }
                lineStart = newline + 1
            }
        }
        return FieldExtraction(fields, truncated)
    }

    private fun String.substringBeforeInlineLabel(): String =
        inlineBoundary.find(this)?.let { substring(0, it.range.first) } ?: this

    private fun parseField(
        key: FieldKey,
        raw: String,
        evidence: EvidenceSpan,
        source: RevisionText,
    ): LabelledField? {
        if (raw.isBlank() || raw.length > 160 || raw.any {
            Character.isISOControl(it) || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
        }) return null
        if (key in moneyKeys) {
            // Keep printed punctuation in rawValue. A final period following an
            // existing fraction can be a sentence delimiter; "100." is ambiguous.
            val match = money.matchEntire(raw) ?: raw.takeIf { it.endsWith('.') }
                ?.dropLast(1)?.let(money::matchEntire)
                ?.takeIf { it.groupValues[2].contains('.') } ?: return null
            val prefix = match.groupValues[1].takeIf(String::isNotEmpty)
            val number = match.groupValues[2]
            val suffix = match.groupValues[3].takeIf(String::isNotEmpty)
            if (prefix != null && suffix != null) return null
            val decimal = decimal(number) ?: return null
            val alternate = match.groupValues[4]
            if (alternate.isNotEmpty() && decimal(alternate)?.plainText != decimal.plainText) return null
            val currency = (prefix ?: suffix)?.uppercase(Locale.ROOT)
            val validated = EvidenceBackedDecimalField(key.name, decimal, listOf(evidence), currency, number)
            if (!validated.validate(source).isValid) return null
            return LabelledField(key, raw, evidence, decimal, currency)
        }
        if (key in identifierKeys && !identifier.matches(raw.removeSuffix("."))) return null
        if (key in dateKeys && !rawDate.matches(raw.removeSuffix("."))) return null
        return LabelledField(key, raw, evidence)
    }

    private fun decimal(raw: String): ExactDecimal? {
        val normal = if (groupedNumber.matches(raw)) raw.replace(",", "") else raw
        return ExactDecimal.parseOrNull(normal)
    }

    private fun keyFor(label: String): FieldKey? = labels[label.trim().lowercase(Locale.ROOT)]

    companion object {
        const val VERSION = "labelled-fields-v1"
        const val MAX_PAGES = 50
        const val MAX_PAGE_UTF16 = 1_000_000
        const val MAX_TOTAL_UTF16 = 2_000_000
        const val MAX_LINE_UTF16 = 512
        const val MAX_CANDIDATES = 256

        private val labelled = Regex("[ \\t]*([A-Za-z][A-Za-z -]{0,47})[ \\t]*:[ \\t]*(.+)")
        private val inlineBoundary = Regex("\\.[ \\t]+(?=[A-Za-z][A-Za-z -]{0,47}[ \\t]*:)")
        private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9/_-]{0,95}")
        // No LocalDate conversion: 03/04/2026 stays ambiguous and printed dates are not validity proof.
        private val rawDate = Regex("(?:[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}|[0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4})")
        private val groupedNumber = Regex("-?(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]?(?:,[0-9]{2})+,[0-9]{3})(?:\\.[0-9]+)?")
        private const val NUMBER = "-?[0-9][0-9,]*(?:\\.[0-9]+)?"
        private const val CURRENCY = "INR|USD|EUR|GBP|₹|\\$|€|£"
        private val money = Regex(
            "(?:(" + CURRENCY + ")[ \\t]*)?(" + NUMBER + ")[ \\t]*(?:(" + CURRENCY + "))?" +
                "(?:[ \\t]+\\(Indian format:[ \\t]*(" + NUMBER + ")\\))?",
            RegexOption.IGNORE_CASE,
        )
        private val moneyKeys = setOf(
            FieldKey.PREMIUM_TOTAL, FieldKey.PREMIUM_BEFORE_TAX, FieldKey.TAX, FieldKey.INSURED_VALUE,
            FieldKey.SUM_INSURED, FieldKey.DEDUCTIBLE, FieldKey.INVOICE_TOTAL, FieldKey.INVOICE_SUBTOTAL, FieldKey.TOTAL,
            FieldKey.SUBTOTAL,
        )
        private val identifierKeys = setOf(FieldKey.POLICY_NUMBER, FieldKey.VEHICLE_REGISTRATION, FieldKey.INVOICE_NUMBER)
        private val dateKeys = setOf(FieldKey.COVERAGE_START, FieldKey.COVERAGE_END, FieldKey.INVOICE_DATE)
        private val labels = mapOf(
            "policy number" to FieldKey.POLICY_NUMBER,
            "policy no" to FieldKey.POLICY_NUMBER,
            "vehicle registration" to FieldKey.VEHICLE_REGISTRATION,
            "policy holder" to FieldKey.POLICY_HOLDER,
            "policyholder" to FieldKey.POLICY_HOLDER,
            "owner" to FieldKey.OWNER,
            "coverage start" to FieldKey.COVERAGE_START,
            "coverage end" to FieldKey.COVERAGE_END,
            "total premium" to FieldKey.PREMIUM_TOTAL,
            "pre-tax premium" to FieldKey.PREMIUM_BEFORE_TAX,
            "premium before tax" to FieldKey.PREMIUM_BEFORE_TAX,
            "tax" to FieldKey.TAX,
            "insured declared value" to FieldKey.INSURED_VALUE,
            "insured value" to FieldKey.INSURED_VALUE,
            "sum insured" to FieldKey.SUM_INSURED,
            "deductible" to FieldKey.DEDUCTIBLE,
            "invoice number" to FieldKey.INVOICE_NUMBER,
            "invoice no" to FieldKey.INVOICE_NUMBER,
            "invoice total" to FieldKey.INVOICE_TOTAL,
            "total" to FieldKey.TOTAL,
            "subtotal" to FieldKey.SUBTOTAL,
            "invoice subtotal" to FieldKey.INVOICE_SUBTOTAL,
            "invoice date" to FieldKey.INVOICE_DATE,
        )
    }
}
