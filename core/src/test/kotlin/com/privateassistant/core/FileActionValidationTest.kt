package com.privateassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileActionValidationTest {
    private val pdf = RevisionRef(DocumentId("policy"), RevisionId("pdf"))
    private val firstImage = RevisionRef(DocumentId("photo1"), RevisionId("original"))
    private val secondImage = RevisionRef(DocumentId("photo2"), RevisionId("original"))
    private val selected = setOf(pdf, firstImage, secondImage)
    private val sources = mapOf(
        pdf to ActionSource(pdf, DocumentMimeType.PDF, 2),
        firstImage to ActionSource(firstImage, DocumentMimeType.JPEG, 1, PixelSize(4000, 3000)),
        secondImage to ActionSource(secondImage, DocumentMimeType.PNG, 1, PixelSize(800, 600)),
    )
    private val validator = ActionValidator()

    @Test
    fun `PDF render rejects invalid page selections and pixel budgets`() {
        fun valid(pages: List<PageRender>) = validator.validateResources(AssistantAction.PdfToImages(pdf, pages), selected, sources).isValid
        val page = PageRender(1, PixelSize(2000, 2000))
        assertTrue(valid(listOf(page)))
        assertFalse(valid(emptyList()))
        assertFalse(valid(listOf(page, page)))
        assertFalse(valid(listOf(page.copy(pageNumber = 3))))
        assertFalse(valid(listOf(page.copy(pageNumber = 2), page)))
        assertFalse(valid(listOf(page.copy(outputSize = PixelSize(2001, 2000)))))
    }

    @Test
    fun `images to PDF preserves explicit order and rejects nonimages`() {
        val action = AssistantAction.ImagesToPdf(listOf(secondImage, firstImage))
        assertEquals(listOf(secondImage, firstImage), action.targets)
        assertTrue(validator.validateResources(action, selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.ImagesToPdf(listOf(pdf)), selected, sources).isValid)
    }

    @Test
    fun `PDF rasterization needs outside consent and a full page selection`() {
        val pages = listOf(PageRender(1, PixelSize(800, 1000)), PageRender(2, PixelSize(800, 1000)))
        val action = AssistantAction.CompressImagePdf(pdf, 100_000, pages)
        assertFalse(validator.validateResources(action, selected, sources).isValid)
        assertTrue(validator.validateResources(action, selected, sources, rasterizationConsents = setOf(pdf)).isValid)
        val partial = AssistantAction.CompressImagePdf(pdf, 100_000, pages.take(1))
        assertFalse(validator.validateResources(partial, selected, sources, rasterizationConsents = setOf(pdf)).isValid)
    }

    @Test
    fun `crop rotation and image compression remain bounded by the original image`() {
        assertTrue(validator.validateResources(AssistantAction.CropImage(firstImage, CropBounds(0, 0, 4000, 3000)), selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.CropImage(firstImage, CropBounds(0, 0, 4001, 3000)), selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.CropImage(firstImage, CropBounds(10, 10, 10, 10)), selected, sources).isValid)
        assertTrue(validator.validateResources(AssistantAction.RotateImage(firstImage, 90), selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.RotateImage(firstImage, 45), selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.CompressImage(firstImage, -1, 101), selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.CompressImage(pdf, 100, 80), selected, sources).isValid)
    }

    @Test
    fun `original retrieval does not depend on OCR but analysis does`() {
        assertTrue(validator.validateResources(AssistantAction.GetOriginal(pdf), selected, sources).isValid)
        assertFalse(validator.validateResources(AssistantAction.AskDocument(listOf(pdf), "What is the premium?"), selected, sources).isValid)
        val partialText = sources + (pdf to sources.getValue(pdf).copy(text = RevisionText(pdf, listOf(PageText(1, "Premium: 1234.50")))))
        assertTrue(validator.validateResources(AssistantAction.AskDocument(listOf(pdf), "What is the premium?"), selected, partialText).isValid)
        assertFalse(validator.validateResources(AssistantAction.Summarize(listOf(pdf)), selected, partialText).isValid)
    }

    @Test
    fun `search and clarification need no implicit source selection`() {
        assertTrue(validator.validateResources(AssistantAction.Search("policy 00123"), emptySet(), emptyMap()).isValid)
        assertTrue(validator.validateResources(AssistantAction.Clarify("Which of these two policies?"), emptySet(), emptyMap()).isValid)
    }
}
