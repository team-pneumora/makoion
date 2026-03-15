package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderSettingsRepositoryTest {
    @Test
    fun `default provider seeds expose one default profile`() {
        val profiles = defaultModelProviderSeeds()

        assertEquals(3, profiles.size)
        assertEquals(listOf("openai", "anthropic", "google-gemini"), profiles.map { it.providerId })
        assertEquals("openai", profiles.single { it.isDefault }.providerId)
        assertTrue(profiles.all { it.supportedModels.isNotEmpty() })
        assertTrue(profiles.all { it.selectedModel == it.defaultModel })
    }

    @Test
    fun `agent model preference chooses enabled default provider`() {
        val preference = resolveAgentModelPreference(
            profiles = listOf(
                ModelProviderProfileState(
                    providerId = "openai",
                    displayName = "OpenAI",
                    supportedModels = listOf("gpt-4.1-mini"),
                    defaultModel = "gpt-4.1-mini",
                    selectedModel = "gpt-4.1-mini",
                    enabled = false,
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
                    isDefault = true,
                    credentialStatus = ModelProviderCredentialStatus.Missing,
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
                ModelProviderProfileState(
                    providerId = "google-gemini",
                    displayName = "Google Gemini",
                    supportedModels = listOf("gemini-2.0-flash"),
                    defaultModel = "gemini-2.0-flash",
                    selectedModel = "gemini-2.0-flash",
                    enabled = true,
                    isDefault = false,
                    credentialStatus = ModelProviderCredentialStatus.Stored,
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
            ),
        )

        assertEquals("anthropic", preference.preferredProviderId)
        assertEquals("Anthropic", preference.preferredProviderLabel)
        assertEquals("claude-3.7-sonnet", preference.preferredModel)
        assertEquals(listOf("anthropic", "google-gemini"), preference.enabledProviderIds)
        assertEquals(listOf("openai", "google-gemini"), preference.configuredProviderIds)
    }
}
