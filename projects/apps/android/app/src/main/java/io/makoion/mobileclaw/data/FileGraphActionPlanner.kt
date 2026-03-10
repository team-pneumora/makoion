package io.makoion.mobileclaw.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FileOrganizeStrategy {
    ByType,
    BySource,
}

data class FilePreviewDetail(
    val fileId: String,
    val title: String,
    val previewType: String,
    val body: String,
    val metadata: List<String>,
)

data class FileSummaryDetail(
    val headline: String,
    val body: String,
    val highlights: List<String>,
)

data class OrganizePlanStep(
    val fileId: String,
    val fileName: String,
    val sourceLabel: String,
    val destinationFolder: String,
    val reason: String,
)

data class FileOrganizePlan(
    val strategy: FileOrganizeStrategy,
    val explanation: String,
    val riskLabel: String,
    val steps: List<OrganizePlanStep>,
)

class LocalFileGraphActionPlanner {
    suspend fun preview(item: IndexedFileItem): FilePreviewDetail = withContext(Dispatchers.Default) {
        FilePreviewDetail(
            fileId = item.id,
            title = item.name,
            previewType = previewTypeFor(item),
            body = buildString {
                append(item.name)
                append(" is available from ")
                append(item.sourceLabel)
                append(". ")
                append(descriptiveMime(item.mimeType))
                append(" It was updated ")
                append(item.modifiedLabel)
                append(" and currently reports ")
                append(item.sizeLabel)
                append(".")
            },
            metadata = listOf(
                "Source: ${item.sourceLabel}",
                "Type: ${item.mimeType}",
                "Size: ${item.sizeLabel}",
                "Modified: ${item.modifiedLabel}",
            ),
        )
    }

    suspend fun summarize(items: List<IndexedFileItem>): FileSummaryDetail = withContext(Dispatchers.Default) {
        val safeItems = items.take(maxSummaryItems)
        if (safeItems.isEmpty()) {
            return@withContext FileSummaryDetail(
                headline = "No indexed files selected",
                body = "Index files first, then the shell can summarize the current file set.",
                highlights = emptyList(),
            )
        }

        val categories = safeItems.groupingBy { categoryFor(it.mimeType) }.eachCount()
        val sources = safeItems.groupingBy { it.sourceLabel }.eachCount()
        val primaryCategory = categories.maxByOrNull { it.value }?.key ?: "files"
        val primarySource = sources.maxByOrNull { it.value }?.key ?: "local storage"

        FileSummaryDetail(
            headline = "${safeItems.size} files across ${categories.size} categories",
            body = "The current batch is dominated by $primaryCategory from $primarySource. Use preview for inspection, or run a dry-run organize plan before moving anything.",
            highlights = buildList {
                add("Largest source: $primarySource (${sources[primarySource]} files)")
                add("Top category: $primaryCategory (${categories[primaryCategory]} files)")
                add("Most recent item: ${safeItems.first().name}")
            },
        )
    }

    suspend fun planOrganize(
        items: List<IndexedFileItem>,
        strategy: FileOrganizeStrategy = FileOrganizeStrategy.ByType,
    ): FileOrganizePlan = withContext(Dispatchers.Default) {
        val safeItems = items.take(maxPlanItems)
        val steps = safeItems.map { item ->
            val destinationFolder = when (strategy) {
                FileOrganizeStrategy.ByType -> folderForCategory(categoryFor(item.mimeType))
                FileOrganizeStrategy.BySource -> "${item.sourceLabel}/Reviewed"
            }
            OrganizePlanStep(
                fileId = item.id,
                fileName = item.name,
                sourceLabel = item.sourceLabel,
                destinationFolder = destinationFolder,
                reason = when (strategy) {
                    FileOrganizeStrategy.ByType ->
                        "Grouped by detected file category ${categoryFor(item.mimeType)}."
                    FileOrganizeStrategy.BySource ->
                        "Grouped by current source ${item.sourceLabel} for staged review."
                },
            )
        }
        FileOrganizePlan(
            strategy = strategy,
            explanation = when (strategy) {
                FileOrganizeStrategy.ByType ->
                    "Dry-run only. Files are grouped into category folders so photos, PDFs, office docs, and audio stay separated before any real move happens."
                FileOrganizeStrategy.BySource ->
                    "Dry-run only. Files are grouped under their current source so you can review source-specific batches before moving them."
            },
            riskLabel = if (steps.size > 3) "Medium" else "Low",
            steps = steps,
        )
    }

    private fun previewTypeFor(item: IndexedFileItem): String {
        return when {
            item.mimeType.startsWith("image/") -> "image"
            item.mimeType.startsWith("text/") -> "text"
            item.mimeType.contains("pdf", ignoreCase = true) -> "document"
            else -> "metadata"
        }
    }

    private fun descriptiveMime(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "It looks like an image asset."
            mimeType.startsWith("audio/") -> "It looks like an audio recording."
            mimeType.contains("pdf", ignoreCase = true) -> "It looks like a PDF document."
            mimeType.contains("sheet", ignoreCase = true) ||
                mimeType.contains("spreadsheet", ignoreCase = true) ->
                "It looks like a spreadsheet document."
            mimeType.contains("presentation", ignoreCase = true) ->
                "It looks like a presentation file."
            mimeType.startsWith("text/") -> "It looks like a text document."
            else -> "It is currently summarized from metadata only."
        }
    }

    private fun categoryFor(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "images"
            mimeType.startsWith("audio/") -> "audio"
            mimeType.contains("pdf", ignoreCase = true) -> "pdfs"
            mimeType.contains("sheet", ignoreCase = true) ||
                mimeType.contains("spreadsheet", ignoreCase = true) -> "spreadsheets"
            mimeType.contains("presentation", ignoreCase = true) -> "presentations"
            mimeType.startsWith("text/") -> "text"
            else -> "documents"
        }
    }

    private fun folderForCategory(category: String): String {
        return when (category) {
            "images" -> "Photos/Reviewed"
            "audio" -> "Audio/Captures"
            "pdfs" -> "Documents/PDF"
            "spreadsheets" -> "Documents/Sheets"
            "presentations" -> "Documents/Presentations"
            "text" -> "Documents/Text"
            else -> "Documents/Misc"
        }
    }

    companion object {
        private const val maxSummaryItems = 12
        private const val maxPlanItems = 10
    }
}
