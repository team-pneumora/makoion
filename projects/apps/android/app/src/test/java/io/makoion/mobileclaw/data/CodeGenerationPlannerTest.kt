package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGenerationPlannerTest {
    @Test
    fun `build code generation plan infers android app target`() {
        val plan = buildCodeGenerationProjectPlan(
            prompt = "모바일 앱을 만들어줘",
            companionAvailable = false,
        )

        assertEquals("Android app", plan.targetLabel)
        assertEquals("Phone local workspace", plan.workspaceLabel)
        assertEquals("Gradle Android project skeleton", plan.outputLabel)
    }

    @Test
    fun `code generation prompt detection requires build verb and code target`() {
        assertTrue(looksLikeCodeGenerationPrompt("telegram bot automation service 만들어줘"))
        assertFalse(looksLikeCodeGenerationPrompt("show my dashboard"))
    }

    @Test
    fun `code generation summary includes workspace and output`() {
        val summary = buildCodeGenerationSummary(
            CodeGenerationProjectPlan(
                title = "Automation build skeleton",
                targetLabel = "Automation workflow",
                workspaceLabel = "Companion-assisted workspace",
                outputLabel = "Automation workflow scaffold",
            ),
        )

        assertTrue(summary.contains("Automation workflow"))
        assertTrue(summary.contains("Companion-assisted workspace"))
        assertTrue(summary.contains("Automation workflow scaffold"))
    }
}
