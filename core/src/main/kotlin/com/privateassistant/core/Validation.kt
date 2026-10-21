package com.privateassistant.core

data class ValidationIssue(val code: String, val message: String)

class ValidationReport(issues: List<ValidationIssue>) {
    val issues: List<ValidationIssue> = frozenList(issues)
    val isValid: Boolean get() = issues.isEmpty()

    companion object {
        fun valid(): ValidationReport = ValidationReport(emptyList())
    }
}

/** Device profiles may lower the file ceilings; they cannot raise the V1 safety bounds. */
data class ResourceLimits(
    val maxImportBytes: Long = 25L * 1024 * 1024,
    val maxPagesPerDocument: Int = 50,
    val maxSourceImagePixels: Long = 20_000_000,
    val maxRenderedPagePixels: Long = 4_000_000,
    val maxConcurrentHeavyJobs: Int = 1,
    val maxDocumentsPerAction: Int = 8,
    val maxTextCharactersPerAction: Int = 250_000,
    val maxQuestionCharacters: Int = 4_000,
) {
    init {
        require(maxImportBytes in 1..25L * 1024 * 1024)
        require(maxPagesPerDocument in 1..50)
        require(maxSourceImagePixels in 1..20_000_000L)
        require(maxRenderedPagePixels in 1..4_000_000L)
        require(maxConcurrentHeavyJobs == 1) { "Only one heavy job may run at a time" }
        require(maxDocumentsPerAction > 0)
        require(maxTextCharactersPerAction > 0)
        require(maxQuestionCharacters > 0)
    }
}

data class ImportRequest(
    val displayName: String,
    val declaredMimeType: String?,
    val byteCount: Long,
    val pageCount: Int? = null,
    val imageSize: PixelSize? = null,
)

class ImportValidator(private val limits: ResourceLimits = ResourceLimits()) {
    /** Validate again with a parsed page count before storing a completed revision. */
    fun validate(request: ImportRequest): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (request.displayName.isBlank() || request.displayName.length > 255) {
            issues += ValidationIssue("invalid_name", "Choose a name between 1 and 255 characters.")
        }
        if (DocumentMimeType.fromWireValue(request.declaredMimeType) == null) {
            issues += ValidationIssue("unsupported_mime_type", "This document type is not supported.")
        }
        if (request.byteCount <= 0 || request.byteCount > limits.maxImportBytes) {
            issues += ValidationIssue("import_size", "The document must fit the configured byte limit.")
        }
        if (request.pageCount != null && request.pageCount !in 1..limits.maxPagesPerDocument) {
            issues += ValidationIssue("page_limit", "The document must fit the configured page limit.")
        }
        if (request.imageSize != null && request.imageSize.pixelCount > limits.maxSourceImagePixels) {
            issues += ValidationIssue("source_pixel_limit", "The source image exceeds the configured pixel limit.")
        }
        return ValidationReport(issues)
    }
}
