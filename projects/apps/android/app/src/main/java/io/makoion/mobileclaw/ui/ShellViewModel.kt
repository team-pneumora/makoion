package io.makoion.mobileclaw.ui

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.ApprovalInboxItem
import io.makoion.mobileclaw.data.ApprovalActionResult
import io.makoion.mobileclaw.data.AgentDestination
import io.makoion.mobileclaw.data.AgentTaskRecord
import io.makoion.mobileclaw.data.AgentTurnContext
import io.makoion.mobileclaw.data.AgentTurnResult
import io.makoion.mobileclaw.data.AuditTrailEvent
import io.makoion.mobileclaw.data.ChatMessage
import io.makoion.mobileclaw.data.ChatMessageRole
import io.makoion.mobileclaw.data.ChatThreadRecord
import io.makoion.mobileclaw.data.CloudDriveConnectionState
import io.makoion.mobileclaw.data.CloudDriveProviderKind
import io.makoion.mobileclaw.data.CompanionAppOpenResult
import io.makoion.mobileclaw.data.CompanionHealthCheckResult
import io.makoion.mobileclaw.data.CompanionSessionNotifyResult
import io.makoion.mobileclaw.data.CompanionWorkflowRunResult
import io.makoion.mobileclaw.data.FileIndexState
import io.makoion.mobileclaw.data.FileOrganizePlan
import io.makoion.mobileclaw.data.FileOrganizeStrategy
import io.makoion.mobileclaw.data.FilePreviewDetail
import io.makoion.mobileclaw.data.FileSummaryDetail
import io.makoion.mobileclaw.data.ModelProviderCredentialStatus
import io.makoion.mobileclaw.data.ModelProviderProfileState
import io.makoion.mobileclaw.data.PairedDeviceState
import io.makoion.mobileclaw.data.PairingSessionState
import io.makoion.mobileclaw.data.PersistedOrganizeExecution
import io.makoion.mobileclaw.data.ResourceRegistryEntryState
import io.makoion.mobileclaw.data.ResourceRegistryHealthState
import io.makoion.mobileclaw.data.ScheduledAutomationRecord
import io.makoion.mobileclaw.data.ScheduledAutomationStatus
import io.makoion.mobileclaw.data.ShellRecoveryState
import io.makoion.mobileclaw.data.TaskFollowUpPresentation
import io.makoion.mobileclaw.data.TaskRetryActionResult
import io.makoion.mobileclaw.data.TransferDraftState
import io.makoion.mobileclaw.data.TransferDraftStatus
import io.makoion.mobileclaw.data.TransportValidationMode
import io.makoion.mobileclaw.data.VoiceEntryState
import io.makoion.mobileclaw.data.OrganizeExecutionResult
import io.makoion.mobileclaw.data.companionAppOpenTargetActionsFolder
import io.makoion.mobileclaw.data.companionAppOpenTargetInbox
import io.makoion.mobileclaw.data.companionAppOpenTargetLatestAction
import io.makoion.mobileclaw.data.companionAppOpenTargetLatestTransfer
import io.makoion.mobileclaw.data.resolveAgentModelPreference
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
    Chat,
    Dashboard,
    History,
    Settings,
    ;

    val label: String
        get() = when (this) {
            Chat -> "Chat"
            Dashboard -> "Dashboard"
            History -> "History"
            Settings -> "Settings"
        }

    val routeKey: String
        get() = when (this) {
            Chat -> "chat"
            Dashboard -> "dashboard"
            History -> "history"
            Settings -> "settings"
        }

    companion object {
        fun fromRouteKey(routeKey: String?): ShellSection {
            return when (routeKey?.lowercase()) {
                Chat.routeKey,
                "overview",
                null -> Chat
                Dashboard.routeKey,
                "approvals" -> Dashboard
                History.routeKey -> History
                Settings.routeKey,
                "files",
                "devices" -> Settings
                else -> Chat
            }
        }
    }
}

data class ShellCard(
    val title: String,
    val description: String,
    val status: String,
)

data class ChatState(
    val draft: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
)

enum class ResourceConnectionStatus {
    Active,
    NeedsSetup,
    Planned,
}

data class ResourceConnectionSummary(
    val id: String,
    val title: String,
    val priorityLabel: String,
    val status: ResourceConnectionStatus,
    val summary: String,
)

data class ShellUiState(
    val selectedSection: ShellSection = ShellSection.Chat,
    val phaseTitle: String = "Phone-hosted agent shell",
    val summary: String = "Makoion is converging on a chat-first product shell. Connected files, drives, companions, MCP endpoints, and APIs will sit behind the agent instead of leading the UI.",
    val overviewCards: List<ShellCard> = defaultOverviewCards,
    val resourceConnections: List<ResourceConnectionSummary> = emptyList(),
    val cloudDriveConnections: List<CloudDriveConnectionState> = emptyList(),
    val providerProfiles: List<ModelProviderProfileState> = emptyList(),
    val chatState: ChatState = ChatState(),
    val activeChatThread: ChatThreadRecord? = null,
    val chatThreads: List<ChatThreadRecord> = emptyList(),
    val agentTasks: List<AgentTaskRecord> = emptyList(),
    val scheduledAutomations: List<ScheduledAutomationRecord> = emptyList(),
    val fileIndexState: FileIndexState = FileIndexState(),
    val approvals: List<ApprovalInboxItem> = emptyList(),
    val auditEvents: List<AuditTrailEvent> = emptyList(),
    val latestNotificationQuickAction: AuditTrailEvent? = null,
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
    val lastOrganizeUpdatedAtLabel: String? = null,
    val lastOrganizeRecovered: Boolean = false,
    val forceDeleteConsentForTesting: Boolean = false,
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

data class CompanionNotifyState(
    val isSending: Boolean = false,
    val result: CompanionSessionNotifyResult? = null,
)

data class CompanionAppOpenState(
    val isSending: Boolean = false,
    val pendingTargetKind: String? = null,
    val result: CompanionAppOpenResult? = null,
)

data class CompanionWorkflowRunState(
    val isSending: Boolean = false,
    val result: CompanionWorkflowRunResult? = null,
)

data class DeviceControlState(
    val pairedDevices: List<PairedDeviceState> = emptyList(),
    val pairingSessions: List<PairingSessionState> = emptyList(),
    val transferDrafts: List<TransferDraftState> = emptyList(),
    val selectedTargetDeviceId: String? = null,
    val isTargetDevicePinnedByUser: Boolean = false,
    val transportDiagnostics: TransportDiagnostics = TransportDiagnostics(),
    val transportAuditEvents: List<AuditTrailEvent> = emptyList(),
    val recoveryState: ShellRecoveryState = ShellRecoveryState(),
    val companionProbe: CompanionProbeState = CompanionProbeState(),
    val companionNotify: CompanionNotifyState = CompanionNotifyState(),
    val companionAppOpen: CompanionAppOpenState = CompanionAppOpenState(),
    val companionWorkflowRun: CompanionWorkflowRunState = CompanionWorkflowRunState(),
)

private val defaultOverviewCards = listOf(
    ShellCard(
        title = "Agent Server",
        description = "The phone owns sessions, tasks, approvals, recovery, and connected resources.",
        status = "Active",
    ),
    ShellCard(
        title = "Chat-first Shell",
        description = "The product UI is moving toward a single conversational surface with lightweight supporting tabs.",
        status = "In progress",
    ),
    ShellCard(
        title = "Connected Resources",
        description = "Files, companions, notifications, and voice capture are already wired as the first agent capabilities.",
        status = "Wiring",
    ),
)

private const val adbReverseEndpoint = "http://127.0.0.1:8787/api/v1/transfers"
private const val emulatorHostEndpoint = "http://10.0.2.2:8787/api/v1/transfers"
private const val desktopWorkflowIdOpenLatestAction = "open_latest_action"
private const val desktopWorkflowLabelOpenLatestAction = "Open latest action"
private const val desktopWorkflowIdOpenLatestTransfer = "open_latest_transfer"
private const val desktopWorkflowLabelOpenLatestTransfer = "Open latest transfer"
private const val desktopWorkflowIdOpenActionsFolder = "open_actions_folder"
private const val desktopWorkflowLabelOpenActionsFolder = "Open actions folder"
private const val desktopAppOpenLabelInbox = "Desktop companion inbox"
private const val desktopAppOpenLabelLatestAction = "Latest action folder"
private const val desktopAppOpenLabelLatestTransfer = "Latest transfer folder"
private const val desktopAppOpenLabelActionsFolder = "Actions folder"

private data class ShellSnapshot(
    val section: ShellSection,
    val chat: ChatState,
    val files: FileIndexState,
    val actions: FileActionState,
    val selectedTargetDeviceId: String?,
    val isTargetDevicePinnedByUser: Boolean,
    val companionProbe: CompanionProbeState,
    val companionNotify: CompanionNotifyState = CompanionNotifyState(),
    val companionAppOpen: CompanionAppOpenState = CompanionAppOpenState(),
    val companionWorkflowRun: CompanionWorkflowRunState = CompanionWorkflowRunState(),
)

private data class DeviceSnapshot(
    val pairedDevices: List<PairedDeviceState>,
    val pairingSessions: List<PairingSessionState>,
    val transferDrafts: List<TransferDraftState>,
)

private data class ChatThreadSnapshot(
    val activeThread: ChatThreadRecord?,
    val threads: List<ChatThreadRecord>,
)

private data class ShellSupportSnapshot(
    val chatThreads: ChatThreadSnapshot,
    val approvals: List<ApprovalInboxItem>,
    val voice: VoiceEntryState,
    val auditInputs: Triple<List<AuditTrailEvent>, ShellRecoveryState, List<AgentTaskRecord>>,
    val cloudDriveConnections: List<CloudDriveConnectionState>,
    val providerProfiles: List<ModelProviderProfileState>,
    val resourceRegistryEntries: List<ResourceRegistryEntryState>,
    val scheduledAutomations: List<ScheduledAutomationRecord>,
)

private data class SettingsSnapshot(
    val cloudDriveConnections: List<CloudDriveConnectionState>,
    val providerProfiles: List<ModelProviderProfileState>,
    val resourceRegistryEntries: List<ResourceRegistryEntryState>,
    val scheduledAutomations: List<ScheduledAutomationRecord>,
)

private data class ResourceRegistrySyncInput(
    val fileIndexState: FileIndexState,
    val pairedDevices: List<PairedDeviceState>,
    val providerProfiles: List<ModelProviderProfileState>,
    val cloudDriveConnections: List<CloudDriveConnectionState>,
)

class ShellViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContainer = (application as MobileClawApplication).appContainer
    private val selectedSection = MutableStateFlow(ShellSection.Chat)
    private val chatState = MutableStateFlow(ChatState())
    private val chatFollowUpPreferences = application.getSharedPreferences(
        chatFollowUpPreferencesName,
        Context.MODE_PRIVATE,
    )
    private val seenTaskFollowUpKeys = linkedSetOf<String>().apply {
        addAll(chatFollowUpPreferences.getStringSet(chatFollowUpKeyName, emptySet()) ?: emptySet())
    }
    private val seenNotificationActionEventIds = linkedSetOf<String>().apply {
        addAll(
            chatFollowUpPreferences.getStringSet(
                chatNotificationActionKeyName,
                emptySet(),
            ) ?: emptySet(),
        )
    }
    private var taskFollowUpBootstrapComplete = false
    private var notificationActionBootstrapComplete = false
    private var requestedTaskFollowUpId: String? = null
    private val fileActionState = MutableStateFlow(FileActionState())
    private val selectedTargetDeviceId = MutableStateFlow<String?>(null)
    private val targetDevicePinnedByUser = MutableStateFlow(false)
    private val companionProbeState = MutableStateFlow(CompanionProbeState())
    private val companionNotifyState = MutableStateFlow(CompanionNotifyState())
    private val companionAppOpenState = MutableStateFlow(CompanionAppOpenState())
    private val companionWorkflowRunState = MutableStateFlow(CompanionWorkflowRunState())
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
            combine(
                combine(
                    selectedSection,
                    chatState,
                    fileIndexState,
                    fileActionState,
                ) { section, chat, files, actions ->
                    arrayOf(section, chat, files, actions)
                },
                combine(selectedTargetDeviceId, targetDevicePinnedByUser) { targetDeviceId, pinnedByUser ->
                    targetDeviceId to pinnedByUser
                },
                companionProbeState,
            ) { shellInputs, targetSelection, companionProbe ->
                val section = shellInputs[0] as ShellSection
                val chat = shellInputs[1] as ChatState
                val files = shellInputs[2] as FileIndexState
                val actions = shellInputs[3] as FileActionState
                val targetDeviceId = targetSelection.first
                val pinnedByUser = targetSelection.second
                ShellSnapshot(
                    section = section,
                    chat = chat,
                    files = files,
                    actions = actions,
                    selectedTargetDeviceId = targetDeviceId,
                    isTargetDevicePinnedByUser = pinnedByUser,
                    companionProbe = companionProbe,
                )
            },
            companionNotifyState,
            companionAppOpenState,
            companionWorkflowRunState,
        ) { shellSnapshot, companionNotify, companionAppOpen, companionWorkflowRun ->
            ShellSnapshot(
                section = shellSnapshot.section,
                chat = shellSnapshot.chat,
                files = shellSnapshot.files,
                actions = shellSnapshot.actions,
                selectedTargetDeviceId = shellSnapshot.selectedTargetDeviceId,
                isTargetDevicePinnedByUser = shellSnapshot.isTargetDevicePinnedByUser,
                companionProbe = shellSnapshot.companionProbe,
                companionNotify = companionNotify,
                companionAppOpen = companionAppOpen,
                companionWorkflowRun = companionWorkflowRun,
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
        combine(
            combine(
                appContainer.chatTranscriptRepository.activeThread,
                appContainer.chatTranscriptRepository.threads,
            ) { activeThread, threads ->
                ChatThreadSnapshot(
                    activeThread = activeThread,
                    threads = threads,
                )
            },
            appContainer.approvalInboxRepository.items,
            appContainer.voiceEntryCoordinator.state,
            combine(
                appContainer.auditTrailRepository.events,
                appContainer.shellRecoveryCoordinator.state,
                appContainer.agentTaskRepository.tasks,
            ) { auditEvents, recoveryState, agentTasks ->
                Triple(auditEvents, recoveryState, agentTasks)
            },
            combine(
                appContainer.cloudDriveConnectionRepository.connections,
                appContainer.modelProviderSettingsRepository.profiles,
                appContainer.resourceRegistryRepository.entries,
                appContainer.scheduledAutomationRepository.automations,
            ) { cloudDriveConnections, providerProfiles, resourceRegistryEntries, scheduledAutomations ->
                SettingsSnapshot(
                    cloudDriveConnections = cloudDriveConnections,
                    providerProfiles = providerProfiles,
                    resourceRegistryEntries = resourceRegistryEntries,
                    scheduledAutomations = scheduledAutomations,
                )
            },
        ) { chatThreads, approvals, voice, auditInputs, settingsInputs ->
            ShellSupportSnapshot(
                chatThreads = chatThreads,
                approvals = approvals,
                voice = voice,
                auditInputs = auditInputs,
                cloudDriveConnections = settingsInputs.cloudDriveConnections,
                providerProfiles = settingsInputs.providerProfiles,
                resourceRegistryEntries = settingsInputs.resourceRegistryEntries,
                scheduledAutomations = settingsInputs.scheduledAutomations,
            )
        },
    ) { shellInputs, deviceInputs, supportInputs ->
        val (auditEvents, recoveryState, agentTasks) = supportInputs.auditInputs
        val recommendedDeviceId = deviceInputs.pairedDevices
            .firstOrNull { it.transportMode == io.makoion.mobileclaw.data.DeviceTransportMode.DirectHttp }
            ?.id
            ?: deviceInputs.pairedDevices.firstOrNull()?.id
        val selectedDeviceId = shellInputs.selectedTargetDeviceId
            ?.takeIf { selectedId -> deviceInputs.pairedDevices.any { it.id == selectedId } }
            ?: recommendedDeviceId
        val selectedDevicePinnedByUser =
            shellInputs.isTargetDevicePinnedByUser &&
                selectedDeviceId != null &&
                selectedDeviceId == shellInputs.selectedTargetDeviceId
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
                event.headline == "devices.health_probe" ||
                event.headline == "devices.session_notify" ||
                event.headline == "devices.app_open" ||
                event.headline == "devices.workflow_run" ||
                event.headline == "shell.recovery"
        }.take(6)
        val latestNotificationQuickAction = auditEvents.firstOrNull { event ->
            event.headline == "notifications.quick_action"
        }
        ShellUiState(
            selectedSection = shellInputs.section,
            chatState = shellInputs.chat,
            resourceConnections = buildResourceConnections(supportInputs.resourceRegistryEntries),
            cloudDriveConnections = supportInputs.cloudDriveConnections,
            providerProfiles = supportInputs.providerProfiles,
            activeChatThread = supportInputs.chatThreads.activeThread,
            chatThreads = supportInputs.chatThreads.threads,
            agentTasks = agentTasks,
            scheduledAutomations = supportInputs.scheduledAutomations,
            fileIndexState = shellInputs.files,
            approvals = supportInputs.approvals,
            auditEvents = auditEvents,
            latestNotificationQuickAction = latestNotificationQuickAction,
            fileActionState = shellInputs.actions,
            deviceControlState = DeviceControlState(
                pairedDevices = deviceInputs.pairedDevices,
                pairingSessions = deviceInputs.pairingSessions,
                transferDrafts = deviceInputs.transferDrafts,
                selectedTargetDeviceId = selectedDeviceId,
                isTargetDevicePinnedByUser = selectedDevicePinnedByUser,
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
                recoveryState = recoveryState,
                companionProbe = shellInputs.companionProbe.takeIf { probe ->
                    probe.result?.deviceId == null || probe.result.deviceId == selectedDeviceId
                } ?: CompanionProbeState(),
                companionNotify = shellInputs.companionNotify.takeIf { notify ->
                    notify.result?.deviceId == null || notify.result.deviceId == selectedDeviceId
                } ?: CompanionNotifyState(),
                companionAppOpen = shellInputs.companionAppOpen.takeIf { appOpen ->
                    appOpen.result?.deviceId == null || appOpen.result.deviceId == selectedDeviceId
                } ?: CompanionAppOpenState(),
                companionWorkflowRun = shellInputs.companionWorkflowRun.takeIf { workflowRun ->
                    workflowRun.result?.deviceId == null || workflowRun.result.deviceId == selectedDeviceId
                } ?: CompanionWorkflowRunState(),
            ),
            voiceEntryState = supportInputs.voice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ShellUiState(fileIndexState = fileIndexState.value),
    )

    init {
        viewModelScope.launch {
            appContainer.chatTranscriptRepository.messages.collect { messages ->
                chatState.update { current ->
                    current.copy(messages = messages)
                }
            }
        }
        viewModelScope.launch {
            appContainer.organizeDebugSettingsRepository.state.collect { debugState ->
                fileActionState.update {
                    it.copy(
                        forceDeleteConsentForTesting = debugState.forceDeleteConsentForTesting,
                    )
                }
            }
        }
        viewModelScope.launch {
            appContainer.organizeExecutionRepository.latest.collect { persisted ->
                persisted ?: return@collect
                applyLatestOrganizeExecution(
                    persisted = persisted,
                    recovered = fileActionState.value.lastOrganizeResult == null,
                )
            }
        }
        viewModelScope.launch {
            appContainer.auditTrailRepository.events.collect { events ->
                surfaceNotificationActionMessages(events)
            }
        }
        viewModelScope.launch {
            appContainer.devicePairingRepository.pairedDevices.collect { devices ->
                reconcileTargetDeviceSelection(devices)
            }
        }
        viewModelScope.launch {
            combine(
                fileIndexState,
                appContainer.devicePairingRepository.pairedDevices,
                appContainer.modelProviderSettingsRepository.profiles,
                appContainer.cloudDriveConnectionRepository.connections,
            ) { files, devices, providerProfiles, cloudDriveConnections ->
                ResourceRegistrySyncInput(
                    fileIndexState = files,
                    pairedDevices = devices,
                    providerProfiles = providerProfiles,
                    cloudDriveConnections = cloudDriveConnections,
                )
            }.collect { snapshot ->
                appContainer.resourceRegistryRepository.syncSnapshot(
                    fileIndexState = snapshot.fileIndexState,
                    pairedDevices = snapshot.pairedDevices,
                    providerProfiles = snapshot.providerProfiles,
                    cloudDriveConnections = snapshot.cloudDriveConnections,
                )
            }
        }
        viewModelScope.launch {
            appContainer.agentTaskRepository.tasks.collect { tasks ->
                surfaceTaskFollowUps(tasks)
            }
        }
        viewModelScope.launch {
            appContainer.chatTranscriptRepository.refresh()
        }
        viewModelScope.launch {
            appContainer.organizeExecutionRepository.refresh()
        }
        refreshFiles()
    }

    fun openSection(section: ShellSection) {
        selectedSection.value = section
    }

    fun requestTaskFollowUp(taskId: String?) {
        if (taskId.isNullOrBlank()) {
            return
        }
        viewModelScope.launch {
            appContainer.agentTaskRepository.findTaskById(taskId)?.let { task ->
                appContainer.chatTranscriptRepository.activateThread(task.threadId)
            }
            requestedTaskFollowUpId = taskId
            selectedSection.value = ShellSection.Chat
            surfaceTaskFollowUps(appContainer.agentTaskRepository.tasks.value)
        }
    }

    fun startNewChatThread() {
        viewModelScope.launch {
            appContainer.chatTranscriptRepository.createThread()
            chatState.update { current ->
                current.copy(
                    draft = "",
                    isProcessing = false,
                )
            }
            selectedSection.value = ShellSection.Chat
        }
    }

    fun openChatThread(threadId: String) {
        viewModelScope.launch {
            appContainer.chatTranscriptRepository.activateThread(threadId) ?: return@launch
            chatState.update { current ->
                current.copy(
                    draft = "",
                    isProcessing = false,
                )
            }
            selectedSection.value = ShellSection.Chat
        }
    }

    fun updateChatDraft(text: String) {
        chatState.update { it.copy(draft = text) }
    }

    fun submitSuggestedChatPrompt(prompt: String) {
        submitPrompt(prompt)
    }

    fun ingestVoiceTranscript(transcript: String) {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) {
            return
        }
        chatState.update { current ->
            if (current.draft.isBlank()) {
                current.copy(draft = trimmed)
            } else if (current.draft.contains(trimmed)) {
                current
            } else {
                current.copy(draft = "${current.draft}\n$trimmed")
            }
        }
        selectedSection.value = ShellSection.Chat
    }

    fun submitChatPrompt() {
        val prompt = chatState.value.draft.trim()
        if (prompt.isBlank()) {
            return
        }
        submitPrompt(prompt)
    }

    private fun submitPrompt(prompt: String) {
        val activeThreadId = appContainer.chatTranscriptRepository.activeThread.value?.id ?: "thread-primary"
        val userMessage = ChatMessage(
            id = "user-${System.currentTimeMillis()}",
            role = ChatMessageRole.User,
            text = prompt,
        )
        chatState.update {
            it.copy(
                draft = "",
                messages = it.messages + userMessage,
                isProcessing = true,
            )
        }
        selectedSection.value = ShellSection.Chat
        viewModelScope.launch {
            appendChatMessage(userMessage, threadId = activeThreadId)
            val currentUiState = uiState.value
            val execution = runCatching {
                appContainer.agentTaskEngine.submitTurn(
                    threadId = activeThreadId,
                    prompt = prompt,
                    context = AgentTurnContext(
                        fileIndexState = currentUiState.fileIndexState,
                        approvals = currentUiState.approvals,
                        tasks = currentUiState.agentTasks,
                        auditEvents = currentUiState.auditEvents,
                        pairedDevices = currentUiState.deviceControlState.pairedDevices,
                        selectedTargetDeviceId = currentUiState.deviceControlState.selectedTargetDeviceId,
                        cloudDriveConnections = currentUiState.cloudDriveConnections,
                        modelPreference = resolveAgentModelPreference(currentUiState.providerProfiles),
                        scheduledAutomations = currentUiState.scheduledAutomations,
                        selectedFileId = currentUiState.fileActionState.selectedFileId,
                    ),
                )
            }.getOrElse { error ->
                appContainer.auditTrailRepository.logAction(
                    action = "agent.turn",
                    result = "failed",
                    details = "Prompt: ${prompt.take(160)} | ${error.message ?: error::class.java.simpleName}",
                )
                appendChatMessage(
                    ChatMessage(
                        id = "assistant-${System.currentTimeMillis()}",
                        role = ChatMessageRole.Assistant,
                        text = "Agent turn failed: ${error.message ?: error::class.java.simpleName}",
                    ),
                    threadId = activeThreadId,
                )
                chatState.update { it.copy(isProcessing = false) }
                appContainer.auditTrailRepository.refresh()
                return@launch
            }
            markTaskFollowUpSeen(execution.task)
            applyAgentTurnResult(
                result = execution.turnResult,
                executionTask = execution.task,
            )
        }
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
            when (val result = appContainer.phoneAgentActionCoordinator.approveApproval(id)) {
                is ApprovalActionResult.Completed -> {
                    result.execution.refreshedFileIndexState?.let { refreshedState ->
                        fileIndexState.value = refreshedState.copy(isRefreshing = false)
                    }
                    result.execution.organizeExecution?.let { persisted ->
                        applyLatestOrganizeExecution(persisted, recovered = false)
                    } ?: result.execution.linkedTask?.summary?.let { summary ->
                        fileActionState.update { current ->
                            current.copy(lastExecutionNote = summary)
                        }
                    }
                }
                is ApprovalActionResult.AlreadyResolved,
                is ApprovalActionResult.Missing -> Unit
            }
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun deny(id: String) {
        viewModelScope.launch {
            when (val result = appContainer.phoneAgentActionCoordinator.denyApproval(id)) {
                is ApprovalActionResult.Completed ->
                    result.execution.linkedTask?.summary?.let { summary ->
                        fileActionState.update { current ->
                            current.copy(lastExecutionNote = summary)
                        }
                    }
                is ApprovalActionResult.AlreadyResolved,
                is ApprovalActionResult.Missing -> Unit
            }
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun retryAgentTask(id: String) {
        viewModelScope.launch {
            when (val result = appContainer.phoneAgentActionCoordinator.retryTask(id)) {
                is TaskRetryActionResult.Completed -> {
                    result.execution.organizeExecution?.let { persisted ->
                        applyLatestOrganizeExecution(persisted, recovered = false)
                    }
                    fileActionState.update { current ->
                        current.copy(lastExecutionNote = result.execution.task.summary)
                    }
                }
                is TaskRetryActionResult.NotEligible ->
                    fileActionState.update { current ->
                        current.copy(lastExecutionNote = result.task.summary)
                    }
                is TaskRetryActionResult.Missing -> Unit
            }
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

    fun stageCloudDriveConnection(provider: CloudDriveProviderKind) {
        viewModelScope.launch {
            appContainer.cloudDriveConnectionRepository.stageConnection(provider)
        }
    }

    fun markMockCloudDriveConnected(provider: CloudDriveProviderKind) {
        viewModelScope.launch {
            appContainer.cloudDriveConnectionRepository.markConnected(
                provider = provider,
                accountLabel = "${provider.displayName} placeholder account",
            )
        }
    }

    fun resetCloudDriveConnection(provider: CloudDriveProviderKind) {
        viewModelScope.launch {
            appContainer.cloudDriveConnectionRepository.resetConnection(provider)
        }
    }

    fun setModelProviderEnabled(
        providerId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            appContainer.modelProviderSettingsRepository.setProviderEnabled(
                providerId = providerId,
                enabled = enabled,
            )
        }
    }

    fun setDefaultModelProvider(providerId: String) {
        viewModelScope.launch {
            appContainer.modelProviderSettingsRepository.setDefaultProvider(providerId)
        }
    }

    fun selectProviderModel(
        providerId: String,
        model: String,
    ) {
        viewModelScope.launch {
            appContainer.modelProviderSettingsRepository.selectModel(
                providerId = providerId,
                model = model,
            )
        }
    }

    fun storeModelProviderCredential(
        providerId: String,
        secret: String,
    ) {
        viewModelScope.launch {
            appContainer.modelProviderSettingsRepository.storeCredential(
                providerId = providerId,
                secret = secret,
            )
        }
    }

    fun clearModelProviderCredential(providerId: String) {
        viewModelScope.launch {
            appContainer.modelProviderSettingsRepository.clearCredential(providerId)
        }
    }

    fun activateScheduledAutomation(automationId: String) {
        viewModelScope.launch {
            appContainer.scheduledAutomationRepository.setStatus(
                automationId = automationId,
                status = ScheduledAutomationStatus.Active,
            )
        }
    }

    fun pauseScheduledAutomation(automationId: String) {
        viewModelScope.launch {
            appContainer.scheduledAutomationRepository.setStatus(
                automationId = automationId,
                status = ScheduledAutomationStatus.Paused,
            )
        }
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
                forceDeleteConsentForTesting = fileActionState.value.forceDeleteConsentForTesting,
            )
            fileActionState.update {
                it.copy(
                    isLoading = false,
                    lastExecutionNote = if (request == null) {
                        "No organize steps were available to submit for approval."
                    } else {
                        "Approval request submitted. Review it in Dashboard before any files move."
                    },
                )
            }
            if (request != null) {
                selectedSection.value = ShellSection.Dashboard
            }
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun setForceDeleteConsentForTesting(enabled: Boolean) {
        viewModelScope.launch {
            appContainer.organizeDebugSettingsRepository.setForceDeleteConsentForTesting(enabled)
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
                )
            }
        }
    }

    fun selectTargetDevice(deviceId: String) {
        val currentDeviceId = selectedTargetDeviceId.value
        if (currentDeviceId == deviceId && targetDevicePinnedByUser.value) {
            targetDevicePinnedByUser.value = false
            reconcileTargetDeviceSelection(appContainer.devicePairingRepository.pairedDevices.value)
            return
        }
        applyTargetDeviceSelection(
            deviceId = deviceId,
            pinnedByUser = true,
        )
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
            val prompt = runCatching {
                appContainer.fileActionExecutor.prepareDeleteConsent(result)
            }.getOrElse { error ->
                fileActionState.update {
                    it.copy(
                        lastExecutionNote = "Android delete consent request failed: ${error.message ?: error::class.java.simpleName}",
                    )
                }
                return@launch
            }
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
            val approvalId = fileActionState.value.lastOrganizeApprovalId
            if (approvalId != null) {
                val persisted = appContainer.organizeExecutionRepository.save(approvalId, updatedResult)
                applyLatestOrganizeExecution(persisted, recovered = false)
                appContainer.agentTaskEngine.recordOrganizeExecution(
                    approvalRequestId = approvalId,
                    result = updatedResult,
                )
            } else {
                fileActionState.update {
                    it.copy(
                        lastExecutionNote = if (granted) {
                            updatedResult.summaryWithStatusNote
                        } else {
                            "Delete consent was not granted. Original files remain pending removal."
                        },
                        lastOrganizeResult = updatedResult,
                        lastOrganizeRecovered = false,
                    )
                }
            }
            approvalId?.let {
                appContainer.approvalInboxRepository.recordExecutionOutcome(
                    id = it,
                    note = updatedResult.summaryWithStatusNote,
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
        appContainer.shellRecoveryCoordinator.requestManualRecovery()
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

    fun sendSessionNotificationToSelectedDevice() {
        val targetDeviceId = selectedTargetDeviceId.value
            ?: appContainer.devicePairingRepository.pairedDevices.value.firstOrNull()?.id
        if (targetDeviceId == null) {
            companionNotifyState.value = CompanionNotifyState()
            return
        }
        viewModelScope.launch {
            companionNotifyState.value = CompanionNotifyState(isSending = true)
            val result = appContainer.devicePairingRepository.sendSessionNotification(
                deviceId = targetDeviceId,
                title = "Makoion session ping",
                body = "Android shell delivered a phase 2 session.notify probe at ${System.currentTimeMillis()}.",
            )
            companionNotifyState.value = CompanionNotifyState(
                isSending = false,
                result = result,
            )
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun openCompanionInboxOnSelectedDevice() {
        sendAppOpenToSelectedDevice(
            targetKind = companionAppOpenTargetInbox,
            targetLabel = desktopAppOpenLabelInbox,
        )
    }

    fun openLatestTransferFolderOnSelectedDevice() {
        sendAppOpenToSelectedDevice(
            targetKind = companionAppOpenTargetLatestTransfer,
            targetLabel = desktopAppOpenLabelLatestTransfer,
        )
    }

    fun openLatestActionFolderOnSelectedDevice() {
        sendAppOpenToSelectedDevice(
            targetKind = companionAppOpenTargetLatestAction,
            targetLabel = desktopAppOpenLabelLatestAction,
        )
    }

    fun openActionsFolderOnSelectedDevice() {
        sendAppOpenToSelectedDevice(
            targetKind = companionAppOpenTargetActionsFolder,
            targetLabel = desktopAppOpenLabelActionsFolder,
        )
    }

    private fun sendAppOpenToSelectedDevice(
        targetKind: String,
        targetLabel: String,
    ) {
        val targetDeviceId = selectedTargetDeviceId.value
            ?: appContainer.devicePairingRepository.pairedDevices.value.firstOrNull()?.id
        if (targetDeviceId == null) {
            companionAppOpenState.value = CompanionAppOpenState()
            return
        }
        viewModelScope.launch {
            companionAppOpenState.value = CompanionAppOpenState(
                isSending = true,
                pendingTargetKind = targetKind,
            )
            val result = appContainer.devicePairingRepository.sendAppOpen(
                deviceId = targetDeviceId,
                targetKind = targetKind,
                targetLabel = targetLabel,
            )
            companionAppOpenState.value = CompanionAppOpenState(
                isSending = false,
                pendingTargetKind = null,
                result = result,
            )
            appContainer.auditTrailRepository.refresh()
        }
    }

    fun runOpenLatestTransferWorkflowOnSelectedDevice() {
        runWorkflowOnSelectedDevice(
            workflowId = desktopWorkflowIdOpenLatestTransfer,
            workflowLabel = desktopWorkflowLabelOpenLatestTransfer,
        )
    }

    fun runOpenLatestActionWorkflowOnSelectedDevice() {
        runWorkflowOnSelectedDevice(
            workflowId = desktopWorkflowIdOpenLatestAction,
            workflowLabel = desktopWorkflowLabelOpenLatestAction,
        )
    }

    fun runOpenActionsFolderWorkflowOnSelectedDevice() {
        runWorkflowOnSelectedDevice(
            workflowId = desktopWorkflowIdOpenActionsFolder,
            workflowLabel = desktopWorkflowLabelOpenActionsFolder,
        )
    }

    private fun runWorkflowOnSelectedDevice(
        workflowId: String,
        workflowLabel: String,
    ) {
        val targetDeviceId = selectedTargetDeviceId.value
            ?: appContainer.devicePairingRepository.pairedDevices.value.firstOrNull()?.id
        if (targetDeviceId == null) {
            companionWorkflowRunState.value = CompanionWorkflowRunState()
            return
        }
        viewModelScope.launch {
            companionWorkflowRunState.value = CompanionWorkflowRunState(isSending = true)
            val result = appContainer.devicePairingRepository.sendWorkflowRun(
                deviceId = targetDeviceId,
                workflowId = workflowId,
                workflowLabel = workflowLabel,
            )
            companionWorkflowRunState.value = CompanionWorkflowRunState(
                isSending = false,
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
                details = "Requested an immediate bridge drain from Settings.",
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

    private fun reconcileTargetDeviceSelection(devices: List<PairedDeviceState>) {
        val recommendedDeviceId = recommendTargetDeviceId(devices)
        val currentDeviceId = selectedTargetDeviceId.value
        when {
            recommendedDeviceId == null -> applyTargetDeviceSelection(
                deviceId = null,
                pinnedByUser = false,
            )
            currentDeviceId == null -> applyTargetDeviceSelection(
                deviceId = recommendedDeviceId,
                pinnedByUser = false,
            )
            devices.none { it.id == currentDeviceId } -> applyTargetDeviceSelection(
                deviceId = recommendedDeviceId,
                pinnedByUser = false,
            )
            !targetDevicePinnedByUser.value && currentDeviceId != recommendedDeviceId -> applyTargetDeviceSelection(
                deviceId = recommendedDeviceId,
                pinnedByUser = false,
            )
        }
    }

    private fun recommendTargetDeviceId(devices: List<PairedDeviceState>): String? {
        return devices.firstOrNull { it.transportMode == io.makoion.mobileclaw.data.DeviceTransportMode.DirectHttp }?.id
            ?: devices.firstOrNull()?.id
    }

    private fun applyTargetDeviceSelection(
        deviceId: String?,
        pinnedByUser: Boolean,
    ) {
        val previousDeviceId = selectedTargetDeviceId.value
        val previousPinnedByUser = targetDevicePinnedByUser.value
        targetDevicePinnedByUser.value = pinnedByUser
        if (previousDeviceId == deviceId && previousPinnedByUser == pinnedByUser) {
            return
        }
        selectedTargetDeviceId.value = deviceId
        if (previousDeviceId != deviceId) {
            resetCompanionActionStateForSelectedDevice(deviceId)
        }
    }

    private fun resetCompanionActionStateForSelectedDevice(deviceId: String?) {
        val currentResult = companionProbeState.value.result
        if (currentResult?.deviceId != deviceId) {
            companionProbeState.value = CompanionProbeState()
        }
        val currentNotify = companionNotifyState.value.result
        if (currentNotify?.deviceId != deviceId) {
            companionNotifyState.value = CompanionNotifyState()
        }
        val currentAppOpen = companionAppOpenState.value.result
        if (currentAppOpen?.deviceId != deviceId) {
            companionAppOpenState.value = CompanionAppOpenState()
        }
        val currentWorkflowRun = companionWorkflowRunState.value.result
        if (currentWorkflowRun?.deviceId != deviceId) {
            companionWorkflowRunState.value = CompanionWorkflowRunState()
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

    private suspend fun appendChatMessage(
        message: ChatMessage,
        threadId: String? = null,
    ) {
        appContainer.chatTranscriptRepository.appendMessage(message, threadId)
    }

    private suspend fun applyAgentTurnResult(
        result: AgentTurnResult,
        executionTask: AgentTaskRecord? = null,
    ) {
        result.refreshedFileIndexState?.let { refreshedState ->
            fileIndexState.value = refreshedState.copy(isRefreshing = false)
        }
        result.trackedTask?.let { trackedTask ->
            markTaskFollowUpSeen(trackedTask)
        }
        result.persistedOrganizeExecution?.let { persisted ->
            applyLatestOrganizeExecution(persisted, recovered = false)
        }
        if (
            result.fileSummary != null ||
            result.organizePlan != null ||
            result.fileActionNote != null
        ) {
            fileActionState.update { current ->
                current.copy(
                    summary = result.fileSummary ?: current.summary,
                    organizePlan = result.organizePlan ?: current.organizePlan,
                    lastExecutionNote = result.fileActionNote ?: current.lastExecutionNote,
                )
            }
        }
        result.companionHealthCheckResult?.let { healthProbeResult ->
            companionProbeState.value = CompanionProbeState(
                isChecking = false,
                result = healthProbeResult,
            )
        }
        result.companionSessionNotifyResult?.let { sessionNotifyResult ->
            companionNotifyState.value = CompanionNotifyState(
                isSending = false,
                result = sessionNotifyResult,
            )
        }
        result.companionAppOpenResult?.let { appOpenResult ->
            companionAppOpenState.value = CompanionAppOpenState(
                isSending = false,
                pendingTargetKind = null,
                result = appOpenResult,
            )
        }
        result.companionWorkflowRunResult?.let { workflowRunResult ->
            companionWorkflowRunState.value = CompanionWorkflowRunState(
                isSending = false,
                result = workflowRunResult,
            )
        }
        val linkedTask = result.trackedTask ?: executionTask
        appendChatMessage(
            ChatMessage(
                id = "assistant-${System.currentTimeMillis()}",
                role = ChatMessageRole.Assistant,
                text = result.reply,
                linkedTaskId = linkedTask?.id,
                linkedApprovalId = result.approvalRequestId ?: linkedTask?.approvalRequestId,
            ),
            threadId = linkedTask?.threadId,
        )
        chatState.update { it.copy(isProcessing = false) }
        selectedSection.value = result.destination.toShellSection()
        surfaceTaskFollowUps(appContainer.agentTaskRepository.tasks.value)
    }

    private fun AgentDestination.toShellSection(): ShellSection {
        return when (this) {
            AgentDestination.Chat -> ShellSection.Chat
            AgentDestination.Dashboard -> ShellSection.Dashboard
            AgentDestination.History -> ShellSection.History
            AgentDestination.Settings -> ShellSection.Settings
        }
    }

    private suspend fun surfaceTaskFollowUps(tasks: List<AgentTaskRecord>) {
        if (chatState.value.isProcessing) {
            return
        }
        surfaceRequestedTaskFollowUp(tasks)
        val candidates = tasks
            .filter(TaskFollowUpPresentation::shouldSurface)
            .sortedBy { it.updatedAtEpochMillis }
        if (candidates.isEmpty()) {
            if (!taskFollowUpBootstrapComplete) {
                taskFollowUpBootstrapComplete = true
            }
            return
        }

        if (!taskFollowUpBootstrapComplete) {
            val now = System.currentTimeMillis()
            candidates.forEach { task ->
                if (task.updatedAtEpochMillis >= now - bootstrapRecentTaskWindowMs &&
                    !seenTaskFollowUpKeys.contains(TaskFollowUpPresentation.followUpKey(task))
                ) {
                    appendTaskFollowUpMessage(task)
                }
                rememberTaskFollowUp(task)
            }
            persistTaskFollowUpKeys()
            taskFollowUpBootstrapComplete = true
            return
        }

        var changed = false
        candidates.forEach { task ->
            val key = TaskFollowUpPresentation.followUpKey(task)
            if (seenTaskFollowUpKeys.contains(key)) {
                return@forEach
            }
            appendTaskFollowUpMessage(task)
            rememberTaskFollowUp(task)
            changed = true
        }
        if (changed) {
            persistTaskFollowUpKeys()
        }
    }

    private suspend fun appendTaskFollowUpMessage(task: AgentTaskRecord) {
        appendChatMessage(
            ChatMessage(
                id = "assistant-followup-${task.id}-${task.updatedAtEpochMillis}",
                role = ChatMessageRole.Assistant,
                text = TaskFollowUpPresentation.chatMessage(task),
                linkedTaskId = task.id,
                linkedApprovalId = task.approvalRequestId,
            ),
            threadId = task.threadId,
        )
    }

    private fun markTaskFollowUpSeen(task: AgentTaskRecord) {
        rememberTaskFollowUp(task)
        persistTaskFollowUpKeys()
    }

    private fun rememberTaskFollowUp(task: AgentTaskRecord) {
        seenTaskFollowUpKeys.add(TaskFollowUpPresentation.followUpKey(task))
        while (seenTaskFollowUpKeys.size > maxSeenTaskFollowUps) {
            val oldest = seenTaskFollowUpKeys.firstOrNull() ?: break
            seenTaskFollowUpKeys.remove(oldest)
        }
    }

    private fun persistTaskFollowUpKeys() {
        chatFollowUpPreferences.edit()
            .putStringSet(chatFollowUpKeyName, seenTaskFollowUpKeys.toSet())
            .apply()
    }

    private suspend fun surfaceNotificationActionMessages(events: List<AuditTrailEvent>) {
        val candidates = events
            .filter { it.headline == notificationTaskFollowUpActionHeadline }
            .sortedBy { it.createdAtEpochMillis }
        if (candidates.isEmpty()) {
            if (!notificationActionBootstrapComplete) {
                notificationActionBootstrapComplete = true
            }
            return
        }

        if (!notificationActionBootstrapComplete) {
            val now = System.currentTimeMillis()
            candidates.forEach { event ->
                if (event.createdAtEpochMillis >= now - bootstrapRecentAuditWindowMs &&
                    !seenNotificationActionEventIds.contains(event.id)
                ) {
                    appendNotificationActionMessage(event)
                }
                rememberNotificationActionEvent(event)
            }
            persistNotificationActionEventIds()
            notificationActionBootstrapComplete = true
            return
        }

        var changed = false
        candidates.forEach { event ->
            if (seenNotificationActionEventIds.contains(event.id)) {
                return@forEach
            }
            appendNotificationActionMessage(event)
            rememberNotificationActionEvent(event)
            changed = true
        }
        if (changed) {
            persistNotificationActionEventIds()
        }
    }

    private suspend fun appendNotificationActionMessage(event: AuditTrailEvent) {
        appendChatMessage(
            ChatMessage(
                id = "assistant-notification-action-${event.id}",
                role = ChatMessageRole.Assistant,
                text = notificationActionChatMessage(event),
                linkedTaskId = relatedTaskIdFor(event),
                linkedApprovalId = relatedApprovalIdFor(event),
            ),
            threadId = relatedThreadIdFor(event),
        )
    }

    private fun notificationActionChatMessage(event: AuditTrailEvent): String {
        return when (event.result.lowercase()) {
            "approved" -> "알림에서 승인 요청을 승인했습니다. 연결된 작업이 계속 진행됩니다."
            "denied" -> "알림에서 승인 요청을 거절했습니다. 연결된 작업을 취소 상태로 반영했습니다."
            "retried" -> "알림에서 작업 재시도를 시작했습니다. 연결된 실행을 다시 이어갑니다."
            "already_resolved" -> "알림 action을 처리하려 했지만 요청이 이미 처리된 상태였습니다."
            "not_eligible" -> "알림에서 재시도를 요청했지만 지금은 다시 시작할 수 없는 상태입니다."
            "missing" -> "알림 action을 처리하려 했지만 연결된 요청을 찾지 못했습니다."
            else -> "알림에서 작업 action을 처리했습니다."
        }
    }

    private fun relatedTaskIdFor(event: AuditTrailEvent): String? {
        val requestId = event.requestId
        return when {
            requestId?.startsWith(taskIdPrefix) == true -> requestId
            requestId?.startsWith(approvalIdPrefix) == true -> null
            else -> taskIdPattern.find(event.details)?.value
        }
    }

    private fun relatedApprovalIdFor(event: AuditTrailEvent): String? {
        val requestId = event.requestId
        return when {
            requestId?.startsWith(approvalIdPrefix) == true -> requestId
            else -> approvalIdPattern.find(event.details)?.value
        }
    }

    private fun relatedThreadIdFor(event: AuditTrailEvent): String? {
        val taskId = relatedTaskIdFor(event)
        if (taskId != null) {
            return appContainer.agentTaskRepository.tasks.value.firstOrNull { it.id == taskId }?.threadId
        }
        val approvalId = relatedApprovalIdFor(event)
        if (approvalId != null) {
            return appContainer.agentTaskRepository.tasks.value.firstOrNull {
                it.approvalRequestId == approvalId
            }?.threadId
        }
        return null
    }

    private fun rememberNotificationActionEvent(event: AuditTrailEvent) {
        seenNotificationActionEventIds.add(event.id)
        while (seenNotificationActionEventIds.size > maxSeenNotificationActionEvents) {
            val oldest = seenNotificationActionEventIds.firstOrNull() ?: break
            seenNotificationActionEventIds.remove(oldest)
        }
    }

    private fun persistNotificationActionEventIds() {
        chatFollowUpPreferences.edit()
            .putStringSet(chatNotificationActionKeyName, seenNotificationActionEventIds.toSet())
            .apply()
    }

    private suspend fun surfaceRequestedTaskFollowUp(tasks: List<AgentTaskRecord>) {
        val requestedTaskId = requestedTaskFollowUpId ?: return
        val task = tasks.firstOrNull { it.id == requestedTaskId } ?: return
        requestedTaskFollowUpId = null
        if (!TaskFollowUpPresentation.shouldSurface(task)) {
            return
        }
        val key = TaskFollowUpPresentation.followUpKey(task)
        if (!seenTaskFollowUpKeys.contains(key)) {
            appendTaskFollowUpMessage(task)
            rememberTaskFollowUp(task)
            persistTaskFollowUpKeys()
        }
    }

    private fun applyLatestOrganizeExecution(
        persisted: PersistedOrganizeExecution,
        recovered: Boolean,
    ) {
        fileActionState.update {
            if (recovered && it.lastOrganizeResult != null) {
                return@update it
            }
            it.copy(
                lastExecutionNote = persisted.result.summaryWithStatusNote,
                lastOrganizeResult = persisted.result,
                lastOrganizeApprovalId = persisted.approvalId,
                lastOrganizeUpdatedAtLabel = persisted.updatedAtLabel,
                lastOrganizeRecovered = recovered,
            )
        }
    }

    private fun buildResourceConnections(
        entries: List<ResourceRegistryEntryState>,
    ): List<ResourceConnectionSummary> {
        return entries.map { entry ->
            ResourceConnectionSummary(
                id = entry.id,
                title = entry.title,
                priorityLabel = entry.priority.label,
                status = when (entry.health) {
                    ResourceRegistryHealthState.Active -> ResourceConnectionStatus.Active
                    ResourceRegistryHealthState.NeedsSetup,
                    ResourceRegistryHealthState.Degraded -> ResourceConnectionStatus.NeedsSetup
                    ResourceRegistryHealthState.Planned -> ResourceConnectionStatus.Planned
                },
                summary = entry.summary,
            )
        }
    }

    companion object {
        private const val chatFollowUpPreferencesName = "makoion_chat_follow_up"
        private const val chatFollowUpKeyName = "seen_task_follow_up_keys"
        private const val chatNotificationActionKeyName = "seen_notification_action_event_ids"
        private const val notificationTaskFollowUpActionHeadline = "notifications.task_follow_up_action"
        private const val maxSeenTaskFollowUps = 200
        private const val maxSeenNotificationActionEvents = 120
        private const val bootstrapRecentTaskWindowMs = 15 * 60 * 1000L
        private const val bootstrapRecentAuditWindowMs = 15 * 60 * 1000L
        private const val taskIdPrefix = "task-"
        private const val approvalIdPrefix = "approval-"
        private val taskIdPattern = Regex("""task-[A-Za-z0-9-]+""")
        private val approvalIdPattern = Regex("""approval-[A-Za-z0-9-]+""")
    }
}
