package io.makoion.mobileclaw.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IndexedFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeLabel: String,
    val modifiedLabel: String,
    val sourceLabel: String,
)

data class FileIndexState(
    val isRefreshing: Boolean = false,
    val permissionGranted: Boolean = false,
    val headline: String = "Media access needed",
    val summary: String = "Grant photo access to bootstrap local indexing. Document indexing through SAF comes next.",
    val scanSource: String = "MediaStore recent files",
    val lastIndexedLabel: String? = null,
    val indexedCount: Int = 0,
    val documentTreeCount: Int = 0,
    val documentRoots: List<String> = emptyList(),
    val indexedItems: List<IndexedFileItem> = emptyList(),
)

interface FileIndexRepository {
    suspend fun refreshIndex(): FileIndexState

    suspend fun registerDocumentTree(treeUri: Uri): FileIndexState
}

object MediaAccessPermissions {
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMediaAccess(context: Context): Boolean {
        val mediaImagesGranted = isGranted(context, Manifest.permission.READ_MEDIA_IMAGES)
        val selectedVisualGranted = isGranted(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        val legacyGranted = isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        return mediaImagesGranted || selectedVisualGranted || legacyGranted
    }

    private fun isGranted(
        context: Context,
        permission: String,
    ): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

class AndroidFileIndexRepository(
    private val context: Context,
) : FileIndexRepository {
    private val preferences = context.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override suspend fun refreshIndex(): FileIndexState = withContext(Dispatchers.IO) {
        val mediaGranted = MediaAccessPermissions.hasMediaAccess(context)
        val documentRoots = loadDocumentTreeUris()
        val mediaItems = if (mediaGranted) {
            queryMediaStoreItems()
        } else {
            emptyList()
        }
        val documentItems = queryDocumentTreeItems(documentRoots)
        val allItems = (mediaItems + documentItems).take(maxIndexedItems)
        val rootNames = documentRoots.mapNotNull { treeUri ->
            DocumentFile.fromTreeUri(context, treeUri)?.name
        }
        val totalSources = buildList {
            if (mediaGranted) {
                add("MediaStore")
            }
            if (rootNames.isNotEmpty()) {
                add("${rootNames.size} document roots")
            }
        }

        if (!mediaGranted && rootNames.isEmpty()) {
            return@withContext FileIndexState()
        }

        val headline = when {
            allItems.isNotEmpty() -> "Indexed ${allItems.size} local files"
            rootNames.isNotEmpty() -> "Document roots attached"
            else -> "Media access granted"
        }
        val summary = when {
            rootNames.isNotEmpty() && mediaGranted ->
                "MediaStore and attached document roots are both being scanned from the Android shell."
            rootNames.isNotEmpty() ->
                "Attached document roots are available even without MediaStore photo permission."
            mediaGranted ->
                "Recent MediaStore items are available now. Attach document roots for deeper folders."
            else ->
                "Attach a document root to start indexing local files."
        }

        FileIndexState(
            permissionGranted = mediaGranted,
            headline = headline,
            summary = summary,
            scanSource = totalSources.joinToString(" + ").ifBlank { "Manual sources" },
            lastIndexedLabel = DateUtils.getRelativeTimeSpanString(
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
            indexedCount = allItems.size,
            documentTreeCount = rootNames.size,
            documentRoots = rootNames,
            indexedItems = allItems,
        )
    }

    override suspend fun registerDocumentTree(treeUri: Uri): FileIndexState {
        withContext(Dispatchers.IO) {
            takePersistablePermission(treeUri)
            val current = preferences.getStringSet(documentTreesKey, emptySet()).orEmpty()
            preferences.edit()
                .putStringSet(documentTreesKey, current + treeUri.toString())
                .apply()
        }
        return refreshIndex()
    }

    private fun queryMediaStoreItems(): List<IndexedFileItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val items = mutableListOf<IndexedFileItem>()

        context.contentResolver.query(
            uri,
            projection,
            "${MediaStore.Files.FileColumns.SIZE} > 0",
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext() && items.size < mediaItemLimit) {
                val modifiedMillis = cursor.getLong(modifiedIndex) * 1000
                items += IndexedFileItem(
                    id = "media-${cursor.getLong(idIndex)}",
                    name = cursor.getString(nameIndex) ?: "Unnamed",
                    mimeType = cursor.getString(mimeIndex) ?: "unknown",
                    sizeLabel = Formatter.formatShortFileSize(
                        context,
                        cursor.getLong(sizeIndex),
                    ),
                    modifiedLabel = relativeTimeLabel(modifiedMillis),
                    sourceLabel = "MediaStore",
                )
            }
        }

        return items
    }

    private fun queryDocumentTreeItems(documentRoots: List<Uri>): List<IndexedFileItem> {
        val items = mutableListOf<IndexedFileItem>()
        for (treeUri in documentRoots) {
            if (items.size >= maxIndexedItems) {
                break
            }
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            val sourceLabel = root.name ?: "Document tree"
            val queue = ArrayDeque<DocumentFile>()
            queue.add(root)

            while (queue.isNotEmpty() && items.size < maxIndexedItems) {
                val node = queue.removeFirst()
                if (node.isDirectory) {
                    node.listFiles().forEach { child ->
                        if (queue.size + items.size < traversalLimit) {
                            queue.addLast(child)
                        }
                    }
                    continue
                }

                items += IndexedFileItem(
                    id = node.uri.toString(),
                    name = node.name ?: "Unnamed document",
                    mimeType = node.type ?: "document",
                    sizeLabel = Formatter.formatShortFileSize(context, node.length()),
                    modifiedLabel = relativeTimeLabel(node.lastModified()),
                    sourceLabel = sourceLabel,
                )
            }
        }
        return items
    }

    private fun loadDocumentTreeUris(): List<Uri> {
        return preferences.getStringSet(documentTreesKey, emptySet())
            .orEmpty()
            .map(Uri::parse)
            .sortedBy(Uri::toString)
    }

    private fun takePersistablePermission(treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun relativeTimeLabel(timestamp: Long): String {
        if (timestamp <= 0L) {
            return "unknown"
        }
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    companion object {
        private const val preferencesName = "mobileclaw_file_index"
        private const val documentTreesKey = "document_trees"
        private const val mediaItemLimit = 8
        private const val maxIndexedItems = 16
        private const val traversalLimit = 32
    }
}
