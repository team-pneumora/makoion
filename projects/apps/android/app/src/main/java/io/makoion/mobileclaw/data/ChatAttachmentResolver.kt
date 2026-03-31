package io.makoion.mobileclaw.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ChatAttachmentResolver {
    suspend fun resolve(uris: List<Uri>): List<ChatAttachment>
}

class AndroidChatAttachmentResolver(
    private val context: Context,
) : ChatAttachmentResolver {
    override suspend fun resolve(uris: List<Uri>): List<ChatAttachment> = withContext(Dispatchers.IO) {
        uris
            .distinctBy(Uri::toString)
            .mapNotNull(::resolveSingle)
    }

    private fun resolveSingle(uri: Uri): ChatAttachment? {
        persistReadPermission(uri)
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
        )

        var displayName: String? = null
        var sizeBytes: Long? = null
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (displayNameIndex >= 0) {
                    displayName = cursor.getString(displayNameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        val resolvedName = displayName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
            ?: return null

        return ChatAttachment(
            id = uri.toString(),
            kind = inferChatAttachmentKind(
                mimeType = mimeType,
                displayName = resolvedName,
            ),
            displayName = resolvedName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            uri = uri.toString(),
            sizeBytes = sizeBytes,
            sizeLabel = sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
            sourceLabel = uri.authority,
        )
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
