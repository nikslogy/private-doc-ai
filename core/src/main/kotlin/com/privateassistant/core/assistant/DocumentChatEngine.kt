package com.privateassistant.core.assistant

import com.privateassistant.core.EvidenceSpan
import com.privateassistant.core.EvidenceValidator
import com.privateassistant.core.RevisionText
import com.privateassistant.core.facts.FieldKey
import com.privateassistant.core.facts.FieldResolution
import com.privateassistant.core.facts.LabelledField
import com.privateassistant.core.facts.LabelledFieldExtractor
import com.privateassistant.core.frozenList
import java.util.Locale

/** Exact literal evidence, not a generated paraphrase or authorization to act. */
class ChatDocumentFact(val key: FieldKey, val rawValue: String, val evidence: EvidenceSpan) {
    override fun toString(): String = "ChatDocumentFact(key=$key)"
}

sealed interface DocumentChatAnswer {
    class Facts(facts: List<ChatDocumentFact>, val summary: Boolean, val partial: Boolean,
        val hasConflicts: Boolean = false) : DocumentChatAnswer {
        val facts: List<ChatDocumentFact> = frozenList(facts)
        override fun toString(): String = "DocumentChatAnswer.Facts"
    }
    class Conflict(facts: List<ChatDocumentFact>) : DocumentChatAnswer {
        val facts: List<ChatDocumentFact> = frozenList(facts)
        override fun toString(): String = "DocumentChatAnswer.Conflict"
    }
    data object Missing : DocumentChatAnswer
    data object Clarify : DocumentChatAnswer
    data object Incomplete : DocumentChatAnswer
    data object Unsupported : DocumentChatAnswer
}

/**
 * A conservative selected-revision question router. Supported English questions
 * read explicit printed labels; dates, currencies and numbers stay literal.
 * It cannot infer policy validity, calculate amounts, execute actions, or answer
 * arbitrary prose questions. The local model's general chat is a separate path.
 */
class DocumentChatEngine {
    fun answer(question: String, source: RevisionText, complete: Boolean): DocumentChatAnswer {
        val normalized = normalize(question) ?: return DocumentChatAnswer.Unsupported
        val summary = summaryQuestion.matches(normalized)
        val field = if (summary) null else fieldQuestion(normalized)
        if (!summary && field == null) return if (ambiguousQuestion(normalized)) {
            DocumentChatAnswer.Clarify
        } else DocumentChatAnswer.Unsupported

        val extracted = LabelledFieldExtractor().extract(source)
        val partial = !complete || extracted.truncated
        val validator = EvidenceValidator()
        fun fact(value: LabelledField): ChatDocumentFact? = if (validator.validate(value.evidence, source).isValid) {
            ChatDocumentFact(value.key, value.rawValue, value.evidence)
        } else null

        if (summary) {
            val candidates = extracted.candidates.mapNotNull(::fact)
            val unique = candidates.distinctBy { it.key to it.rawValue }
            if (unique.isEmpty()) return if (partial) DocumentChatAnswer.Incomplete else DocumentChatAnswer.Missing
            val conflicts = unique.groupBy { it.key }.values.any { it.size > 1 }
            return DocumentChatAnswer.Facts(unique.take(MAX_SUMMARY_FACTS), summary = true,
                partial = partial || unique.size > MAX_SUMMARY_FACTS, hasConflicts = conflicts)
        }

        // A later unread page may contain a conflicting value. Never call an
        // observed value unique if the pinned revision has incomplete evidence.
        if (partial) return DocumentChatAnswer.Incomplete
        return when (val resolved = extracted.resolve(requireNotNull(field))) {
            FieldResolution.Missing -> DocumentChatAnswer.Missing
            is FieldResolution.Unique -> fact(resolved.field)?.let {
                DocumentChatAnswer.Facts(listOf(it), summary = false, partial = false)
            } ?: DocumentChatAnswer.Unsupported
            is FieldResolution.Conflict -> {
                val facts = resolved.candidates.mapNotNull(::fact).distinctBy { it.rawValue }
                if (facts.size < 2) DocumentChatAnswer.Unsupported else DocumentChatAnswer.Conflict(facts)
            }
        }
    }

    private fun fieldQuestion(question: String): FieldKey? {
        val subject = subject(question) ?: return null
        return questions[subject]
    }

    private fun ambiguousQuestion(question: String): Boolean = subject(question) in ambiguousSubjects

    private fun subject(question: String): String? {
        var subject = question
        if (subject.startsWith("please ")) subject = subject.removePrefix("please ")
        prefixes.firstOrNull(subject::startsWith)?.let { subject = subject.removePrefix(it) }
        if (subject.startsWith("the ")) subject = subject.removePrefix("the ")
        else if (subject.startsWith("my ")) subject = subject.removePrefix("my ")
        else if (subject.startsWith("this ")) subject = subject.removePrefix("this ")
        if (subject.startsWith("amount of the ")) subject = subject.removePrefix("amount of the ")
        else if (subject.startsWith("amount of ")) subject = subject.removePrefix("amount of ")
        suffix.find(subject)?.let { subject = subject.substring(0, it.range.first) }
        if (subject.endsWith(" please")) subject = subject.removeSuffix(" please")
        return subject.takeIf { it.isNotBlank() }
    }

    private fun normalize(question: String): String? {
        if (question.isBlank() || question.length > LocalChatPrompt.MAX_QUESTION_UTF16 ||
            question.any { it.isISOControl() && !it.isWhitespace() }) return null
        return question.trim().lowercase(Locale.ROOT).replace('\u2019', '\'')
            .trimEnd('?', '!', '.').replace(Regex("\\s+"), " ")
    }

    companion object {
        const val MAX_SUMMARY_FACTS = 8
        private val prefixes = listOf("how much is ", "how much was ", "what is ", "what are ", "what's ",
            "show me ", "tell me ", "give me ", "when does ", "when is ", "who is ", "show ", "find ", "read ")
        private val suffix = Regex(" (?:in|on|for|of|from) (?:this|the|my|selected) (?:document|policy|invoice)(?: please)?$")
        private val ambiguousSubjects = setOf("amount", "premium", "premium amount", "policy premium", "insurance premium",
            "policy premium amount", "policy amount", "insurance amount", "coverage amount", "total amount", "price", "cost")
        private val summaryQuestion = Regex("(?:please )?(?:summari[sz]e(?: document facts| (?:this|the|my|selected)(?: document| policy| invoice)?)?|(?:give|show) me (?:a |the )?(?:summary|key facts)(?: of (?:this|the|my|selected) (?:document|policy|invoice))?|summary(?: of (?:this|the|my|selected) (?:document|policy|invoice))?|key facts)")
        private val questions = mapOf(
            "policy number" to FieldKey.POLICY_NUMBER, "policy no" to FieldKey.POLICY_NUMBER,
            "vehicle registration" to FieldKey.VEHICLE_REGISTRATION, "registration number" to FieldKey.VEHICLE_REGISTRATION,
            "policy holder" to FieldKey.POLICY_HOLDER, "policyholder" to FieldKey.POLICY_HOLDER,
            "policy holder name" to FieldKey.POLICY_HOLDER, "policyholder name" to FieldKey.POLICY_HOLDER,
            "owner" to FieldKey.OWNER, "owner name" to FieldKey.OWNER,
            "coverage start" to FieldKey.COVERAGE_START, "coverage start date" to FieldKey.COVERAGE_START,
            "policy start date" to FieldKey.COVERAGE_START,
            "coverage end" to FieldKey.COVERAGE_END, "coverage end date" to FieldKey.COVERAGE_END,
            "policy end date" to FieldKey.COVERAGE_END, "expiry date" to FieldKey.COVERAGE_END,
            "expiration date" to FieldKey.COVERAGE_END, "policy expiry" to FieldKey.COVERAGE_END,
            "policy expire" to FieldKey.COVERAGE_END, "policy expires" to FieldKey.COVERAGE_END,
            "total premium" to FieldKey.PREMIUM_TOTAL, "premium total" to FieldKey.PREMIUM_TOTAL,
            "pre-tax premium" to FieldKey.PREMIUM_BEFORE_TAX, "premium before tax" to FieldKey.PREMIUM_BEFORE_TAX,
            "tax" to FieldKey.TAX, "tax amount" to FieldKey.TAX,
            "insured value" to FieldKey.INSURED_VALUE, "insured declared value" to FieldKey.INSURED_VALUE,
            "idv" to FieldKey.INSURED_VALUE, "sum insured" to FieldKey.SUM_INSURED,
            "deductible" to FieldKey.DEDUCTIBLE, "invoice number" to FieldKey.INVOICE_NUMBER,
            "invoice no" to FieldKey.INVOICE_NUMBER, "invoice total" to FieldKey.INVOICE_TOTAL,
            "invoice subtotal" to FieldKey.INVOICE_SUBTOTAL, "invoice date" to FieldKey.INVOICE_DATE,
            "total" to FieldKey.TOTAL, "subtotal" to FieldKey.SUBTOTAL,
        )
    }
}
