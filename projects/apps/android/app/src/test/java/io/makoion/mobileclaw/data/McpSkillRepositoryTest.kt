package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSkillRepositoryTest {
    @Test
    fun `default mcp skill seeds expose syncable agent extensions`() {
        val seeds = defaultMcpSkillSeeds()

        assertEquals(3, seeds.size)
        assertTrue(seeds.all { it.capabilities.contains("mcp.tools.call") })
        assertTrue(seeds.any { it.capabilities.contains("browser.navigate") })
        assertTrue(seeds.any { it.capabilities.contains("api.response.ingest") })
    }
}
