package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceRegistryRepositoryTest {
    @Test
    fun `build resource registry entries marks missing local resources as setup needed`() {
        val entries = buildResourceRegistryEntries(
            fileIndexState = FileIndexState(),
            pairedDevices = emptyList(),
            providerProfiles = emptyList(),
        )

        assertEquals(7, entries.size)
        assertEquals(ResourceRegistryHealthState.NeedsSetup, entries.first { it.id == resourceIdPhoneLocalStorage }.health)
        assertEquals(ResourceRegistryHealthState.NeedsSetup, entries.first { it.id == resourceIdPhoneDocumentRoots }.health)
        assertEquals(ResourceRegistryHealthState.Planned, entries.first { it.id == resourceIdCloudDrives }.health)
        assertEquals(ResourceRegistryHealthState.NeedsSetup, entries.first { it.id == resourceIdExternalCompanions }.health)
        assertEquals(ResourceRegistryHealthState.Planned, entries.first { it.id == resourceIdAiModelProviders }.health)
        assertEquals(ResourceRegistryHealthState.Planned, entries.first { it.id == resourceIdMcpApiEndpoints }.health)
        assertEquals(ResourceRegistryHealthState.Planned, entries.first { it.id == resourceIdDeliveryChannels }.health)
    }

    @Test
    fun `build resource registry entries reflects active runtime resources`() {
        val entries = buildResourceRegistryEntries(
            fileIndexState = FileIndexState(
                permissionGranted = true,
                scanSource = "MediaStore + 2 document roots",
                indexedCount = 12,
                documentTreeCount = 2,
                documentRoots = listOf("Projects", "Archive"),
            ),
            pairedDevices = listOf(
                PairedDeviceState(
                    id = "device-1",
                    name = "Desktop companion",
                    role = "Desktop companion",
                    health = "Healthy",
                    capabilities = listOf("files.transfer", "workflow.run"),
                    transportMode = DeviceTransportMode.DirectHttp,
                    endpointLabel = "127.0.0.1",
                    validationMode = TransportValidationMode.Normal,
                ),
            ),
            providerProfiles = listOf(
                ModelProviderProfileState(
                    providerId = "openai",
                    displayName = "OpenAI",
                    supportedModels = listOf("gpt-4.1-mini"),
                    defaultModel = "gpt-4.1-mini",
                    selectedModel = "gpt-4.1-mini",
                    enabled = true,
                    isDefault = true,
                    credentialStatus = ModelProviderCredentialStatus.Stored,
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
                ModelProviderProfileState(
                    providerId = "anthropic",
                    displayName = "Anthropic",
                    supportedModels = listOf("claude-3.7-sonnet"),
                    defaultModel = "claude-3.7-sonnet",
                    selectedModel = "claude-3.7-sonnet",
                    enabled = true,
                    isDefault = false,
                    credentialStatus = ModelProviderCredentialStatus.Stored,
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
            ),
        )

        val localStorage = entries.first { it.id == resourceIdPhoneLocalStorage }
        val companions = entries.first { it.id == resourceIdExternalCompanions }
        val aiProviders = entries.first { it.id == resourceIdAiModelProviders }

        assertEquals(ResourceRegistryHealthState.Active, localStorage.health)
        assertTrue(localStorage.capabilities.contains("files.organize"))
        assertEquals("12", localStorage.metadata["indexedCount"])

        assertEquals(ResourceRegistryHealthState.Active, companions.health)
        assertTrue(companions.capabilities.contains("files.transfer"))
        assertEquals("1", companions.metadata["pairedCount"])

        assertEquals(ResourceRegistryHealthState.Active, aiProviders.health)
        assertTrue(aiProviders.capabilities.contains("model.route"))
        assertEquals("openai", aiProviders.metadata["defaultProviderId"])
    }
}
