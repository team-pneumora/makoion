package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.AgentTurnContext
import io.makoion.mobileclaw.data.CloudDriveConnectionStatus
import io.makoion.mobileclaw.data.DeliveryChannelStatus
import io.makoion.mobileclaw.data.ExternalEndpointStatus
import io.makoion.mobileclaw.data.resolveAgentModelPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceStackChatFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun controlResourceStackFromChat() {
        runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-resource-stack",
                prompt = "Connect Google Drive",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.cloudDriveConnectionRepository.connections.value.any {
                it.provider.providerId == "gdrive" && it.status == CloudDriveConnectionStatus.Connected
            }
        }

        runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-resource-stack",
                prompt = "Stage browser automation profile",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.externalEndpointRepository.profiles.value.any {
                it.endpointId == "browser-automation-profile" && it.status == ExternalEndpointStatus.Staged
            }
        }

        runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-resource-stack",
                prompt = "Connect desktop companion relay",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.deliveryChannelRepository.profiles.value.any {
                it.channelId == "desktop-companion-delivery" && it.status == DeliveryChannelStatus.Connected
            }
        }

        val summaryExecution = runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-resource-stack",
                prompt = "Show resource stack",
                context = buildTurnContext(),
            )
        }

        assertEquals(
            "Expected the Google Drive connector to be recorded as connected.",
            CloudDriveConnectionStatus.Connected,
            application.appContainer.cloudDriveConnectionRepository.connections.value.first {
                it.provider.providerId == "gdrive"
            }.status,
        )
        assertEquals(
            "Expected the browser automation endpoint to be staged.",
            ExternalEndpointStatus.Staged,
            application.appContainer.externalEndpointRepository.profiles.value.first {
                it.endpointId == "browser-automation-profile"
            }.status,
        )
        assertEquals(
            "Expected the desktop companion relay to be recorded as connected.",
            DeliveryChannelStatus.Connected,
            application.appContainer.deliveryChannelRepository.profiles.value.first {
                it.channelId == "desktop-companion-delivery"
            }.status,
        )
        assertTrue(
            "Expected the resource-stack reply to mention the connected cloud drive and delivery relay.",
            summaryExecution.turnResult.reply.contains("Google Drive", ignoreCase = true) &&
                summaryExecution.turnResult.reply.contains("Desktop companion relay", ignoreCase = true),
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
