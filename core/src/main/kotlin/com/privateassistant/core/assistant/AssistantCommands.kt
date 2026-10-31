package com.privateassistant.core.assistant

import com.privateassistant.core.identity.IdentifierKind
import java.text.Normalizer
import java.util.Locale

/** What the user's own words describe. Terms only rank candidates; they never select a file alone. */
data class DocumentHint(
    val terms: List<String>,
    val identifier: IdentifierKind? = null,
    /** "this", "it": refer to the document the user currently has in focus, if any. */
    val deictic: Boolean = false,
)

enum class PhotoSpec(val widthMm: Int, val heightMm: Int, val widthPx: Int, val heightPx: Int) {
    /** India/UK/EU/most of Asia: 35 x 45 mm at 300 DPI. */
    PASSPORT_35X45(35, 45, 413, 531),
    /** United States passport/visa: 2 x 2 inch at 300 DPI. */
    US_2X2(51, 51, 600, 600),
}

/**
 * Inert interpretation of the current user's typed request. A command is a proposal shown to the
 * user; executing it still requires a direct tap on an app-owned confirmation control.
 */
sealed interface AssistantCommand {
    data class LookupIdentifier(val kind: IdentifierKind) : AssistantCommand
    data class FindDocument(val hint: DocumentHint) : AssistantCommand
    data class CompressToSize(val targetBytes: Long, val hint: DocumentHint) : AssistantCommand
    data class ConvertToImage(val hint: DocumentHint) : AssistantCommand
    data class ConvertToPdf(val hint: DocumentHint) : AssistantCommand
    data class PassportPhoto(val spec: PhotoSpec, val hint: DocumentHint) : AssistantCommand
}

/** Deterministic English rules for the user's own message. Not an evaluated intent classifier. */
object AssistantCommandParser {
    fun parse(input: String): AssistantCommand? {
        if (input.isBlank() || input.length > LocalChatPrompt.MAX_QUESTION_UTF16) return null
        val text = normalize(input)
        // Deleting, emailing or sending to someone stays with the existing direct controls.
        if (MUTATE_OR_SEND.containsMatchIn(text) || SEND_TO_RECIPIENT.containsMatchIn(text)) return null
        val identifier = identifierKind(text)

        if (PASSPORT_PHOTO.containsMatchIn(text)) {
            val spec = if (US_SIZE.containsMatchIn(text)) PhotoSpec.US_2X2 else PhotoSpec.PASSPORT_35X45
            return AssistantCommand.PassportPhoto(spec, hint(text, null, photo = true))
        }
        targetBytes(text)?.let { bytes ->
            if (COMPRESS_VERB.containsMatchIn(text) || SIZE_LIMIT.containsMatchIn(text)) {
                return AssistantCommand.CompressToSize(bytes, hint(text, identifier))
            }
        }
        val toImage = TO_IMAGE.containsMatchIn(text)
        val toPdf = TO_PDF.containsMatchIn(text)
        if (toImage && !toPdf) return AssistantCommand.ConvertToImage(hint(text, identifier))
        if (toPdf && !toImage) return AssistantCommand.ConvertToPdf(hint(text, identifier))
        if (toImage && toPdf) {
            // "pdf to jpg" vs "image to pdf": the format after the last "to/into/as" wins.
            val last = FORMAT_TARGET.findAll(text).lastOrNull()?.groupValues?.get(1)
            return if (last == "pdf") AssistantCommand.ConvertToPdf(hint(text, identifier))
            else AssistantCommand.ConvertToImage(hint(text, identifier))
        }
        if (identifier != null) {
            val wantsDocument = (DOCUMENT_WORD.containsMatchIn(text) || DOC_VERB.containsMatchIn(text)) &&
                !NUMBER_WORD.containsMatchIn(text)
            return if (wantsDocument) AssistantCommand.FindDocument(hint(text, identifier))
            else AssistantCommand.LookupIdentifier(identifier)
        }
        if (FIND_VERB.containsMatchIn(text) && !QUESTION.containsMatchIn(text) &&
            (PERSONAL.containsMatchIn(text) || DOCUMENT_WORD.containsMatchIn(text))) {
            val hint = hint(text, null)
            if (hint.terms.isNotEmpty()) return AssistantCommand.FindDocument(hint)
        }
        return null
    }

    fun identifierKind(question: String): IdentifierKind? {
        val text = normalize(question)
        if (PASSPORT_PHOTO.containsMatchIn(text)) return null
        return IDENTIFIER_WORDS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }?.first
    }

    /** Decimal kilobytes/megabytes: a 200 KB target also satisfies portals that count 1024-byte KB. */
    fun targetBytes(text: String): Long? {
        val match = SIZE.find(normalize(text)) ?: return null
        val amount = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val unit = if (match.groupValues[2].startsWith("m")) 1_000_000L else 1_000L
        val bytes = amount.multiply(unit.toBigDecimal()).toLong()
        return bytes.takeIf { it in MIN_TARGET_BYTES..MAX_TARGET_BYTES }
    }

    private fun hint(text: String, identifier: IdentifierKind?, photo: Boolean = false): DocumentHint {
        val deictic = DEICTIC.containsMatchIn(text)
        val terms = WORD.findAll(text).map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS && it.none(Char::isDigit) }
            .distinct().take(8).toList()
        return DocumentHint(if (photo) (terms + "photo").distinct() else terms, identifier, deictic)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ").trim()

    const val MIN_TARGET_BYTES = 10_000L
    const val MAX_TARGET_BYTES = 25_000_000L

    private val MUTATE_OR_SEND = Regex("^(?:please )?(?:(?:can|could|would|will) you )?" +
        "(?:email|e-mail|delete|remove|erase|wipe|rename|move)\\b")
    private val SEND_TO_RECIPIENT = Regex("\\b(?:send|forward|share|email|mail)\\b.*\\bto (?!(?:an? )?" +
        "(?:pdf|jpe?g|png|image|photo|picture|pic)s?\\b)[\\p{L}\\p{N}@]")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val SIZE = Regex("(\\d{1,5}(?:\\.\\d{1,2})?)\\s?(kb|kbs|kilobytes?|k|mb|mbs|megabytes?)\\b")
    private val SIZE_LIMIT = Regex("\\b(?:under|below|less than|lesser than|smaller than|within|up ?to|max(?:imum)?|" +
        "not more than|no more than|at most|lower than|less then)\\b|<")
    private val COMPRESS_VERB = Regex("\\b(?:compress|reduce|shrink|resize|smaller|optimi[sz]e|minimi[sz]e|decrease|size)\\b")
    private val PASSPORT_PHOTO = Regex("passport[- ]?(?:size|sized)\\b|passport[- ](?:photo|photograph|pic)s?\\b|" +
        "\\b2 ?x ?2 ?(?:inch )?(?:photo|picture)")
    private val US_SIZE = Regex("\\b2 ?x ?2\\b|\\bus\\b|american|51 ?x ?51")
    private val TO_IMAGE = Regex("\\b(?:in|as|into|to|an?)\\s(?:an? )?(?:image|jpe?g|png|photo|picture|pic)s?\\b(?! to)|" +
        "\\b(?:image|jpe?g|png|photo|picture) (?:format|file|version|copy)\\b|\\bpdf ?(?:to|2) ?(?:image|jpe?g|png)\\b")
    private val TO_PDF = Regex("\\b(?:in|as|into|to|a)\\s(?:a )?pdf\\b(?! to)|\\bpdf (?:format|file|version|copy)\\b|" +
        "\\b(?:image|jpe?g|png|photo|picture|scan)s? ?(?:to|2) ?pdf\\b|\\bmake (?:a |it )?pdf\\b")
    private val FORMAT_TARGET = Regex("\\b(?:to|into|as|in|2)\\s(?:an? )?(pdf|image|jpe?g|png|photo|picture)\\b")
    private val DOCUMENT_WORD = Regex("\\b(?:card|document|doc|copy|file|pdf|scan|certificate|photo|image|picture)s?\\b")
    private val NUMBER_WORD = Regex("\\b(?:number|no|num|code|what(?:'s| is))\\b")
    private val DOC_VERB = Regex("\\b(?:show|open|view|display)\\b")
    private val QUESTION = Regex("\\b(?:what|how|when|why|which|who|does|did|do|is there|are there|tell|explain|" +
        "summari[sz]e|summary)\\b")
    private val FIND_VERB = Regex("\\b(?:show|open|view|find|get|fetch|where(?:'s| is))\\b")
    private val PERSONAL = Regex("\\b(?:my|our|mine)\\b")
    private val DEICTIC = Regex("\\b(?:this|it|that|current|selected)\\b")

    private val IDENTIFIER_WORDS: List<Pair<IdentifierKind, Regex>> = listOf(
        IdentifierKind.GSTIN to Regex("\\bgst(?:in| number| no)?\\b"),
        IdentifierKind.PAN to Regex("\\bpan\\b|permanent account number"),
        IdentifierKind.AADHAAR to Regex("\\ba+dh?a+r\\b|\\baadhaar\\b|\\badhaar\\b|\\buid(?:ai)?\\b|आधार"),
        IdentifierKind.PASSPORT to Regex("\\bpassport\\b"),
        IdentifierKind.VOTER_ID to Regex("\\bvoter|\\bepic\\b|election card"),
        IdentifierKind.DRIVING_LICENCE to Regex("driving licen[cs]e|driver'?s? licen[cs]e|\\bdl\\b|\\blicen[cs]e number"),
        IdentifierKind.IFSC to Regex("\\bifsc?\\b"),
        IdentifierKind.VEHICLE_REGISTRATION to Regex("vehicle (?:number|no|registration)|registration number|number plate|\\bcar number|\\bbike number"),
        IdentifierKind.UAN to Regex("\\buan\\b"),
        IdentifierKind.BANK_ACCOUNT to Regex("\\b(?:bank )?(?:account|a/c) (?:number|no)\\b|\\bbank account\\b"),
        IdentifierKind.EMAIL to Regex("\\be-?mail\\b"),
        IdentifierKind.MOBILE to Regex("\\bmobile\\b|\\bphone (?:number|no)\\b|\\bcontact number\\b"),
        IdentifierKind.DATE_OF_BIRTH to Regex("date of birth|\\bdob\\b|birth ?date|birthday|\\bborn\\b"),
    )

    private val STOP_WORDS = setOf(
        "a", "an", "the", "my", "me", "mine", "our", "i", "want", "need", "please", "can", "could", "would", "you",
        "give", "get", "make", "create", "show", "open", "view", "find", "where", "display", "fetch", "bring", "send", "this", "that", "it", "its",
        "of", "in", "into", "to", "as", "for", "with", "and", "or", "is", "are", "be", "what", "where", "whats",
        "compress", "reduce", "shrink", "resize", "smaller", "size", "sized", "less", "than", "under", "below",
        "within", "upto", "up", "max", "maximum", "kb", "kbs", "mb", "mbs", "k", "format", "file", "version",
        "copy", "convert", "image", "images", "jpg", "jpeg", "png", "pdf", "picture", "pic", "document", "doc",
        "number", "no", "card", "passport", "photograph", "photos", "us", "inch", "help", "do", "from", "on",
        "current", "selected", "now", "just", "only", "so", "then", "lower", "not", "more", "at", "most",
        "optimise", "optimize", "minimise", "minimize", "decrease", "scan", "scanned",
    )
}

/** Trusted descriptor built by the app from the unlocked vault; never from model output. */
data class DocumentDescriptor(
    val id: String,
    val name: String,
    val mimeType: String,
    val folderName: String? = null,
    val identifiers: Set<IdentifierKind> = emptySet(),
)

enum class SourceRequirement { ANY, IMAGE, PDF, IMAGE_OR_PDF }

sealed interface DocumentResolution {
    data class Single(val id: String) : DocumentResolution
    /** The user must pick; candidates are ranked and bounded. */
    data class Choose(val ids: List<String>) : DocumentResolution
    data object NoCompatible : DocumentResolution
}

object DocumentResolver {
    const val MAX_CHOICES = 6

    fun resolve(
        hint: DocumentHint,
        documents: List<DocumentDescriptor>,
        requirement: SourceRequirement,
        focusedId: String? = null,
    ): DocumentResolution {
        val compatible = documents.filter { compatible(it.mimeType, requirement) }
        if (compatible.isEmpty()) return DocumentResolution.NoCompatible
        if (hint.deictic && focusedId != null && compatible.any { it.id == focusedId }) return DocumentResolution.Single(focusedId)
        val scored = compatible.map { it to score(hint, it) }
        val best = scored.maxOf { it.second }
        if (best <= 0) {
            return if (compatible.size == 1) DocumentResolution.Single(compatible.single().id)
            else DocumentResolution.Choose(compatible.take(MAX_CHOICES).map { it.id })
        }
        val top = scored.filter { it.second == best }
        return if (top.size == 1) DocumentResolution.Single(top.single().first.id)
        else DocumentResolution.Choose(top.take(MAX_CHOICES).map { it.first.id })
    }

    fun compatible(mimeType: String, requirement: SourceRequirement): Boolean = when (requirement) {
        SourceRequirement.ANY -> true
        SourceRequirement.IMAGE -> mimeType == "image/jpeg" || mimeType == "image/png"
        SourceRequirement.PDF -> mimeType == "application/pdf"
        SourceRequirement.IMAGE_OR_PDF -> mimeType == "image/jpeg" || mimeType == "image/png" || mimeType == "application/pdf"
    }

    private fun score(hint: DocumentHint, document: DocumentDescriptor): Int {
        val name = tokens(document.name) + tokens(document.folderName.orEmpty())
        var score = 0
        for (term in hint.terms) {
            if (term in name) score += 3
            else if (term.length >= 4 && name.any { it.startsWith(term) || term.startsWith(it) && it.length >= 4 }) score += 2
        }
        hint.identifier?.let { kind ->
            if (kind in document.identifiers) score += 4
            if (NAME_WORDS[kind].orEmpty().any { it in name }) score += 4
        }
        return score
    }

    private fun tokens(value: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(value.lowercase(Locale.ROOT)).map { it.value }.toSet()

    private val NAME_WORDS = mapOf(
        IdentifierKind.PAN to setOf("pan", "pancard"),
        IdentifierKind.AADHAAR to setOf("aadhaar", "aadhar", "adhar", "adhaar", "uid", "eaadhaar"),
        IdentifierKind.PASSPORT to setOf("passport"),
        IdentifierKind.VOTER_ID to setOf("voter", "epic", "election"),
        IdentifierKind.DRIVING_LICENCE to setOf("dl", "licence", "license", "driving"),
        IdentifierKind.GSTIN to setOf("gst", "gstin"),
        IdentifierKind.VEHICLE_REGISTRATION to setOf("rc", "registration", "vehicle"),
        IdentifierKind.BANK_ACCOUNT to setOf("bank", "passbook", "cheque", "statement"),
    )
}
