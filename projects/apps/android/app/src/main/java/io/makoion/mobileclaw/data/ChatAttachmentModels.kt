package io.makoion.mobileclaw.data

enum class ChatAttachmentKind {
    Photo,
    Video,
    Audio,
    File,
}

data class ChatAttachment(
    val id: String,
    val kind: ChatAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val uri: String? = null,
    val sizeBytes: Long? = null,
    val sizeLabel: String? = null,
    val sourceLabel: String? = null,
)

internal fun inferChatAttachmentKind(
    mimeType: String?,
    displayName: String = "",
): ChatAttachmentKind {
    val normalizedMimeType = mimeType?.trim()?.lowercase().orEmpty()
    if (normalizedMimeType.startsWith("image/")) {
        return ChatAttachmentKind.Photo
    }
    if (normalizedMimeType.startsWith("video/")) {
        return ChatAttachmentKind.Video
    }
    if (normalizedMimeType.startsWith("audio/")) {
        return ChatAttachmentKind.Audio
    }

    val normalizedName = displayName.trim().lowercase()
    return when {
        normalizedName.endsWith(".jpg") ||
            normalizedName.endsWith(".jpeg") ||
            normalizedName.endsWith(".png") ||
            normalizedName.endsWith(".gif") ||
            normalizedName.endsWith(".webp") ||
            normalizedName.endsWith(".heic") ||
            normalizedName.endsWith(".heif") -> ChatAttachmentKind.Photo

        normalizedName.endsWith(".mp4") ||
            normalizedName.endsWith(".mov") ||
            normalizedName.endsWith(".mkv") ||
            normalizedName.endsWith(".webm") -> ChatAttachmentKind.Video

        normalizedName.endsWith(".mp3") ||
            normalizedName.endsWith(".m4a") ||
            normalizedName.endsWith(".wav") ||
            normalizedName.endsWith(".ogg") -> ChatAttachmentKind.Audio

        else -> ChatAttachmentKind.File
    }
}

internal fun chatAttachmentSummaryLine(attachments: List<ChatAttachment>): String {
    if (attachments.isEmpty()) {
        return "No attachments."
    }
    val names = attachments.take(maxAttachmentSummaryNames).joinToString(", ") { it.displayName }
    val overflow = attachments.size - maxAttachmentSummaryNames
    val suffix = if (overflow > 0) ", +$overflow more" else ""
    return "${attachments.size} attachment(s): $names$suffix"
}

internal fun chatAttachmentPromptSummary(attachments: List<ChatAttachment>): String {
    if (attachments.isEmpty()) {
        return "No current-turn attachments."
    }
    val byKind = attachments.groupingBy { it.kind }.eachCount()
    val counts = buildList {
        byKind[ChatAttachmentKind.Photo]?.takeIf { it > 0 }?.let { add("$it photo") }
        byKind[ChatAttachmentKind.Video]?.takeIf { it > 0 }?.let { add("$it video") }
        byKind[ChatAttachmentKind.Audio]?.takeIf { it > 0 }?.let { add("$it audio") }
        byKind[ChatAttachmentKind.File]?.takeIf { it > 0 }?.let { add("$it file") }
    }.joinToString(", ")
    return "${attachments.size} attachment(s) available for this turn: $counts. ${chatAttachmentSummaryLine(attachments)}"
}

private const val maxAttachmentSummaryNames = 3
