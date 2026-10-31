package com.privateassistant.app.ui

import androidx.annotation.StringRes
import com.privateassistant.app.R
import com.privateassistant.core.assistant.PhotoSpec
import com.privateassistant.core.identity.IdentifierKind
import com.privateassistant.platform.vault.VaultDocument

/** Closed set of local file tools the assistant can propose. The user confirms each run. */
enum class ToolKind { COMPRESS, TO_IMAGE, TO_PDF, PASSPORT_PHOTO }

class AssistantToolRequest(
    val kind: ToolKind,
    val document: VaultDocument,
    val targetBytes: Long = 0,
    val spec: PhotoSpec = PhotoSpec.PASSPORT_35X45,
) {
    override fun toString(): String = "AssistantToolRequest(kind=$kind)"
}

sealed interface AssistantToolOutcome {
    class Created(val documents: List<VaultDocument>, val faceFound: Boolean? = null) : AssistantToolOutcome
    data class AlreadyFits(val bytes: Long) : AssistantToolOutcome
    data class Unreachable(val smallestBytes: Long) : AssistantToolOutcome
}

/**
 * Runs a confirmed tool against an authorized pinned revision and stores outputs as new encrypted
 * documents. Implementations never receive model text; requests come from app-owned controls.
 */
interface AssistantToolHost {
    suspend fun run(request: AssistantToolRequest, cancelled: () -> Boolean): AssistantToolOutcome
    /** Publishes the refreshed document list after this chat created new files. */
    suspend fun documentsCreated()
    fun folderName(folderId: String?): String?
}

enum class ActionStatus { CHOOSE, PROPOSED, RUNNING, DONE, CANCELLED, FAILED }

class AssistantActionUi(
    val tool: ToolKind,
    val status: ActionStatus,
    val document: VaultDocument?,
    val choices: List<VaultDocument> = emptyList(),
    val targetBytes: Long = 0,
    val spec: PhotoSpec = PhotoSpec.PASSPORT_35X45,
) {
    fun with(status: ActionStatus, document: VaultDocument? = this.document) =
        AssistantActionUi(tool, status, document, if (status == ActionStatus.CHOOSE) choices else emptyList(), targetBytes, spec)

    override fun toString(): String = "AssistantActionUi(tool=$tool, status=$status)"
}

/** A printed identifier with its exact cited source. Values live only in unlocked memory. */
class IdentifierAnswerUi(
    val kind: IdentifierKind,
    val value: String,
    val masked: Boolean,
    val document: VaultDocument,
    val pageNumber: Int,
    val contextLine: String,
) {
    override fun toString(): String = "IdentifierAnswerUi(kind=$kind)"
}

@StringRes
internal fun identifierLabel(kind: IdentifierKind): Int = when (kind) {
    IdentifierKind.PAN -> R.string.id_pan
    IdentifierKind.AADHAAR -> R.string.id_aadhaar
    IdentifierKind.PASSPORT -> R.string.id_passport
    IdentifierKind.VOTER_ID -> R.string.id_voter
    IdentifierKind.DRIVING_LICENCE -> R.string.id_driving_licence
    IdentifierKind.GSTIN -> R.string.id_gstin
    IdentifierKind.IFSC -> R.string.id_ifsc
    IdentifierKind.VEHICLE_REGISTRATION -> R.string.id_vehicle
    IdentifierKind.BANK_ACCOUNT -> R.string.id_bank_account
    IdentifierKind.UAN -> R.string.id_uan
    IdentifierKind.EMAIL -> R.string.id_email
    IdentifierKind.MOBILE -> R.string.id_mobile
    IdentifierKind.DATE_OF_BIRTH -> R.string.id_dob
}

@StringRes
internal fun toolTitle(tool: ToolKind): Int = when (tool) {
    ToolKind.COMPRESS -> R.string.tool_compress_title
    ToolKind.TO_IMAGE -> R.string.tool_to_image_title
    ToolKind.TO_PDF -> R.string.tool_to_pdf_title
    ToolKind.PASSPORT_PHOTO -> R.string.tool_passport_title
}
