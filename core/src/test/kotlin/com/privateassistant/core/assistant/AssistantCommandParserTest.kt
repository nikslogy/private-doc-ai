package com.privateassistant.core.assistant

import com.privateassistant.core.identity.IdentifierKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCommandParserTest {
    private fun parse(text: String) = AssistantCommandParser.parse(text)

    @Test fun `identifier questions become direct lookups`() {
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.PAN), parse("What is my PAN number?"))
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.PAN), parse("my pan"))
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.AADHAAR), parse("tell me my aadhar no"))
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.PASSPORT), parse("what's my passport number"))
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.DATE_OF_BIRTH), parse("What is my date of birth"))
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.GSTIN), parse("give me our GSTIN"))
        assertEquals(AssistantCommand.LookupIdentifier(IdentifierKind.IFSC), parse("bank IFSC code?"))
    }

    @Test fun `asking for the card itself finds the document`() {
        val command = parse("Show my PAN card") as AssistantCommand.FindDocument
        assertEquals(IdentifierKind.PAN, command.hint.identifier)
        val passport = parse("open my passport") as AssistantCommand.FindDocument
        assertEquals(IdentifierKind.PASSPORT, passport.hint.identifier)
    }

    @Test fun `image format requests convert the matching document`() {
        val command = parse("I want my pan number in image format") as AssistantCommand.ConvertToImage
        assertEquals(IdentifierKind.PAN, command.hint.identifier)
        assertTrue(parse("convert my resume pdf to jpg") is AssistantCommand.ConvertToImage)
        assertTrue(parse("pdf to jpeg please") is AssistantCommand.ConvertToImage)
        assertTrue(parse("send aadhaar as an image") is AssistantCommand.ConvertToImage)
    }

    @Test fun `pdf requests convert images`() {
        assertTrue(parse("convert this photo to pdf") is AssistantCommand.ConvertToPdf)
        assertTrue(parse("I need my marksheet in pdf format") is AssistantCommand.ConvertToPdf)
        assertTrue(parse("jpg to pdf") is AssistantCommand.ConvertToPdf)
    }

    @Test fun `size limits become compression with decimal units`() {
        val command = parse("I want this pdf with less than 200kb") as AssistantCommand.CompressToSize
        assertEquals(200_000L, command.targetBytes)
        assertTrue(command.hint.deictic)
        val resume = parse("compress my resume to 1.5 MB") as AssistantCommand.CompressToSize
        assertEquals(1_500_000L, resume.targetBytes)
        assertEquals(listOf("resume"), resume.hint.terms)
        val pan = parse("PAN card under 100 KB") as AssistantCommand.CompressToSize
        assertEquals(IdentifierKind.PAN, pan.hint.identifier)
        assertNull(AssistantCommandParser.targetBytes("under 2 kb"))
        assertNull(AssistantCommandParser.targetBytes("no size here"))
    }

    @Test fun `passport size photo requests`() {
        val command = parse("I want my photo - passport size") as AssistantCommand.PassportPhoto
        assertEquals(PhotoSpec.PASSPORT_35X45, command.spec)
        assertTrue("photo" in command.hint.terms)
        assertEquals(PhotoSpec.US_2X2, (parse("make a 2x2 photo for US visa") as AssistantCommand.PassportPhoto).spec)
        assertTrue(parse("passport photo please") is AssistantCommand.PassportPhoto)
        // A passport number question is not a photo request.
        assertTrue(parse("what is my passport number") is AssistantCommand.LookupIdentifier)
    }

    @Test fun `general questions and document questions are left to chat`() {
        assertNull(parse("What is the capital of France?"))
        assertNull(parse("What does my policy say about cancellation?"))
        assertNull(parse("summarise my insurance policy"))
        assertNull(parse("hello"))
    }

    @Test fun `plain find requests carry name terms`() {
        val command = parse("show my electricity bill") as AssistantCommand.FindDocument
        assertEquals(listOf("electricity", "bill"), command.hint.terms)
    }
}

class DocumentResolverTest {
    private val docs = listOf(
        DocumentDescriptor("pan", "PAN card.pdf", "application/pdf", identifiers = setOf(IdentifierKind.PAN)),
        DocumentDescriptor("scan", "Scan 0042.jpg", "image/jpeg", identifiers = setOf(IdentifierKind.AADHAAR)),
        DocumentDescriptor("resume", "Resume 2026.pdf", "application/pdf"),
        DocumentDescriptor("selfie", "my photo.jpg", "image/jpeg", folderName = "Personal"),
        DocumentDescriptor("notes", "notes.txt", "text/plain"),
    )

    @Test fun `identifier and name evidence pick one document`() {
        assertEquals(DocumentResolution.Single("pan"),
            DocumentResolver.resolve(DocumentHint(emptyList(), IdentifierKind.PAN), docs, SourceRequirement.ANY))
        assertEquals(DocumentResolution.Single("scan"),
            DocumentResolver.resolve(DocumentHint(emptyList(), IdentifierKind.AADHAAR), docs, SourceRequirement.IMAGE_OR_PDF))
        assertEquals(DocumentResolution.Single("resume"),
            DocumentResolver.resolve(DocumentHint(listOf("resume")), docs, SourceRequirement.PDF))
        assertEquals(DocumentResolution.Single("selfie"),
            DocumentResolver.resolve(DocumentHint(listOf("photo")), docs, SourceRequirement.IMAGE))
    }

    @Test fun `no evidence asks the user to choose among compatible files`() {
        val result = DocumentResolver.resolve(DocumentHint(listOf("unknown")), docs, SourceRequirement.IMAGE)
        assertEquals(DocumentResolution.Choose(listOf("scan", "selfie")), result)
        assertEquals(DocumentResolution.NoCompatible,
            DocumentResolver.resolve(DocumentHint(emptyList()), docs.filter { it.mimeType == "text/plain" }, SourceRequirement.PDF))
    }

    @Test fun `deictic requests use the focused document only when compatible`() {
        val hint = DocumentHint(emptyList(), deictic = true)
        assertEquals(DocumentResolution.Single("resume"), DocumentResolver.resolve(hint, docs, SourceRequirement.PDF, "resume"))
        assertTrue(DocumentResolver.resolve(hint, docs, SourceRequirement.PDF, "notes") is DocumentResolution.Choose)
    }
}

class ExistingQuestionCompatibilityTest {
    /** Questions covered by earlier controller tests must keep their established chat routes. */
    @Test fun `established document and general questions are not commands`() {
        listOf(
            "How can I cancel my booking?", "How does cancellation work?", "Name a primary colour.",
            "What are the cancellation requirements?", "What is the tax?", "What is the total premium?",
            "Which animal says meow?", "What is my total premium?", "Delete this policy", "Email my invoice to someone",
            "What is the policy number?", "Summarize this document", "What do my documents say about cancellation?",
        ).forEach { assertNull(it, AssistantCommandParser.parse(it)) }
    }
}
