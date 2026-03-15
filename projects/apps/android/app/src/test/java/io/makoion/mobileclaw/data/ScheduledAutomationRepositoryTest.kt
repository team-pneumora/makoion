package io.makoion.mobileclaw.data

import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledAutomationRepositoryTest {
    @Test
    fun `summary mentions schedule and delivery labels`() {
        val summary = buildScheduledAutomationSummary(
            ScheduledAutomationPlan(
                title = "Daily AI news",
                scheduleLabel = "Daily",
                deliveryLabel = "Telegram",
                recurringHint = true,
            ),
        )

        assertTrue(summary.contains("Daily"))
        assertTrue(summary.contains("Telegram"))
    }
}
