package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.AgentTurnContext
import io.makoion.mobileclaw.data.ExternalEndpointStatus
import io.makoion.mobileclaw.data.resolveAgentModelPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpSkillChatFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectAndSyncMcpSkillsFromChat() {
        runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-mcp",
                prompt = "Connect the MCP bridge",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.externalEndpointRepository.profiles.value.any {
                it.endpointId == "companion-mcp-bridge" &&
                    it.status == ExternalEndpointStatus.Connected &&
                    it.toolNames.size >= 3
            }
        }

        val statusExecution = runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-mcp",
                prompt = "Show MCP status",
                context = buildTurnContext(),
            )
        }

        val syncExecution = runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-mcp",
                prompt = "Update MCP skills from the MCP bridge",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.mcpSkillRepository.skills.value.size >= 3
        }

        val toolsExecution = runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-mcp",
                prompt = "List MCP tools",
                context = buildTurnContext(),
            )
        }

        val endpoint = application.appContainer.externalEndpointRepository.profiles.value.first {
            it.endpointId == "companion-mcp-bridge"
        }

        assertEquals(
            "Expected the MCP bridge to be recorded as connected.",
            ExternalEndpointStatus.Connected,
            endpoint.status,
        )
        assertTrue(
            "Expected MCP skill sync to install at least the seeded skill catalog.",
            application.appContainer.mcpSkillRepository.skills.value.size >= 3,
        )
        assertTrue(
            "Expected the MCP status reply to include the Direct HTTP bridge transport.",
            statusExecution.turnResult.reply.contains("Direct HTTP bridge", ignoreCase = true),
        )
        assertTrue(
            "Expected the skill sync reply to mention the connector tool inventory.",
            syncExecution.turnResult.reply.contains("tool", ignoreCase = true),
        )
        assertTrue(
            "Expected the MCP tools reply to list desktop.app.open.",
            toolsExecution.turnResult.reply.contains("desktop.app.open", ignoreCase = true),
        )
        assertTrue(
            "Expected the endpoint profile to persist the synced skill count and last sync time.",
            endpoint.syncedSkillCount >= 3 && endpoint.lastSyncAtEpochMillis != null,
        )
    }

    private val application: MobileClawApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MobileClawApplication

    private suspend fun buildTurnContext(): AgentTurnContext {
        val appContainer = application.appContainer
        val fileIndexState = appContainer.fileIndexRepository.refreshIndex()
        appContainer.approvalInboxRepository.refresh()
        appContainer.agentTaskRepository.refresh()
        appContainer.auditTrailRepository.refresh()
        appContainer.devicePairingRepository.refresh()
        appContainer.cloudDriveConnectionRepository.refresh()
        appContainer.externalEndpointRepository.refresh()
        appContainer.deliveryChannelRepository.refresh()
        appContainer.scheduledAutomationRepository.refresh()
        return AgentTurnContext(
            fileIndexState = fileIndexState,
            approvals = appContainer.approvalInboxRepository.items.value,
            tasks = appContainer.agentTaskRepository.tasks.value,
            auditEvents = appContainer.auditTrailRepository.events.value,
            pairedDevices = appContainer.devicePairingRepository.pairedDevices.value,
            selectedTargetDeviceId = null,
            cloudDriveConnections = appContainer.cloudDriveConnectionRepository.connections.value,
            modelPreference = resolveAgentModelPreference(appContainer.modelProviderSettingsRepository.profiles.value),
            externalEndpoints = appContainer.externalEndpointRepository.profiles.value,
            deliveryChannels = appContainer.deliveryChannelRepository.profiles.value,
            scheduledAutomations = appContainer.scheduledAutomationRepository.automations.value,
            selectedFileId = null,
        )
    }
}
