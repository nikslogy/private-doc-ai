package com.privateassistant.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportValidationTest {
    private val limits = ResourceLimits(maxImportBytes = 1024, maxPagesPerDocument = 3)
    private val validator = ImportValidator(limits)

    @Test
    fun `exact configured boundaries are accepted`() {
        val report = validator.validate(ImportRequest("invoice.pdf", "application/pdf", 1024, 3))
        assertTrue(report.isValid)
    }

    @Test
    fun `untrusted metadata reports independent violations`() {
        val report = validator.validate(ImportRequest("", "application/octet-stream", 1025, 4))
        assertEquals(
            setOf("invalid_name", "unsupported_mime_type", "import_size", "page_limit"),
            report.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `zero byte files and nonpositive page counts are rejected`() {
        assertFalse(validator.validate(ImportRequest("empty.pdf", "application/pdf", 0, 0)).isValid)
    }

    @Test
    fun `missing MIME cannot be inferred from a filename`() {
        assertFalse(validator.validate(ImportRequest("looks-like.pdf", null, 20)).isValid)
        assertEquals(DocumentMimeType.PDF, DocumentMimeType.fromWireValue(" Application/PDF ; charset=binary"))
    }

    @Test
    fun `production limits bound PDF pages and source image dimensions`() {
        val production = ImportValidator()
        assertTrue(production.validate(ImportRequest("policy.pdf", "application/pdf", 1, 50)).isValid)
        assertFalse(production.validate(ImportRequest("policy.pdf", "application/pdf", 1, 51)).isValid)
        assertTrue(production.validate(ImportRequest("photo.jpg", "image/jpeg", 1, imageSize = PixelSize(5000, 4000))).isValid)
        assertFalse(production.validate(ImportRequest("photo.jpg", "image/jpeg", 1, imageSize = PixelSize(5001, 4000))).isValid)
        assertFalse(production.validate(ImportRequest("photo.jpg", "image/jpeg", 1, imageSize = PixelSize(Int.MAX_VALUE, Int.MAX_VALUE))).isValid)
        assertThrows(IllegalArgumentException::class.java) { ResourceLimits(maxPagesPerDocument = 51) }
        assertThrows(IllegalArgumentException::class.java) { ResourceLimits(maxSourceImagePixels = 20_000_001) }
        assertThrows(IllegalArgumentException::class.java) { ResourceLimits(maxRenderedPagePixels = 4_000_001) }
    }

    @Test
    fun `identifiers cannot escape an internal storage directory`() {
        listOf("../document", "a/b", "a\\b", "", "document\nnext").forEach {
            assertThrows(IllegalArgumentException::class.java) { DocumentId(it) }
            assertThrows(IllegalArgumentException::class.java) { RevisionId(it) }
        }
    }

    @Test
    fun `derivative provenance cannot be mutated after revision creation`() {
        val original = RevisionRef(DocumentId("policy"), RevisionId("original"))
        val derivative = original.copy(revisionId = RevisionId("page-images"))
        val parents = mutableListOf(original)
        val revision = DocumentRevision(
            derivative, "page.jpg", DocumentMimeType.JPEG, 100, 1, Instant.EPOCH,
            Sha256Digest("a".repeat(64)), parents = parents,
        )
        parents.clear()
        assertEquals(listOf(original), revision.parents)
        assertThrows(IllegalArgumentException::class.java) { Sha256Digest("not-a-digest") }
        assertThrows(IllegalArgumentException::class.java) {
            Document(DocumentId("different-document"), "Policy", original, Instant.EPOCH)
        }
    }
}
