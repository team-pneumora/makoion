package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.AgentTurnContext
import io.makoion.mobileclaw.data.resolveAgentModelPreference
import kotlinx.coroutines.runBlocking
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
            application.appContainer.externalEndpointRepository.markConnected("companion-mcp-bridge")
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-mcp",
                prompt = "Update MCP skills from the MCP bridge",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.mcpSkillRepository.skills.value.size >= 3
        }

        assertTrue(
            "Expected MCP skill sync to install at least the seeded skill catalog.",
            application.appContainer.mcpSkillRepository.skills.value.size >= 3,
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
