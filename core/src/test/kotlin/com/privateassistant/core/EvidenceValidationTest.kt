package com.privateassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceValidationTest {
    private val ref = RevisionRef(DocumentId("invoice"), RevisionId("v1"))
    private val pageText = "Invoice total: $1234.50. Pay within 30 days."
    private val source = RevisionText(ref, listOf(PageText(1, pageText)))
    private val quote = "Invoice total: $1234.50."
    private val evidence = EvidenceSpan(ref, 1, 0, quote.length, quote)

    @Test
    fun `supported decimals keep their full precision and scale`() {
        val value = ExactDecimal.parse("123456789012345678901234567890.00100")
        assertEquals("123456789012345678901234567890.00100", value.value.toPlainString())
        assertEquals(5, value.value.scale())
    }

    @Test
    fun `ambiguous locale and floating point representations are rejected`() {
        listOf("1,234.50", "1234,50", "1e3", "NaN", "Infinity", "01.00", "1.", "+1", " 1").forEach {
            assertThrows(IllegalArgumentException::class.java) { ExactDecimal.parse(it) }
        }
    }

    @Test
    fun `numeric extraction requires exact matching source evidence`() {
        val field = EvidenceBackedDecimalField("total", ExactDecimal.parse("1234.50"), listOf(evidence), "USD")
        assertTrue(field.validate(source).isValid)
        assertFalse(EvidenceBackedDecimalField("total", ExactDecimal.parse("1234.5"), listOf(evidence)).validate(source).isValid)
        assertFalse(EvidenceBackedDecimalField("total", ExactDecimal.parse("1234.50"), emptyList()).validate(source).isValid)
    }

    @Test
    fun `fabricated quote cannot substantiate a correct looking field`() {
        val fabricated = evidence.copy(quote = "Invoice total: $9000.00.")
        val result = EvidenceBackedDecimalField("total", ExactDecimal.parse("9000.00"), listOf(fabricated)).validate(source)
        assertTrue(result.issues.any { it.code == "evidence_quote" })
    }

    @Test
    fun `citation to a different revision is rejected even with identical source text`() {
        val stale = evidence.copy(ref = ref.copy(revisionId = RevisionId("v0")))
        assertTrue(EvidenceValidator().validate(stale, source).issues.any { it.code == "evidence_revision" })
    }

    @Test
    fun `missing pages and malformed offsets fail without indexing exceptions`() {
        val validator = EvidenceValidator()
        assertFalse(validator.validate(evidence.copy(pageNumber = 2), source).isValid)
        assertFalse(validator.validate(evidence.copy(startOffset = -1), source).isValid)
        assertFalse(validator.validate(evidence.copy(endOffset = Int.MAX_VALUE), source).isValid)
        assertFalse(validator.validate(evidence.copy(endOffset = 0), source).isValid)
    }

    @Test
    fun `a positive decimal cannot be sourced from a negative amount or larger number`() {
        fun fieldSupported(text: String, citeNumberOnly: Boolean = false): Boolean {
            val document = RevisionText(ref, listOf(PageText(1, text)))
            val start = if (citeNumberOnly) text.indexOf("12.30") else 0
            val end = if (citeNumberOnly) start + "12.30".length else text.length
            val span = EvidenceSpan(ref, 1, start, end, text.substring(start, end))
            return EvidenceBackedDecimalField("amount", ExactDecimal.parse("12.30"), listOf(span)).validate(document).isValid
        }
        assertFalse(fieldSupported("Refund -12.30"))
        assertFalse(fieldSupported("Amount 112.30"))
        assertFalse(fieldSupported("Amount 12.300"))
        assertTrue(fieldSupported("Amount $12.30."))
        assertFalse(fieldSupported("Refund -12.30", citeNumberOnly = true))
        assertFalse(fieldSupported("Refund − ₹12.30", citeNumberOnly = true))
        assertFalse(fieldSupported("Refund -\u200B12.30", citeNumberOnly = true))
        assertFalse(fieldSupported("Refund 12.30-", citeNumberOnly = true))
        assertFalse(fieldSupported("Refund (₹12.30)", citeNumberOnly = true))
        assertFalse(fieldSupported("Amount 112.30", citeNumberOnly = true))
        assertFalse(fieldSupported("Amount 12.300", citeNumberOnly = true))
        assertFalse(fieldSupported("Amount 1 12.30", citeNumberOnly = true))
        assertTrue(fieldSupported("Amount $12.30.", citeNumberOnly = true))
    }

    @Test
    fun `source text snapshots caller collections`() {
        val pages = mutableListOf(PageText(1, "original"))
        val snapshot = RevisionText(ref, pages)
        pages.clear()
        assertEquals("original", snapshot.page(1)?.text)
    }

    @Test
    fun `Indian and Western grouping preserve the raw number without using floating point`() {
        fun check(raw: String, canonical: String): Boolean {
            val text = "Premium: ₹$raw."
            val document = RevisionText(ref, listOf(PageText(1, text)))
            val span = EvidenceSpan(ref, 1, 0, text.length, text)
            return EvidenceBackedDecimalField("premium", ExactDecimal.parse(canonical), listOf(span), "INR", raw).validate(document).isValid
        }
        assertTrue(check("1,23,456.00", "123456.00"))
        assertTrue(check("123,456.00", "123456.00"))
        assertFalse(check("12,34.00", "1234.00"))
        assertFalse(check("1,23,456.00", "123456.0"))
    }
}
