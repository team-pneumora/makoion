package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDriveConnectionRepositoryTest {
    @Test
    fun `default cloud drive seeds cover the three planned providers`() {
        val seeds = defaultCloudDriveSeeds()

        assertEquals(
            listOf(
                CloudDriveProviderKind.GoogleDrive,
                CloudDriveProviderKind.OneDrive,
                CloudDriveProviderKind.Dropbox,
            ),
            seeds.map { it.provider },
        )
        assertTrue(seeds.all { it.status == CloudDriveConnectionStatus.NeedsSetup })
        assertTrue(seeds.all { it.supportedScopes.isNotEmpty() })
    }

    @Test
    fun `resource registry promotes cloud drives when a connector is mock ready`() {
        val entries = buildResourceRegistryEntries(
            fileIndexState = FileIndexState(),
            pairedDevices = emptyList(),
            providerProfiles = emptyList(),
            cloudDriveConnections = listOf(
                CloudDriveConnectionState(
                    provider = CloudDriveProviderKind.GoogleDrive,
                    status = CloudDriveConnectionStatus.Connected,
                    summary = "Mock ready",
                    supportedScopes = listOf("drive.readonly"),
                    accountLabel = "test-account",
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
            ),
        )

        val cloudEntry = entries.first { it.id == resourceIdCloudDrives }
        assertEquals(ResourceRegistryHealthState.Active, cloudEntry.health)
        assertTrue(cloudEntry.capabilities.contains("cloud.files.search"))
        assertEquals("1", cloudEntry.metadata["connectedCount"])
    }
}
