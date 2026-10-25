package com.privateassistant.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalChatPromptTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Input must be rejected before native generation") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun completeCurrentQuestionAndLiteralFormattingAreRetained() {
        val question = "  Explain 000042 and INR 5,00,000.00100 exactly.  "
        val prompt = LocalChatPrompt.build(question, emptyList())
        assertTrue(prompt.endsWith("\nUser: $question\nAssistant:"))
        assertTrue(prompt.contains("cannot access, change or share files"))
        assertTrue(prompt.length <= LocalChatPrompt.MAX_PROMPT_UTF16)
    }

    @Test fun currentInputBoundaryNeverGetsTruncatedToFitHistory() {
        val question = "Q".repeat(LocalChatPrompt.MAX_QUESTION_UTF16)
        val history = (0..100).map { ChatTurn(ChatRole.ASSISTANT, "Older ${it}: " + "a".repeat(500)) }
        val prompt = LocalChatPrompt.build(question, history)
        assertTrue(prompt.endsWith("\nUser: $question\nAssistant:"))
        assertTrue(prompt.length <= 1024)
        rejected { LocalChatPrompt.build(question + "x", emptyList()) }
    }

    @Test fun latestHistoryIsUsedInOrderAndOlderHistoryIsDiscarded() {
        val history = listOf(
            ChatTurn(ChatRole.USER, "HISTORY-TOO-OLD-A"), ChatTurn(ChatRole.ASSISTANT, "HISTORY-TOO-OLD-B"),
            ChatTurn(ChatRole.USER, "How many corners does a triangle have?"),
            ChatTurn(ChatRole.ASSISTANT, "A triangle has three corners."),
            ChatTurn(ChatRole.USER, "What color did I mention?"),
            ChatTurn(ChatRole.ASSISTANT, "You mentioned blue."),
        )
        val prompt = LocalChatPrompt.build("And the shape?", history)
        assertFalse(prompt.contains("HISTORY-TOO-OLD"))
        assertTrue(prompt.indexOf("How many corners") < prompt.indexOf("A triangle has three corners"))
        assertTrue(prompt.indexOf("You mentioned blue") < prompt.indexOf("And the shape?"))
    }

    @Test fun historyExcerptsNeverLeaveAnIsolatedSurrogate() {
        val text = "a".repeat(159) + "\uD83D\uDD35" + " blue"
        val prompt = LocalChatPrompt.build("Describe it.", listOf(ChatTurn(ChatRole.USER, text)))
        assertTrue(prompt.contains("a".repeat(159) + " [excerpt]"))
        assertFalse(prompt.any { Character.isSurrogate(it) })
    }

    @Test fun supplementaryCharactersCanAppearInTheCompleteCurrentQuestion() {
        val question = "\uD83D\uDD35".repeat(200)
        val prompt = LocalChatPrompt.build(question, emptyList())
        assertTrue(prompt.endsWith("\nUser: $question\nAssistant:"))
    }

    @Test fun blankMalformedAndOversizedTextFailBeforeInference() {
        listOf("", " \n\t", "\uD83D", "\uDD35", "x".repeat(401)).forEach { value ->
            rejected { LocalChatPrompt.build(value, emptyList()) }
        }
        listOf("", "\uD83D", "\uDD35", "x".repeat(2049)).forEach { value ->
            rejected { ChatTurn(ChatRole.USER, value) }
        }
    }

    @Test fun noHistoryCaseStillNamesTheCurrentSpeaker() {
        val prompt = LocalChatPrompt.build("Hello", emptyList())
        assertTrue(prompt.endsWith("User: Hello\nAssistant:"))
        assertEquals(1, Regex("User:").findAll(prompt).count())
    }

    @Test fun turnDebugOutputDoesNotExposePrivateText() {
        val marker = "SYNTHETIC-CHAT-PRIVATE-MARKER"
        assertFalse(ChatTurn(ChatRole.USER, marker).toString().contains(marker))
        assertFalse(ChatTurn(ChatRole.ASSISTANT, marker).toString().contains(marker))
    }
}
