package com.privateassistant.core.search

import com.privateassistant.core.DocumentId
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.PageText
import com.privateassistant.core.RevisionId
import com.privateassistant.core.RevisionRef
import com.privateassistant.core.RevisionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedLexicalSearchTest {
    private val ref = RevisionRef(DocumentId("policy-001"), RevisionId("original-v1"))

    @Test
    fun `all literal terms must occur on the same requested page`() {
        val source = RevisionText(ref, listOf(PageText(1, "premium"), PageText(2, "secret")))
        val query = LiteralSearchQuery.parse("premium secret")
        assertNull(GroundedLexicalSearch.findPage(query, source, 1))
        assertNull(GroundedLexicalSearch.findPage(query, source, 2))
        assertNull(GroundedLexicalSearch.findPage(query, source, 3))
    }

    @Test
    fun `OR injection cannot turn an all terms search into disjunction`() {
        assertNull(find("premium secret", "premium OR secret"))
        assertNotNull(find("premium OR secret", "premium OR secret"))
        assertNull(find("premium", "* () \""))
    }

    @Test
    fun `similar identifiers and numeric regrouping do not satisfy the requested literal`() {
        assertNull(find("Policy 12 premium 123456.00", "0012"))
        assertNull(find("Policy AB 001/07", "AB-001/07"))
        assertNull(find("Premium 123456.00", "1,23,456.00"))
        assertNull(find("Premium 1,23,456.00", "23"))
        assertNull(find("Refund -12.30", "12.30"))
        assertNotNull(find("Policy AB-001/07 premium ₹1,23,456.00", "AB-001/07 1,23,456.00"))
    }

    @Test
    fun `Unicode matches keep original UTF16 token offsets including supplementary prefixes`() {
        val text = "📄 प्रीमियम विम्याची रक्कम ₹1,23,456.00"
        val token = lexicalTokens(text).first()
        assertEquals(3, token.start)
        assertEquals("प्रीमियम", text.substring(token.start, token.end))
        val source = RevisionText(ref, listOf(PageText(7, text)))
        val hit = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("विम्याची रक्कम"), source, 7)!!
        assertEquals(ref, hit.ref)
        assertEquals(7, hit.pageNumber)
        assertEquals(text.substring(hit.startOffset, hit.endOffset), hit.quote)
        assertTrue(EvidenceValidator().validate(hit.evidence, source).isValid)
    }

    @Test
    fun `normalized match quotes the exact unnormalized stored text`() {
        val source = RevisionText(ref, listOf(PageText(1, "Premium cafe\u0301")))
        val hit = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("CAFÉ"), source, 1)!!
        assertEquals("Premium cafe\u0301", hit.quote)
        assertTrue(EvidenceValidator().validate(hit.evidence, source).isValid)
        assertNull(find("Premium café", "cafe"))
    }

    @Test
    fun `a hit cannot validate against another revision or changed page text`() {
        val source = RevisionText(ref, listOf(PageText(1, "Premium 0012")))
        val hit = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("0012"), source, 1)!!
        val stale = RevisionText(ref.copy(revisionId = RevisionId("original-v0")), source.pages)
        assertFalse(EvidenceValidator().validate(hit.evidence, stale).isValid)
        val changed = RevisionText(ref, listOf(PageText(1, "Premium 0013")))
        assertFalse(EvidenceValidator().validate(hit.evidence, changed).isValid)
    }

    @Test
    fun `bounded snippet prefers the shortest occurrence window rather than the first mention`() {
        val text = "premium " + "context ".repeat(80) + "premium secret " + "tail ".repeat(80)
        val source = RevisionText(ref, listOf(PageText(1, text)))
        val hit = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("premium secret"), source, 1)!!
        assertTrue(hit.startOffset > 0)
        assertTrue(hit.quote.contains("premium secret"))
        assertTrue(hit.quote.length <= GroundedLexicalSearch.MAX_SNIPPET_UTF16)
        assertEquals(3, hit.score)
        assertTrue(EvidenceValidator().validate(hit.evidence, source).isValid)
    }

    @Test
    fun `snippet boundaries never cut a surrogate pair or leave a leading combining mark`() {
        val text = "premium " + "a".repeat(231) + "📄" + "b".repeat(100)
        val hit = find(text, "premium")!!
        assertTrue(hit.quote.length <= 240)
        assertFalse(Character.isHighSurrogate(hit.quote.last()))
        val source = "a".repeat(117) + "📄" + " ".repeat(11) + "premium" + "b".repeat(100)
        val page = RevisionText(ref, listOf(PageText(1, source)))
        val narrow = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("premium"), page, 1, 32)
        // Add a separator so the final literal remains its own token.
        val separated = RevisionText(ref, listOf(PageText(1, source.replace("premium", "premium "))))
        val valid = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("premium"), separated, 1, 32)!!
        assertNull(narrow)
        assertFalse(Character.isLowSurrogate(valid.quote.first()))
        assertTrue(EvidenceValidator().validate(valid.evidence, separated).isValid)
    }

    @Test
    fun `snippets trim an incomplete combining sequence while leaving stored evidence intact`() {
        val text = "premium " + "a".repeat(231) + "कि" + "z".repeat(50)
        val source = RevisionText(ref, listOf(PageText(1, text)))
        val hit = GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("premium"), source, 1)!!
        assertEquals(239, hit.endOffset)
        assertFalse(hit.quote.endsWith("क"))
        assertTrue(EvidenceValidator().validate(hit.evidence, source).isValid)
    }

    @Test
    fun `literal ranking frequency and snippet work stay bounded`() {
        val hit = find("premium ".repeat(100), "premium premium")!!
        assertEquals(32, hit.score)
        assertEquals(listOf("premium"), hit.matchedTerms)
        assertTrue(hit.quote.length <= 240)
        assertThrows(IllegalArgumentException::class.java) {
            GroundedLexicalSearch.findPage(LiteralSearchQuery.parse("x"), RevisionText(ref, listOf(PageText(1, "x"))), 1, 241)
        }
    }

    private fun find(text: String, query: String): GroundedSearchHit? = GroundedLexicalSearch.findPage(
        LiteralSearchQuery.parse(query), RevisionText(ref, listOf(PageText(1, text))), 1,
    )
}
