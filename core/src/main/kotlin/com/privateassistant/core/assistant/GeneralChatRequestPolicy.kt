package com.privateassistant.core.assistant

import java.text.Normalizer
import java.util.Locale

enum class GeneralChatRoute { GENERATE, CHOOSE_DOCUMENT, USE_DOCUMENT_CONTROLS }

/**
 * A bounded routing aid for the current user's general-chat input only.
 * These explicit English rules are not an evaluated language/intent classifier.
 * They grant no authority: neither document text nor generated text is routed
 * here, and a notice never executes an action or changes source selection.
 */
object GeneralChatRequestPolicy {
    fun route(question: String): GeneralChatRoute {
        require(question.isNotBlank() && question.length <= LocalChatPrompt.MAX_QUESTION_UTF16)
        val text = Normalizer.normalize(question, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT).replace('\u2019', '\'').trim().replace(WHITESPACE, " ")

        val document = DOCUMENT_TOPIC.containsMatchIn(text)
        val personal = PERSONAL_SCOPE.containsMatchIn(text) || PERSONAL_SOURCE.containsMatchIn(text) ||
            DEICTIC_SOURCE.containsMatchIn(text)
        val field = FIELD_TOPIC.containsMatchIn(text)

        // This path offers a current UI control instead of asking a text model
        // to claim that a file was changed, shared or sent. Broad imperative
        // matching is deliberately conservative, including missing objects.
        if (DIRECT_ACTION.containsMatchIn(text) || EXTERNAL_MESSAGE.containsMatchIn(text) ||
            (ACTION_WORD.containsMatchIn(text) && (document || ACTION_OBJECT.containsMatchIn(text)))) {
            return GeneralChatRoute.USE_DOCUMENT_CONTROLS
        }

        if ((personal && (document || field)) ||
            (DOCUMENT_LOOKUP.containsMatchIn(text) && (document || field)) ||
            (VALUE_QUESTION.containsMatchIn(text) && field)) {
            return GeneralChatRoute.CHOOSE_DOCUMENT
        }

        return GeneralChatRoute.GENERATE
    }

    private val WHITESPACE = Regex("\\s+")
    private val DOCUMENT_TOPIC = Regex(
        "\\b(?:documents?|docs?|files?|pdfs?|invoices?|polic(?:y|ies)|photos?|images?|pictures?|scans?|vault|attachments?)\\b",
    )
    private val FIELD_TOPIC = Regex(
        "\\b(?:premiums?|tax|idv|policyholder|deductible|coverage|insurance|sum insured|insured value|" +
            "registration|expiry|expiration|invoice totals?|policy number|invoice number|policy holder)\\b",
    )
    private val PERSONAL_SCOPE = Regex(
        "\\b(?:my|our|selected|uploaded|imported|attached|stored|saved)\\b",
    )
    private val DEICTIC_SOURCE = Regex(
        "\\b(?:this|these|that|those) (?:total |pre-tax |uploaded |imported |attached |selected |stored |saved )?" +
            "(?:documents?|docs?|polic(?:y|ies)|invoices?|pdfs?|files?|photos?|images?|premiums?|tax|coverage|" +
            "insurance|insured value|idv|expiry|expiration)\\b",
    )
    private val PERSONAL_SOURCE = Regex("\\bi (?:have|uploaded|imported|attached|stored|saved|paid|own)\\b")
    private val ACTION_WORD = Regex(
        "\\b(?:delete|remove|erase|wipe|clear|share|export|send|upload|download|convert|crop|rotate|compress|" +
            "rename|move|forward|print|save|attach|email|merge|split|combine|reduce)\\b",
    )
    private val ACTION_OBJECT = Regex("\\b(?:conversation|chat|history|answer|response|everything|all)\\b")
    private val DIRECT_ACTION = Regex(
        "^(?:please )?(?:(?:can|could|would|will) you |i (?:want|need) you to |" +
            "i (?:want|need) to |help me (?:to )?)?" +
            "(?:delete|remove|erase|wipe|clear|share|export|send|upload|download|convert|crop|rotate|compress|" +
            "rename|move|forward|print|save|attach|email|merge|split|combine)" +
            "(?:\\s*[?!.]*$| (?:it|them|everything|all|this|these|that|those)\\b)",
    )
    private val EXTERNAL_MESSAGE = Regex(
        "^(?:please )?(?:(?:can|could|would|will) you )?(?:send|email|forward)\\b.*\\bto\\b",
    )
    private val DOCUMENT_LOOKUP = Regex(
        "\\b(?:summari[sz]e|summary of|read the|show (?:me )?the|find (?:me )?the|look (?:at|in) the)\\b",
    )
    private val VALUE_QUESTION = Regex(
        "\\b(?:how much (?:is|was)|what(?:'s| is| are) the|when (?:is|does) the|who is the)\\b",
    )
}
