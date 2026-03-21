package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.AgentTurnContext
import io.makoion.mobileclaw.data.ShellRecoveryStatus
import io.makoion.mobileclaw.data.resolveAgentModelPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellRecoveryChatFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun runAndInspectShellRecoveryFromChat() {
        val recoveryExecution = runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-shell-recovery",
                prompt = "Run shell recovery now",
                context = buildTurnContext(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            val recoveryState = application.appContainer.shellRecoveryCoordinator.state.value
            recoveryState.triggerLabel == "Manual" && recoveryState.status != ShellRecoveryStatus.Running
        }

        val recoveryState = application.appContainer.shellRecoveryCoordinator.state.value
        assertTrue(
            "Expected chat-triggered shell recovery to finish successfully.",
            recoveryState.status == ShellRecoveryStatus.Success,
        )
        assertTrue(
            "Expected the recovery turn reply to summarize the successful recovery.",
            recoveryExecution.turnResult.reply.contains("Shell recovery", ignoreCase = true) &&
                recoveryExecution.turnResult.reply.contains("successful", ignoreCase = true),
        )

        val statusExecution = runBlocking {
            application.appContainer.agentTaskEngine.submitTurn(
                threadId = "thread-instrumentation-shell-recovery",
                prompt = "Show shell recovery status",
                context = buildTurnContext(),
            )
        }

        assertTrue(
            "Expected shell recovery status replies to mention the latest manual trigger.",
            statusExecution.turnResult.reply.contains("Latest trigger: Manual", ignoreCase = true),
        )
        assertTrue(
            "Expected shell recovery status replies to include chat recovery detail.",
            statusExecution.turnResult.reply.contains("Chat recovery restored active thread", ignoreCase = true),
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
