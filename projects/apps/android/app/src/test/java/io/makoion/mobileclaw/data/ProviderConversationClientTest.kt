package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConversationClientTest {
    @Test
    fun `resolve conversation provider prefers supported configured default`() {
        val profile = resolveConversationProviderProfile(
            preference = AgentModelPreference(preferredProviderId = "google-gemini"),
            profiles = listOf(
                profileState(
                    providerId = "google-gemini",
                    displayName = "Google Gemini",
                    credentialStatus = ModelProviderCredentialStatus.Stored,
                    enabled = true,
                    isDefault = true,
                ),
                profileState(
                    providerId = "openai",
                    displayName = "OpenAI",
                    credentialStatus = ModelProviderCredentialStatus.Stored,
                    enabled = true,
                    isDefault = false,
                ),
                profileState(
                    providerId = "anthropic",
                    displayName = "Anthropic",
                    credentialStatus = ModelProviderCredentialStatus.Missing,
                    enabled = true,
                    isDefault = false,
                ),
            ),
        )

        assertNotNull(profile)
        assertEquals("openai", profile?.providerId)
    }

    @Test
    fun `conversation system prompt reflects current resource state`() {
        val prompt = buildConversationSystemPrompt(
            AgentTurnContext(
                fileIndexState = FileIndexState(
                    indexedCount = 12,
                    permissionGranted = true,
                    documentTreeCount = 2,
                ),
                approvals = listOf(
                    ApprovalInboxItem(
                        id = "approval-1",
                        title = "Organize files",
                        action = "files.organize",
                        risk = ApprovalInboxRisk.Medium,
                        summary = "Pending review",
                        requestedAtLabel = "just now",
                        status = ApprovalInboxStatus.Pending,
                    ),
                ),
                tasks = emptyList(),
                auditEvents = emptyList(),
                attachments = listOf(
                    ChatAttachment(
                        id = "attachment-1",
                        kind = ChatAttachmentKind.File,
                        displayName = "spec.pdf",
                        mimeType = "application/pdf",
                    ),
                ),
                pairedDevices = listOf(
                    PairedDeviceState(
                        id = "device-1",
                        name = "Desktop",
                        role = "desktop-companion",
                        health = "healthy",
                        capabilities = listOf("health", "notify"),
                        transportMode = DeviceTransportMode.DirectHttp,
                        endpointLabel = "http://127.0.0.1:8799",
                        validationMode = TransportValidationMode.Normal,
                    ),
                ),
                selectedTargetDeviceId = "device-1",
                modelPreference = AgentModelPreference(configuredProviderIds = listOf("openai")),
            ),
        )

        assertTrue(prompt.contains("indexed files 12"))
        assertTrue(prompt.contains("paired companions 1"))
        assertTrue(prompt.contains("pending approvals 1"))
        assertTrue(prompt.contains("configured providers 1"))
        assertTrue(prompt.contains("attachment(s) available"))
        assertTrue(prompt.contains("spec.pdf"))
    }

    @Test
    fun `conversation transcript includes attachment summary`() {
        val transcript = buildConversationTranscript(
            recentMessages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = ChatMessageRole.User,
                    text = "",
                    attachments = listOf(
                        ChatAttachment(
                            id = "attachment-1",
                            kind = ChatAttachmentKind.Photo,
                            displayName = "IMG_1001.jpg",
                            mimeType = "image/jpeg",
                        ),
                    ),
                ),
            ),
            currentPrompt = "What is attached?",
        )

        assertTrue(transcript.contains("Attachments"))
        assertTrue(transcript.contains("IMG_1001.jpg"))
    }

    private fun profileState(
        providerId: String,
        displayName: String,
        credentialStatus: ModelProviderCredentialStatus,
        enabled: Boolean,
        isDefault: Boolean,
    ): ModelProviderProfileState {
        return ModelProviderProfileState(
            providerId = providerId,
            displayName = displayName,
            supportedModels = listOf("test-model"),
            defaultModel = "test-model",
            selectedModel = "test-model",
            enabled = enabled,
            isDefault = isDefault,
            credentialStatus = credentialStatus,
            updatedAtEpochMillis = 0L,
            updatedAtLabel = "just now",
        )
    }
}
