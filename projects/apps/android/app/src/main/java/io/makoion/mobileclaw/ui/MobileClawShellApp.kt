package io.makoion.mobileclaw.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.makoion.mobileclaw.BuildConfig
import io.makoion.mobileclaw.data.AgentTaskRecord
import io.makoion.mobileclaw.data.AgentDestination
import io.makoion.mobileclaw.data.AgentPlannerMode
import io.makoion.mobileclaw.data.AgentTaskStatus
import io.makoion.mobileclaw.data.ApprovalInboxItem
import io.makoion.mobileclaw.data.ApprovalInboxRisk
import io.makoion.mobileclaw.data.ApprovalInboxStatus
import io.makoion.mobileclaw.data.AuditTrailEvent
import io.makoion.mobileclaw.data.ChatMessage
import io.makoion.mobileclaw.data.ChatMessageRole
import io.makoion.mobileclaw.data.ChatThreadRecord
import io.makoion.mobileclaw.data.CloudDriveConnectionState
import io.makoion.mobileclaw.data.CloudDriveConnectionStatus
import io.makoion.mobileclaw.data.CloudDriveProviderKind
import io.makoion.mobileclaw.data.CompanionAppOpenStatus
import io.makoion.mobileclaw.data.CompanionHealthCheckResult
import io.makoion.mobileclaw.data.CompanionHealthStatus
import io.makoion.mobileclaw.data.CompanionSessionNotifyStatus
import io.makoion.mobileclaw.data.CompanionWorkflowRunStatus
import io.makoion.mobileclaw.data.DeviceTransportMode
import io.makoion.mobileclaw.data.FileIndexState
import io.makoion.mobileclaw.data.FileOrganizePlan
import io.makoion.mobileclaw.data.FilePreviewDetail
import io.makoion.mobileclaw.data.FileSummaryDetail
import io.makoion.mobileclaw.data.IndexedFileItem
import io.makoion.mobileclaw.data.MediaAccessPermissions
import io.makoion.mobileclaw.data.ModelProviderCredentialStatus
import io.makoion.mobileclaw.data.ModelProviderProfileState
import io.makoion.mobileclaw.data.OrganizeExecutionEntry
import io.makoion.mobileclaw.data.OrganizeExecutionResult
import io.makoion.mobileclaw.data.OrganizeExecutionStatus
import io.makoion.mobileclaw.data.PairedDeviceState
import io.makoion.mobileclaw.data.PairingSessionState
import io.makoion.mobileclaw.data.PairingSessionStatus
import io.makoion.mobileclaw.data.ScheduledAutomationRecord
import io.makoion.mobileclaw.data.ScheduledAutomationStatus
import io.makoion.mobileclaw.data.ShellRecoveryState
import io.makoion.mobileclaw.data.ShellRecoveryStatus
import io.makoion.mobileclaw.data.TransferDraftStatus
import io.makoion.mobileclaw.data.TransferDraftState
import io.makoion.mobileclaw.data.TransportValidationMode
import io.makoion.mobileclaw.data.VoiceEntryState
import io.makoion.mobileclaw.data.companionAppOpenTargetActionsFolder
import io.makoion.mobileclaw.data.companionAppOpenTargetInbox
import io.makoion.mobileclaw.data.companionAppOpenTargetLatestAction
import io.makoion.mobileclaw.data.companionAppOpenTargetLatestTransfer
import io.makoion.mobileclaw.data.companionCapabilityAppOpen
import io.makoion.mobileclaw.data.companionCapabilitySessionNotify
import io.makoion.mobileclaw.data.companionCapabilityWorkflowRun
import io.makoion.mobileclaw.ui.theme.ClawGold
import io.makoion.mobileclaw.ui.theme.ClawGreen
import io.makoion.mobileclaw.ui.theme.ClawInk
import kotlinx.coroutines.launch

@Composable
fun MobileClawShellApp(
    startSection: ShellSection = ShellSection.Chat,
    startTaskId: String? = null,
    startTaskFollowUpKey: String? = null,
    shellViewModel: ShellViewModel = viewModel(),
) {
    val uiState by shellViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        shellViewModel.refreshFiles()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            shellViewModel.postQuickActionsNotification()
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            shellViewModel.toggleVoiceCapture()
        }
    }
    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(shellViewModel::attachDocumentTree)
    }
    val deleteConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        shellViewModel.resolveDeleteConsent(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(startSection) {
        shellViewModel.openSection(startSection)
    }
    LaunchedEffect(startTaskId, startTaskFollowUpKey) {
        shellViewModel.requestTaskFollowUp(startTaskId)
    }
    LaunchedEffect(shellViewModel) {
        shellViewModel.deleteConsentPrompts.collect { prompt ->
            deleteConsentLauncher.launch(
                IntentSenderRequest.Builder(prompt.intentSender).build(),
            )
        }
    }
    LaunchedEffect(uiState.voiceEntryState.recentTranscripts.firstOrNull()?.id) {
        val latestTranscript = uiState.voiceEntryState.recentTranscripts.firstOrNull() ?: return@LaunchedEffect
        shellViewModel.ingestVoiceTranscript(latestTranscript.text)
    }

    val requestMediaAccess = {
        mediaPermissionLauncher.launch(MediaAccessPermissions.requiredPermissions())
    }
    val showQuickActions = {
        if (needsNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            shellViewModel.postQuickActionsNotification()
        }
    }
    val openDocumentTree = {
        documentTreeLauncher.launch(null)
    }
    val toggleVoiceCapture = {
        if (uiState.voiceEntryState.isActive) {
            shellViewModel.toggleVoiceCapture()
        } else if (needsAudioPermission(context)) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            shellViewModel.toggleVoiceCapture()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                ShellSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = uiState.selectedSection == section,
                        onClick = { shellViewModel.openSection(section) },
                        icon = {
                            Icon(
                                imageVector = section.icon,
                                contentDescription = section.label,
                            )
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (uiState.selectedSection) {
            ShellSection.Chat -> ChatScreen(
                uiState = uiState,
                innerPadding = innerPadding,
                onToggleVoiceCapture = toggleVoiceCapture,
                onUpdateDraft = shellViewModel::updateChatDraft,
                onSendPrompt = shellViewModel::submitChatPrompt,
                onSubmitSuggestedPrompt = shellViewModel::submitSuggestedChatPrompt,
                onStartNewSession = shellViewModel::startNewChatThread,
                onApprove = shellViewModel::approve,
                onDeny = shellViewModel::deny,
                onRetry = shellViewModel::retryAgentTask,
            )
            ShellSection.Dashboard -> DashboardScreen(
                uiState = uiState,
                innerPadding = innerPadding,
                onApprove = shellViewModel::approve,
                onDeny = shellViewModel::deny,
                onActivateAutomation = shellViewModel::activateScheduledAutomation,
                onPauseAutomation = shellViewModel::pauseScheduledAutomation,
                onOpenHistory = { shellViewModel.openSection(ShellSection.History) },
                onOpenSettings = { shellViewModel.openSection(ShellSection.Settings) },
            )
            ShellSection.History -> HistoryScreen(
                activeChatThread = uiState.activeChatThread,
                chatThreads = uiState.chatThreads,
                chatMessages = uiState.chatState.messages,
                agentTasks = uiState.agentTasks,
                auditEvents = uiState.auditEvents,
                latestNotificationQuickAction = uiState.latestNotificationQuickAction,
                innerPadding = innerPadding,
                onOpenThread = shellViewModel::openChatThread,
            )
            ShellSection.Settings -> SettingsScreen(
                resourceConnections = uiState.resourceConnections,
                cloudDriveConnections = uiState.cloudDriveConnections,
                providerProfiles = uiState.providerProfiles,
                fileIndexState = uiState.fileIndexState,
                fileActionState = uiState.fileActionState,
                approvals = uiState.approvals,
                deviceControlState = uiState.deviceControlState,
                voiceEntryState = uiState.voiceEntryState,
                latestNotificationQuickAction = uiState.latestNotificationQuickAction,
                innerPadding = innerPadding,
                onRequestMediaAccess = requestMediaAccess,
                onOpenDocumentTree = openDocumentTree,
                onRefreshFiles = shellViewModel::refreshFiles,
                onStageCloudDriveConnection = shellViewModel::stageCloudDriveConnection,
                onMarkMockCloudDriveConnected = shellViewModel::markMockCloudDriveConnected,
                onResetCloudDriveConnection = shellViewModel::resetCloudDriveConnection,
                onSetModelProviderEnabled = shellViewModel::setModelProviderEnabled,
                onSetDefaultModelProvider = shellViewModel::setDefaultModelProvider,
                onSelectProviderModel = shellViewModel::selectProviderModel,
                onStoreProviderCredential = shellViewModel::storeModelProviderCredential,
                onClearProviderCredential = shellViewModel::clearModelProviderCredential,
                onToggleVoiceCapture = toggleVoiceCapture,
                onShowQuickActions = showQuickActions,
                onSelectFile = shellViewModel::selectFile,
                onSummarizeFiles = shellViewModel::summarizeCurrentFiles,
                onPlanOrganizeByType = shellViewModel::planOrganizeByType,
                onPlanOrganizeBySource = shellViewModel::planOrganizeBySource,
                onSetForceDeleteConsentForTesting = shellViewModel::setForceDeleteConsentForTesting,
                onRequestOrganizeApproval = shellViewModel::requestOrganizeApproval,
                onRequestDeleteConsent = shellViewModel::requestDeleteConsentForLatestOrganize,
                onShareCurrentFiles = shellViewModel::shareCurrentFiles,
                onStartPairing = shellViewModel::startPairing,
                onApprovePairing = shellViewModel::approvePairing,
                onDenyPairing = shellViewModel::denyPairing,
                onSelectTargetDevice = shellViewModel::selectTargetDevice,
                onSendCurrentFilesToDevice = shellViewModel::sendCurrentFilesToSelectedDevice,
                onArmDirectHttpBridge = shellViewModel::armDirectHttpBridge,
                onUseLoopbackBridge = shellViewModel::useLoopbackBridge,
                onSetTransportValidationMode = shellViewModel::setTransportValidationMode,
                onUseAdbReverseEndpoint = shellViewModel::useAdbReverseEndpoint,
                onUseEmulatorHostEndpoint = shellViewModel::useEmulatorHostEndpoint,
                onRefreshDeviceState = shellViewModel::refreshDeviceState,
                onProbeSelectedDeviceHealth = shellViewModel::probeSelectedDeviceHealth,
                onSendSessionNotification = shellViewModel::sendSessionNotificationToSelectedDevice,
                onOpenCompanionInbox = shellViewModel::openCompanionInboxOnSelectedDevice,
                onOpenLatestActionFolder = shellViewModel::openLatestActionFolderOnSelectedDevice,
                onOpenLatestTransferFolder = shellViewModel::openLatestTransferFolderOnSelectedDevice,
                onOpenActionsFolder = shellViewModel::openActionsFolderOnSelectedDevice,
                onRunOpenLatestActionWorkflow = shellViewModel::runOpenLatestActionWorkflowOnSelectedDevice,
                onRunOpenLatestTransferWorkflow = shellViewModel::runOpenLatestTransferWorkflowOnSelectedDevice,
                onRunOpenActionsFolderWorkflow = shellViewModel::runOpenActionsFolderWorkflowOnSelectedDevice,
                onDrainTransferOutboxNow = shellViewModel::drainTransferOutboxNow,
                onRetryFailedTransfers = shellViewModel::retryFailedTransfersForSelectedDevice,
            )
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
}

private fun needsAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) != PackageManager.PERMISSION_GRANTED
}

private val ShellSection.icon: ImageVector
    get() = when (this) {
        ShellSection.Chat -> Icons.Default.Home
        ShellSection.Dashboard -> Icons.Default.CheckCircle
        ShellSection.History -> Icons.Default.FolderOpen
        ShellSection.Settings -> Icons.Default.DevicesOther
    }

@Composable
private fun ChatScreen(
    uiState: ShellUiState,
    innerPadding: PaddingValues,
    onToggleVoiceCapture: () -> Unit,
    onUpdateDraft: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onSubmitSuggestedPrompt: (String) -> Unit,
    onStartNewSession: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val pendingApprovals = uiState.approvals
        .filter { it.status == ApprovalInboxStatus.Pending }
        .take(maxChatInlineApprovals)
    val retryableTasks = uiState.agentTasks
        .filter(::isChatRetryableTask)
        .take(maxChatInlineRetries)
    val quickPrompts = buildChatQuickPrompts(uiState)
    val selectedCompanion = uiState.deviceControlState.pairedDevices.firstOrNull {
        it.id == uiState.deviceControlState.selectedTargetDeviceId
    } ?: uiState.deviceControlState.pairedDevices.firstOrNull { device ->
        device.transportMode == DeviceTransportMode.DirectHttp
    } ?: uiState.deviceControlState.pairedDevices.firstOrNull()
    val latestCompanionProbeAudit = uiState.auditEvents.firstOrNull { event ->
        event.headline == "devices.health_probe" &&
            (selectedCompanion?.name == null || event.details.contains(selectedCompanion.name))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(
                eyebrow = "Chat-first shell",
                title = "Talk to Makoion",
                summary = "Ask Makoion to use connected files, drives, companions, MCP endpoints, and APIs from this phone-hosted agent shell.",
            )
        }
        uiState.activeChatThread?.let { thread ->
            item {
                ChatSessionCard(
                    thread = thread,
                    onStartNewSession = onStartNewSession,
                )
            }
        }
        if (quickPrompts.isNotEmpty()) {
            item {
                ChatQuickStartCard(
                    prompts = quickPrompts,
                    selectedCompanionName = selectedCompanion?.name,
                    selectedCompanionPinnedByUser = uiState.deviceControlState.isTargetDevicePinnedByUser,
                    companionProbe = uiState.deviceControlState.companionProbe,
                    latestCompanionProbeAudit = latestCompanionProbeAudit,
                    onSubmitPrompt = onSubmitSuggestedPrompt,
                )
            }
        }
        if (uiState.chatState.isProcessing) {
            item {
                StatusCard(
                    title = "Agent runtime active",
                    summary = "Makoion is planning or executing this turn against the resources currently connected to the phone.",
                    status = "Working",
                    icon = Icons.Default.NotificationsActive,
                )
            }
        }
            items(
                items = uiState.chatState.messages,
                key = { it.id },
            ) { message ->
            ChatMessageCard(
                message = message,
                tasks = uiState.agentTasks,
                approvals = uiState.approvals,
                onApprove = onApprove,
                onDeny = onDeny,
                onRetry = onRetry,
                onSubmitPrompt = onSubmitSuggestedPrompt,
            )
        }
        if (pendingApprovals.isNotEmpty() || retryableTasks.isNotEmpty()) {
            item {
                ChatInlineActionsCard(
                    approvals = pendingApprovals,
                    retryableTasks = retryableTasks,
                    remainingApprovalCount = uiState.approvals.count { it.status == ApprovalInboxStatus.Pending } - pendingApprovals.size,
                    remainingRetryCount = uiState.agentTasks.count(::isChatRetryableTask) - retryableTasks.size,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onRetry = onRetry,
                )
            }
        }
        item {
            ChatComposerCard(
                draft = uiState.chatState.draft,
                isProcessing = uiState.chatState.isProcessing,
                voiceActive = uiState.voiceEntryState.isActive,
                onUpdateDraft = onUpdateDraft,
                onToggleVoiceCapture = onToggleVoiceCapture,
                onSendPrompt = onSendPrompt,
            )
        }
    }
}

private data class ChatQuickPrompt(
    val label: String,
    val prompt: String,
)

private fun buildChatQuickPrompts(uiState: ShellUiState): List<ChatQuickPrompt> {
    return buildList {
        add(
            ChatQuickPrompt(
                label = "Refresh resources",
                prompt = promptRefreshResources,
            ),
        )
        if (uiState.fileIndexState.permissionGranted && uiState.fileIndexState.indexedItems.isNotEmpty()) {
            add(
                ChatQuickPrompt(
                    label = "Summarize files",
                    prompt = promptSummarizeCurrentFiles,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Organize by type",
                    prompt = promptOrganizeFilesByType,
                ),
            )
        } else {
            add(
                ChatQuickPrompt(
                    label = "Open settings",
                    prompt = promptOpenSettingsAndResources,
                ),
            )
        }
        if (uiState.approvals.any { it.status == ApprovalInboxStatus.Pending }) {
            add(
                ChatQuickPrompt(
                    label = "Review approvals",
                    prompt = promptShowDashboardAndApprovals,
                ),
            )
        } else {
            add(
                ChatQuickPrompt(
                    label = "Show dashboard",
                    prompt = promptShowDashboard,
                ),
            )
        }
        if (uiState.deviceControlState.pairedDevices.isNotEmpty()) {
            add(
                ChatQuickPrompt(
                    label = "Check companion health",
                    prompt = promptCheckCompanionHealth,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Send notification",
                    prompt = promptSendDesktopNotification,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Run latest action workflow",
                    prompt = promptRunOpenLatestActionWorkflow,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Run latest workflow",
                    prompt = promptRunOpenLatestTransferWorkflow,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Run actions workflow",
                    prompt = promptRunOpenActionsFolderWorkflow,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Open latest action",
                    prompt = promptOpenLatestActionFolder,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Open latest transfer",
                    prompt = promptOpenLatestTransferFolder,
                ),
            )
            add(
                ChatQuickPrompt(
                    label = "Open companion inbox",
                    prompt = promptOpenCompanionInbox,
                ),
            )
        }
    }
}

@Composable
private fun ChatQuickStartCard(
    prompts: List<ChatQuickPrompt>,
    selectedCompanionName: String?,
    selectedCompanionPinnedByUser: Boolean,
    companionProbe: CompanionProbeState,
    latestCompanionProbeAudit: AuditTrailEvent?,
    onSubmitPrompt: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Quick starts",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Start common tasks from chat. Each shortcut submits a real agent turn through the task runtime.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            selectedCompanionName?.let { companionName ->
                Text(
                    text = if (selectedCompanionPinnedByUser) {
                        "Pinned companion target: $companionName. Tap the same device again in Settings to return to auto-select."
                    } else {
                        "Auto target: $companionName. Makoion follows the newest Direct HTTP companion unless you pin a device in Settings."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawGreen,
                )
            }
            when {
                companionProbe.isChecking -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ClawGold.copy(alpha = 0.14f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Latest companion status",
                                style = MaterialTheme.typography.labelLarge,
                                color = ClawInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Checking companion health...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClawGold,
                            )
                        }
                    }
                }
                companionProbe.result != null -> {
                    val probe = companionProbe.result
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = companionProbeStatusColor(probe.status).copy(alpha = 0.12f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Latest companion status",
                                style = MaterialTheme.typography.labelLarge,
                                color = ClawInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = companionProbeStatusLabel(probe.status),
                                style = MaterialTheme.typography.titleSmall,
                                color = companionProbeStatusColor(probe.status),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${probe.summary} • ${probe.checkedAtLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = companionProbeDetailLabel(probe),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (probe.status == CompanionHealthStatus.Healthy) {
                                Text(
                                    text = "This is the latest probe result even if older chat history below still shows earlier failures.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClawInk.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
                latestCompanionProbeAudit != null -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = companionProbeAuditStatusColor(latestCompanionProbeAudit.result).copy(alpha = 0.12f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Latest companion status",
                                style = MaterialTheme.typography.labelLarge,
                                color = ClawInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = companionProbeAuditStatusLabel(latestCompanionProbeAudit.result),
                                style = MaterialTheme.typography.titleSmall,
                                color = companionProbeAuditStatusColor(latestCompanionProbeAudit.result),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${companionProbeAuditSummary(latestCompanionProbeAudit)} • ${latestCompanionProbeAudit.createdAtLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            companionProbeAuditDetail(latestCompanionProbeAudit)?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = "Recovered from recent device history while the current chat session was restarted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ClawInk.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
            prompts.chunked(2).forEach { rowPrompts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowPrompts.forEach { prompt ->
                        AssistChip(
                            onClick = { onSubmitPrompt(prompt.prompt) },
                            label = { Text(prompt.label) },
                            modifier = Modifier.weight(1f),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = ClawGreen.copy(alpha = 0.12f),
                                labelColor = ClawInk,
                            ),
                        )
                    }
                    if (rowPrompts.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInlineActionsCard(
    approvals: List<ApprovalInboxItem>,
    retryableTasks: List<AgentTaskRecord>,
    remainingApprovalCount: Int,
    remainingRetryCount: Int,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pending chat actions",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Resolve reviews and blocked retries without leaving the conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            approvals.forEach { approval ->
                ChatApprovalInlineCard(
                    approval = approval,
                    onApprove = { onApprove(approval.id) },
                    onDeny = { onDeny(approval.id) },
                )
            }
            retryableTasks.forEach { task ->
                ChatRetryInlineCard(
                    task = task,
                    onRetry = { onRetry(task.id) },
                )
            }
            if (remainingApprovalCount > 0 || remainingRetryCount > 0) {
                Text(
                    text = buildString {
                        append("More actions remain")
                        if (remainingApprovalCount > 0) {
                            append(" • $remainingApprovalCount approval")
                            if (remainingApprovalCount > 1) {
                                append("s")
                            }
                        }
                        if (remainingRetryCount > 0) {
                            append(" • $remainingRetryCount retry")
                            if (remainingRetryCount > 1) {
                                append(" tasks")
                            } else {
                                append(" task")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGold,
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    uiState: ShellUiState,
    innerPadding: PaddingValues,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onActivateAutomation: (String) -> Unit,
    onPauseAutomation: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val pendingApprovals = uiState.approvals.count { it.status == ApprovalInboxStatus.Pending }
    val activeTasks = uiState.agentTasks.filter { task ->
        task.status !in listOf(
            AgentTaskStatus.Succeeded,
            AgentTaskStatus.Failed,
            AgentTaskStatus.Cancelled,
        )
    }
    val connectedResourceCount = buildList {
        if (uiState.fileIndexState.permissionGranted) {
            add("media")
        }
        if (uiState.fileIndexState.documentTreeCount > 0) {
            add("documents")
        }
        if (uiState.deviceControlState.pairedDevices.isNotEmpty()) {
            add("companions")
        }
    }.size
    val selectedDevice = uiState.deviceControlState.pairedDevices.firstOrNull {
        it.id == uiState.deviceControlState.selectedTargetDeviceId
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(
                eyebrow = "Agent activity",
                title = uiState.phaseTitle,
                summary = uiState.summary,
            )
        }
        item {
            StatusCard(
                title = "Pending reviews",
                summary = "High-risk execution stays reviewable before the agent commits changes.",
                status = if (pendingApprovals == 0) "Clear" else "$pendingApprovals pending",
                icon = Icons.Default.CheckCircle,
            )
        }
        item {
            StatusCard(
                title = "Connected resources",
                summary = "Media permission, document roots, and paired companions currently exposed to the shell.",
                status = if (connectedResourceCount == 0) "None yet" else "$connectedResourceCount active",
                icon = Icons.Default.DevicesOther,
            )
        }
        if (activeTasks.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No active tasks",
                    summary = "New chat turns will create durable agent tasks here, including waiting-user and waiting-resource states.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "Active tasks",
                    subtitle = "These tasks are still running, waiting for approval, or blocked on resource setup.",
                )
            }
            items(
                items = activeTasks.take(4),
                key = { it.id },
            ) { task ->
                AgentTaskCard(task = task)
            }
        }
        if (uiState.scheduledAutomations.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No recorded automations",
                    summary = "Recurring chat requests will be stored here as durable automation skeletons before the scheduler worker is fully wired.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "Scheduled automations",
                    subtitle = "Recurring requests stay visible here with placeholder status until background scheduling and delivery executors are fully implemented.",
                )
            }
            items(
                items = uiState.scheduledAutomations.take(4),
                key = { it.id },
            ) { automation ->
                ScheduledAutomationCard(
                    automation = automation,
                    onActivate = { onActivateAutomation(automation.id) },
                    onPause = { onPauseAutomation(automation.id) },
                )
            }
        }
        item {
            WorkflowStatusCard(snapshot = organizeWorkflowSnapshot(uiState.fileActionState, uiState.approvals))
        }
        item {
            WorkflowStatusCard(
                snapshot = transferWorkflowSnapshot(
                    selectedDevice = selectedDevice,
                    diagnostics = uiState.deviceControlState.transportDiagnostics,
                    companionProbe = uiState.deviceControlState.companionProbe,
                ),
            )
        }
        item {
            ApprovalAuditShortcutCard(
                latestAuditEvent = uiState.auditEvents.firstOrNull(),
                onJumpToAudit = onOpenHistory,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open settings")
                }
                OutlinedButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open history")
                }
            }
        }
        if (pendingApprovals == 0) {
            item {
                EmptyStateCard(
                    title = "No pending approvals",
                    summary = "When the agent needs confirmation before a risky action, the request will show up here.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "Approval queue",
                    subtitle = "Dashboard keeps the current review queue close to the main chat flow.",
                )
            }
            items(
                items = uiState.approvals.filter { it.status == ApprovalInboxStatus.Pending },
                key = { it.id },
            ) { approval ->
                ApprovalInboxCard(
                    item = approval,
                    onApprove = onApprove,
                    onDeny = onDeny,
                )
            }
        }
        uiState.fileActionState.lastOrganizeResult?.let { result ->
            item {
                OrganizeExecutionResultCard(
                    result = result,
                    updatedAtLabel = uiState.fileActionState.lastOrganizeUpdatedAtLabel,
                    recovered = uiState.fileActionState.lastOrganizeRecovered,
                    onRequestDeleteConsent = {},
                )
            }
        }
        item {
            NotificationQuickActionCard(event = uiState.latestNotificationQuickAction)
        }
        items(uiState.overviewCards) { card ->
            ShellCardView(card = card, icon = Icons.Default.Home)
        }
    }
}

@Composable
private fun HistoryScreen(
    activeChatThread: ChatThreadRecord?,
    chatThreads: List<ChatThreadRecord>,
    chatMessages: List<ChatMessage>,
    agentTasks: List<AgentTaskRecord>,
    auditEvents: List<AuditTrailEvent>,
    latestNotificationQuickAction: AuditTrailEvent?,
    innerPadding: PaddingValues,
    onOpenThread: (String) -> Unit,
) {
    val completedTasks = agentTasks.filter { task ->
        task.status in listOf(
            AgentTaskStatus.Succeeded,
            AgentTaskStatus.Failed,
            AgentTaskStatus.Cancelled,
        )
    }
    val recentChatMessages = chatMessages.takeLast(4)
    val userMessageCount = chatMessages.count { it.role == ChatMessageRole.User }
    val assistantMessageCount = chatMessages.count { it.role == ChatMessageRole.Assistant }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "History",
                subtitle = "Makoion keeps durable chat sessions, task outcomes, and audit traces on-device so the agent can explain what it did.",
            )
        }
        if (chatThreads.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No chat sessions yet",
                    summary = "The first durable conversation thread will appear here once the agent transcript is created.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "Chat sessions",
                    subtitle = "Conversation history survives restarts and anchors later agent decisions to a local transcript.",
                )
            }
            items(
                items = chatThreads,
                key = { it.id },
            ) { thread ->
                ChatThreadHistoryCard(
                    thread = thread,
                    isActive = thread.id == activeChatThread?.id,
                    onOpenThread = onOpenThread,
                )
            }
        }
        activeChatThread?.let { thread ->
            item {
                ChatTranscriptHistoryCard(
                    thread = thread,
                    recentMessages = recentChatMessages,
                    userMessageCount = userMessageCount,
                    assistantMessageCount = assistantMessageCount,
                )
            }
        }
        item {
            NotificationQuickActionCard(event = latestNotificationQuickAction)
        }
        if (completedTasks.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No completed tasks yet",
                    summary = "Finished, failed, or cancelled agent tasks will accumulate here alongside the audit trail.",
                )
            }
        } else {
            item {
                SectionHeader(
                    title = "Task records",
                    subtitle = "Completed tasks keep the durable outcome of each chat turn, separate from low-level audit events.",
                )
            }
            items(
                items = completedTasks.take(8),
                key = { it.id },
            ) { task ->
                AgentTaskCard(task = task)
            }
        }
        if (auditEvents.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No history yet",
                    summary = "Audit records will land here as soon as the shell executes or reviews work.",
                )
            }
        } else {
            items(
                items = auditEvents,
                key = { it.id },
            ) { event ->
                AuditEventCard(event = event)
            }
        }
    }
}

@Composable
private fun ChatThreadHistoryCard(
    thread: ChatThreadRecord,
    isActive: Boolean,
    onOpenThread: (String) -> Unit,
) {
    Card(
        modifier = Modifier.clickable { onOpenThread(thread.id) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${thread.messageCount} messages • Updated ${thread.updatedAtLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClawGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = thread.lastMessagePreview ?: "No transcript content yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (isActive) {
                    "Current session"
                } else {
                    "Tap to open this session"
                },
                style = MaterialTheme.typography.labelMedium,
                color = ClawGreen,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ChatSessionCard(
    thread: ChatThreadRecord,
    onStartNewSession: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Current session",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${thread.title} • ${thread.messageCount} messages • Updated ${thread.updatedAtLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onStartNewSession,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New session")
            }
        }
    }
}

@Composable
private fun ChatTranscriptHistoryCard(
    thread: ChatThreadRecord,
    recentMessages: List<ChatMessage>,
    userMessageCount: Int,
    assistantMessageCount: Int,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Current session snapshot",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Thread ${thread.title} has $userMessageCount user messages and $assistantMessageCount assistant messages. Last updated ${thread.updatedAtLabel}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recentMessages.isEmpty()) {
                Text(
                    text = "Transcript messages will appear here once the session collects turns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                recentMessages.forEach { message ->
                    Text(
                        text = "${if (message.role == ChatMessageRole.User) "You" else "Makoion"} • ${message.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.role == ChatMessageRole.User) {
                            ClawGreen
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    resourceConnections: List<ResourceConnectionSummary>,
    cloudDriveConnections: List<CloudDriveConnectionState>,
    providerProfiles: List<ModelProviderProfileState>,
    fileIndexState: FileIndexState,
    fileActionState: FileActionState,
    approvals: List<ApprovalInboxItem>,
    deviceControlState: DeviceControlState,
    voiceEntryState: VoiceEntryState,
    latestNotificationQuickAction: AuditTrailEvent?,
    innerPadding: PaddingValues,
    onRequestMediaAccess: () -> Unit,
    onOpenDocumentTree: () -> Unit,
    onRefreshFiles: () -> Unit,
    onStageCloudDriveConnection: (CloudDriveProviderKind) -> Unit,
    onMarkMockCloudDriveConnected: (CloudDriveProviderKind) -> Unit,
    onResetCloudDriveConnection: (CloudDriveProviderKind) -> Unit,
    onSetModelProviderEnabled: (String, Boolean) -> Unit,
    onSetDefaultModelProvider: (String) -> Unit,
    onSelectProviderModel: (String, String) -> Unit,
    onStoreProviderCredential: (String, String) -> Unit,
    onClearProviderCredential: (String) -> Unit,
    onToggleVoiceCapture: () -> Unit,
    onShowQuickActions: () -> Unit,
    onSelectFile: (String) -> Unit,
    onSummarizeFiles: () -> Unit,
    onPlanOrganizeByType: () -> Unit,
    onPlanOrganizeBySource: () -> Unit,
    onSetForceDeleteConsentForTesting: (Boolean) -> Unit,
    onRequestOrganizeApproval: () -> Unit,
    onRequestDeleteConsent: () -> Unit,
    onShareCurrentFiles: () -> Unit,
    onStartPairing: () -> Unit,
    onApprovePairing: (String) -> Unit,
    onDenyPairing: (String) -> Unit,
    onSelectTargetDevice: (String) -> Unit,
    onSendCurrentFilesToDevice: () -> Unit,
    onArmDirectHttpBridge: (String) -> Unit,
    onUseLoopbackBridge: (String) -> Unit,
    onSetTransportValidationMode: (String, TransportValidationMode) -> Unit,
    onUseAdbReverseEndpoint: (String) -> Unit,
    onUseEmulatorHostEndpoint: (String) -> Unit,
    onRefreshDeviceState: () -> Unit,
    onProbeSelectedDeviceHealth: () -> Unit,
    onSendSessionNotification: () -> Unit,
    onOpenCompanionInbox: () -> Unit,
    onOpenLatestActionFolder: () -> Unit,
    onOpenLatestTransferFolder: () -> Unit,
    onOpenActionsFolder: () -> Unit,
    onRunOpenLatestActionWorkflow: () -> Unit,
    onRunOpenLatestTransferWorkflow: () -> Unit,
    onRunOpenActionsFolderWorkflow: () -> Unit,
    onDrainTransferOutboxNow: () -> Unit,
    onRetryFailedTransfers: () -> Unit,
) {
    val selectedDevice = deviceControlState.pairedDevices.firstOrNull {
        it.id == deviceControlState.selectedTargetDeviceId
    }
    var showAdvancedTools by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Settings and connections",
                subtitle = "Connected resources, permissions, and companion registrations live here. Core usage should stay in Chat, Dashboard, and History.",
            )
        }
        item {
            SectionHeader(
                title = "Resource stack",
                subtitle = "Makoion should understand resources in priority order: phone storage first, then cloud drives, then external companions, then MCP/API connections.",
            )
        }
        items(
            items = resourceConnections,
            key = { it.id },
        ) { resource ->
            ResourceConnectionCard(resource = resource)
        }
        if (cloudDriveConnections.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Cloud drive connectors",
                    subtitle = "Priority 2 connectors are seeded here first. OAuth and token vault wiring are still pending, so these actions only record staged or mock-ready placeholder states.",
                )
            }
            items(
                items = cloudDriveConnections,
                key = { it.provider.providerId },
            ) { connection ->
                CloudDriveConnectionCard(
                    connection = connection,
                    onStage = { onStageCloudDriveConnection(connection.provider) },
                    onMarkMockReady = { onMarkMockCloudDriveConnected(connection.provider) },
                    onReset = { onResetCloudDriveConnection(connection.provider) },
                )
            }
        }
        if (providerProfiles.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "AI model providers",
                    subtitle = "Choose the default provider and model that the phone agent should carry into future routed turns, and store provider secrets in the phone vault.",
                )
            }
            items(
                items = providerProfiles,
                key = { it.providerId },
            ) { profile ->
                ModelProviderProfileCard(
                    profile = profile,
                    onSetEnabled = { enabled ->
                        onSetModelProviderEnabled(profile.providerId, enabled)
                    },
                    onSetDefault = {
                        onSetDefaultModelProvider(profile.providerId)
                    },
                    onSelectModel = { model ->
                        onSelectProviderModel(profile.providerId, model)
                    },
                    onStoreCredential = { secret ->
                        onStoreProviderCredential(profile.providerId, secret)
                    },
                    onClearCredential = {
                        onClearProviderCredential(profile.providerId)
                    },
                )
            }
        }
        item {
            FileIndexControlCard(
                state = fileIndexState,
                onRequestMediaAccess = onRequestMediaAccess,
                onOpenDocumentTree = onOpenDocumentTree,
                onRefreshFiles = onRefreshFiles,
            )
        }
        item {
            VoiceEntryCard(
                state = voiceEntryState,
                onToggleVoiceCapture = onToggleVoiceCapture,
                onShowQuickActions = onShowQuickActions,
            )
        }
        item {
            NotificationQuickActionCard(event = latestNotificationQuickAction)
        }
        item {
            SectionHeader(
                title = "Advanced capability tools",
                subtitle = "Capability probes and debug-time execution controls stay here so the main product flow can remain chat-first.",
            )
        }
        item {
            OutlinedButton(
                onClick = { showAdvancedTools = !showAdvancedTools },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvancedTools) "Hide advanced tools" else "Show advanced tools")
            }
        }
        if (showAdvancedTools) {
            item {
                FileActionConsoleCard(
                    fileActionState = fileActionState,
                    deviceControlState = deviceControlState,
                    hasIndexedFiles = fileIndexState.indexedItems.isNotEmpty(),
                    onSummarizeFiles = onSummarizeFiles,
                    onPlanOrganizeByType = onPlanOrganizeByType,
                    onPlanOrganizeBySource = onPlanOrganizeBySource,
                    onSetForceDeleteConsentForTesting = onSetForceDeleteConsentForTesting,
                    onRequestOrganizeApproval = onRequestOrganizeApproval,
                    onRequestDeleteConsent = onRequestDeleteConsent,
                    onShareCurrentFiles = onShareCurrentFiles,
                    onSelectTargetDevice = onSelectTargetDevice,
                    onSendCurrentFilesToDevice = onSendCurrentFilesToDevice,
                )
            }
            item {
                WorkflowStatusCard(snapshot = organizeWorkflowSnapshot(fileActionState, approvals))
            }
            if (fileIndexState.indexedItems.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = if (fileIndexState.permissionGranted) {
                            "No indexed resources yet"
                        } else {
                            "Media access has not been granted"
                        },
                        summary = fileIndexState.summary,
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = "Indexed resources",
                        subtitle = "Advanced preview of the currently indexed local resources.",
                    )
                }
                items(
                    items = fileIndexState.indexedItems.take(8),
                    key = { it.id },
                ) { file ->
                    IndexedFileCard(
                        file = file,
                        selected = fileActionState.selectedFileId == file.id,
                        onClick = { onSelectFile(file.id) },
                    )
                }
            }
            fileActionState.summary?.let { summary ->
                item {
                    FileSummaryCard(summary = summary)
                }
            }
            fileActionState.organizePlan?.let { plan ->
                item {
                    OrganizePlanCard(plan = plan)
                }
            }
            fileActionState.lastOrganizeResult?.let { result ->
                item {
                    OrganizeExecutionResultCard(
                        result = result,
                        updatedAtLabel = fileActionState.lastOrganizeUpdatedAtLabel,
                        recovered = fileActionState.lastOrganizeRecovered,
                        onRequestDeleteConsent = onRequestDeleteConsent,
                    )
                }
            }
        }
        item {
            Button(
                onClick = onStartPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start pairing session")
            }
        }
        if (deviceControlState.pairingSessions.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Pending pairing sessions",
                    subtitle = "Companion authorization requests stay reviewable from Settings.",
                )
            }
            items(
                items = deviceControlState.pairingSessions,
                key = { it.id },
            ) { session ->
                PairingSessionCard(
                    session = session,
                    onApprovePairing = onApprovePairing,
                    onDenyPairing = onDenyPairing,
                )
            }
        }
        if (deviceControlState.pairedDevices.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Paired companions",
                    subtitle = "Connected companions live here. Tap one to pin it as the target, or tap the pinned card again to return to auto-select.",
                )
            }
            items(
                items = deviceControlState.pairedDevices,
                key = { it.id },
            ) { device ->
                DeviceCard(
                    device = device,
                    selected = device.id == deviceControlState.selectedTargetDeviceId,
                    selectionPinnedByUser = deviceControlState.isTargetDevicePinnedByUser,
                    onClick = { onSelectTargetDevice(device.id) },
                    onArmDirectHttpBridge = onArmDirectHttpBridge,
                    onUseLoopbackBridge = onUseLoopbackBridge,
                    onSetTransportValidationMode = onSetTransportValidationMode,
                    onUseAdbReverseEndpoint = { onUseAdbReverseEndpoint(device.id) },
                    onUseEmulatorHostEndpoint = { onUseEmulatorHostEndpoint(device.id) },
                )
            }
        }
        if (showAdvancedTools) {
            item {
                SectionHeader(
                    title = "Advanced companion diagnostics",
                    subtitle = "Transport validation, workflow probes, and recovery traces stay here instead of leading the product UI.",
                )
            }
            item {
                WorkflowStatusCard(
                    snapshot = transferWorkflowSnapshot(
                        selectedDevice = selectedDevice,
                        diagnostics = deviceControlState.transportDiagnostics,
                        companionProbe = deviceControlState.companionProbe,
                    ),
                )
            }
            item {
                BridgeDebugCard(
                    selectedDevice = selectedDevice,
                    diagnostics = deviceControlState.transportDiagnostics,
                    recoveryState = deviceControlState.recoveryState,
                    companionProbe = deviceControlState.companionProbe,
                    companionNotify = deviceControlState.companionNotify,
                    companionAppOpen = deviceControlState.companionAppOpen,
                    companionWorkflowRun = deviceControlState.companionWorkflowRun,
                    onRefreshDeviceState = onRefreshDeviceState,
                    onProbeSelectedDeviceHealth = onProbeSelectedDeviceHealth,
                    onSendSessionNotification = onSendSessionNotification,
                    onOpenCompanionInbox = onOpenCompanionInbox,
                    onOpenLatestActionFolder = onOpenLatestActionFolder,
                    onOpenLatestTransferFolder = onOpenLatestTransferFolder,
                    onOpenActionsFolder = onOpenActionsFolder,
                    onRunOpenLatestActionWorkflow = onRunOpenLatestActionWorkflow,
                    onRunOpenLatestTransferWorkflow = onRunOpenLatestTransferWorkflow,
                    onRunOpenActionsFolderWorkflow = onRunOpenActionsFolderWorkflow,
                    onDrainTransferOutboxNow = onDrainTransferOutboxNow,
                    onRetryFailedTransfers = onRetryFailedTransfers,
                )
            }
            item {
                TransportAuditTraceCard(
                    selectedDevice = selectedDevice,
                    events = deviceControlState.transportAuditEvents,
                )
            }
            if (deviceControlState.transferDrafts.isNotEmpty()) {
                items(
                    items = deviceControlState.transferDrafts,
                    key = { it.id },
                ) { draft ->
                    TransferDraftCard(draft = draft)
                }
            }
        }
    }
}

@Composable
private fun ChatMessageCard(
    message: ChatMessage,
    tasks: List<AgentTaskRecord>,
    approvals: List<ApprovalInboxItem>,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRetry: (String) -> Unit,
    onSubmitPrompt: (String) -> Unit,
) {
    val relatedTask = message.linkedTaskId?.let { taskId ->
        tasks.firstOrNull { it.id == taskId }
    }
    val relatedApproval = message.linkedApprovalId?.let { approvalId ->
        approvals.firstOrNull { it.id == approvalId }
    } ?: relatedTask?.approvalRequestId?.let { approvalId ->
        approvals.firstOrNull { it.id == approvalId }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (message.role == ChatMessageRole.User) {
                ClawGreen.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (message.role == ChatMessageRole.User) "You" else "Makoion",
                style = MaterialTheme.typography.labelLarge,
                color = if (message.role == ChatMessageRole.User) ClawGreen else ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            if (message.role == ChatMessageRole.Assistant && (relatedTask != null || relatedApproval != null)) {
                ChatMessageContextCard(
                    task = relatedTask,
                    approval = relatedApproval,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onRetry = onRetry,
                    onSubmitPrompt = onSubmitPrompt,
                )
            }
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatMessageContextCard(
    task: AgentTaskRecord?,
    approval: ApprovalInboxItem?,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRetry: (String) -> Unit,
    onSubmitPrompt: (String) -> Unit,
) {
    val primaryDetail = task?.summary ?: approval?.summary
    val secondaryDetail = when {
        task != null && approval != null && approval.summary != task.summary -> approval.summary
        else -> null
    }
    val followUp = chatMessageFollowUp(task = task, approval = approval)
    Surface(
        color = ClawGold.copy(alpha = 0.08f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (task?.title ?: approval?.title)?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                task?.let {
                    AssistChip(
                        onClick = {},
                        label = { Text("Task • ${agentTaskStatusLabel(it.status)}") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = agentTaskStatusColor(it.status).copy(alpha = 0.16f),
                            labelColor = ClawInk,
                        ),
                    )
                }
                approval?.let {
                    AssistChip(
                        onClick = {},
                        label = { Text("Approval • ${statusLabel(it.status)}") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = statusColor(it.status).copy(alpha = 0.16f),
                            labelColor = ClawInk,
                        ),
                    )
                }
                task?.let {
                    AssistChip(
                        onClick = {},
                        label = { Text("Next • ${agentDestinationLabel(it.destination)}") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = ClawGreen.copy(alpha = 0.12f),
                            labelColor = ClawInk,
                        ),
                    )
                }
                task?.plannerMode?.let {
                    AssistChip(
                        onClick = {},
                        label = { Text("Planner • ${agentPlannerModeLabel(it)}") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = plannerModeColor(it).copy(alpha = 0.16f),
                            labelColor = ClawInk,
                        ),
                    )
                }
            }
            primaryDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task?.plannerSummary?.takeIf { it.isNotBlank() }?.let { plannerSummary ->
                Text(
                    text = "Planner summary • $plannerSummary",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawGreen,
                )
            }
            task?.plannerCapabilities?.takeIf { it.isNotEmpty() }?.let { capabilities ->
                Text(
                    text = "Capabilities • ${capabilities.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task?.plannerResources?.takeIf { it.isNotEmpty() }?.let { resources ->
                Text(
                    text = "Resources • ${resources.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            secondaryDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task?.let {
                Text(
                    text = "Updated ${it.updatedAtLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = agentTaskStatusColor(it.status),
                )
            } ?: approval?.let {
                Text(
                    text = "Requested ${it.requestedAtLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = riskColor(it.risk),
                )
            }
            followUp?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (approval?.status == ApprovalInboxStatus.Pending) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onDeny(approval.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Deny")
                    }
                    FilledTonalButton(
                        onClick = { onApprove(approval.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Approve")
                    }
                }
            } else if (task != null && isChatRetryableTask(task)) {
                FilledTonalButton(
                    onClick = { onRetry(task.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry task")
                }
            }
            if (task?.actionKey == companionHealthProbeActionKeyForChat &&
                task.status == AgentTaskStatus.Succeeded
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSubmitPrompt(promptSendDesktopNotification) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Send notification")
                    }
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptRunOpenActionsFolderWorkflow) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Run actions workflow")
                    }
                }
            }
            if (task?.actionKey == companionSessionNotifyActionKeyForChat &&
                task.status == AgentTaskStatus.Succeeded
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSubmitPrompt(promptRunOpenActionsFolderWorkflow) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Run actions workflow")
                    }
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenCompanionInbox) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open companion inbox")
                    }
                }
            }
            if (task?.actionKey == filesTransferActionKeyForChat &&
                task.status in listOf(AgentTaskStatus.Running, AgentTaskStatus.Succeeded)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSubmitPrompt(promptOpenLatestTransferFolder) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open latest transfer")
                    }
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenCompanionInbox) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open companion inbox")
                    }
                }
            }
            if (task?.actionKey == companionAppOpenActionKeyForChat &&
                task.status != AgentTaskStatus.WaitingResource
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenCompanionInbox) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Inbox")
                    }
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenLatestTransferFolder) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Latest")
                    }
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenActionsFolder) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Actions")
                    }
                }
            }
            if (task?.actionKey == companionWorkflowRunActionKeyForChat &&
                task.status != AgentTaskStatus.WaitingResource
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenLatestTransferFolder) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Latest")
                    }
                    OutlinedButton(
                        onClick = { onSubmitPrompt(promptOpenActionsFolder) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Actions")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatApprovalInlineCard(
    approval: ApprovalInboxItem,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        color = riskColor(approval.risk).copy(alpha = 0.08f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = approval.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(statusLabel(approval.status)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = statusColor(approval.status).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = approval.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Risk ${approval.risk.name.lowercase()} • Requested ${approval.requestedAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = riskColor(approval.risk),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Deny")
                }
                FilledTonalButton(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Approve")
                }
            }
        }
    }
}

@Composable
private fun ChatRetryInlineCard(
    task: AgentTaskRecord,
    onRetry: () -> Unit,
) {
    Surface(
        color = agentTaskStatusColor(task.status).copy(alpha = 0.08f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(agentTaskStatusLabel(task.status)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = agentTaskStatusColor(task.status).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = task.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retry task")
            }
        }
    }
}

@Composable
private fun ChatComposerCard(
    draft: String,
    isProcessing: Boolean,
    voiceActive: Boolean,
    onUpdateDraft: (String) -> Unit,
    onToggleVoiceCapture: () -> Unit,
    onSendPrompt: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Conversation",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onUpdateDraft,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = {
                    Text("Ask Makoion to use your connected resources.")
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleVoiceCapture,
                    enabled = !isProcessing || voiceActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (voiceActive) "Stop voice" else "Voice")
                }
                FilledTonalButton(
                    onClick = onSendPrompt,
                    enabled = !isProcessing && draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isProcessing) "Working..." else "Send")
                }
            }
        }
    }
}

@Composable
private fun OverviewScreen(
    uiState: ShellUiState,
    innerPadding: PaddingValues,
    onRequestMediaAccess: () -> Unit,
    onOpenDocumentTree: () -> Unit,
    onRefreshFiles: () -> Unit,
    onToggleVoiceCapture: () -> Unit,
    onShowQuickActions: () -> Unit,
    onOpenApprovals: () -> Unit,
) {
    val pendingApprovals = uiState.approvals.count { it.status == ApprovalInboxStatus.Pending }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            HeroCard(
                eyebrow = "Native Android shell",
                title = uiState.phaseTitle,
                summary = uiState.summary,
            )
        }
        item {
            ActionCard(
                fileIndexState = uiState.fileIndexState,
                pendingApprovals = pendingApprovals,
                voiceEntryState = uiState.voiceEntryState,
                onRequestMediaAccess = onRequestMediaAccess,
                onOpenDocumentTree = onOpenDocumentTree,
                onRefreshFiles = onRefreshFiles,
                onToggleVoiceCapture = onToggleVoiceCapture,
                onShowQuickActions = onShowQuickActions,
                onOpenApprovals = onOpenApprovals,
            )
        }
        item {
            StatusCard(
                title = uiState.fileIndexState.headline,
                summary = uiState.fileIndexState.summary,
                status = if (uiState.fileIndexState.permissionGranted) {
                    "${uiState.fileIndexState.indexedCount} indexed"
                } else {
                    "Permission needed"
                },
                icon = Icons.Default.FolderOpen,
            )
        }
        item {
            StatusCard(
                title = uiState.voiceEntryState.headline,
                summary = uiState.voiceEntryState.summary,
                status = if (uiState.voiceEntryState.isActive) {
                    "Active"
                } else {
                    "Idle"
                },
                icon = Icons.Default.Mic,
            )
        }
        items(uiState.overviewCards) { card ->
            ShellCardView(card = card, icon = Icons.Default.Home)
        }
    }
}

@Composable
private fun FilesScreen(
    fileIndexState: FileIndexState,
    fileActionState: FileActionState,
    approvals: List<ApprovalInboxItem>,
    deviceControlState: DeviceControlState,
    innerPadding: PaddingValues,
    onRequestMediaAccess: () -> Unit,
    onOpenDocumentTree: () -> Unit,
    onRefreshFiles: () -> Unit,
    onSelectFile: (String) -> Unit,
    onSummarizeFiles: () -> Unit,
    onPlanOrganizeByType: () -> Unit,
    onPlanOrganizeBySource: () -> Unit,
    onSetForceDeleteConsentForTesting: (Boolean) -> Unit,
    onRequestOrganizeApproval: () -> Unit,
    onRequestDeleteConsent: () -> Unit,
    onShareCurrentFiles: () -> Unit,
    onSelectTargetDevice: (String) -> Unit,
    onSendCurrentFilesToDevice: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Unified File Graph",
                subtitle = "MediaStore indexing is live first. SAF-backed document providers and cloud connectors follow next.",
            )
        }
        item {
            FileIndexControlCard(
                state = fileIndexState,
                onRequestMediaAccess = onRequestMediaAccess,
                onOpenDocumentTree = onOpenDocumentTree,
                onRefreshFiles = onRefreshFiles,
            )
        }
        item {
            FileActionConsoleCard(
                fileActionState = fileActionState,
                deviceControlState = deviceControlState,
                hasIndexedFiles = fileIndexState.indexedItems.isNotEmpty(),
                onSummarizeFiles = onSummarizeFiles,
                onPlanOrganizeByType = onPlanOrganizeByType,
                onPlanOrganizeBySource = onPlanOrganizeBySource,
                onSetForceDeleteConsentForTesting = onSetForceDeleteConsentForTesting,
                onRequestOrganizeApproval = onRequestOrganizeApproval,
                onRequestDeleteConsent = onRequestDeleteConsent,
                onShareCurrentFiles = onShareCurrentFiles,
                onSelectTargetDevice = onSelectTargetDevice,
                onSendCurrentFilesToDevice = onSendCurrentFilesToDevice,
            )
        }
        item {
            WorkflowStatusCard(snapshot = organizeWorkflowSnapshot(fileActionState, approvals))
        }
        fileActionState.preview?.let { preview ->
            item {
                FilePreviewCard(preview = preview)
            }
        }
        fileActionState.summary?.let { summary ->
            item {
                FileSummaryCard(summary = summary)
            }
        }
        fileActionState.organizePlan?.let { plan ->
            item {
                OrganizePlanCard(plan = plan)
            }
        }
        fileActionState.lastOrganizeResult?.let { result ->
            item {
                OrganizeExecutionResultCard(
                    result = result,
                    updatedAtLabel = fileActionState.lastOrganizeUpdatedAtLabel,
                    recovered = fileActionState.lastOrganizeRecovered,
                    onRequestDeleteConsent = onRequestDeleteConsent,
                )
            }
        }
        if (fileIndexState.indexedItems.isEmpty()) {
            item {
                EmptyStateCard(
                    title = if (fileIndexState.permissionGranted) {
                        "No recent files yet"
                    } else {
                        "Media access has not been granted"
                    },
                    summary = fileIndexState.summary,
                )
            }
        } else {
            items(
                items = fileIndexState.indexedItems,
                key = { it.id },
            ) { file ->
                IndexedFileCard(
                    file = file,
                    selected = fileActionState.selectedFileId == file.id,
                    onClick = { onSelectFile(file.id) },
                )
            }
        }
    }
}

@Composable
private fun ApprovalsScreen(
    approvals: List<ApprovalInboxItem>,
    auditEvents: List<AuditTrailEvent>,
    innerPadding: PaddingValues,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    val pendingApprovals = approvals.count { it.status == ApprovalInboxStatus.Pending }
    val latestAuditEvent = auditEvents.firstOrNull()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val auditHeaderIndex = if (approvals.isEmpty()) 3 else approvals.size + 2

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Approval Inbox",
                subtitle = "$pendingApprovals pending actions remain reviewable before any risky execution path proceeds.",
            )
        }
        item {
            ApprovalAuditShortcutCard(
                latestAuditEvent = latestAuditEvent,
                onJumpToAudit = {
                    scope.launch {
                        listState.animateScrollToItem(auditHeaderIndex)
                    }
                },
            )
        }
        if (approvals.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No approval requests",
                    summary = "New high-risk file actions will surface here before execution.",
                )
            }
        } else {
            items(
                items = approvals,
                key = { it.id },
            ) { approval ->
                ApprovalInboxCard(
                    item = approval,
                    onApprove = onApprove,
                    onDeny = onDeny,
                )
            }
        }
        item {
            SectionHeader(
                title = "Audit Trail",
                subtitle = "Approval decisions are now persisted locally and surfaced here for review.",
            )
        }
        if (auditEvents.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No audit events yet",
                    summary = "Approval decisions will append audit records once you act on requests.",
                )
            }
        } else {
            items(
                items = auditEvents,
                key = { it.id },
            ) { event ->
                AuditEventCard(event = event)
            }
        }
    }
}

@Composable
private fun ApprovalAuditShortcutCard(
    latestAuditEvent: AuditTrailEvent?,
    onJumpToAudit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Audit quick jump",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = latestAuditEvent?.let { event ->
                    "Latest audit: ${event.headline} / ${event.result} / ${event.createdAtLabel}"
                } ?: "No audit events yet. Jump to the audit section to confirm when new review or recovery records land.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onJumpToAudit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Jump to audit trail")
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    deviceControlState: DeviceControlState,
    voiceEntryState: VoiceEntryState,
    latestNotificationQuickAction: AuditTrailEvent?,
    innerPadding: PaddingValues,
    onToggleVoiceCapture: () -> Unit,
    onShowQuickActions: () -> Unit,
    onStartPairing: () -> Unit,
    onApprovePairing: (String) -> Unit,
    onDenyPairing: (String) -> Unit,
    onSelectTargetDevice: (String) -> Unit,
    onArmDirectHttpBridge: (String) -> Unit,
    onUseLoopbackBridge: (String) -> Unit,
    onSetTransportValidationMode: (String, TransportValidationMode) -> Unit,
    onUseAdbReverseEndpoint: (String) -> Unit,
    onUseEmulatorHostEndpoint: (String) -> Unit,
    onRefreshDeviceState: () -> Unit,
    onProbeSelectedDeviceHealth: () -> Unit,
    onSendSessionNotification: () -> Unit,
    onOpenCompanionInbox: () -> Unit,
    onOpenLatestActionFolder: () -> Unit,
    onOpenLatestTransferFolder: () -> Unit,
    onOpenActionsFolder: () -> Unit,
    onRunOpenLatestActionWorkflow: () -> Unit,
    onRunOpenLatestTransferWorkflow: () -> Unit,
    onRunOpenActionsFolderWorkflow: () -> Unit,
    onDrainTransferOutboxNow: () -> Unit,
    onRetryFailedTransfers: () -> Unit,
) {
    val selectedDevice = deviceControlState.pairedDevices.firstOrNull {
        it.id == deviceControlState.selectedTargetDeviceId
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Devices and Pairing",
                subtitle = "QR trust, explicit capability grants, loopback delivery, and direct HTTP companion transport all surface here.",
            )
        }
        item {
            VoiceEntryCard(
                state = voiceEntryState,
                onToggleVoiceCapture = onToggleVoiceCapture,
                onShowQuickActions = onShowQuickActions,
            )
        }
        item {
            NotificationQuickActionCard(event = latestNotificationQuickAction)
        }
        item {
            Button(
                onClick = onStartPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start pairing session")
            }
        }
        item {
            SectionHeader(
                title = "Pairing sessions",
                subtitle = "Each request surfaces a short QR secret and requested capabilities before approval.",
            )
        }
        if (deviceControlState.pairingSessions.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No pairing sessions",
                    summary = "Create a new session when you want to authorize a companion device.",
                )
            }
        } else {
            items(
                items = deviceControlState.pairingSessions,
                key = { it.id },
            ) { session ->
                PairingSessionCard(
                    session = session,
                    onApprovePairing = onApprovePairing,
                    onDenyPairing = onDenyPairing,
                )
            }
        }
        item {
                SectionHeader(
                    title = "Paired devices",
                    subtitle = "Tap a device to pin it as the default target, or tap the pinned card again to return to auto-select.",
                )
            }
        if (deviceControlState.pairedDevices.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No paired devices yet",
                    summary = "Approved sessions will appear here with their granted capabilities.",
                )
            }
        } else {
            items(
                items = deviceControlState.pairedDevices,
                key = { it.id },
            ) { device ->
                DeviceCard(
                    device = device,
                    selected = device.id == deviceControlState.selectedTargetDeviceId,
                    selectionPinnedByUser = deviceControlState.isTargetDevicePinnedByUser,
                    onClick = { onSelectTargetDevice(device.id) },
                    onArmDirectHttpBridge = onArmDirectHttpBridge,
                    onUseLoopbackBridge = onUseLoopbackBridge,
                    onSetTransportValidationMode = onSetTransportValidationMode,
                    onUseAdbReverseEndpoint = { onUseAdbReverseEndpoint(device.id) },
                    onUseEmulatorHostEndpoint = { onUseEmulatorHostEndpoint(device.id) },
                )
            }
        }
        item {
            SectionHeader(
                title = "Transfer outbox",
                subtitle = "Send-to-device actions move through loopback or direct HTTP bridge transport and surface state here.",
            )
        }
        item {
            WorkflowStatusCard(
                snapshot = transferWorkflowSnapshot(
                    selectedDevice = selectedDevice,
                    diagnostics = deviceControlState.transportDiagnostics,
                    companionProbe = deviceControlState.companionProbe,
                ),
            )
        }
        item {
            BridgeDebugCard(
                selectedDevice = selectedDevice,
                diagnostics = deviceControlState.transportDiagnostics,
                recoveryState = deviceControlState.recoveryState,
                companionProbe = deviceControlState.companionProbe,
                companionNotify = deviceControlState.companionNotify,
                companionAppOpen = deviceControlState.companionAppOpen,
                companionWorkflowRun = deviceControlState.companionWorkflowRun,
                onRefreshDeviceState = onRefreshDeviceState,
                onProbeSelectedDeviceHealth = onProbeSelectedDeviceHealth,
                onSendSessionNotification = onSendSessionNotification,
                onOpenCompanionInbox = onOpenCompanionInbox,
                onOpenLatestActionFolder = onOpenLatestActionFolder,
                onOpenLatestTransferFolder = onOpenLatestTransferFolder,
                onOpenActionsFolder = onOpenActionsFolder,
                onRunOpenLatestActionWorkflow = onRunOpenLatestActionWorkflow,
                onRunOpenLatestTransferWorkflow = onRunOpenLatestTransferWorkflow,
                onRunOpenActionsFolderWorkflow = onRunOpenActionsFolderWorkflow,
                onDrainTransferOutboxNow = onDrainTransferOutboxNow,
                onRetryFailedTransfers = onRetryFailedTransfers,
            )
        }
        item {
            TransportAuditTraceCard(
                selectedDevice = selectedDevice,
                events = deviceControlState.transportAuditEvents,
            )
        }
        if (deviceControlState.transferDrafts.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "Transfer queue is empty",
                    summary = "Queue a send from Chat or the advanced tools after selecting a paired target device.",
                )
            }
        } else {
            items(
                items = deviceControlState.transferDrafts,
                key = { it.id },
            ) { draft ->
                TransferDraftCard(draft = draft)
            }
        }
    }
}

@Composable
private fun NotificationQuickActionCard(event: AuditTrailEvent?) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Notification quick actions",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            if (event == null) {
                Text(
                    text = "Use the quick actions notification and tap Voice or Approvals. The latest action will be recorded here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = event.result.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGreen,
                )
                Text(
                    text = "${event.createdAtLabel} • ${event.details}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResourceConnectionCard(resource: ResourceConnectionSummary) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = resource.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = resource.priorityLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = resourceConnectionStatusColor(resource.status).copy(alpha = 0.16f),
                ) {
                    Text(
                        text = resourceConnectionStatusLabel(resource.status),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = resourceConnectionStatusColor(resource.status),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = resource.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelProviderProfileCard(
    profile: ModelProviderProfileState,
    onSetEnabled: (Boolean) -> Unit,
    onSetDefault: () -> Unit,
    onSelectModel: (String) -> Unit,
    onStoreCredential: (String) -> Unit,
    onClearCredential: () -> Unit,
) {
    var credentialDraft by rememberSaveable(profile.providerId) { mutableStateOf("") }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Selected model ${profile.selectedModel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (profile.enabled) "On" else "Off",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (profile.enabled) ClawGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = profile.enabled,
                        onCheckedChange = onSetEnabled,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (profile.isDefault) {
                                "Default route"
                            } else {
                                "Optional route"
                            },
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (profile.isDefault) {
                            ClawGreen.copy(alpha = 0.16f)
                        } else {
                            ClawInk.copy(alpha = 0.08f)
                        },
                        labelColor = ClawInk,
                    ),
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Credential ${providerCredentialStatusLabel(profile.credentialStatus)}") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = providerCredentialStatusColor(profile.credentialStatus).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            profile.baseUrl?.takeIf { it.isNotBlank() } ?: "Provider default endpoint",
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = ClawGold.copy(alpha = 0.12f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = if (profile.credentialStatus == ModelProviderCredentialStatus.Stored) {
                    profile.credentialLabel?.let { "Credential label $it" }
                        ?: "Credential storage is configured for this provider."
                } else {
                    "Credential storage is not configured yet. Add the API key or token here and it will be stored in the phone vault."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = credentialDraft,
                onValueChange = { credentialDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API key or token") },
                placeholder = { Text("Stored in phone vault") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        onStoreCredential(credentialDraft)
                        credentialDraft = ""
                    },
                    enabled = credentialDraft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (profile.credentialStatus == ModelProviderCredentialStatus.Stored) {
                            "Update credential"
                        } else {
                            "Save credential"
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        onClearCredential()
                        credentialDraft = ""
                    },
                    enabled = profile.credentialStatus == ModelProviderCredentialStatus.Stored,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear credential")
                }
            }
            if (!profile.isDefault) {
                OutlinedButton(
                    onClick = onSetDefault,
                    enabled = profile.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Make default provider")
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Supported models",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profile.supportedModels.forEach { model ->
                        FilterChip(
                            selected = model == profile.selectedModel,
                            onClick = { onSelectModel(model) },
                            enabled = profile.enabled,
                            label = { Text(model) },
                        )
                    }
                }
            }
            Text(
                text = "Updated ${profile.updatedAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CloudDriveConnectionCard(
    connection: CloudDriveConnectionState,
    onStage: () -> Unit,
    onMarkMockReady: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = connection.provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    connection.accountLabel?.let { accountLabel ->
                        Text(
                            text = "Recorded account $accountLabel",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClawGreen,
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = cloudDriveStatusColor(connection.status).copy(alpha = 0.16f),
                ) {
                    Text(
                        text = cloudDriveStatusLabel(connection.status),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = cloudDriveStatusColor(connection.status),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                text = connection.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                connection.supportedScopes.forEach { scope ->
                    AssistChip(
                        onClick = {},
                        label = { Text(scope) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = ClawGold.copy(alpha = 0.12f),
                            labelColor = ClawInk,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onStage,
                    modifier = Modifier.weight(1f),
                    enabled = connection.status != CloudDriveConnectionStatus.Staged,
                ) {
                    Text("Stage")
                }
                FilledTonalButton(
                    onClick = onMarkMockReady,
                    modifier = Modifier.weight(1f),
                    enabled = connection.status != CloudDriveConnectionStatus.Connected,
                ) {
                    Text("Mock ready")
                }
            }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                enabled = connection.status != CloudDriveConnectionStatus.NeedsSetup,
            ) {
                Text("Reset connector")
            }
            Text(
                text = "Updated ${connection.updatedAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BridgeDebugCard(
    selectedDevice: PairedDeviceState?,
    diagnostics: TransportDiagnostics,
    recoveryState: ShellRecoveryState,
    companionProbe: CompanionProbeState,
    companionNotify: CompanionNotifyState,
    companionAppOpen: CompanionAppOpenState,
    companionWorkflowRun: CompanionWorkflowRunState,
    onRefreshDeviceState: () -> Unit,
    onProbeSelectedDeviceHealth: () -> Unit,
    onSendSessionNotification: () -> Unit,
    onOpenCompanionInbox: () -> Unit,
    onOpenLatestActionFolder: () -> Unit,
    onOpenLatestTransferFolder: () -> Unit,
    onOpenActionsFolder: () -> Unit,
    onRunOpenLatestActionWorkflow: () -> Unit,
    onRunOpenLatestTransferWorkflow: () -> Unit,
    onRunOpenActionsFolderWorkflow: () -> Unit,
    onDrainTransferOutboxNow: () -> Unit,
    onRetryFailedTransfers: () -> Unit,
) {
    val supportsSessionNotify = selectedDevice?.supportsCompanionCapability(companionCapabilitySessionNotify) == true
    val supportsAppOpen = selectedDevice?.supportsCompanionCapability(companionCapabilityAppOpen) == true
    val supportsWorkflowRun = selectedDevice?.supportsCompanionCapability(companionCapabilityWorkflowRun) == true
    val canCheckCompanionHealth = selectedDevice != null && !companionProbe.isChecking
    val canSendSessionNotification =
        selectedDevice != null &&
            selectedDevice.transportMode == DeviceTransportMode.DirectHttp &&
            supportsSessionNotify &&
            !companionNotify.isSending
    val canSendAppOpen =
        selectedDevice != null &&
            selectedDevice.transportMode == DeviceTransportMode.DirectHttp &&
            supportsAppOpen &&
            !companionAppOpen.isSending
    val canRunWorkflow =
        selectedDevice != null &&
            selectedDevice.transportMode == DeviceTransportMode.DirectHttp &&
            supportsWorkflowRun &&
            !companionWorkflowRun.isSending
    var showDirectRemoteActions by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Bridge controls",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = selectedDevice?.let {
                    "Selected target: ${it.name} • ${transportValidationModeLabel(it.validationMode)}"
                } ?: "Select a paired device to focus retries and validation settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Use Chat quick starts and chat follow-up buttons for companion actions first. The controls below stay here only for diagnostics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = buildString {
                    append("${diagnostics.queuedCount} queued")
                    append(" • ${diagnostics.sendingCount} sending")
                    append(" • ${diagnostics.failedCount} failed")
                    append(" • ${diagnostics.receiptReviewCount} receipt review")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = diagnostics.nextRetryLabel?.let {
                    "Next retry $it. ${diagnostics.retryScheduledCount} draft(s) are waiting on backoff."
                } ?: "No delayed retries are pending for the current target.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (diagnostics.nextRetryLabel == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    ClawGold
                },
            )
            Text(
                text = shellRecoveryStatusLabel(recoveryState.status),
                style = MaterialTheme.typography.labelLarge,
                color = shellRecoveryStatusColor(recoveryState.status),
            )
            listOfNotNull(recoveryState.triggerLabel, recoveryState.updatedAtLabel)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" • ")
                ?.let { recoveryMeta ->
                    Text(
                        text = recoveryMeta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            Text(
                text = recoveryState.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = recoveryState.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            selectedDevice?.endpointLabel?.let { endpoint ->
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            selectedDevice?.takeIf { it.transportMode == DeviceTransportMode.DirectHttp }?.let { device ->
                when {
                    device.capabilities.isEmpty() -> Text(
                        text = "Check companion health to load the remote capability snapshot. session.notify, app.open, and workflow.run stay disabled until then.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClawGold,
                    )
                    !supportsSessionNotify || !supportsAppOpen || !supportsWorkflowRun -> {
                        val unavailableActions = buildList {
                            if (!supportsSessionNotify) {
                                add("session.notify")
                            }
                            if (!supportsAppOpen) {
                                add("app.open")
                            }
                            if (!supportsWorkflowRun) {
                                add("workflow.run")
                            }
                        }
                        Text(
                            text = "Advertised capabilities currently omit ${unavailableActions.joinToString()}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClawGold,
                        )
                    }
                }
            }
            companionProbe.result?.let { probe ->
                Text(
                    text = companionProbeStatusLabel(probe.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = companionProbeStatusColor(probe.status),
                )
                Text(
                    text = "${probe.summary} • ${probe.checkedAtLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = companionProbeDetailLabel(probe),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            companionNotify.result?.let { notify ->
                Text(
                    text = companionNotifyStatusLabel(notify.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = companionNotifyStatusColor(notify.status),
                )
                Text(
                    text = "${notify.summary} • ${notify.sentAtLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = notify.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            companionAppOpen.result?.let { appOpen ->
                Text(
                    text = companionAppOpenStatusLabel(appOpen.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = companionAppOpenStatusColor(appOpen.status),
                )
                Text(
                    text = "${appOpen.targetLabel} • ${appOpen.summary} • ${appOpen.sentAtLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = appOpen.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            companionWorkflowRun.result?.let { workflowRun ->
                Text(
                    text = companionWorkflowRunStatusLabel(workflowRun.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = companionWorkflowRunStatusColor(workflowRun.status),
                )
                Text(
                    text = "${workflowRun.workflowId} • ${workflowRun.summary} • ${workflowRun.sentAtLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = workflowRun.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onRefreshDeviceState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Refresh device state")
            }
            OutlinedButton(
                onClick = { showDirectRemoteActions = !showDirectRemoteActions },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (showDirectRemoteActions) {
                        "Hide direct bridge actions"
                    } else {
                        "Show direct bridge actions"
                    },
                )
            }
            if (showDirectRemoteActions) {
                OutlinedButton(
                    onClick = onProbeSelectedDeviceHealth,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canCheckCompanionHealth,
                ) {
                    Text(
                        if (companionProbe.isChecking) {
                            "Checking companion health..."
                        } else {
                            "Check companion health"
                        },
                    )
                }
                FilledTonalButton(
                    onClick = onSendSessionNotification,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSendSessionNotification,
                ) {
                    Text(
                        if (companionNotify.isSending) {
                            "Sending session notification..."
                        } else {
                            "Send session notification"
                        },
                    )
                }
                FilledTonalButton(
                    onClick = onOpenCompanionInbox,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSendAppOpen,
                ) {
                    Text(
                        if (
                            companionAppOpen.isSending &&
                            companionAppOpen.pendingTargetKind == companionAppOpenTargetInbox
                        ) {
                            "Opening companion inbox..."
                        } else {
                            "Open companion inbox"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onOpenLatestActionFolder,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSendAppOpen,
                ) {
                    Text(
                        if (
                            companionAppOpen.isSending &&
                            companionAppOpen.pendingTargetKind == companionAppOpenTargetLatestAction
                        ) {
                            "Opening latest action..."
                        } else {
                            "Open latest action folder"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onOpenLatestTransferFolder,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSendAppOpen,
                ) {
                    Text(
                        if (
                            companionAppOpen.isSending &&
                            companionAppOpen.pendingTargetKind == companionAppOpenTargetLatestTransfer
                        ) {
                            "Opening latest transfer..."
                        } else {
                            "Open latest transfer folder"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onOpenActionsFolder,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSendAppOpen,
                ) {
                    Text(
                        if (
                            companionAppOpen.isSending &&
                            companionAppOpen.pendingTargetKind == companionAppOpenTargetActionsFolder
                        ) {
                            "Opening actions folder..."
                        } else {
                            "Open actions folder"
                        },
                    )
                }
                FilledTonalButton(
                    onClick = onRunOpenLatestActionWorkflow,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canRunWorkflow,
                ) {
                    Text(
                        if (companionWorkflowRun.isSending) {
                            "Running workflow..."
                        } else {
                            "Run workflow: open latest action"
                        },
                    )
                }
                FilledTonalButton(
                    onClick = onRunOpenLatestTransferWorkflow,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canRunWorkflow,
                ) {
                    Text(
                        if (companionWorkflowRun.isSending) {
                            "Running workflow..."
                        } else {
                            "Run workflow: open latest transfer"
                        },
                    )
                }
                OutlinedButton(
                    onClick = onRunOpenActionsFolderWorkflow,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canRunWorkflow,
                ) {
                    Text("Run workflow: open actions folder")
                }
            }
            OutlinedButton(
                onClick = onDrainTransferOutboxNow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Drain transfer outbox now")
            }
            Button(
                onClick = onRetryFailedTransfers,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedDevice != null,
            ) {
                Text("Requeue failed drafts")
            }
        }
    }
}

@Composable
private fun TransportAuditTraceCard(
    selectedDevice: PairedDeviceState?,
    events: List<AuditTrailEvent>,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Transport trace",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = selectedDevice?.let {
                    "Focused device is ${it.name}. Trace stays transport-wide so recovery and manual actions remain visible."
                } ?: "Transport-wide audit events appear here after queue, retry, bridge mode, and receipt validation changes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (events.isEmpty()) {
                Text(
                    text = "No transport audit events yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                events.forEach { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(0.76f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = event.headline,
                                style = MaterialTheme.typography.labelLarge,
                                color = ClawInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = event.details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text(event.result.replace('_', ' ')) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = transportAuditResultColor(event.result).copy(alpha = 0.16f),
                                    labelColor = ClawInk,
                                ),
                            )
                            Text(
                                text = event.createdAtLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    eyebrow: String,
    title: String,
    summary: String,
) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            ClawGreen.copy(alpha = 0.18f),
                            ClawGold.copy(alpha = 0.16f),
                        ),
                    ),
                )
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGreen,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    fileIndexState: FileIndexState,
    pendingApprovals: Int,
    voiceEntryState: VoiceEntryState,
    onRequestMediaAccess: () -> Unit,
    onOpenDocumentTree: () -> Unit,
    onRefreshFiles: () -> Unit,
    onToggleVoiceCapture: () -> Unit,
    onShowQuickActions: () -> Unit,
    onOpenApprovals: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Phase 1 shell actions",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalButton(
                onClick = if (fileIndexState.permissionGranted) onRefreshFiles else onRequestMediaAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (fileIndexState.permissionGranted) {
                        "Refresh local file index"
                    } else {
                        "Grant media access"
                    },
                )
            }
            FilledTonalButton(
                onClick = onOpenDocumentTree,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Attach document root (${fileIndexState.documentTreeCount})",
                )
            }
            FilledTonalButton(
                onClick = onOpenApprovals,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Review pending approvals ($pendingApprovals)")
            }
            Button(
                onClick = onShowQuickActions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Post quick actions notification")
            }
            OutlinedButton(
                onClick = onToggleVoiceCapture,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    if (voiceEntryState.isActive) {
                        "Stop voice quick entry"
                    } else {
                        "Start voice quick entry"
                    },
                )
            }
        }
    }
}

@Composable
private fun FileIndexControlCard(
    state: FileIndexState,
    onRequestMediaAccess: () -> Unit,
    onOpenDocumentTree: () -> Unit,
    onRefreshFiles: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (state.permissionGranted) {
                                "Granted"
                            } else {
                                "Permission"
                            },
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (state.permissionGranted) {
                            ClawGreen.copy(alpha = 0.16f)
                        } else {
                            ClawGold.copy(alpha = 0.16f)
                        },
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = state.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = "Source: ${state.scanSource}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.lastIndexedLabel?.let { label ->
                Text(
                    text = "Last indexed: $label",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Indexed items: ${state.indexedCount}",
                style = MaterialTheme.typography.labelLarge,
                color = ClawGreen,
            )
            if (state.documentRoots.isNotEmpty()) {
                Text(
                    text = "Document roots: ${state.documentRoots.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.permissionGranted) {
                Button(
                    onClick = onRefreshFiles,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Refresh MediaStore scan")
                }
            } else {
                Button(
                    onClick = onRequestMediaAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant media access")
                }
            }
            OutlinedButton(
                onClick = onOpenDocumentTree,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Attach document root")
            }
        }
    }
}

@Composable
private fun FileActionConsoleCard(
    fileActionState: FileActionState,
    deviceControlState: DeviceControlState,
    hasIndexedFiles: Boolean,
    onSummarizeFiles: () -> Unit,
    onPlanOrganizeByType: () -> Unit,
    onPlanOrganizeBySource: () -> Unit,
    onSetForceDeleteConsentForTesting: (Boolean) -> Unit,
    onRequestOrganizeApproval: () -> Unit,
    onRequestDeleteConsent: () -> Unit,
    onShareCurrentFiles: () -> Unit,
    onSelectTargetDevice: (String) -> Unit,
    onSendCurrentFilesToDevice: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "File graph actions",
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (fileActionState.selectedFileId == null) {
                    "No single file selected. Actions will target the current indexed batch."
                } else {
                    "A file is selected. Actions will focus on the current file."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fileActionState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = onShareCurrentFiles,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasIndexedFiles && !fileActionState.isLoading,
            ) {
                Text("Share with Android")
            }
            FilledTonalButton(
                onClick = onSummarizeFiles,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasIndexedFiles && !fileActionState.isLoading,
            ) {
                Text("Summarize current files")
            }
            OutlinedButton(
                onClick = onPlanOrganizeByType,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasIndexedFiles && !fileActionState.isLoading,
            ) {
                Text("Plan organize by type")
            }
            OutlinedButton(
                onClick = onPlanOrganizeBySource,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasIndexedFiles && !fileActionState.isLoading,
            ) {
                Text("Plan organize by source")
            }
            if (BuildConfig.DEBUG) {
                Text(
                    text = "Organize debug",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGreen,
                    fontWeight = FontWeight.SemiBold,
                )
                if (fileActionState.forceDeleteConsentForTesting) {
                    FilledTonalButton(
                        onClick = { onSetForceDeleteConsentForTesting(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Force delete consent path  •  selected")
                    }
                    Text(
                        text = "Next organize approval will intentionally keep supported MediaStore originals pending delete consent so the Android consent launcher can be regression-tested.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClawGold,
                    )
                } else {
                    OutlinedButton(
                        onClick = { onSetForceDeleteConsentForTesting(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Force delete consent path")
                    }
                }
            }
            Button(
                onClick = onRequestOrganizeApproval,
                modifier = Modifier.fillMaxWidth(),
                enabled = fileActionState.organizePlan != null && !fileActionState.isLoading,
            ) {
                Text("Request organize approval")
            }
            fileActionState.lastOrganizeResult
                ?.takeIf { it.deleteConsentRequiredCount > 0 }
                ?.let { result ->
                    FilledTonalButton(
                        onClick = onRequestDeleteConsent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Request Android delete consent (${result.deleteConsentRequiredCount})")
                    }
                    Text(
                        text = result.statusNote
                            ?: "The latest organize execution is waiting on Android delete consent. Until that system sheet completes, this is not a full success.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClawGold,
                    )
                }
            if (deviceControlState.pairedDevices.isEmpty()) {
                EmptyStateCard(
                    title = "No paired transfer target",
                    summary = "Approve a pairing session from Settings before queueing send-to-device actions.",
                )
            } else {
                Text(
                    text = "Send target",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGreen,
                    fontWeight = FontWeight.SemiBold,
                )
                deviceControlState.pairedDevices.forEach { device ->
                    val isSelected = device.id == deviceControlState.selectedTargetDeviceId
                    if (isSelected) {
                        FilledTonalButton(
                            onClick = { onSelectTargetDevice(device.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${device.name}  •  selected")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelectTargetDevice(device.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(device.name)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onSendCurrentFilesToDevice,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasIndexedFiles && !fileActionState.isLoading,
                ) {
                    Text("Queue send to selected device")
                }
            }
            fileActionState.lastExecutionNote?.let { note ->
                StatusTranscriptCard(
                    title = "Latest action",
                    body = note,
                )
            }
        }
    }
}

private enum class WorkflowTone {
    Neutral,
    Success,
    Warning,
    Danger,
}

private data class WorkflowStage(
    val label: String,
    val value: String,
    val tone: WorkflowTone,
)

private data class WorkflowSnapshot(
    val title: String,
    val headline: String,
    val summary: String,
    val verdict: String,
    val tone: WorkflowTone,
    val stages: List<WorkflowStage>,
    val note: String? = null,
)

@Composable
private fun WorkflowStatusCard(snapshot: WorkflowSnapshot) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(0.7f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = snapshot.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = ClawGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = snapshot.headline,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(snapshot.verdict) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = workflowToneColor(snapshot.tone).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = snapshot.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            snapshot.stages.forEach { stage ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stage.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(stage.value) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = workflowToneColor(stage.tone).copy(alpha = 0.16f),
                            labelColor = ClawInk,
                        ),
                    )
                }
            }
            snapshot.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = workflowToneColor(snapshot.tone),
                )
            }
        }
    }
}

private fun organizeWorkflowSnapshot(
    fileActionState: FileActionState,
    approvals: List<ApprovalInboxItem>,
): WorkflowSnapshot {
    val organizeApprovals = approvals.filter { it.action == "files.organize.execute" }
    val pendingOrganizeApprovals = organizeApprovals.count { it.status == ApprovalInboxStatus.Pending }
    val lastResult = fileActionState.lastOrganizeResult
    val planStage = WorkflowStage(
        label = "Dry-run plan",
        value = if (fileActionState.organizePlan != null) "Ready" else "Not created",
        tone = if (fileActionState.organizePlan != null) WorkflowTone.Success else WorkflowTone.Warning,
    )
    val approvalStage = WorkflowStage(
        label = "File approval",
        value = when {
            pendingOrganizeApprovals > 0 -> "Pending"
            lastResult != null -> "Completed"
            fileActionState.organizePlan != null -> "Not requested"
            else -> "No request"
        },
        tone = when {
            pendingOrganizeApprovals > 0 -> WorkflowTone.Warning
            lastResult != null -> WorkflowTone.Success
            fileActionState.organizePlan != null -> WorkflowTone.Warning
            else -> WorkflowTone.Neutral
        },
    )
    val executionStage = WorkflowStage(
        label = "Execution",
        value = when {
            lastResult == null -> "Not run"
            lastResult.failedCount == lastResult.processedCount -> "Failed"
            lastResult.deleteConsentRequiredCount > 0 -> "Needs consent"
            lastResult.failedCount > 0 || lastResult.copiedOnlyCount > 0 -> "Partial"
            else -> "Success"
        },
        tone = when {
            lastResult == null -> WorkflowTone.Neutral
            lastResult.failedCount == lastResult.processedCount -> WorkflowTone.Danger
            lastResult.deleteConsentRequiredCount > 0 -> WorkflowTone.Warning
            lastResult.failedCount > 0 || lastResult.copiedOnlyCount > 0 -> WorkflowTone.Warning
            else -> WorkflowTone.Success
        },
    )

    return when {
        lastResult != null && lastResult.failedCount == 0 &&
            lastResult.deleteConsentRequiredCount == 0 &&
            lastResult.copiedOnlyCount == 0 -> WorkflowSnapshot(
            title = "Organize test status",
            headline = "Organize execution succeeded",
            summary = "All ${lastResult.processedCount} files were verified and moved into Makoion-managed folders.",
            verdict = "Success",
            tone = WorkflowTone.Success,
            stages = listOf(planStage, approvalStage, executionStage),
            note = "Proof of completion is the Latest organize execution card below with only Moved results.",
        )
        lastResult != null && lastResult.deleteConsentRequiredCount > 0 -> WorkflowSnapshot(
            title = "Organize test status",
            headline = "Organize copied files but still needs Android delete consent",
            summary = "The destination copies were verified, but Android has not removed all originals yet.",
            verdict = "Action needed",
            tone = WorkflowTone.Warning,
            stages = listOf(planStage, approvalStage, executionStage),
            note = lastResult.statusNote
                ?: "Tap Request Android delete consent. Until that finishes, this is not a full success.",
        )
        lastResult != null && (lastResult.failedCount > 0 || lastResult.copiedOnlyCount > 0) -> WorkflowSnapshot(
            title = "Organize test status",
            headline = if (lastResult.failedCount == lastResult.processedCount) {
                "Organize execution failed"
            } else {
                "Organize execution finished with mixed results"
            },
            summary = "Moved ${lastResult.movedCount}, copied-only ${lastResult.copiedOnlyCount}, failed ${lastResult.failedCount}, verified ${lastResult.verifiedCount}.",
            verdict = if (lastResult.failedCount == lastResult.processedCount) "Failed" else "Partial",
            tone = if (lastResult.failedCount == lastResult.processedCount) WorkflowTone.Danger else WorkflowTone.Warning,
            stages = listOf(planStage, approvalStage, executionStage),
            note = "Check the per-file entries below. A full success requires no failed or copied-only files.",
        )
        pendingOrganizeApprovals > 0 -> WorkflowSnapshot(
            title = "Organize test status",
            headline = "Organize approval is pending",
            summary = "An organize request exists, but nothing has moved yet. Companion pairing approval does not execute file organize.",
            verdict = "Waiting",
            tone = WorkflowTone.Warning,
            stages = listOf(planStage, approvalStage, executionStage),
            note = "Review the request from Dashboard and approve it to trigger execution.",
        )
        fileActionState.organizePlan != null -> WorkflowSnapshot(
            title = "Organize test status",
            headline = "Dry-run plan is ready, but execution has not started",
            summary = "The plan only describes what would happen. Files will not move until you request approval.",
            verdict = "Ready",
            tone = WorkflowTone.Warning,
            stages = listOf(planStage, approvalStage, executionStage),
            note = "Next step: tap Request organize approval, then review and approve it from Dashboard.",
        )
        else -> WorkflowSnapshot(
            title = "Organize test status",
            headline = "Organize has not started",
            summary = "Indexed files are available, but there is no dry-run plan, no organize approval request, and no execution result yet.",
            verdict = "Not started",
            tone = WorkflowTone.Neutral,
            stages = listOf(planStage, approvalStage, executionStage),
            note = "Start with Plan organize by type or Plan organize by source.",
        )
    }
}

private fun transferWorkflowSnapshot(
    selectedDevice: PairedDeviceState?,
    diagnostics: TransportDiagnostics,
    companionProbe: CompanionProbeState,
): WorkflowSnapshot {
    if (selectedDevice == null) {
        return WorkflowSnapshot(
            title = "Transfer test status",
            headline = "No paired transfer target",
            summary = "Device pairing has not completed for the current transfer flow, so send-to-device cannot succeed yet.",
            verdict = "Not ready",
            tone = WorkflowTone.Warning,
            stages = listOf(
                WorkflowStage("Pairing", "Missing", WorkflowTone.Warning),
                WorkflowStage("Endpoint", "Unavailable", WorkflowTone.Neutral),
                WorkflowStage("Companion health", "Unavailable", WorkflowTone.Neutral),
                WorkflowStage("Delivery", "Not started", WorkflowTone.Neutral),
            ),
            note = "Approve a pairing session first. This is separate from file organize approval.",
        )
    }

    val endpointStage = when {
        selectedDevice.transportMode == DeviceTransportMode.Loopback -> WorkflowStage(
            label = "Endpoint",
            value = "Loopback",
            tone = WorkflowTone.Success,
        )
        selectedDevice.endpointLabel.isNullOrBlank() -> WorkflowStage(
            label = "Endpoint",
            value = "Missing",
            tone = WorkflowTone.Danger,
        )
        selectedDevice.endpointLabel.contains("10.0.2.2") -> WorkflowStage(
            label = "Endpoint",
            value = "Emulator only",
            tone = WorkflowTone.Warning,
        )
        selectedDevice.endpointLabel.contains("127.0.0.1") -> WorkflowStage(
            label = "Endpoint",
            value = "adb reverse",
            tone = WorkflowTone.Success,
        )
        else -> WorkflowStage(
            label = "Endpoint",
            value = "Custom",
            tone = WorkflowTone.Warning,
        )
    }
    val healthStage = when {
        selectedDevice.transportMode == DeviceTransportMode.Loopback -> WorkflowStage(
            label = "Companion health",
            value = "Not required",
            tone = WorkflowTone.Success,
        )
        companionProbe.result == null -> WorkflowStage(
            label = "Companion health",
            value = "Unchecked",
            tone = WorkflowTone.Warning,
        )
        companionProbe.result.status == CompanionHealthStatus.Healthy -> WorkflowStage(
            label = "Companion health",
            value = "Online",
            tone = WorkflowTone.Success,
        )
        companionProbe.result.status == CompanionHealthStatus.Unreachable -> WorkflowStage(
            label = "Companion health",
            value = "Failed",
            tone = WorkflowTone.Danger,
        )
        companionProbe.result.status == CompanionHealthStatus.Misconfigured -> WorkflowStage(
            label = "Companion health",
            value = "Misconfigured",
            tone = WorkflowTone.Danger,
        )
        else -> WorkflowStage(
            label = "Companion health",
            value = "Skipped",
            tone = WorkflowTone.Warning,
        )
    }
    val deliveryStage = when {
        diagnostics.failedCount > 0 -> WorkflowStage("Delivery", "Failed", WorkflowTone.Danger)
        diagnostics.receiptReviewCount > 0 -> WorkflowStage("Delivery", "Needs review", WorkflowTone.Warning)
        diagnostics.deliveredCount > 0 -> WorkflowStage("Delivery", "Success", WorkflowTone.Success)
        diagnostics.sendingCount > 0 -> WorkflowStage("Delivery", "Sending", WorkflowTone.Warning)
        diagnostics.queuedCount > 0 -> WorkflowStage("Delivery", "Queued", WorkflowTone.Warning)
        else -> WorkflowStage("Delivery", "No transfer yet", WorkflowTone.Neutral)
    }
    val stages = listOf(
        WorkflowStage("Pairing", "Approved", WorkflowTone.Success),
        endpointStage,
        healthStage,
        deliveryStage,
    )

    return when {
        diagnostics.failedCount > 0 || healthStage.tone == WorkflowTone.Danger -> WorkflowSnapshot(
            title = "Transfer test status",
            headline = "Transfer is not succeeding yet",
            summary = companionProbe.result?.summary
                ?: "The current transport path still has a blocking failure or no successful delivery result.",
            verdict = "Failed",
            tone = WorkflowTone.Danger,
            stages = stages,
            note = if (selectedDevice.endpointLabel?.contains("10.0.2.2") == true) {
                "10.0.2.2 works only for emulators. On a physical phone, switch to Use adb reverse localhost."
            } else {
                "A success requires companion health to pass and at least one draft to reach Delivered without receipt review."
            },
        )
        diagnostics.receiptReviewCount > 0 -> WorkflowSnapshot(
            title = "Transfer test status",
            headline = "Transfer delivered but the receipt is not fully trusted",
            summary = "The companion responded, but receipt validation found an issue that still needs review.",
            verdict = "Review",
            tone = WorkflowTone.Warning,
            stages = stages,
            note = "Check the draft card and transport trace below before treating this as a success.",
        )
        diagnostics.deliveredCount > 0 -> WorkflowSnapshot(
            title = "Transfer test status",
            headline = "Transfer succeeded",
            summary = "At least one draft reached Delivered and there are no current failed or receipt-review drafts for the selected target.",
            verdict = "Success",
            tone = WorkflowTone.Success,
            stages = stages,
            note = "You can confirm success in the transfer draft list below.",
        )
        diagnostics.sendingCount > 0 || diagnostics.queuedCount > 0 -> WorkflowSnapshot(
            title = "Transfer test status",
            headline = "Transfer is in progress",
            summary = "The selected target has queued or active drafts, but no confirmed delivery yet.",
            verdict = "Pending",
            tone = WorkflowTone.Warning,
            stages = stages,
            note = "Wait for the draft status to become Delivered or for a failure reason to appear.",
        )
        else -> WorkflowSnapshot(
            title = "Transfer test status",
            headline = if (selectedDevice.transportMode == DeviceTransportMode.DirectHttp) {
                "Transport is configured, but no delivery has been attempted"
            } else {
                "Loopback transport is ready"
            },
            summary = "Pairing is approved, but there is no successful delivery result yet for the selected target.",
            verdict = "Ready",
            tone = WorkflowTone.Neutral,
            stages = stages,
            note = if (selectedDevice.endpointLabel?.contains("10.0.2.2") == true) {
                "10.0.2.2 is the emulator preset. Use adb reverse localhost on a physical phone."
            } else {
                "Queue a send from Chat or the advanced tools and check whether the draft becomes Delivered."
            },
        )
    }
}

@Composable
private fun FilePreviewCard(preview: FilePreviewDetail) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelLarge,
                color = ClawGreen,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = preview.title,
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = preview.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview.metadata.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileSummaryCard(summary: FileSummaryDetail) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Summary",
                style = MaterialTheme.typography.labelLarge,
                color = ClawGreen,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary.headline,
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.highlights.forEach { highlight ->
                Text(
                    text = "• $highlight",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OrganizePlanCard(plan: FileOrganizePlan) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Dry-run organize plan",
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(plan.riskLabel) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = ClawGold.copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = plan.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.steps.forEach { step ->
                Surface(
                    color = ClawGreen.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = step.fileName,
                            style = MaterialTheme.typography.labelLarge,
                            color = ClawInk,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${step.sourceLabel} -> ${step.destinationFolder}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClawInk,
                        )
                        Text(
                            text = step.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizeExecutionResultCard(
    result: OrganizeExecutionResult,
    updatedAtLabel: String?,
    recovered: Boolean,
    onRequestDeleteConsent: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Latest organize execution",
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${result.verifiedCount} verified") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = ClawGreen.copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            updatedAtLabel?.let { label ->
                Text(
                    text = if (recovered) {
                        "Restored from a previous session • updated $label"
                    } else {
                        "Updated $label"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGold,
                )
            }
            Text(
                text = result.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.statusNote?.let { statusNote ->
                Text(
                    text = statusNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawGold,
                )
            }
            if (result.entries.any { it.detail.contains("Debug build forced") }) {
                Text(
                    text = "Debug regression mode forced this organize run into the delete consent path.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawGold,
                )
            }
            if (result.deleteConsentRequiredCount > 0) {
                Button(
                    onClick = onRequestDeleteConsent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Request Android delete consent (${result.deleteConsentRequiredCount})")
                }
            }
            result.entries.forEach { entry ->
                OrganizeExecutionEntryView(entry = entry)
            }
        }
    }
}

@Composable
private fun OrganizeExecutionEntryView(entry: OrganizeExecutionEntry) {
    Surface(
        color = organizeExecutionColor(entry.status).copy(alpha = 0.1f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(0.72f),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(organizeExecutionLabel(entry.status)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = organizeExecutionColor(entry.status).copy(alpha = 0.2f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = "${entry.sourceLabel} -> ${entry.destinationFolder}",
                style = MaterialTheme.typography.bodyMedium,
                color = ClawInk,
            )
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceEntryCard(
    state: VoiceEntryState,
    onToggleVoiceCapture: () -> Unit,
    onShowQuickActions: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.headline,
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.partialTranscript?.let { transcript ->
                StatusTranscriptCard(
                    title = "Live transcript",
                    body = transcript,
                )
            }
            state.finalTranscript?.let { transcript ->
                StatusTranscriptCard(
                    title = "Latest transcript",
                    body = transcript,
                )
            }
            if (state.recentTranscripts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recent captures",
                        style = MaterialTheme.typography.labelLarge,
                        color = ClawGreen,
                    )
                    state.recentTranscripts.forEach { entry ->
                        Text(
                            text = "${entry.capturedAtLabel}  •  ${entry.text}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onToggleVoiceCapture,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isActive) "Stop" else "Start")
                }
                OutlinedButton(
                    onClick = onShowQuickActions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Notify")
                }
            }
        }
    }
}

@Composable
private fun StatusTranscriptCard(
    title: String,
    body: String,
) {
    Surface(
        color = ClawGreen.copy(alpha = 0.08f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = ClawGreen,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = ClawInk,
            )
        }
    }
}

@Composable
private fun ApprovalInboxCard(
    item: ApprovalInboxItem,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(0.72f),
                )
                Spacer(modifier = Modifier.size(12.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(item.risk.name) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = riskColor(item.risk).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Action: ${item.action}  •  Requested ${item.requestedAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssistChip(
                onClick = {},
                label = { Text(statusLabel(item.status)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = statusColor(item.status).copy(alpha = 0.16f),
                    labelColor = ClawInk,
                ),
            )
            if (item.status == ApprovalInboxStatus.Pending) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onDeny(item.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Deny")
                    }
                    Button(
                        onClick = { onApprove(item.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Approve")
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexedFileCard(
    file: IndexedFileItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                ClawGreen.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = ClawGreen.copy(alpha = 0.14f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = ClawGreen,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = file.sourceLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGreen,
                )
                Text(
                    text = "${file.sizeLabel}  •  ${file.modifiedLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentTaskCard(task: AgentTaskRecord) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(agentTaskStatusLabel(task.status)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = agentTaskStatusColor(task.status).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = task.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.replyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawGreen,
                )
            }
            task.plannerSummary?.takeIf { it.isNotBlank() }?.let { plannerSummary ->
                Text(
                    text = "Planner • ${task.plannerMode?.let(::agentPlannerModeLabel) ?: "Structured"} • $plannerSummary",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClawGreen,
                )
            }
            task.plannerCapabilities.takeIf { it.isNotEmpty() }?.let { capabilities ->
                Text(
                    text = "Capabilities ${capabilities.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.plannerResources.takeIf { it.isNotEmpty() }?.let { resources ->
                Text(
                    text = "Resources ${resources.joinToString()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Action ${task.actionKey}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.retryCount > 0 || task.nextRetryAtLabel != null || task.lastError != null) {
                Text(
                    text = buildString {
                        append("Retry ")
                        append(task.retryCount)
                        append("/")
                        append(task.maxRetryCount)
                        task.nextRetryAtLabel?.let { nextRetry ->
                            append("  •  Next ")
                            append(nextRetry)
                        }
                        task.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                            append("  •  ")
                            append(error)
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (task.status == AgentTaskStatus.RetryScheduled) {
                        ClawGold
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = "Route ${task.destination.name}  •  Updated ${task.updatedAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = task.prompt,
                style = MaterialTheme.typography.labelLarge,
                color = ClawGold,
            )
        }
    }
}

@Composable
private fun AuditEventCard(event: AuditTrailEvent) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(event.result) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (event.result == "approved") {
                            ClawGreen.copy(alpha = 0.16f)
                        } else {
                            ClawGold.copy(alpha = 0.16f)
                        },
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = event.details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = event.createdAtLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PairingSessionCard(
    session: PairingSessionState,
    onApprovePairing: (String) -> Unit,
    onDenyPairing: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.requestedRole,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(pairingStatusLabel(session.status)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = pairingStatusColor(session.status).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = "QR secret ${session.qrSecret}  •  Created ${session.createdAtLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Expires ${session.expiresAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Capabilities: ${session.requestedCapabilities.joinToString()}",
                style = MaterialTheme.typography.bodyMedium,
                color = ClawGreen,
            )
            if (session.status == PairingSessionStatus.Approved) {
                Text(
                    text = "This only authorizes the companion device. Risky file actions still surface as approvals in Dashboard and the active chat context.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.status == PairingSessionStatus.Pending) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { onDenyPairing(session.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Deny pairing")
                    }
                    Button(
                        onClick = { onApprovePairing(session.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Approve pairing")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: PairedDeviceState,
    selected: Boolean,
    selectionPinnedByUser: Boolean,
    onClick: () -> Unit,
    onArmDirectHttpBridge: (String) -> Unit,
    onUseLoopbackBridge: (String) -> Unit,
    onSetTransportValidationMode: (String, TransportValidationMode) -> Unit,
    onUseAdbReverseEndpoint: () -> Unit,
    onUseEmulatorHostEndpoint: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                ClawGreen.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(ClawGreen.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.DevicesOther,
                        contentDescription = null,
                        tint = ClawGreen,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = device.role,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = device.health,
                        style = MaterialTheme.typography.labelLarge,
                        color = ClawGreen,
                    )
                }
            }
            Text(
                text = "Transport: ${deviceTransportModeLabel(device.transportMode)}",
                style = MaterialTheme.typography.labelLarge,
                color = deviceTransportModeColor(device.transportMode),
            )
            Text(
                text = "Validation: ${transportValidationModeLabel(device.validationMode)}",
                style = MaterialTheme.typography.labelLarge,
                color = if (device.validationMode == TransportValidationMode.Normal) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    ClawGold
                },
            )
            device.endpointLabel?.let { endpoint ->
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (endpoint.contains("10.0.2.2")) {
                    Text(
                        text = "10.0.2.2 is the emulator-only preset. Use adb reverse localhost on a physical phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClawGold,
                    )
                }
            }
            Text(
                text = device.capabilities.takeIf { it.isNotEmpty() }?.joinToString()
                    ?: "Capabilities pending companion health probe.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (device.capabilities.isEmpty()) {
                    ClawGold
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (selected) {
                Text(
                    text = if (selectionPinnedByUser) {
                        "Pinned send target"
                    } else {
                        "Auto-selected latest companion"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGold,
                )
            }
            if (device.transportMode == DeviceTransportMode.DirectHttp) {
                EndpointPresetButtons(
                    onUseAdbReverseEndpoint = onUseAdbReverseEndpoint,
                    onUseEmulatorHostEndpoint = onUseEmulatorHostEndpoint,
                )
                ValidationModeButtons(
                    currentMode = device.validationMode,
                    onSetMode = { mode -> onSetTransportValidationMode(device.id, mode) },
                )
                OutlinedButton(
                    onClick = { onUseLoopbackBridge(device.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use loopback fallback")
                }
            } else {
                Button(
                    onClick = { onArmDirectHttpBridge(device.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Arm LAN bridge")
                }
            }
        }
    }
}

@Composable
private fun EndpointPresetButtons(
    onUseAdbReverseEndpoint: () -> Unit,
    onUseEmulatorHostEndpoint: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Direct HTTP endpoint presets",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        FilledTonalButton(
            onClick = onUseAdbReverseEndpoint,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use adb reverse localhost")
        }
        OutlinedButton(
            onClick = onUseEmulatorHostEndpoint,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use emulator host")
        }
    }
}

@Composable
private fun ValidationModeButtons(
    currentMode: TransportValidationMode,
    onSetMode: (TransportValidationMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Transport validation mode",
            style = MaterialTheme.typography.labelLarge,
            color = ClawGreen,
            fontWeight = FontWeight.SemiBold,
        )
        TransportValidationMode.entries.forEach { mode ->
            val selected = mode == currentMode
            if (selected) {
                FilledTonalButton(
                    onClick = { onSetMode(mode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${transportValidationModeLabel(mode)}  •  selected")
                }
            } else {
                OutlinedButton(
                    onClick = { onSetMode(mode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(transportValidationModeLabel(mode))
                }
            }
        }
    }
}

@Composable
private fun TransferDraftCard(draft: TransferDraftState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = draft.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                AssistChip(
                    onClick = {},
                    label = { Text(transferStatusLabel(draft.status)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = transferStatusColor(draft.status).copy(alpha = 0.16f),
                        labelColor = ClawInk,
                    ),
                )
            }
            Text(
                text = "Queued ${draft.createdAtLabel}  •  Updated ${draft.updatedAtLabel}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = draft.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            draft.nextAttemptAtLabel?.let { retryLabel ->
                Text(
                    text = "Next retry $retryLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClawGold,
                )
            }
            if (draft.receiptReviewRequired) {
                Text(
                    text = draft.receiptIssue?.let { issue ->
                        "Receipt review needed: $issue"
                    } ?: "Receipt review needed before this delivery can be trusted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC15B52),
                )
            }
            Text(
                text = buildString {
                    append(draft.fileNames.joinToString())
                    append("  •  Attempts ")
                    append(draft.attemptCount)
                    draft.deliveryModeLabel?.takeIf { it.isNotBlank() }?.let { mode ->
                        append("  •  ")
                        append(mode)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    summary: String,
    status: String,
    icon: ImageVector,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = ClawGreen.copy(alpha = 0.14f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ClawGreen,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(0.7f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(status) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = ClawGold.copy(alpha = 0.16f),
                    labelColor = ClawInk,
                ),
            )
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    summary: String,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ClawInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = ClawInk,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShellCardView(
    card: ShellCard,
    icon: ImageVector,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = ClawGreen.copy(alpha = 0.14f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = ClawGreen,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(0.7f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ClawInk,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(card.status) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = ClawGold.copy(alpha = 0.16f),
                    labelColor = ClawInk,
                ),
            )
        }
    }
}

private fun workflowToneColor(tone: WorkflowTone): Color {
    return when (tone) {
        WorkflowTone.Neutral -> ClawInk
        WorkflowTone.Success -> ClawGreen
        WorkflowTone.Warning -> ClawGold
        WorkflowTone.Danger -> Color(0xFFC15B52)
    }
}

private fun riskColor(risk: ApprovalInboxRisk): Color {
    return when (risk) {
        ApprovalInboxRisk.Low -> ClawGreen
        ApprovalInboxRisk.Medium -> ClawGold
        ApprovalInboxRisk.High -> Color(0xFFC15B52)
    }
}

private fun statusColor(status: ApprovalInboxStatus): Color {
    return when (status) {
        ApprovalInboxStatus.Pending -> ClawGold
        ApprovalInboxStatus.Approved -> ClawGreen
        ApprovalInboxStatus.Denied -> Color(0xFFC15B52)
    }
}

private fun statusLabel(status: ApprovalInboxStatus): String {
    return when (status) {
        ApprovalInboxStatus.Pending -> "Pending review"
        ApprovalInboxStatus.Approved -> "Approved"
        ApprovalInboxStatus.Denied -> "Denied"
    }
}

private fun pairingStatusColor(status: PairingSessionStatus): Color {
    return when (status) {
        PairingSessionStatus.Pending -> ClawGold
        PairingSessionStatus.Approved -> ClawGreen
        PairingSessionStatus.Denied -> Color(0xFFC15B52)
    }
}

private fun pairingStatusLabel(status: PairingSessionStatus): String {
    return when (status) {
        PairingSessionStatus.Pending -> "Pending"
        PairingSessionStatus.Approved -> "Approved"
        PairingSessionStatus.Denied -> "Denied"
    }
}

private fun transferStatusColor(status: TransferDraftStatus): Color {
    return when (status) {
        TransferDraftStatus.Queued -> ClawGold
        TransferDraftStatus.Sending -> Color(0xFF5D8CC9)
        TransferDraftStatus.Delivered -> ClawGreen
        TransferDraftStatus.Failed -> Color(0xFFC15B52)
    }
}

private fun transferStatusLabel(status: TransferDraftStatus): String {
    return when (status) {
        TransferDraftStatus.Queued -> "Queued"
        TransferDraftStatus.Sending -> "Sending"
        TransferDraftStatus.Delivered -> "Delivered"
        TransferDraftStatus.Failed -> "Failed"
    }
}

private fun agentTaskStatusColor(status: AgentTaskStatus): Color {
    return when (status) {
        AgentTaskStatus.Queued,
        AgentTaskStatus.Planning,
        AgentTaskStatus.Running -> ClawGold
        AgentTaskStatus.WaitingUser,
        AgentTaskStatus.WaitingResource,
        AgentTaskStatus.Paused,
        AgentTaskStatus.RetryScheduled -> Color(0xFF5D8CC9)
        AgentTaskStatus.Succeeded -> ClawGreen
        AgentTaskStatus.Failed,
        AgentTaskStatus.Cancelled -> Color(0xFFC15B52)
    }
}

private fun agentTaskStatusLabel(status: AgentTaskStatus): String {
    return when (status) {
        AgentTaskStatus.Queued -> "Queued"
        AgentTaskStatus.Planning -> "Planning"
        AgentTaskStatus.WaitingUser -> "Waiting user"
        AgentTaskStatus.WaitingResource -> "Waiting resource"
        AgentTaskStatus.Running -> "Running"
        AgentTaskStatus.Paused -> "Paused"
        AgentTaskStatus.RetryScheduled -> "Retry scheduled"
        AgentTaskStatus.Succeeded -> "Succeeded"
        AgentTaskStatus.Failed -> "Failed"
        AgentTaskStatus.Cancelled -> "Cancelled"
    }
}

private fun agentDestinationLabel(destination: AgentDestination): String {
    return when (destination) {
        AgentDestination.Chat -> "Chat"
        AgentDestination.Dashboard -> "Dashboard"
        AgentDestination.History -> "History"
        AgentDestination.Settings -> "Settings"
    }
}

private fun chatMessageFollowUp(
    task: AgentTaskRecord?,
    approval: ApprovalInboxItem?,
): String? {
    return when {
        approval?.status == ApprovalInboxStatus.Pending ->
            "Next step: approve or deny this request here in the conversation."
        task == null -> null
        task.actionKey == companionHealthProbeActionKeyForChat &&
            task.status == AgentTaskStatus.Succeeded ->
            "Next step: send a desktop notification or run a companion workflow from chat."
        task.actionKey == companionSessionNotifyActionKeyForChat &&
            task.status == AgentTaskStatus.Succeeded ->
            "Next step: continue from chat with a workflow run or another companion surface request."
        task.actionKey == companionAppOpenActionKeyForChat &&
            task.status == AgentTaskStatus.Succeeded ->
            "Next step: steer another companion surface from chat or continue the conversation."
        task.actionKey == companionWorkflowRunActionKeyForChat &&
            task.status == AgentTaskStatus.Succeeded ->
            "Next step: reopen the surfaced folder from chat or move on to the next turn."
        task.status == AgentTaskStatus.Running || task.status == AgentTaskStatus.Planning ->
            "The agent is still working on this turn."
        task.status == AgentTaskStatus.WaitingResource && isChatRetryableTask(task) ->
            "This task is blocked on a resource or companion condition. Fix the dependency, then retry it from chat."
        task.status == AgentTaskStatus.WaitingResource ->
            "This task is waiting on a missing resource. Settings is the next place to fix the connection."
        task.status == AgentTaskStatus.WaitingUser ->
            "This turn still needs your review before the agent can continue."
        task.status == AgentTaskStatus.RetryScheduled ->
            "A retry is already scheduled, but you can force another attempt from chat."
        task.status == AgentTaskStatus.Succeeded ->
            "This task finished and the result is now attached to the active conversation."
        task.status == AgentTaskStatus.Failed && isChatRetryableTask(task) ->
            "The task failed, but it still has a safe retry path from the conversation."
        task.status == AgentTaskStatus.Failed ->
            "The task failed and needs inspection before another run makes sense."
        task.status == AgentTaskStatus.Cancelled ->
            "This task was cancelled and will not run again unless you create a new request."
        task.status == AgentTaskStatus.Paused ->
            "This task is paused and waiting for the runtime to resume it."
        else -> null
    }
}

private fun isChatRetryableTask(task: AgentTaskRecord): Boolean {
    val retryableStatus = task.status == AgentTaskStatus.RetryScheduled ||
        task.status == AgentTaskStatus.Failed ||
        task.status == AgentTaskStatus.WaitingResource
    if (!retryableStatus) {
        return false
    }
    return when (task.actionKey) {
        "files.organize.execute" -> task.maxRetryCount > 0
        "files.transfer.execute" -> !task.approvalRequestId.isNullOrBlank()
        else -> false
    }
}

private const val maxChatInlineApprovals = 2
private const val maxChatInlineRetries = 2
private const val promptRefreshResources = "Refresh my connected resources"
private const val promptSummarizeCurrentFiles = "Summarize my current files"
private const val promptOrganizeFilesByType = "Organize my files by type"
private const val promptOpenSettingsAndResources = "Open settings and show my connected resources"
private const val promptShowDashboardAndApprovals = "Show my dashboard and pending approvals"
private const val promptShowDashboard = "Show my dashboard"
private const val promptCheckCompanionHealth = "Check companion health"
private const val promptSendDesktopNotification = "Send a desktop notification"
private const val promptRunOpenLatestActionWorkflow = "Run the open latest action workflow"
private const val promptRunOpenLatestTransferWorkflow = "Run the open latest transfer workflow"
private const val promptRunOpenActionsFolderWorkflow = "Run the open actions folder workflow"
private const val promptOpenLatestActionFolder = "Open the latest action folder"
private const val promptOpenLatestTransferFolder = "Open the latest transfer folder"
private const val promptOpenCompanionInbox = "Open the companion inbox"
private const val promptOpenActionsFolder = "Open the actions folder"
private const val filesTransferActionKeyForChat = "files.transfer.execute"
private const val companionHealthProbeActionKeyForChat = "devices.health_probe"
private const val companionSessionNotifyActionKeyForChat = "devices.session_notify"
private const val companionAppOpenActionKeyForChat = "devices.app_open"
private const val companionWorkflowRunActionKeyForChat = "devices.workflow_run"

private fun transportAuditResultColor(result: String): Color {
    return when (result) {
        "queued",
        "sending",
        "delivered",
        "approved",
        "recovered",
        "requeued",
        "manual_drain_requested",
        "loopback",
        "direct_http",
        "normal",
        "partial_receipt",
        "malformed_receipt",
        "retry_once",
        "delayed_ack",
        "timeout_once",
        "disconnect_once"
        -> ClawGreen
        "retry_scheduled",
        "delivered_receipt_warning"
        -> ClawGold
        "failed",
        "denied"
        -> Color(0xFFC15B52)
        else -> ClawInk
    }
}

private fun companionProbeStatusColor(status: CompanionHealthStatus): Color {
    return when (status) {
        CompanionHealthStatus.Healthy -> ClawGreen
        CompanionHealthStatus.Unreachable -> Color(0xFFC15B52)
        CompanionHealthStatus.Misconfigured -> ClawGold
        CompanionHealthStatus.Skipped -> ClawInk
    }
}

private fun shellRecoveryStatusColor(status: ShellRecoveryStatus): Color {
    return when (status) {
        ShellRecoveryStatus.Idle -> ClawInk
        ShellRecoveryStatus.Running -> ClawGold
        ShellRecoveryStatus.Success -> ClawGreen
        ShellRecoveryStatus.Failed -> Color(0xFFC15B52)
    }
}

private fun shellRecoveryStatusLabel(status: ShellRecoveryStatus): String {
    return when (status) {
        ShellRecoveryStatus.Idle -> "Shell recovery idle"
        ShellRecoveryStatus.Running -> "Shell recovery running"
        ShellRecoveryStatus.Success -> "Shell recovery passed"
        ShellRecoveryStatus.Failed -> "Shell recovery failed"
    }
}

private fun companionProbeStatusLabel(status: CompanionHealthStatus): String {
    return when (status) {
        CompanionHealthStatus.Healthy -> "Companion online"
        CompanionHealthStatus.Unreachable -> "Companion unreachable"
        CompanionHealthStatus.Misconfigured -> "Companion misconfigured"
        CompanionHealthStatus.Skipped -> "Probe skipped"
    }
}

private fun companionProbeDetailLabel(probe: CompanionHealthCheckResult): String {
    if (probe.status != CompanionHealthStatus.Healthy) {
        return probe.detail
    }
    val detail = probe.detail.trim()
    if (detail.isBlank()) {
        return probe.summary
    }
    return if (detail.startsWith("http", ignoreCase = true)) {
        "Endpoint • $detail"
    } else {
        "Inbox • $detail"
    }
}

private fun companionProbeAuditStatusColor(result: String): Color {
    return when (result.lowercase()) {
        "ok" -> ClawGreen
        "failed" -> Color(0xFFC15B52)
        "misconfigured" -> ClawGold
        else -> ClawInk
    }
}

private fun companionProbeAuditStatusLabel(result: String): String {
    return when (result.lowercase()) {
        "ok" -> "Companion online"
        "failed" -> "Companion unreachable"
        "misconfigured" -> "Companion misconfigured"
        else -> "Probe skipped"
    }
}

private fun companionProbeAuditSummary(event: AuditTrailEvent): String {
    val body = event.details.substringAfter(": ", event.details).trim()
    if (!body.contains(" • ")) {
        return body
    }
    val segments = body.split(" • ").map(String::trim).filter(String::isNotBlank)
    val lastSegment = segments.lastOrNull().orEmpty()
    val hasTrailingLocation = lastSegment.startsWith("http", ignoreCase = true) ||
        lastSegment.contains(":\\") ||
        lastSegment.startsWith("/")
    return if (hasTrailingLocation && segments.size > 1) {
        segments.dropLast(1).joinToString(" • ")
    } else {
        body
    }
}

private fun companionProbeAuditDetail(event: AuditTrailEvent): String? {
    val body = event.details.substringAfter(": ", event.details).trim()
    if (!body.contains(" • ")) {
        return null
    }
    val segments = body.split(" • ").map(String::trim).filter(String::isNotBlank)
    val lastSegment = segments.lastOrNull().orEmpty()
    val hasTrailingLocation = lastSegment.startsWith("http", ignoreCase = true) ||
        lastSegment.contains(":\\") ||
        lastSegment.startsWith("/")
    if (!hasTrailingLocation) {
        return null
    }
    return if (lastSegment.startsWith("http", ignoreCase = true)) {
        "Endpoint • $lastSegment"
    } else {
        "Inbox • $lastSegment"
    }
}

private fun companionNotifyStatusColor(status: CompanionSessionNotifyStatus): Color {
    return when (status) {
        CompanionSessionNotifyStatus.Delivered -> ClawGreen
        CompanionSessionNotifyStatus.Failed -> Color(0xFFC15B52)
        CompanionSessionNotifyStatus.Misconfigured -> ClawGold
        CompanionSessionNotifyStatus.Skipped -> ClawInk
    }
}

private fun companionNotifyStatusLabel(status: CompanionSessionNotifyStatus): String {
    return when (status) {
        CompanionSessionNotifyStatus.Delivered -> "Companion notification delivered"
        CompanionSessionNotifyStatus.Failed -> "Companion notification failed"
        CompanionSessionNotifyStatus.Misconfigured -> "Companion notification misconfigured"
        CompanionSessionNotifyStatus.Skipped -> "Notification skipped"
    }
}

private fun companionAppOpenStatusColor(status: CompanionAppOpenStatus): Color {
    return when (status) {
        CompanionAppOpenStatus.Opened -> ClawGreen
        CompanionAppOpenStatus.Recorded -> ClawGold
        CompanionAppOpenStatus.Failed -> Color(0xFFC15B52)
        CompanionAppOpenStatus.Misconfigured -> ClawGold
        CompanionAppOpenStatus.Skipped -> ClawInk
    }
}

private fun companionAppOpenStatusLabel(status: CompanionAppOpenStatus): String {
    return when (status) {
        CompanionAppOpenStatus.Opened -> "Companion app.open delivered"
        CompanionAppOpenStatus.Recorded -> "Companion app.open recorded"
        CompanionAppOpenStatus.Failed -> "Companion app.open failed"
        CompanionAppOpenStatus.Misconfigured -> "Companion app.open misconfigured"
        CompanionAppOpenStatus.Skipped -> "app.open skipped"
    }
}

private fun companionWorkflowRunStatusColor(status: CompanionWorkflowRunStatus): Color {
    return when (status) {
        CompanionWorkflowRunStatus.Completed -> ClawGreen
        CompanionWorkflowRunStatus.Recorded -> ClawGold
        CompanionWorkflowRunStatus.Failed -> Color(0xFFC15B52)
        CompanionWorkflowRunStatus.Misconfigured -> ClawGold
        CompanionWorkflowRunStatus.Skipped -> ClawInk
    }
}

private fun companionWorkflowRunStatusLabel(status: CompanionWorkflowRunStatus): String {
    return when (status) {
        CompanionWorkflowRunStatus.Completed -> "Companion workflow.run completed"
        CompanionWorkflowRunStatus.Recorded -> "Companion workflow.run recorded"
        CompanionWorkflowRunStatus.Failed -> "Companion workflow.run failed"
        CompanionWorkflowRunStatus.Misconfigured -> "Companion workflow.run misconfigured"
        CompanionWorkflowRunStatus.Skipped -> "workflow.run skipped"
    }
}

private fun deviceTransportModeColor(mode: DeviceTransportMode): Color {
    return when (mode) {
        DeviceTransportMode.Loopback -> ClawGold
        DeviceTransportMode.DirectHttp -> ClawGreen
    }
}

private fun deviceTransportModeLabel(mode: DeviceTransportMode): String {
    return when (mode) {
        DeviceTransportMode.Loopback -> "Loopback"
        DeviceTransportMode.DirectHttp -> "Direct HTTP"
    }
}

private fun PairedDeviceState.supportsCompanionCapability(capability: String): Boolean {
    return capabilities.any { advertised ->
        advertised.equals(capability, ignoreCase = true)
    }
}

private fun transportValidationModeLabel(mode: TransportValidationMode): String {
    return when (mode) {
        TransportValidationMode.Normal -> "Normal receipts"
        TransportValidationMode.PartialReceipt -> "Partial receipt"
        TransportValidationMode.MalformedReceipt -> "Malformed receipt"
        TransportValidationMode.RetryOnce -> "Retry once"
        TransportValidationMode.DelayedAck -> "Delayed ack"
        TransportValidationMode.TimeoutOnce -> "Timeout once"
        TransportValidationMode.DisconnectOnce -> "Disconnect once"
    }
}

private fun resourceConnectionStatusColor(status: ResourceConnectionStatus): Color {
    return when (status) {
        ResourceConnectionStatus.Active -> ClawGreen
        ResourceConnectionStatus.NeedsSetup -> ClawGold
        ResourceConnectionStatus.Planned -> Color(0xFF5D8CC9)
    }
}

private fun resourceConnectionStatusLabel(status: ResourceConnectionStatus): String {
    return when (status) {
        ResourceConnectionStatus.Active -> "Active"
        ResourceConnectionStatus.NeedsSetup -> "Needs setup"
        ResourceConnectionStatus.Planned -> "Planned"
    }
}

@Composable
private fun ScheduledAutomationCard(
    automation: ScheduledAutomationRecord,
    onActivate: () -> Unit,
    onPause: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = automation.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClawInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = automation.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = scheduledAutomationStatusColor(automation.status).copy(alpha = 0.16f),
                ) {
                    Text(
                        text = scheduledAutomationStatusLabel(automation.status),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheduledAutomationStatusColor(automation.status),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(automation.scheduleLabel) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(automation.deliveryLabel) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Updated ${automation.updatedAtLabel}") },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (automation.status == ScheduledAutomationStatus.Active) {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Pause placeholder")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onActivate,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Activate placeholder")
                    }
                }
            }
        }
    }
}

private fun cloudDriveStatusColor(status: CloudDriveConnectionStatus): Color {
    return when (status) {
        CloudDriveConnectionStatus.NeedsSetup -> ClawGold
        CloudDriveConnectionStatus.Staged -> Color(0xFF5D8CC9)
        CloudDriveConnectionStatus.Connected -> ClawGreen
    }
}

private fun cloudDriveStatusLabel(status: CloudDriveConnectionStatus): String {
    return when (status) {
        CloudDriveConnectionStatus.NeedsSetup -> "Needs setup"
        CloudDriveConnectionStatus.Staged -> "Staged"
        CloudDriveConnectionStatus.Connected -> "Mock ready"
    }
}

private fun scheduledAutomationStatusColor(status: ScheduledAutomationStatus): Color {
    return when (status) {
        ScheduledAutomationStatus.Planned -> ClawGold
        ScheduledAutomationStatus.Active -> ClawGreen
        ScheduledAutomationStatus.Paused -> Color(0xFF6D7C8A)
    }
}

private fun scheduledAutomationStatusLabel(status: ScheduledAutomationStatus): String {
    return when (status) {
        ScheduledAutomationStatus.Planned -> "Planned"
        ScheduledAutomationStatus.Active -> "Placeholder active"
        ScheduledAutomationStatus.Paused -> "Paused"
    }
}

private fun providerCredentialStatusColor(status: ModelProviderCredentialStatus): Color {
    return when (status) {
        ModelProviderCredentialStatus.Missing -> ClawGold
        ModelProviderCredentialStatus.Stored -> ClawGreen
    }
}

private fun providerCredentialStatusLabel(status: ModelProviderCredentialStatus): String {
    return when (status) {
        ModelProviderCredentialStatus.Missing -> "Missing"
        ModelProviderCredentialStatus.Stored -> "Stored"
    }
}

private fun plannerModeColor(mode: AgentPlannerMode): Color {
    return when (mode) {
        AgentPlannerMode.Answer -> ClawGreen
        AgentPlannerMode.Question -> ClawGold
        AgentPlannerMode.Plan -> Color(0xFF5D8CC9)
        AgentPlannerMode.ActionIntent -> ClawInk
        AgentPlannerMode.Escalation -> Color(0xFFC15B52)
    }
}

private fun agentPlannerModeLabel(mode: AgentPlannerMode): String {
    return when (mode) {
        AgentPlannerMode.Answer -> "Answer"
        AgentPlannerMode.Question -> "Question"
        AgentPlannerMode.Plan -> "Plan"
        AgentPlannerMode.ActionIntent -> "Action"
        AgentPlannerMode.Escalation -> "Escalation"
    }
}

private fun organizeExecutionColor(status: OrganizeExecutionStatus): Color {
    return when (status) {
        OrganizeExecutionStatus.Moved -> ClawGreen
        OrganizeExecutionStatus.DeleteConsentRequired -> ClawGold
        OrganizeExecutionStatus.CopiedOnly -> Color(0xFF5D8CC9)
        OrganizeExecutionStatus.Failed -> Color(0xFFC15B52)
    }
}

private fun organizeExecutionLabel(status: OrganizeExecutionStatus): String {
    return when (status) {
        OrganizeExecutionStatus.Moved -> "Moved"
        OrganizeExecutionStatus.DeleteConsentRequired -> "Delete consent"
        OrganizeExecutionStatus.CopiedOnly -> "Copied only"
        OrganizeExecutionStatus.Failed -> "Failed"
    }
}
