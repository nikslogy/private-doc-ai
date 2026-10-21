package com.privateassistant.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralSearchQueryTest {
    @Test
    fun `FTS operators are encoded literal terms rather than executable syntax`() {
        val query = LiteralSearchQuery.parse("premium OR secret")
        assertEquals(listOf("premium", "OR", "secret"), query.literals)
        assertEquals("\"t7072656d69756d\" AND \"t6f72\" AND \"t736563726574\"", query.ftsMatch)
    }

    @Test
    fun `quotes field selectors wildcard and NEAR cannot enter MATCH syntax`() {
        val query = LiteralSearchQuery.parse("\"premium\" OR * NEAR(1) title:secret --")
        assertTrue(Regex("\"t[0-9a-f]+\"(?: AND \"t[0-9a-f]+\")*").matches(query.ftsMatch!!))
        assertTrue(query.literals.contains("title:secret"))
        assertFalse(query.ftsMatch!!.contains("NEAR"))
        assertFalse(query.ftsMatch!!.contains('*'))
    }

    @Test
    fun `empty punctuation only and unattached marks have no MATCH expression`() {
        listOf("", "  \n\t", "*()\" :- , ;", "\u0301\u200d").forEach {
            val query = LiteralSearchQuery.parse(it)
            assertTrue(query.literals.isEmpty())
            assertNull(query.ftsMatch)
        }
    }

    @Test
    fun `oversized input and too many terms are rejected without truncation`() {
        assertThrows(IllegalArgumentException::class.java) {
            LiteralSearchQuery.parse("x".repeat(LiteralSearchQuery.MAX_INPUT_UTF16 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiteralSearchQuery.parse((1..LiteralSearchQuery.MAX_TERMS + 1).joinToString(" ") { "x$it" })
        }
        assertEquals(512, LiteralSearchQuery.parse("x".repeat(512)).literals.single().length)
        assertEquals(24, LiteralSearchQuery.parse((1..24).joinToString(" ") { "x$it" }).literals.size)
    }

    @Test
    fun `Hindi Marathi combining marks and virama joiner remain in their literals`() {
        val raw = "प्रीमियम विम्याची क्\u200dष रक्कम"
        assertEquals(listOf("प्रीमियम", "विम्याची", "क्\u200dष", "रक्कम"), LiteralSearchQuery.parse(raw).literals)
        assertEquals(4, LiteralSearchQuery.indexTerms(raw).split(' ').size)
        assertFalse(LiteralSearchQuery.indexTerms("रक्कम") == LiteralSearchQuery.indexTerms("रकम"))
    }

    @Test
    fun `identifier zeroes separators signs and Indian grouped decimals are preserved`() {
        val query = LiteralSearchQuery.parse("0012 AB-001/07 1,23,456.00 -12.30 +12.30 −12.30")
        assertEquals(listOf("0012", "AB-001/07", "1,23,456.00", "-12.30", "+12.30", "−12.30"), query.literals)
        assertFalse(LiteralSearchQuery.indexTerms("0012") == LiteralSearchQuery.indexTerms("12"))
        assertFalse(LiteralSearchQuery.indexTerms("1,23,456.00") == LiteralSearchQuery.indexTerms("123456.00"))
    }

    @Test
    fun `NFC and locale independent case use the same index encoding without stripping accents`() {
        assertEquals(LiteralSearchQuery.indexTerms("CAFÉ"), LiteralSearchQuery.indexTerms("cafe\u0301"))
        assertFalse(LiteralSearchQuery.indexTerms("café") == LiteralSearchQuery.indexTerms("cafe"))
        val query = LiteralSearchQuery.parse("CAFÉ cafe\u0301")
        assertEquals(listOf("CAFÉ"), query.literals)
        assertEquals("\"${LiteralSearchQuery.indexTerms("cafe\u0301")}\"", query.ftsMatch)
    }

    @Test
    fun `index contains only encoded complete tokens and ignores unqueryable oversized tokens`() {
        val index = LiteralSearchQuery.indexTerms("premium ₹ प्रीमियम 0012,03.00 premium")
        assertTrue(Regex("t[0-9a-f]+(?: t[0-9a-f]+)*").matches(index))
        assertEquals(3, index.split(' ').size)
        assertEquals("", LiteralSearchQuery.indexTerms("x".repeat(513)))
        assertThrows(IllegalArgumentException::class.java) {
            LiteralSearchQuery.indexTerms("x".repeat(LiteralSearchQuery.MAX_PAGE_UTF16 + 1))
        }
    }

    @Test
    fun `query term collection cannot be modified by callers`() {
        val query = LiteralSearchQuery.parse("premium")
        assertThrows(UnsupportedOperationException::class.java) {
            (query.literals as MutableList<String>).add("other")
        }
    }
}
