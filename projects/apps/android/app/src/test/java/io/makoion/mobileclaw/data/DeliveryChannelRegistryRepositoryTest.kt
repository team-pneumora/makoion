package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryChannelRegistryRepositoryTest {
    @Test
    fun `default delivery channel seeds include local telegram companion and webhook`() {
        val seeds = defaultDeliveryChannelSeeds()

        assertEquals(
            listOf(
                DeliveryChannelType.LocalNotification,
                DeliveryChannelType.TelegramBot,
                DeliveryChannelType.DesktopCompanion,
                DeliveryChannelType.Webhook,
            ),
            seeds.map { it.type },
        )
        assertTrue(seeds.all { it.supportedDeliveries.isNotEmpty() })
        assertTrue(seeds.any { it.status == DeliveryChannelStatus.Connected })
    }

    @Test
    fun `resource registry promotes delivery channels when one delivery is connected`() {
        val entries = buildResourceRegistryEntries(
            fileIndexState = FileIndexState(),
            pairedDevices = emptyList(),
            providerProfiles = emptyList(),
            deliveryChannels = listOf(
                DeliveryChannelProfileState(
                    channelId = "phone-local-notification",
                    displayName = "Phone local notification",
                    type = DeliveryChannelType.LocalNotification,
                    status = DeliveryChannelStatus.Connected,
                    summary = "Connected",
                    supportedDeliveries = listOf("notifications.local"),
                    destinationLabel = "Android system notifications",
                    updatedAtEpochMillis = 0L,
                    updatedAtLabel = "just now",
                ),
            ),
        )

        val deliveryEntry = entries.first { it.id == resourceIdDeliveryChannels }
        assertEquals(ResourceRegistryHealthState.Active, deliveryEntry.health)
        assertTrue(deliveryEntry.capabilities.contains("notifications.local"))
        assertEquals("1", deliveryEntry.metadata["connectedCount"])
    }
}
