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
        assertEquals(
            listOf("gpt-5.4", "gpt-5.4-pro", "gpt-5-mini", "gpt-5-nano", "gpt-4.1"),
            profiles.first { it.providerId == "openai" }.supportedModels,
        )
        assertEquals(
            listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5"),
            profiles.first { it.providerId == "anthropic" }.supportedModels,
        )
    }

    @Test
    fun `agent model preference chooses enabled default provider`() {
        val preference = resolveAgentModelPreference(
            profiles = listOf(
                ModelProviderProfileState(
                    providerId = "openai",
                    displayName = "OpenAI",
                    supportedModels = listOf("gpt-5.4"),
                    defaultModel = "gpt-5.4",
                    selectedModel = "gpt-5.4",
                    enabled = false,
                    isDefault = true,
                    credentialStatus = ModelProviderCredentialStatus.Stored,
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
                ModelProviderProfileState(
                    providerId = "anthropic",
                    displayName = "Anthropic",
                    supportedModels = listOf("claude-sonnet-4-6"),
                    defaultModel = "claude-sonnet-4-6",
                    selectedModel = "claude-sonnet-4-6",
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
        assertEquals("claude-sonnet-4-6", preference.preferredModel)
        assertEquals(listOf("anthropic", "google-gemini"), preference.enabledProviderIds)
        assertEquals(listOf("openai", "google-gemini"), preference.configuredProviderIds)
    }

    @Test
    fun `normalize seed selected model keeps current choice only when still supported`() {
        val openAiSeed = defaultModelProviderSeeds().first { it.providerId == "openai" }

        assertEquals("gpt-5.4-pro", normalizeSeedSelectedModel("gpt-5.4-pro", openAiSeed))
        assertEquals("gpt-5.4", normalizeSeedSelectedModel("gpt-4.1-mini", openAiSeed))
    }

    @Test
    fun `credential mask keeps prefix and suffix only`() {
        assertEquals("sk-t...7890", maskProviderCredential("sk-test-1234567890"))
        assertEquals("a...f", maskProviderCredential("abcdef"))
    }
}
