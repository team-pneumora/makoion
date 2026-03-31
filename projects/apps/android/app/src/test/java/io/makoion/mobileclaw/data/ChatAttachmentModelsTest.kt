package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentModelsTest {
    @Test
    fun `infer attachment kind prefers mime type`() {
        assertEquals(ChatAttachmentKind.Photo, inferChatAttachmentKind("image/jpeg"))
        assertEquals(ChatAttachmentKind.Video, inferChatAttachmentKind("video/mp4"))
        assertEquals(ChatAttachmentKind.Audio, inferChatAttachmentKind("audio/mpeg"))
        assertEquals(ChatAttachmentKind.File, inferChatAttachmentKind("application/pdf"))
    }

    @Test
    fun `attachment summary includes count and names`() {
        val summary = chatAttachmentSummaryLine(
            listOf(
                attachment("Screenshot.png", ChatAttachmentKind.Photo),
                attachment("spec.pdf", ChatAttachmentKind.File),
            ),
        )

        assertTrue(summary.contains("2 attachment"))
        assertTrue(summary.contains("Screenshot.png"))
        assertTrue(summary.contains("spec.pdf"))
    }

    private fun attachment(
        name: String,
        kind: ChatAttachmentKind,
    ): ChatAttachment {
        return ChatAttachment(
            id = name,
            kind = kind,
            displayName = name,
            mimeType = "application/octet-stream",
        )
    }
}
