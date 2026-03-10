package io.makoion.mobileclaw.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class OrganizeExecutionStatus {
    Moved,
    DeleteConsentRequired,
    CopiedOnly,
    Failed,
}

data class OrganizeExecutionEntry(
    val fileId: String,
    val fileName: String,
    val sourceLabel: String,
    val destinationFolder: String,
    val status: OrganizeExecutionStatus,
    val detail: String,
)

data class DeleteConsentLaunch(
    val intentSender: IntentSender,
    val requestedCount: Int,
)

data class OrganizeExecutionResult(
    val processedCount: Int,
    val movedCount: Int,
    val copiedOnlyCount: Int,
    val deleteConsentRequiredCount: Int,
    val failedCount: Int,
    val verifiedCount: Int,
    val destinationLabel: String,
    val entries: List<OrganizeExecutionEntry>,
) {
    val summary: String
        get() = buildString {
            append("Organize execution finished for ")
            append(processedCount)
            append(" files. ")
            append(movedCount)
            append(" moved")
            if (deleteConsentRequiredCount > 0) {
                append(", ")
                append(deleteConsentRequiredCount)
                append(" awaiting delete consent")
            }
            if (copiedOnlyCount > 0) {
                append(", ")
                append(copiedOnlyCount)
                append(" copied-only")
            }
            if (failedCount > 0) {
                append(", ")
                append(failedCount)
                append(" failed")
            }
            append(". Verified ")
            append(verifiedCount)
            append(" destination copies")
            append(". Destination root: ")
            append(destinationLabel)
            append(".")
        }

    fun rebuild(entries: List<OrganizeExecutionEntry>): OrganizeExecutionResult {
        return copy(
            movedCount = entries.count { it.status == OrganizeExecutionStatus.Moved },
            copiedOnlyCount = entries.count { it.status == OrganizeExecutionStatus.CopiedOnly },
            deleteConsentRequiredCount = entries.count { it.status == OrganizeExecutionStatus.DeleteConsentRequired },
            failedCount = entries.count { it.status == OrganizeExecutionStatus.Failed },
            verifiedCount = entries.count { it.status != OrganizeExecutionStatus.Failed },
            entries = entries,
        )
    }
}

class FileActionExecutor(
    private val context: Context,
    private val auditTrailRepository: AuditTrailRepository,
) {
    suspend fun share(items: List<IndexedFileItem>) {
        val uris = items.mapNotNull(::shareUriFor)
        if (uris.isEmpty()) {
            auditTrailRepository.logAction(
                action = "files.share",
                result = "skipped",
                details = "No shareable file URIs were available for the current selection.",
            )
            return
        }

        withContext(Dispatchers.Main) {
            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = items.first().mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }.apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share files").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

        auditTrailRepository.logAction(
            action = "files.share",
            result = "launched",
            details = "Opened the Android share sheet for ${uris.size} files.",
        )
    }

    suspend fun executeApprovedOrganize(item: ApprovalInboxItem): OrganizeExecutionResult {
        val payloadRaw = item.payloadJson
            ?: throw IllegalStateException("No organize payload was stored for this approval request.")
        val payload = OrganizeApprovalPayload.fromJson(payloadRaw)
        return withContext(Dispatchers.IO) {
            var movedCount = 0
            var copiedOnlyCount = 0
            var deleteConsentRequiredCount = 0
            var failedCount = 0
            var verifiedCount = 0
            val entries = mutableListOf<OrganizeExecutionEntry>()

            payload.steps.forEach { step ->
                val sourceUri = shareUriFor(step.fileId)
                if (sourceUri == null) {
                    failedCount += 1
                    entries += OrganizeExecutionEntry(
                        fileId = step.fileId,
                        fileName = step.fileName,
                        sourceLabel = step.sourceLabel,
                        destinationFolder = step.destinationFolder,
                        status = OrganizeExecutionStatus.Failed,
                        detail = "Source URI could not be resolved for execution.",
                    )
                    return@forEach
                }

                val destinationUri = runCatching {
                    createManagedDestination(
                        fileName = step.fileName,
                        mimeType = step.mimeType,
                        destinationFolder = step.destinationFolder,
                    )
                }.getOrElse { error ->
                    failedCount += 1
                    entries += OrganizeExecutionEntry(
                        fileId = step.fileId,
                        fileName = step.fileName,
                        sourceLabel = step.sourceLabel,
                        destinationFolder = step.destinationFolder,
                        status = OrganizeExecutionStatus.Failed,
                        detail = "Managed destination could not be created: ${error.message}",
                    )
                    null
                }

                if (destinationUri == null) {
                    return@forEach
                }

                val copiedBytes = runCatching {
                    copyIntoManagedDestination(sourceUri, destinationUri)
                }.getOrElse {
                    context.contentResolver.delete(destinationUri, null, null)
                    null
                }

                if (copiedBytes == null) {
                    failedCount += 1
                    entries += OrganizeExecutionEntry(
                        fileId = step.fileId,
                        fileName = step.fileName,
                        sourceLabel = step.sourceLabel,
                        destinationFolder = step.destinationFolder,
                        status = OrganizeExecutionStatus.Failed,
                        detail = "Copy into the managed destination failed before verification.",
                    )
                    return@forEach
                }

                val verification = verifyManagedDestination(
                    sourceUri = sourceUri,
                    destinationUri = destinationUri,
                    copiedBytes = copiedBytes,
                )
                if (!verification.verified) {
                    context.contentResolver.delete(destinationUri, null, null)
                    failedCount += 1
                    entries += OrganizeExecutionEntry(
                        fileId = step.fileId,
                        fileName = step.fileName,
                        sourceLabel = step.sourceLabel,
                        destinationFolder = step.destinationFolder,
                        status = OrganizeExecutionStatus.Failed,
                        detail = verification.detail,
                    )
                    return@forEach
                }

                verifiedCount += 1

                when (deleteSourceBestEffort(step.fileId, sourceUri)) {
                    DeleteDisposition.Deleted -> {
                        movedCount += 1
                        entries += OrganizeExecutionEntry(
                            fileId = step.fileId,
                            fileName = step.fileName,
                            sourceLabel = step.sourceLabel,
                            destinationFolder = step.destinationFolder,
                            status = OrganizeExecutionStatus.Moved,
                            detail = "${verification.detail} Original source removed successfully.",
                        )
                    }
                    DeleteDisposition.PermissionRequired -> {
                        deleteConsentRequiredCount += 1
                        entries += OrganizeExecutionEntry(
                            fileId = step.fileId,
                            fileName = step.fileName,
                            sourceLabel = step.sourceLabel,
                            destinationFolder = step.destinationFolder,
                            status = OrganizeExecutionStatus.DeleteConsentRequired,
                            detail = "${verification.detail} Android still requires explicit delete consent for the original source.",
                        )
                    }
                    DeleteDisposition.Failed -> {
                        copiedOnlyCount += 1
                        entries += OrganizeExecutionEntry(
                            fileId = step.fileId,
                            fileName = step.fileName,
                            sourceLabel = step.sourceLabel,
                            destinationFolder = step.destinationFolder,
                            status = OrganizeExecutionStatus.CopiedOnly,
                            detail = "${verification.detail} Original source could not be removed automatically.",
                        )
                    }
                }
            }

            val result = OrganizeExecutionResult(
                processedCount = payload.steps.size,
                movedCount = movedCount,
                copiedOnlyCount = copiedOnlyCount,
                deleteConsentRequiredCount = deleteConsentRequiredCount,
                failedCount = failedCount,
                verifiedCount = verifiedCount,
                destinationLabel = managedDestinationLabel,
                entries = entries,
            )
            auditTrailRepository.logAction(
                action = "files.organize.execute",
                result = when {
                    failedCount > 0 -> "partial"
                    deleteConsentRequiredCount > 0 -> "delete_consent_required"
                    copiedOnlyCount > 0 -> "copied_only"
                    else -> "moved"
                },
                details = result.summary,
            )
            result
        }
    }

    suspend fun prepareDeleteConsent(result: OrganizeExecutionResult): DeleteConsentLaunch? {
        val entries = result.entries.filter { it.status == OrganizeExecutionStatus.DeleteConsentRequired }
        val uris = entries.mapNotNull { shareUriFor(it.fileId) }
            .distinct()
            .filter { isMediaStoreSource(it.toString(), it) }
        if (uris.isEmpty()) {
            return null
        }

        val intentSender = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            }
            uris.size == 1 -> {
                legacyRecoverableDeleteIntentSender(uris.first()) ?: return null
            }
            else -> return null
        }

        auditTrailRepository.logAction(
            action = "files.organize.delete_consent",
            result = "requested",
            details = "Requested Android delete consent for ${uris.size} original files after organize execution.",
        )
        return DeleteConsentLaunch(
            intentSender = intentSender,
            requestedCount = uris.size,
        )
    }

    suspend fun resolveDeleteConsent(
        result: OrganizeExecutionResult,
        granted: Boolean,
    ): OrganizeExecutionResult {
        if (!granted) {
            auditTrailRepository.logAction(
                action = "files.organize.delete_consent",
                result = "denied",
                details = "Delete consent was denied or cancelled. Original files remain in place.",
            )
            return result
        }

        val updatedEntries = result.entries.map { entry ->
            if (entry.status != OrganizeExecutionStatus.DeleteConsentRequired) {
                return@map entry
            }

            val sourceUri = shareUriFor(entry.fileId)
            if (sourceUri == null || !sourceExists(sourceUri)) {
                entry.copy(
                    status = OrganizeExecutionStatus.Moved,
                    detail = entry.detail.substringBefore(" Android still requires") +
                        " Delete consent completed and the original source was removed.",
                )
            } else {
                entry.copy(
                    status = OrganizeExecutionStatus.CopiedOnly,
                    detail = entry.detail.substringBefore(" Android still requires") +
                        " Delete consent completed but the original source is still present.",
                )
            }
        }

        val updatedResult = result.rebuild(updatedEntries)
        auditTrailRepository.logAction(
            action = "files.organize.delete_consent",
            result = if (updatedResult.deleteConsentRequiredCount == 0) "resolved" else "partial",
            details = updatedResult.summary,
        )
        return updatedResult
    }

    private fun shareUriFor(item: IndexedFileItem): Uri? = shareUriFor(item.id)

    private fun shareUriFor(fileId: String): Uri? {
        return when {
            fileId.startsWith("media-") -> {
                val mediaId = fileId.removePrefix("media-").toLongOrNull() ?: return null
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    mediaId,
                )
            }
            fileId.startsWith("content://") -> Uri.parse(fileId)
            else -> null
        }
    }

    private fun createManagedDestination(
        fileName: String,
        mimeType: String,
        destinationFolder: String,
    ): Uri {
        val (collectionUri, relativeRoot) = mediaCollectionFor(mimeType)
        val relativePath = "$relativeRoot/$managedDestinationLabel/$destinationFolder"
            .replace("//", "/")
        val destinationUri = context.contentResolver.insert(
            collectionUri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: throw IllegalStateException("Unable to create a managed destination entry.")
        return destinationUri
    }

    private fun copyIntoManagedDestination(
        sourceUri: Uri,
        destinationUri: Uri,
    ): Long? {
        val copiedBytes = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                input.copyTo(output)
            } ?: return null
        } ?: return null

        context.contentResolver.update(
            destinationUri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null,
        )
        return copiedBytes
    }

    private fun verifyManagedDestination(
        sourceUri: Uri,
        destinationUri: Uri,
        copiedBytes: Long,
    ): DestinationVerification {
        val sourceBytes = contentLengthFor(sourceUri)
        val destinationBytes = contentLengthFor(destinationUri)
        val destinationReadable = runCatching {
            context.contentResolver.openAssetFileDescriptor(destinationUri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        val verified = when {
            sourceBytes >= 0L && destinationBytes >= 0L -> sourceBytes == destinationBytes
            destinationBytes >= 0L -> destinationBytes == copiedBytes || copiedBytes == 0L
            else -> destinationReadable
        }
        val destinationLabel = when {
            destinationBytes >= 0L -> Formatter.formatShortFileSize(context, destinationBytes)
            copiedBytes >= 0L -> Formatter.formatShortFileSize(context, copiedBytes)
            else -> "unknown size"
        }
        return if (verified) {
            DestinationVerification(
                verified = true,
                detail = "Destination verified at $destinationLabel.",
            )
        } else {
            val sourceLabel = if (sourceBytes >= 0L) {
                Formatter.formatShortFileSize(context, sourceBytes)
            } else {
                "unknown source size"
            }
            DestinationVerification(
                verified = false,
                detail = "Destination verification failed (source $sourceLabel, copied $destinationLabel).",
            )
        }
    }

    private fun deleteSourceBestEffort(
        fileId: String,
        sourceUri: Uri,
    ): DeleteDisposition {
        return runCatching {
            DocumentFile.fromSingleUri(context, sourceUri)?.let { document ->
                if (document.delete()) {
                    return@runCatching DeleteDisposition.Deleted
                }
            }
            when {
                context.contentResolver.delete(sourceUri, null, null) > 0 -> DeleteDisposition.Deleted
                isMediaStoreSource(fileId, sourceUri) -> DeleteDisposition.PermissionRequired
                else -> DeleteDisposition.Failed
            }
        }.getOrElse { error ->
            when {
                error is SecurityException && isMediaStoreSource(fileId, sourceUri) ->
                    DeleteDisposition.PermissionRequired
                else -> DeleteDisposition.Failed
            }
        }
    }

    private fun legacyRecoverableDeleteIntentSender(sourceUri: Uri): IntentSender? {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) {
            return null
        }
        return runCatching {
            context.contentResolver.delete(sourceUri, null, null)
            null
        }.getOrElse { error ->
            if (error is RecoverableSecurityException) {
                error.userAction.actionIntent.intentSender
            } else {
                null
            }
        }
    }

    private fun sourceExists(sourceUri: Uri): Boolean {
        return runCatching {
            context.contentResolver.query(
                sourceUri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        }.getOrDefault(false)
    }

    private fun contentLengthFor(uri: Uri): Long {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun isMediaStoreSource(
        fileId: String,
        sourceUri: Uri,
    ): Boolean {
        return fileId.startsWith("media-") ||
            sourceUri.authority?.contains("media", ignoreCase = true) == true
    }

    private fun mediaCollectionFor(mimeType: String): Pair<Uri, String> {
        return when {
            mimeType.startsWith("image/") -> {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            }
            mimeType.startsWith("audio/") -> {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            }
            mimeType.startsWith("video/") -> {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            }
            else -> {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOCUMENTS
            }
        }
    }

    companion object {
        private const val managedDestinationLabel = "MobileClaw"
    }
}

private data class DestinationVerification(
    val verified: Boolean,
    val detail: String,
)

private enum class DeleteDisposition {
    Deleted,
    PermissionRequired,
    Failed,
}
