package io.makoion.mobileclaw.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.ApprovalInboxItem
import io.makoion.mobileclaw.data.AuditTrailEvent
import io.makoion.mobileclaw.data.CompanionHealthCheckResult
import io.makoion.mobileclaw.data.FileIndexState
import io.makoion.mobileclaw.data.FileOrganizePlan
import io.makoion.mobileclaw.data.FileOrganizeStrategy
import io.makoion.mobileclaw.data.FilePreviewDetail
import io.makoion.mobileclaw.data.FileSummaryDetail
import io.makoion.mobileclaw.data.PairedDeviceState
import io.makoion.mobileclaw.data.PairingSessionState
import io.makoion.mobileclaw.data.TransferDraftState
import io.makoion.mobileclaw.data.TransferDraftStatus
import io.makoion.mobileclaw.data.TransportValidationMode
import io.makoion.mobileclaw.data.VoiceEntryState
import io.makoion.mobileclaw.data.OrganizeExecutionResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ShellSection {
    Overview,
    Files,
    Approvals,
    Devices,
    ;

    val label: String
        get() = when (this) {
            Overview -> "Overview"
            Files -> "Files"
            Approvals -> "Approvals"
            Devices -> "Devices"
        }

    val routeKey: String
        get() = name.lowercase()

    companion object {
        fun fromRouteKey(routeKey: String?): ShellSection {
            return entries.firstOrNull { it.routeKey == routeKey } ?: Overview
        }
    }
}

data class ShellCard(
    val title: String,
    val description: String,
    val status: String,
)

data class ShellUiState(
    val selectedSection: ShellSection = ShellSection.Overview,
    val phaseTitle: String = "Phase 1 Android MVP shell",
    val summary: String = "Native Android first. The shell now exercises file indexing, approval review, notifications, and voice capture entry points around the completed core contracts.",
    val overviewCards: List<ShellCard> = defaultOverviewCards,
    val fileIndexState: FileIndexState = FileIndexState(),
    val approvals: List<ApprovalInboxItem> = emptyList(),
    val auditEvents: List<AuditTrailEvent> = emptyList(),
    val fileActionState: FileActionState = FileActionState(),
    val deviceControlState: DeviceControlState = DeviceControlState(),
    val voiceEntryState: VoiceEntryState = VoiceEntryState(),
)

data class FileActionState(
    val selectedFileId: String? = null,
    val preview: FilePreviewDetail? = null,
    val summary: FileSummaryDetail? = null,
    val organizePlan: FileOrganizePlan? = null,
    val isLoading: Boolean = false,
    val lastExecutionNote: String? = null,
    val lastOrganizeResult: OrganizeExecutionResult? = null,
    val lastOrganizeApprovalId: String? = null,
)

data class DeleteConsentPrompt(
    val intentSender: IntentSender,
    val requestedCount: Int,
)

data class TransportDiagnostics(
    val queuedCount: Int = 0,
    val sendingCount: Int = 0,
    val deliveredCount: Int = 0,
    val failedCount: Int = 0,
    val retryScheduledCount: Int = 0,
    val receiptReviewCount: Int = 0,
    val nextRetryLabel: String? = null,
)

data class CompanionProbeState(
    val isChecking: Boolean = false,
    val result: CompanionHealthCheckResult? = null,
)

data class DeviceControlState(
    val pairedDevices: List<PairedDeviceState> = emptyList(),
    val pairingSessions: List<PairingSessionState> = emptyList(),
    val transferDrafts: List<TransferDraftState> = emptyList(),
    val selectedTargetDeviceId: String? = null,
    val transportDiagnostics: TransportDiagnostics = TransportDiagnostics(),
    val transportAuditEvents: List<AuditTrailEvent> = emptyList(),
    val companionProbe: CompanionProbeState = CompanionProbeState(),
)

private val defaultOverviewCards = listOf(
    ShellCard(
        title = "Phone Hub",
        description = "Compose shell is the primary surface for file work, approvals, and device orchestration.",
        status = "Active",
    ),
    ShellCard(
        title = "Core Contracts",
        description = "Task engine, policy, model routing, DB schema, and pairing contracts are already available.",
        status = "Ready",
    ),
    ShellCard(
        title = "Native Bridges",
        description = "MediaStore access, notifications, and voice capture stay native from day one.",
        status = "Wiring",
    ),
)

private const val adbReverseEndpoint = "http://127.0.0.1:8787/api/v1/transfers"
private const val emulatorHostEndpoint = "http://10.0.2.2:8787/api/v1/transfers"

private data class ShellSnapshot(
    val section: ShellSection,
    val files: FileIndexState,
    val actions: FileActionState,
    val selectedTargetDeviceId: String?,
    val companionProbe: CompanionProbeState,
)

private data class DeviceSnapshot(
    val pairedDevices: List<PairedDeviceState>,
    val pairingSessions: List<PairingSessionState>,
    val transferDrafts: List<TransferDraftState>,
)

class ShellViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContainer = (application as MobileClawApplication).appContainer
    private val selectedSection = MutableStateFlow(ShellSection.Overview)
    private val fileActionState = MutableStateFlow(FileActionState())
    private val selectedTargetDeviceId = MutableStateFlow<String?>(null)
    private val companionProbeState = MutableStateFlow(CompanionProbeState())
    private val _deleteConsentPrompts = MutableSharedFlow<DeleteConsentPrompt>(extraBufferCapacity = 1)
    private val fileIndexState = MutableStateFlow(
        FileIndexState(
            isRefreshing = true,
            headline = "Scanning local files",
            summary = "Checking Android permissions and recent MediaStore entries for the shell.",
        ),
    )
    val deleteConsentPrompts: SharedFlow<DeleteConsentPrompt> = _deleteConsentPrompts.asSharedFlow()

    val uiState: StateFlow<ShellUiState> = combine(
        combine(
            selectedSection,
            fileIndexState,
            fileActionState,
            selectedTargetDeviceId,
            companionProbeState,
        ) { section, files, actions, targetDeviceId, companionProbe ->
            ShellSnapshot(
                section = section,
                files = files,
                actions = actions,
                selectedTargetDeviceId = targetDeviceId,
                companionProbe = companionProbe,
            )
        },
        combine(
            appContainer.devicePairingRepository.pairedDevices,
            appContainer.devicePairingRepository.pairingSessions,
            appContainer.devicePairingRepository.transferDrafts,
        ) { pairedDevices, pairingSessions, transferDrafts ->
            DeviceSnapshot(
                pairedDevices = pairedDevices,
                pairingSessions = pairingSessions,
                transferDrafts = transferDrafts,
            )
        },
        appContainer.approvalInboxRepository.items,
        appContainer.voiceEntryCoordinator.state,
        appContainer.auditTrailRepository.events,
    ) { shellInputs, deviceInputs, approvals, voice, auditEvents ->
        val selectedDeviceId = shellInputs.selectedTargetDeviceId
            ?: deviceInputs.pairedDevices.firstOrNull()?.id
        val focusedDrafts = deviceInputs.transferDrafts.let { drafts ->
            if (selectedDeviceId == null) {
                drafts
            } else {
                drafts.filter { it.deviceId == selectedDeviceId }
            }
        }
        val nextRetryDraft = focusedDrafts
            .filter { it.nextAttemptAtEpochMillis != null && it.nextAttemptAtLabel != null }
            .minByOrNull { it.nextAttemptAtEpochMillis ?: Long.MAX_VALUE }
        val transportAuditEvents = auditEvents.filter { event ->
            event.headline == "files.send_to_device" ||
                event.headline == "devices.transport" ||
                event.headline == "devices.transport_validation" ||
                event.headline == "devices.transport_endpoint" ||
                event.headline == "devices.health_probe"
        }.take(6)
        ShellUiState(
            selectedSection = shellInputs.section,
            fileIndexState = shellInputs.files,
            approvals = approvals,
            auditEvents = auditEvents,
            fileActionState = shellInputs.actions,
            deviceControlState = DeviceControlState(
                pairedDevices = deviceInputs.pairedDevices,
                pairingSessions = deviceInputs.pairingSessions,
                transferDrafts = deviceInputs.transferDrafts,
                selectedTargetDeviceId = selectedDeviceId,
                transportDiagnostics = TransportDiagnostics(
                    queuedCount = focusedDrafts.count { it.status == TransferDraftStatus.Queued },
                    sendingCount = focusedDrafts.count { it.status == TransferDraftStatus.Sending },
                    deliveredCount = focusedDrafts.count { it.status == TransferDraftStatus.Delivered },
                    failedCount = focusedDrafts.count { it.status == TransferDraftStatus.Failed },
                    retryScheduledCount = focusedDrafts.count { it.nextAttemptAtLabel != null },
                    receiptReviewCount = focusedDrafts.count { it.receiptReviewRequired },
                    nextRetryLabel = nextRetryDraft?.nextAttemptAtLabel,
                ),
                transportAuditEvents = transportAuditEvents,
                companionProbe = shellInputs.companionProbe.takeIf { probe ->
                    probe.result?.deviceId == null || probe.result.deviceId == selectedDeviceId
                } ?: CompanionProbeState(),
            ),
            voiceEntryState = voice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ShellUiState(fileIndexState = fileIndexState.value),
    )

    init {
        refreshFiles()
    }

    fun openSection(section: ShellSection) {
        selectedSection.value = section
    }

    fun refreshFiles() {
        viewModelScope.launch {
            fileIndexState.update { current ->
                current.copy(
                    isRefreshing = true,
                    headline = if (current.permissionGranted) {
                        current.headline
                    } else {
                        "Scanning local files"
                    },
                )
            }
            refreshFilesInternal()
        }
    }

    fun attachDocumentTree(treeUri: Uri) {
        viewModelScope.launch {
            fileIndexState.update { current ->
                current.copy(
                    isRefreshing = true,
                    headline = "Attaching document root",
                )
            }
            fileIndexState.value = appContainer.fileIndexRepository.registerDocumentTree(treeUri)
        }
    }

    fun approve(id: String) {
        viewModelScope.launch {
            val approval = appContainer.approvalInboxRepository.approve(id)
            if (approval?.action == "files.organize.execute" && !approval.payloadJson.isNullOrBlank()) {
                val result = appContainer.fileActionExecutor.executeApprovedOrganize(approval)
                appContainer.approvalInboxRepository.recordExecutionOutcome(
                    id = approval.id,
                    note = result.summary,
                )
                fileActionState.update {
                    it.copy(
                        lastExecutionNote = result.summary,
                        lastOrganizeResult = result,
                        lastOrganizeApprovalId = approval.id,
                    )
                }
                refreshFilesInternal()
            }
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun deny(id: String) {
        viewModelScope.launch {
            appContainer.approvalInboxRepository.deny(id)
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun toggleVoiceCapture() {
        if (appContainer.voiceEntryCoordinator.state.value.isActive) {
            appContainer.voiceEntryCoordinator.stopCapture()
        } else {
            appContainer.voiceEntryCoordinator.startCapture()
        }
    }

    fun postQuickActionsNotification() {
        appContainer.voiceEntryCoordinator.postQuickActionsNotification()
    }

    fun selectFile(fileId: String) {
        if (fileActionState.value.selectedFileId == fileId) {
            fileActionState.update {
                it.copy(
                    selectedFileId = null,
                    preview = null,
                )
            }
            return
        }
        val item = fileIndexState.value.indexedItems.firstOrNull { it.id == fileId } ?: return
        viewModelScope.launch {
            fileActionState.update {
                it.copy(
                    selectedFileId = fileId,
                    isLoading = true,
                )
            }
            val preview = appContainer.fileGraphActionPlanner.preview(item)
            fileActionState.update {
                it.copy(
                    selectedFileId = fileId,
                    preview = preview,
                    isLoading = false,
                    lastExecutionNote = null,
                    lastOrganizeResult = null,
                    lastOrganizeApprovalId = null,
                )
            }
        }
    }

    fun summarizeCurrentFiles() {
        val items = actionTargetItems()
        viewModelScope.launch {
            fileActionState.update { it.copy(isLoading = true) }
            val summary = appContainer.fileGraphActionPlanner.summarize(items)
            fileActionState.update {
                it.copy(
                    summary = summary,
                    isLoading = false,
                    lastExecutionNote = null,
                    lastOrganizeResult = null,
                    lastOrganizeApprovalId = null,
                )
            }
        }
    }

    fun planOrganizeByType() {
        planOrganize(FileOrganizeStrategy.ByType)
    }

    fun planOrganizeBySource() {
        planOrganize(FileOrganizeStrategy.BySource)
    }

    fun requestOrganizeApproval() {
        val plan = fileActionState.value.organizePlan
        val items = actionTargetItems()
        if (plan == null) {
            fileActionState.update {
                it.copy(lastExecutionNote = "Create a dry-run organize plan before requesting approval.")
            }
            return
        }
        viewModelScope.launch {
            fileActionState.update { it.copy(isLoading = true) }
            val request = appContainer.approvalInboxRepository.submitOrganizeApproval(
                plan = plan,
                items = items,
            )
            fileActionState.update {
                it.copy(
                    isLoading = false,
                    lastExecutionNote = if (request == null) {
                        "No organize steps were available to submit for approval."
                    } else {
                        "Approval request submitted. Review it in the Approvals tab before any files move."
                    },
                    lastOrganizeResult = null,
                    lastOrganizeApprovalId = null,
                )
            }
            if (request != null) {
                selectedSection.value = ShellSection.Approvals
            }
            appContainer.auditTrailRepository.refresh()
        }
    }

    private fun planOrganize(strategy: FileOrganizeStrategy) {
        val items = actionTargetItems()
        viewModelScope.launch {
            fileActionState.update { it.copy(isLoading = true) }
            val plan = appContainer.fileGraphActionPlanner.planOrganize(
                items = items,
                strategy = strategy,
            )
            fileActionState.update {
                it.copy(
                    organizePlan = plan,
                    isLoading = false,
                    lastExecutionNote = null,
                    lastOrganizeResult = null,
                    lastOrganizeApprovalId = null,
                )
            }
        }
    }

    fun shareCurrentFiles() {
        val items = actionTargetItems()
        viewModelScope.launch {
            fileActionState.update { it.copy(isLoading = true) }
            appContainer.fileActionExecutor.share(items)
            fileActionState.update {
                it.copy(
                    isLoading = false,
                    lastExecutionNote = if (items.isEmpty()) {
                        "No files selected for sharing."
                    } else {
                        "Opened the Android share sheet for ${items.size} files."
                    },
                    lastOrganizeResult = null,
                    lastOrganizeApprovalId = null,
                )
            }
        }
    }

    fun selectTargetDevice(deviceId: String) {
        selectedTargetDeviceId.value = deviceId
        val currentResult = companionProbeState.value.result
        if (currentResult?.deviceId != deviceId) {
            companionProbeState.value = CompanionProbeState()
        }
    }

    fun sendCurrentFilesToSelectedDevice() {
        val targetDeviceId = selectedTargetDeviceId.value
            ?: appContainer.devicePairingRepository.pairedDevices.value.firstOrNull()?.id
        val items = actionTargetItems()
        if (targetDeviceId == null) {
            fileActionState.update {
                it.copy(lastExecutionNote = "Pair a device before sending files.")
            }
            return
        }
        viewModelScope.launch {
            fileActionState.update { it.copy(isLoading = true) }
            appContainer.devicePairingRepository.queueTransfer(
                deviceId = targetDeviceId,
                files = items,
            )
            fileActionState.update {
                it.copy(
                    isLoading = false,
                    lastExecutionNote = if (items.isEmpty()) {
                        "No files selected for device transfer."
                    } else {
                        "Queued ${items.size} files. Bridge delivery will continue in the background."
                    },
                    lastOrganizeResult = null,
                    lastOrganizeApprovalId = null,
                )
            }
        }
    }

    fun requestDeleteConsentForLatestOrganize() {
        val result = fileActionState.value.lastOrganizeResult
        if (result == null || result.deleteConsentRequiredCount == 0) {
            fileActionState.update {
                it.copy(lastExecutionNote = "No organize originals are waiting for Android delete consent.")
            }
            return
        }
        viewModelScope.launch {
            val prompt = appContainer.fileActionExecutor.prepareDeleteConsent(result)
            if (prompt == null) {
                fileActionState.update {
                    it.copy(
                        lastExecutionNote = "Android could not prepare a delete consent prompt for the current originals.",
                    )
                }
                return@launch
            }
            fileActionState.update {
                it.copy(
                    lastExecutionNote = "Android will request delete consent for ${prompt.requestedCount} original files.",
                )
            }
            _deleteConsentPrompts.emit(
                DeleteConsentPrompt(
                    intentSender = prompt.intentSender,
                    requestedCount = prompt.requestedCount,
                ),
            )
        }
    }

    fun resolveDeleteConsent(granted: Boolean) {
        val currentResult = fileActionState.value.lastOrganizeResult ?: return
        viewModelScope.launch {
            val updatedResult = appContainer.fileActionExecutor.resolveDeleteConsent(
                result = currentResult,
                granted = granted,
            )
            fileActionState.update {
                it.copy(
                    lastExecutionNote = if (granted) {
                        updatedResult.summary
                    } else {
                        "Delete consent was not granted. Original files remain pending removal."
                    },
                    lastOrganizeResult = updatedResult,
                )
            }
            fileActionState.value.lastOrganizeApprovalId?.let { approvalId ->
                appContainer.approvalInboxRepository.recordExecutionOutcome(
                    id = approvalId,
                    note = updatedResult.summary,
                )
            }
            appContainer.auditTrailRepository.refresh()
            if (granted) {
                refreshFilesInternal()
            }
        }
    }

    fun startPairing() {
        viewModelScope.launch {
            appContainer.devicePairingRepository.startPairing()
        }
    }

    fun approvePairing(sessionId: String) {
        viewModelScope.launch {
            appContainer.devicePairingRepository.approvePairing(sessionId)
        }
    }

    fun denyPairing(sessionId: String) {
        viewModelScope.launch {
            appContainer.devicePairingRepository.denyPairing(sessionId)
        }
    }

    fun armDirectHttpBridge(deviceId: String) {
        viewModelScope.launch {
            appContainer.devicePairingRepository.armDirectHttpBridge(deviceId)
        }
    }

    fun useLoopbackBridge(deviceId: String) {
        viewModelScope.launch {
            appContainer.devicePairingRepository.useLoopbackBridge(deviceId)
        }
    }

    fun setTransportValidationMode(
        deviceId: String,
        mode: TransportValidationMode,
    ) {
        viewModelScope.launch {
            appContainer.devicePairingRepository.setTransportValidationMode(deviceId, mode)
        }
    }

    fun useAdbReverseEndpoint(deviceId: String) {
        updateDirectHttpEndpoint(
            deviceId = deviceId,
            endpointUrl = adbReverseEndpoint,
        )
    }

    fun useEmulatorHostEndpoint(deviceId: String) {
        updateDirectHttpEndpoint(
            deviceId = deviceId,
            endpointUrl = emulatorHostEndpoint,
        )
    }

    fun refreshDeviceState() {
        viewModelScope.launch {
            appContainer.devicePairingRepository.refresh()
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun probeSelectedDeviceHealth() {
        val targetDeviceId = selectedTargetDeviceId.value
            ?: appContainer.devicePairingRepository.pairedDevices.value.firstOrNull()?.id
        if (targetDeviceId == null) {
            companionProbeState.value = CompanionProbeState(
                result = null,
            )
            return
        }
        viewModelScope.launch {
            companionProbeState.value = CompanionProbeState(isChecking = true)
            val result = appContainer.devicePairingRepository.probeCompanion(targetDeviceId)
            companionProbeState.value = CompanionProbeState(
                isChecking = false,
                result = result,
            )
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun drainTransferOutboxNow() {
        viewModelScope.launch {
            appContainer.auditTrailRepository.logAction(
                action = "files.send_to_device",
                result = "manual_drain_requested",
                details = "Requested an immediate bridge drain from the Devices tab.",
            )
            appContainer.transferBridgeCoordinator.scheduleDrain()
            appContainer.devicePairingRepository.refresh()
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun retryFailedTransfersForSelectedDevice() {
        viewModelScope.launch {
            val targetDeviceId = selectedTargetDeviceId.value
            appContainer.devicePairingRepository.retryFailedTransfers(targetDeviceId)
            appContainer.auditTrailRepository.refresh()
        }
    }

    private fun actionTargetItems() =
        fileIndexState.value.indexedItems.let { items ->
            val selectedId = fileActionState.value.selectedFileId
            if (selectedId == null) {
                items
            } else {
                items.filter { it.id == selectedId }
        }
    }

    private fun updateDirectHttpEndpoint(
        deviceId: String,
        endpointUrl: String,
    ) {
        viewModelScope.launch {
            appContainer.devicePairingRepository.setDirectHttpEndpoint(
                deviceId = deviceId,
                endpointUrl = endpointUrl,
            )
            appContainer.auditTrailRepository.refresh()
        }
    }

    private suspend fun refreshFilesInternal() {
        val refreshed = appContainer.fileIndexRepository.refreshIndex()
        fileIndexState.value = refreshed.copy(isRefreshing = false)
        val selectedFileId = fileActionState.value.selectedFileId
        if (selectedFileId != null && refreshed.indexedItems.none { it.id == selectedFileId }) {
            fileActionState.update {
                it.copy(
                    selectedFileId = null,
                    preview = null,
                )
            }
        }
    }
}
