package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledAutomationPlannerTest {
    @Test
    fun `plan builder detects daily telegram automation`() {
        val plan = buildScheduledAutomationPlan(
            "매일 아침 AI 뉴스 기사 자동수집해서 텔레그램으로 보내줘",
        )

        assertTrue(plan.recurringHint)
        assertEquals("Daily morning", plan.scheduleLabel)
        assertEquals("Telegram", plan.deliveryLabel)
        assertTrue(plan.title.contains("AI 뉴스"))
    }

    @Test
    fun `automation prompt detection ignores one off request`() {
        assertFalse(
            looksLikeScheduledAutomationPrompt(
                "Browse the web once and summarize the latest Android agent runtime updates",
            ),
        )
    }
}
