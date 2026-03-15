package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserResearchPlannerTest {
    @Test
    fun `build browser research brief detects recurring telegram delivery`() {
        val brief = buildBrowserResearchBrief(
            "매일 아침 AI 뉴스 기사 브라우저로 조사해서 텔레그램으로 보내줘",
        )

        assertTrue(brief.recurringHint)
        assertEquals("Telegram", brief.requestedDelivery)
        assertTrue(brief.query.contains("AI 뉴스"))
    }

    @Test
    fun `build browser research brief falls back to chat summary without recurring hint`() {
        val brief = buildBrowserResearchBrief(
            "Browse the web and research the latest Android local-first AI agent patterns",
        )

        assertFalse(brief.recurringHint)
        assertEquals("Chat summary", brief.requestedDelivery)
        assertTrue(brief.query.startsWith("Browse the web"))
    }
}
