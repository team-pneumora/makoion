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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.makoion.mobileclaw.data.ApprovalInboxItem
import io.makoion.mobileclaw.data.ApprovalInboxRisk
import io.makoion.mobileclaw.data.ApprovalInboxStatus
import io.makoion.mobileclaw.data.AuditTrailEvent
import io.makoion.mobileclaw.data.CompanionHealthStatus
import io.makoion.mobileclaw.data.DeviceTransportMode
import io.makoion.mobileclaw.data.FileIndexState
import io.makoion.mobileclaw.data.FileOrganizePlan
import io.makoion.mobileclaw.data.FilePreviewDetail
import io.makoion.mobileclaw.data.FileSummaryDetail
import io.makoion.mobileclaw.data.IndexedFileItem
import io.makoion.mobileclaw.data.MediaAccessPermissions
import io.makoion.mobileclaw.data.OrganizeExecutionEntry
import io.makoion.mobileclaw.data.OrganizeExecutionResult
import io.makoion.mobileclaw.data.OrganizeExecutionStatus
import io.makoion.mobileclaw.data.PairedDeviceState
import io.makoion.mobileclaw.data.PairingSessionState
import io.makoion.mobileclaw.data.PairingSessionStatus
import io.makoion.mobileclaw.data.TransferDraftStatus
import io.makoion.mobileclaw.data.TransferDraftState
import io.makoion.mobileclaw.data.TransportValidationMode
import io.makoion.mobileclaw.data.VoiceEntryState
import io.makoion.mobileclaw.ui.theme.ClawGold
import io.makoion.mobileclaw.ui.theme.ClawGreen
import io.makoion.mobileclaw.ui.theme.ClawInk

@Composable
fun MobileClawShellApp(
    startSection: ShellSection = ShellSection.Overview,
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
    LaunchedEffect(shellViewModel) {
        shellViewModel.deleteConsentPrompts.collect { prompt ->
            deleteConsentLauncher.launch(
                IntentSenderRequest.Builder(prompt.intentSender).build(),
            )
        }
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
            ShellSection.Overview -> OverviewScreen(
                uiState = uiState,
                innerPadding = innerPadding,
                onRequestMediaAccess = requestMediaAccess,
                onOpenDocumentTree = openDocumentTree,
                onRefreshFiles = shellViewModel::refreshFiles,
                onToggleVoiceCapture = toggleVoiceCapture,
                onShowQuickActions = showQuickActions,
                onOpenApprovals = { shellViewModel.openSection(ShellSection.Approvals) },
            )
            ShellSection.Files -> FilesScreen(
                fileIndexState = uiState.fileIndexState,
                fileActionState = uiState.fileActionState,
                deviceControlState = uiState.deviceControlState,
                innerPadding = innerPadding,
                onRequestMediaAccess = requestMediaAccess,
                onOpenDocumentTree = openDocumentTree,
                onRefreshFiles = shellViewModel::refreshFiles,
                onSelectFile = shellViewModel::selectFile,
                onSummarizeFiles = shellViewModel::summarizeCurrentFiles,
                onPlanOrganizeByType = shellViewModel::planOrganizeByType,
                onPlanOrganizeBySource = shellViewModel::planOrganizeBySource,
                onRequestOrganizeApproval = shellViewModel::requestOrganizeApproval,
                onRequestDeleteConsent = shellViewModel::requestDeleteConsentForLatestOrganize,
                onShareCurrentFiles = shellViewModel::shareCurrentFiles,
                onSelectTargetDevice = shellViewModel::selectTargetDevice,
                onSendCurrentFilesToDevice = shellViewModel::sendCurrentFilesToSelectedDevice,
            )
            ShellSection.Approvals -> ApprovalsScreen(
                approvals = uiState.approvals,
                auditEvents = uiState.auditEvents,
                innerPadding = innerPadding,
                onApprove = shellViewModel::approve,
                onDeny = shellViewModel::deny,
            )
            ShellSection.Devices -> DevicesScreen(
                deviceControlState = uiState.deviceControlState,
                voiceEntryState = uiState.voiceEntryState,
                innerPadding = innerPadding,
                onToggleVoiceCapture = toggleVoiceCapture,
                onShowQuickActions = showQuickActions,
                onStartPairing = shellViewModel::startPairing,
                onApprovePairing = shellViewModel::approvePairing,
                onDenyPairing = shellViewModel::denyPairing,
                onSelectTargetDevice = shellViewModel::selectTargetDevice,
                onArmDirectHttpBridge = shellViewModel::armDirectHttpBridge,
                onUseLoopbackBridge = shellViewModel::useLoopbackBridge,
                onSetTransportValidationMode = shellViewModel::setTransportValidationMode,
                onUseAdbReverseEndpoint = shellViewModel::useAdbReverseEndpoint,
                onUseEmulatorHostEndpoint = shellViewModel::useEmulatorHostEndpoint,
                onRefreshDeviceState = shellViewModel::refreshDeviceState,
                onProbeSelectedDeviceHealth = shellViewModel::probeSelectedDeviceHealth,
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
        ShellSection.Overview -> Icons.Default.Home
        ShellSection.Files -> Icons.Default.FolderOpen
        ShellSection.Approvals -> Icons.Default.CheckCircle
        ShellSection.Devices -> Icons.Default.DevicesOther
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
    deviceControlState: DeviceControlState,
    innerPadding: PaddingValues,
    onRequestMediaAccess: () -> Unit,
    onOpenDocumentTree: () -> Unit,
    onRefreshFiles: () -> Unit,
    onSelectFile: (String) -> Unit,
    onSummarizeFiles: () -> Unit,
    onPlanOrganizeByType: () -> Unit,
    onPlanOrganizeBySource: () -> Unit,
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
                onRequestOrganizeApproval = onRequestOrganizeApproval,
                onShareCurrentFiles = onShareCurrentFiles,
                onSelectTargetDevice = onSelectTargetDevice,
                onSendCurrentFilesToDevice = onSendCurrentFilesToDevice,
            )
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
                title = "Approval Inbox",
                subtitle = "$pendingApprovals pending actions remain reviewable before any risky execution path proceeds.",
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
private fun DevicesScreen(
    deviceControlState: DeviceControlState,
    voiceEntryState: VoiceEntryState,
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
                subtitle = "Tap a device to make it the default send target for file transfer drafts.",
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
            BridgeDebugCard(
                selectedDevice = selectedDevice,
                diagnostics = deviceControlState.transportDiagnostics,
                companionProbe = deviceControlState.companionProbe,
                onRefreshDeviceState = onRefreshDeviceState,
                onProbeSelectedDeviceHealth = onProbeSelectedDeviceHealth,
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
                    summary = "Queue a send from the Files tab after selecting a paired target device.",
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
private fun BridgeDebugCard(
    selectedDevice: PairedDeviceState?,
    diagnostics: TransportDiagnostics,
    companionProbe: CompanionProbeState,
    onRefreshDeviceState: () -> Unit,
    onProbeSelectedDeviceHealth: () -> Unit,
    onDrainTransferOutboxNow: () -> Unit,
    onRetryFailedTransfers: () -> Unit,
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
            selectedDevice?.endpointLabel?.let { endpoint ->
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    text = probe.detail,
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
                onClick = onProbeSelectedDeviceHealth,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedDevice != null && !companionProbe.isChecking,
            ) {
                Text(
                    if (companionProbe.isChecking) {
                        "Checking companion health..."
                    } else {
                        "Check companion health"
                    },
                )
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
                Text("Open approval inbox ($pendingApprovals)")
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
    onRequestOrganizeApproval: () -> Unit,
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
            Button(
                onClick = onRequestOrganizeApproval,
                modifier = Modifier.fillMaxWidth(),
                enabled = fileActionState.organizePlan != null && !fileActionState.isLoading,
            ) {
                Text("Request organize approval")
            }
            if (deviceControlState.pairedDevices.isEmpty()) {
                EmptyStateCard(
                    title = "No paired transfer target",
                    summary = "Use the Devices tab to approve a pairing session before queuing send-to-device actions.",
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
            Text(
                text = result.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            }
            Text(
                text = device.capabilities.joinToString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected) {
                Text(
                    text = "Current send target",
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

private fun companionProbeStatusLabel(status: CompanionHealthStatus): String {
    return when (status) {
        CompanionHealthStatus.Healthy -> "Companion healthy"
        CompanionHealthStatus.Unreachable -> "Companion unreachable"
        CompanionHealthStatus.Misconfigured -> "Companion misconfigured"
        CompanionHealthStatus.Skipped -> "Probe skipped"
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
