package com.privateassistant.core

/** Model suggestions are inert values. A separate executor validates before performing an action. */
sealed class AssistantAction(targets: List<RevisionRef>) {
    val targets: List<RevisionRef> = frozenList(targets)

    class Search(val query: String) : AssistantAction(emptyList())
    class GetOriginal(val source: RevisionRef) : AssistantAction(listOf(source))
    class AskDocument(targets: List<RevisionRef>, val question: String) : AssistantAction(targets)
    class Summarize(targets: List<RevisionRef>) : AssistantAction(targets)

    class PdfToImages(val source: RevisionRef, pages: List<PageRender>) : AssistantAction(listOf(source)) {
        val pages: List<PageRender> = frozenList(pages)
    }

    /** The list is the explicit user-chosen output order; it is never implicitly sorted. */
    class ImagesToPdf(orderedImages: List<RevisionRef>) : AssistantAction(orderedImages)

    class CompressImage(val source: RevisionRef, val targetBytes: Long, val quality: Int) : AssistantAction(listOf(source))

    /** Rasterizing a PDF requires independent current user consent in the validation context. */
    class CompressImagePdf(
        val source: RevisionRef,
        val targetBytes: Long,
        pages: List<PageRender>,
    ) : AssistantAction(listOf(source)) {
        val pages: List<PageRender> = frozenList(pages)
    }

    class CropImage(val source: RevisionRef, val bounds: CropBounds) : AssistantAction(listOf(source))
    class RotateImage(val source: RevisionRef, val degreesClockwise: Int) : AssistantAction(listOf(source))
    class Clarify(val question: String) : AssistantAction(emptyList())
}

data class PageRender(val pageNumber: Int, val outputSize: PixelSize)
data class CropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Trusted metadata read from the vault/parser, not metadata supplied by a model. */
data class ActionSource(
    val ref: RevisionRef,
    val mimeType: DocumentMimeType,
    val pageCount: Int,
    val imageSize: PixelSize? = null,
    val text: RevisionText? = null,
) {
    init {
        require(pageCount > 0)
        require(text == null || text.ref == ref)
        require(text == null || text.pages.all { it.pageNumber <= pageCount })
    }
}

/** Follow ups keep explicit source revisions even after a newer revision is imported. */
class FollowUpContext(pinnedRevisions: List<RevisionRef>) {
    val pinnedRevisions: List<RevisionRef> = frozenList(pinnedRevisions)

    init {
        require(this.pinnedRevisions.isNotEmpty())
        require(this.pinnedRevisions.distinct().size == this.pinnedRevisions.size)
    }

    fun question(text: String): AssistantAction.AskDocument = AssistantAction.AskDocument(pinnedRevisions, text)
}

class ActionValidator(private val limits: ResourceLimits = ResourceLimits()) {
    /** Convenience for already extracted read sources. File actions need [validateResources]. */
    fun validate(
        action: AssistantAction,
        selectedRevisions: Set<RevisionRef>,
        availableSources: Map<RevisionRef, RevisionText>,
    ): ValidationReport = validateResources(
        action,
        selectedRevisions,
        availableSources.mapValues { (_, text) ->
            ActionSource(text.ref, DocumentMimeType.PLAIN_TEXT, text.pages.maxOf { it.pageNumber }, text = text)
        },
    )

    /**
     * Inputs are trusted session snapshots. Consent comes from the current UI loss disclosure,
     * never a model-produced flag, and callers must discard it when the selection/session ends.
     * The executor must also recheck the vault session before reading or publishing results.
     */
    fun validateResources(
        action: AssistantAction,
        selectedRevisions: Set<RevisionRef>,
        availableSources: Map<RevisionRef, ActionSource>,
        rasterizationConsents: Set<RevisionRef> = emptySet(),
    ): ValidationReport {
        val selectedSnapshot = selectedRevisions.toSet()
        val sourceSnapshot = availableSources.toMap()
        val consentSnapshot = rasterizationConsents.toSet()
        val issues = mutableListOf<ValidationIssue>()
        fun reject(code: String, message: String) { issues += ValidationIssue(code, message) }
        val needsTargets = action !is AssistantAction.Search && action !is AssistantAction.Clarify
        if ((needsTargets && action.targets.isEmpty()) || action.targets.size > limits.maxDocumentsPerAction) {
            reject("action_target_count", "Select a supported number of document revisions.")
        }
        if (action.targets.distinct().size != action.targets.size) reject("duplicate_target", "Select each revision once.")
        if (action.targets.any { it !in selectedSnapshot }) reject("unselected_revision", "Use explicitly selected revisions only.")
        if (action.targets.any { sourceSnapshot[it]?.ref != it }) reject("unavailable_revision", "The pinned revision is unavailable.")

        val sources = action.targets.mapNotNull { ref -> sourceSnapshot[ref]?.takeIf { source -> source.ref == ref } }
        sources.forEach { source ->
            if (source.mimeType == DocumentMimeType.PDF && source.pageCount > limits.maxPagesPerDocument) {
                reject("page_limit", "The PDF exceeds the configured page limit.")
            }
            if (source.imageSize != null && source.imageSize.pixelCount > limits.maxSourceImagePixels) {
                reject("source_pixel_limit", "The source image exceeds the configured pixel limit.")
            }
        }
        fun validateQuestion(text: String) {
            if (text.isBlank() || text.length > limits.maxQuestionCharacters) reject("invalid_question", "Enter text within the configured limit.")
        }
        fun requireType(pdf: Boolean) {
            if (sources.any { if (pdf) it.mimeType != DocumentMimeType.PDF else !it.mimeType.isImage() }) {
                reject("action_mime_type", "The selected source type is incompatible with this operation.")
            }
            if (!pdf && sources.any { it.mimeType.isImage() && it.imageSize == null }) {
                reject("missing_image_geometry", "Read the source image dimensions before transforming it.")
            }
        }
        fun validateRenders(pages: List<PageRender>, allPages: Boolean = false) {
            val count = sources.singleOrNull()?.pageCount ?: 0
            if (pages.isEmpty() || pages.size > limits.maxPagesPerDocument ||
                pages.map { it.pageNumber }.distinct().size != pages.size ||
                pages.any { it.pageNumber !in 1..count } ||
                pages.zipWithNext().any { (first, second) -> first.pageNumber >= second.pageNumber } ||
                (allPages && (pages.size != count || pages.withIndex().any { (index, page) -> page.pageNumber != index + 1 }))
            ) reject("invalid_page_selection", "Choose valid unique PDF pages in source order.")
            if (pages.any { it.outputSize.pixelCount > limits.maxRenderedPagePixels }) {
                reject("render_pixel_limit", "A rendered page exceeds the configured pixel limit.")
            }
        }
        fun validateTargetBytes(bytes: Long) {
            if (bytes <= 0 || bytes > limits.maxImportBytes) reject("invalid_compression_target", "Choose a positive bounded target size.")
        }
        when (action) {
            is AssistantAction.Search -> validateQuestion(action.query)
            is AssistantAction.Clarify -> validateQuestion(action.question)
            is AssistantAction.GetOriginal -> Unit
            is AssistantAction.AskDocument, is AssistantAction.Summarize -> {
                if (action is AssistantAction.AskDocument) validateQuestion(action.question)
                if (sources.any { it.text == null || it.text.pages.all { page -> page.text.isBlank() } }) {
                    reject("missing_source_text", "Analysis requires extracted source text.")
                }
                if (action is AssistantAction.Summarize && sources.any { it.text?.pages?.size != it.pageCount }) {
                    reject("incomplete_source_text", "A complete summary requires extraction coverage for every page.")
                }
                val characters = sources.sumOf { source -> source.text?.pages?.sumOf { it.text.length.toLong() } ?: 0L }
                if (characters > limits.maxTextCharactersPerAction) reject("action_text_limit", "The selected text exceeds the configured limit.")
            }
            is AssistantAction.PdfToImages -> { requireType(pdf = true); validateRenders(action.pages) }
            is AssistantAction.ImagesToPdf -> requireType(pdf = false)
            is AssistantAction.CompressImage -> {
                requireType(pdf = false)
                validateTargetBytes(action.targetBytes)
                if (action.quality !in 1..100) reject("invalid_image_quality", "Image quality must be between 1 and 100.")
            }
            is AssistantAction.CompressImagePdf -> {
                requireType(pdf = true)
                validateTargetBytes(action.targetBytes)
                validateRenders(action.pages, allPages = true)
                if (action.source !in consentSnapshot) reject("rasterization_consent", "Accept the disclosed PDF rasterization losses first.")
            }
            is AssistantAction.CropImage -> {
                requireType(pdf = false)
                val size = sources.singleOrNull()?.imageSize
                val crop = action.bounds
                if (size == null || crop.left < 0 || crop.top < 0 || crop.right <= crop.left || crop.bottom <= crop.top ||
                    crop.right > size.width || crop.bottom > size.height
                ) reject("invalid_crop", "The crop must be a nonempty region within the source image.")
            }
            is AssistantAction.RotateImage -> {
                requireType(pdf = false)
                if (action.degreesClockwise !in setOf(90, 180, 270)) reject("invalid_rotation", "Choose a quarter-turn rotation.")
            }
        }
        return ValidationReport(issues)
    }
}

private fun DocumentMimeType.isImage(): Boolean = this == DocumentMimeType.JPEG || this == DocumentMimeType.PNG
