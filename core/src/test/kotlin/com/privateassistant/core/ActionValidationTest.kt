package com.privateassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidationTest {
    private val first = RevisionRef(DocumentId("document"), RevisionId("v1"))
    private val second = RevisionRef(DocumentId("document"), RevisionId("v2"))
    private val sources = mapOf(
        first to RevisionText(first, listOf(PageText(1, "one"))),
        second to RevisionText(second, listOf(PageText(1, "two"))),
    )
    private val validator = ActionValidator()

    @Test
    fun `follow up stays pinned after the latest document revision changes`() {
        val context = FollowUpContext(listOf(first))
        val followUp = context.question("What is the due date?")
        assertEquals(listOf(first), followUp.targets)
        assertTrue(validator.validate(followUp, setOf(first), sources).isValid)
        assertFalse(validator.validate(followUp, setOf(second), sources).isValid)
    }

    @Test
    fun `model cannot expand the selected source scope`() {
        val action = AssistantAction.Summarize(listOf(first, second))
        val result = validator.validate(action, setOf(first), sources)
        assertTrue(result.issues.any { it.code == "unselected_revision" })
    }

    @Test
    fun `a map key cannot conceal a different revision body`() {
        val wronglyIndexed = mapOf(first to sources.getValue(second))
        val result = validator.validate(AssistantAction.Summarize(listOf(first)), setOf(first), wronglyIndexed)
        assertTrue(result.issues.any { it.code == "unavailable_revision" })
    }

    @Test
    fun `read actions need distinct sources and nonempty questions`() {
        assertFalse(validator.validate(AssistantAction.Summarize(emptyList()), emptySet(), sources).isValid)
        assertFalse(validator.validate(AssistantAction.Summarize(listOf(first, first)), setOf(first), sources).isValid)
        assertFalse(validator.validate(AssistantAction.AskDocument(listOf(first), " "), setOf(first), sources).isValid)
    }

    @Test
    fun `combined source text is bounded before an action runs`() {
        val limited = ActionValidator(ResourceLimits(maxTextCharactersPerAction = 5))
        assertTrue(limited.validate(AssistantAction.Summarize(listOf(first)), setOf(first), sources).isValid)
        assertFalse(limited.validate(AssistantAction.Summarize(listOf(first, second)), setOf(first, second), sources).isValid)
    }

    @Test
    fun `mutating selection after creating an action cannot redirect it`() {
        val selection = mutableListOf(first)
        val action = AssistantAction.Summarize(selection)
        selection[0] = second
        assertEquals(listOf(first), action.targets)
    }
}
