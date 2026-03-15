package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEndpointRegistryRepositoryTest {
    @Test
    fun `default external endpoint seeds cover mcp browser and api categories`() {
        val seeds = defaultExternalEndpointSeeds()

        assertEquals(
            listOf(
                ExternalEndpointCategory.McpServer,
                ExternalEndpointCategory.BrowserAutomation,
                ExternalEndpointCategory.ThirdPartyApi,
            ),
            seeds.map { it.category },
        )
        assertTrue(seeds.all { it.status == ExternalEndpointStatus.NeedsSetup })
        assertTrue(seeds.all { it.supportedCapabilities.isNotEmpty() })
    }

    @Test
    fun `resource registry promotes mcp api entry when an endpoint is mock ready`() {
        val entries = buildResourceRegistryEntries(
            fileIndexState = FileIndexState(),
            pairedDevices = emptyList(),
            providerProfiles = emptyList(),
            externalEndpoints = listOf(
                ExternalEndpointProfileState(
                    endpointId = "browser-automation-profile",
                    displayName = "Browser automation profile",
                    category = ExternalEndpointCategory.BrowserAutomation,
                    status = ExternalEndpointStatus.Connected,
                    summary = "Mock ready",
                    supportedCapabilities = listOf("browser.navigate", "browser.extract"),
                    endpointLabel = "Browser automation placeholder",
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
            ),
        )

        val endpointEntry = entries.first { it.id == resourceIdMcpApiEndpoints }
        assertEquals(ResourceRegistryHealthState.Active, endpointEntry.health)
        assertTrue(endpointEntry.capabilities.contains("browser.navigate"))
        assertEquals("1", endpointEntry.metadata["connectedCount"])
    }
}
