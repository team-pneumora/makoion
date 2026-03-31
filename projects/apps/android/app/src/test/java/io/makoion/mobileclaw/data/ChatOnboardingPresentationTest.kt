package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOnboardingPresentationTest {
    @Test
    fun `entry presentation points to setup when provider is not configured`() {
        val presentation = buildChatEntryPresentation(
            prefersKorean = false,
            hasConfiguredProvider = false,
            hasIndexedFiles = false,
            hasPairedCompanion = false,
        )

        assertEquals("Start with one request", presentation.headline)
        assertTrue(presentation.note.contains("Start setup first"))
        assertEquals(listOf("Start setup"), presentation.prompts.map { it.label })
    }

    @Test
    fun `entry presentation prefers file summary when files are ready`() {
        val presentation = buildChatEntryPresentation(
            prefersKorean = false,
            hasConfiguredProvider = true,
            hasIndexedFiles = true,
            hasPairedCompanion = false,
        )

        assertEquals(listOf("Summarize files"), presentation.prompts.map { it.label })
        assertTrue(presentation.note.contains("Add photo, video, file, or voice"))
    }

    @Test
    fun `explain capabilities reply mentions setup when provider is missing`() {
        val reply = buildExplainCapabilitiesReply(
            prefersKorean = false,
            hasConfiguredProvider = false,
        )

        assertTrue(reply.contains("Start setup first"))
    }
}
