package io.makoion.mobileclaw.data

import android.text.format.DateUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ShellRecoveryStatus {
    Idle,
    Running,
    Success,
    Failed,
}

data class ShellRecoveryState(
    val status: ShellRecoveryStatus = ShellRecoveryStatus.Idle,
    val triggerLabel: String? = null,
    val updatedAtLabel: String? = null,
    val summary: String = "Foreground recovery has not run yet.",
    val detail: String = "The shell refreshes chat transcripts, approvals, tasks, scheduled automations, devices, organize executions, audit events, and transfer recovery whenever the app comes back to the foreground.",
)

class ShellRecoveryCoordinator(
    private val approvalInboxRepository: ApprovalInboxRepository,
    private val agentTaskRepository: AgentTaskRepository,
    private val agentTaskRetryCoordinator: AgentTaskRetryCoordinator,
    private val auditTrailRepository: AuditTrailRepository,
    private val chatTranscriptRepository: ChatTranscriptRepository,
    private val devicePairingRepository: DevicePairingRepository,
    private val organizeExecutionRepository: OrganizeExecutionRepository,
    private val scheduledAutomationRepository: ScheduledAutomationRepository,
    private val scheduledAutomationCoordinator: ScheduledAutomationCoordinator,
    private val transferBridgeCoordinator: TransferBridgeCoordinator,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ShellRecoveryState())
    private var recoveryJob: Job? = null
    private var lastRecoveryAtEpochMillis: Long = 0L
    private var started = false

    val state: StateFlow<ShellRecoveryState> = _state.asStateFlow()

    fun start() {
        if (started) {
            return
        }
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        requestRecovery(trigger = RecoveryTrigger.AppLaunch, force = true)
    }

    fun requestManualRecovery() {
        requestRecovery(trigger = RecoveryTrigger.ManualRefresh, force = true)
    }

    override fun onStart(owner: LifecycleOwner) {
        requestRecovery(trigger = RecoveryTrigger.ForegroundResume)
    }

    private fun requestRecovery(
        trigger: RecoveryTrigger,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRecoveryAtEpochMillis < minRecoveryIntervalMs) {
            return
        }
        lastRecoveryAtEpochMillis = now
        _state.value = ShellRecoveryState(
            status = ShellRecoveryStatus.Running,
            triggerLabel = trigger.label,
            updatedAtLabel = relativeTimeLabel(now),
            summary = "${trigger.label} recovery is refreshing shell state.",
            detail = "Refreshing chat transcripts, approvals, tasks, scheduled automations, paired devices, organize executions, audit events, and transfer recovery.",
        )
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            val failures = mutableListOf<String>()
            var transferRecoverySnapshot = TransferRecoverySnapshot()
            var taskRecoverySnapshot = AgentTaskRecoverySnapshot()
            var automationSyncSnapshot = ScheduledAutomationSyncSnapshot()
            refreshStep("chat transcripts", failures) {
                chatTranscriptRepository.refresh()
            }
            refreshStep("approvals", failures) {
                approvalInboxRepository.refresh()
            }
            refreshStep("tasks", failures) {
                agentTaskRepository.refresh()
            }
            refreshStep("automations", failures) {
                automationSyncSnapshot = scheduledAutomationCoordinator.syncScheduledWork()
            }
            refreshStep("devices", failures) {
                devicePairingRepository.refresh()
            }
            refreshStep("organize", failures) {
                organizeExecutionRepository.refresh()
            }
            refreshStep("task recovery", failures) {
                taskRecoverySnapshot = recoverAgentTasks()
                val pendingRetryTaskCount = agentTaskRepository.tasks.value.count { task ->
                    task.status == AgentTaskStatus.RetryScheduled
                }
                if (pendingRetryTaskCount > 0) {
                    agentTaskRetryCoordinator.schedulePendingRetryWork()
                    taskRecoverySnapshot = taskRecoverySnapshot.copy(
                        retryWorkScheduled = true,
                    )
                }
            }
            refreshStep("audit", failures) {
                auditTrailRepository.refresh()
            }
            refreshStep("transfer recovery", failures) {
                transferRecoverySnapshot = transferBridgeCoordinator.recoverShellState()
                devicePairingRepository.refresh()
                auditTrailRepository.refresh()
            }
            val recoveryDetails = buildRecoveryDetails(
                transferRecoverySnapshot = transferRecoverySnapshot,
                taskRecoverySnapshot = taskRecoverySnapshot,
                automationSyncSnapshot = automationSyncSnapshot,
            )
            val completedAt = System.currentTimeMillis()
            if (failures.isEmpty()) {
                val successState = ShellRecoveryState(
                    status = ShellRecoveryStatus.Success,
                    triggerLabel = trigger.label,
                    updatedAtLabel = relativeTimeLabel(completedAt),
                    summary = "${trigger.label} recovery refreshed chat, approvals, tasks, automations, devices, organize, audit, and transfer recovery.",
                    detail = recoveryDetails,
                )
                _state.value = successState
                if (trigger.auditSuccess) {
                    auditTrailRepository.logAction(
                        action = "shell.recovery",
                        result = "passed",
                        details = "${trigger.label} recovery completed. $recoveryDetails",
                    )
                }
            } else {
                val failureState = ShellRecoveryState(
                    status = ShellRecoveryStatus.Failed,
                    triggerLabel = trigger.label,
                    updatedAtLabel = relativeTimeLabel(completedAt),
                    summary = "${trigger.label} recovery completed with failures.",
                    detail = "Failed step(s): ${failures.joinToString(", ")}",
                )
                _state.value = failureState
                auditTrailRepository.logAction(
                    action = "shell.recovery",
                    result = "failed",
                    details = "${trigger.label} recovery failed. Failed step(s): ${failures.joinToString(", ")}",
                )
            }
        }
    }

    private suspend fun refreshStep(
        label: String,
        failures: MutableList<String>,
        block: suspend () -> Unit,
    ) {
        runCatching {
            block()
        }.onFailure {
            failures += label
        }
    }

    private fun relativeTimeLabel(timestamp: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private fun buildRecoveryDetails(
        transferRecoverySnapshot: TransferRecoverySnapshot,
        taskRecoverySnapshot: AgentTaskRecoverySnapshot,
        automationSyncSnapshot: ScheduledAutomationSyncSnapshot,
    ): String {
        val chatThreadCount = chatTranscriptRepository.threads.value.size
        val approvalCount = approvalInboxRepository.items.value.size
        val taskCount = agentTaskRepository.tasks.value.size
        val automationCount = scheduledAutomationRepository.automations.value.size
        val pairedDeviceCount = devicePairingRepository.pairedDevices.value.size
        val auditCount = auditTrailRepository.events.value.size
        val organizeState = if (organizeExecutionRepository.latest.value == null) {
            "no organize result loaded"
        } else {
            "latest organize result loaded"
        }
        return buildString {
            append("Loaded ")
            append(chatThreadCount)
            append(" chat thread(s), ")
            append(approvalCount)
            append(" approval(s), ")
            append(taskCount)
            append(" task(s), ")
            append(automationCount)
            append(" automation(s), ")
            append(pairedDeviceCount)
            append(" paired device(s), ")
            append(organizeState)
            append(", and ")
            append(auditCount)
            append(" audit event(s). ")
            append(buildChatRecoverySummary())
            append(" ")
            append(buildAutomationRecoverySummary(automationSyncSnapshot))
            append(" ")
            append(buildTaskRecoverySummary(taskRecoverySnapshot))
            append(" ")
            append(
                buildTransferRecoverySummary(
                    transferRecoverySnapshot = transferRecoverySnapshot,
                ),
            )
        }
    }

    private fun buildChatRecoverySummary(): String {
        val activeThread = chatTranscriptRepository.activeThread.value
        return if (activeThread == null) {
            "Chat recovery did not find an active thread."
        } else {
            "Chat recovery restored active thread ${activeThread.title} with ${activeThread.messageCount} message(s)."
        }
    }

    private fun buildAutomationRecoverySummary(
        automationSyncSnapshot: ScheduledAutomationSyncSnapshot,
    ): String {
        return when {
            automationSyncSnapshot.automationCount == 0 ->
                "Automation recovery found no scheduled automations."
            else -> buildString {
                append("Automation recovery loaded ")
                append(automationSyncSnapshot.automationCount)
                append(" automation(s)")
                val statusCounts = buildList {
                    if (automationSyncSnapshot.activeCount > 0) {
                        add("${automationSyncSnapshot.activeCount} active")
                    }
                    if (automationSyncSnapshot.plannedCount > 0) {
                        add("${automationSyncSnapshot.plannedCount} planned")
                    }
                    if (automationSyncSnapshot.pausedCount > 0) {
                        add("${automationSyncSnapshot.pausedCount} paused")
                    }
                }
                if (statusCounts.isNotEmpty()) {
                    append(" (")
                    append(statusCounts.joinToString(", "))
                    append(")")
                }
                when {
                    automationSyncSnapshot.repairedScheduleWindowCount > 0 -> {
                        append(" and repaired ")
                        append(automationSyncSnapshot.repairedScheduleWindowCount)
                        append(" schedule window(s).")
                    }
                    automationSyncSnapshot.activeCount > 0 -> {
                        append(" and confirmed ")
                        append(automationSyncSnapshot.activeCount)
                        append(" active schedule(s).")
                    }
                    else -> append(".")
                }
            }
        }
    }

    private suspend fun recoverAgentTasks(): AgentTaskRecoverySnapshot {
        var snapshot = AgentTaskRecoverySnapshot()
        val tasks = agentTaskRepository.tasks.value
        tasks.forEach { task ->
            if (task.status in terminalTaskStates) {
                return@forEach
            }

            val approvalId = task.approvalRequestId
            if (approvalId == null) {
                if (task.status in interruptedForegroundStates) {
                    val summary = "Recovery marked this task as failed because the foreground turn was interrupted before a durable outcome was stored."
                    if (updateRecoveredTask(task, AgentTaskStatus.Failed, summary)) {
                        snapshot = snapshot.copy(
                            interruptedTaskCount = snapshot.interruptedTaskCount + 1,
                        )
                    }
                }
                return@forEach
            }

            val approval = approvalInboxRepository.items.value.firstOrNull { it.id == approvalId }
            when (approval?.status) {
                ApprovalInboxStatus.Pending -> {
                    val summary = "Recovery confirmed that this task is still waiting for user approval."
                    if (updateRecoveredTask(task, AgentTaskStatus.WaitingUser, summary)) {
                        snapshot = snapshot.copy(
                            waitingUserTaskCount = snapshot.waitingUserTaskCount + 1,
                        )
                    }
                }
                ApprovalInboxStatus.Denied -> {
                    val summary = "Recovery found that the linked approval was denied, so the task is cancelled."
                    if (updateRecoveredTask(task, AgentTaskStatus.Cancelled, summary)) {
                        snapshot = snapshot.copy(
                            cancelledTaskCount = snapshot.cancelledTaskCount + 1,
                        )
                    }
                }
                ApprovalInboxStatus.Approved -> {
                    val execution = organizeExecutionRepository.findByApprovalId(approvalId)
                    if (execution == null) {
                        val summary = "Recovery found an approved request without a durable execution result."
                        val scheduledTask = agentTaskRetryCoordinator.markTaskRetryScheduled(
                            taskId = task.id,
                            summary = "$summary The retry worker will resume it using the shared task backoff policy.",
                            errorMessage = "Approved task had no durable execution result during recovery.",
                            replyPreview = task.replyPreview,
                            immediate = true,
                        )
                        if (scheduledTask?.status == AgentTaskStatus.RetryScheduled) {
                            snapshot = snapshot.copy(
                                retryScheduledTaskCount = snapshot.retryScheduledTaskCount + 1,
                            )
                        } else if (scheduledTask?.status == AgentTaskStatus.Failed) {
                            snapshot = snapshot.copy(
                                interruptedTaskCount = snapshot.interruptedTaskCount + 1,
                            )
                        }
                    } else {
                        val recoveredStatus = when {
                            execution.result.deleteConsentRequiredCount > 0 -> AgentTaskStatus.WaitingUser
                            execution.result.failedCount == 0 && execution.result.copiedOnlyCount == 0 -> AgentTaskStatus.Succeeded
                            else -> AgentTaskStatus.Failed
                        }
                        val summary = when (recoveredStatus) {
                            AgentTaskStatus.WaitingUser ->
                                execution.result.statusNote
                                    ?: "Recovery restored an organize task that is still waiting for Android delete consent."
                            AgentTaskStatus.Succeeded ->
                                "Recovery restored a successful organize execution."
                            else ->
                                "Recovery restored an organize execution that finished with issues."
                        }
                        if (
                            updateRecoveredTask(
                                task = task,
                                status = recoveredStatus,
                                summary = summary,
                                replyPreview = execution.result.summaryWithStatusNote.take(maxRecoveredReplyPreviewLength),
                            )
                        ) {
                            snapshot = if (recoveredStatus == AgentTaskStatus.WaitingUser) {
                                snapshot.copy(
                                    waitingUserTaskCount = snapshot.waitingUserTaskCount + 1,
                                )
                            } else {
                                snapshot.copy(
                                    restoredExecutionTaskCount = snapshot.restoredExecutionTaskCount + 1,
                                )
                            }
                        }
                    }
                }
                null -> {
                    val summary = "Recovery could not find the linked approval record. Keep this task on hold until the request is recreated."
                    if (updateRecoveredTask(task, AgentTaskStatus.WaitingResource, summary)) {
                        snapshot = snapshot.copy(
                            waitingResourceTaskCount = snapshot.waitingResourceTaskCount + 1,
                        )
                    }
                }
            }
        }
        agentTaskRepository.refresh()
        return snapshot
    }

    private suspend fun updateRecoveredTask(
        task: AgentTaskRecord,
        status: AgentTaskStatus,
        summary: String,
        replyPreview: String? = null,
    ): Boolean {
        if (task.status == status && task.summary == summary && replyPreview == null) {
            return false
        }
        agentTaskRepository.updateTask(
            taskId = task.id,
            status = status,
            summary = summary,
            replyPreview = replyPreview,
            lastError = task.lastError,
        )
        return true
    }

    private fun buildTaskRecoverySummary(
        taskRecoverySnapshot: AgentTaskRecoverySnapshot,
    ): String {
        return when {
            taskRecoverySnapshot == AgentTaskRecoverySnapshot() ->
                "Task recovery found no interrupted or stale task states."
            else -> buildString {
                val changedStates = buildList {
                    if (taskRecoverySnapshot.interruptedTaskCount > 0) {
                        add("${taskRecoverySnapshot.interruptedTaskCount} interrupted")
                    }
                    if (taskRecoverySnapshot.waitingUserTaskCount > 0) {
                        add("${taskRecoverySnapshot.waitingUserTaskCount} waiting-user")
                    }
                    if (taskRecoverySnapshot.waitingResourceTaskCount > 0) {
                        add("${taskRecoverySnapshot.waitingResourceTaskCount} waiting-resource")
                    }
                    if (taskRecoverySnapshot.retryScheduledTaskCount > 0) {
                        add("${taskRecoverySnapshot.retryScheduledTaskCount} retry-scheduled")
                    }
                    if (taskRecoverySnapshot.cancelledTaskCount > 0) {
                        add("${taskRecoverySnapshot.cancelledTaskCount} cancelled")
                    }
                    if (taskRecoverySnapshot.restoredExecutionTaskCount > 0) {
                        add("${taskRecoverySnapshot.restoredExecutionTaskCount} restored-execution")
                    }
                }
                if (changedStates.isEmpty()) {
                    append("Task recovery found existing retry-scheduled tasks")
                } else {
                    append("Task recovery changed ")
                    append(changedStates.joinToString(", "))
                    append(" task state(s)")
                }
                if (taskRecoverySnapshot.retryWorkScheduled) {
                    append(" and scheduled a retry worker")
                }
                append(".")
            }
        }
    }

    private fun buildTransferRecoverySummary(
        transferRecoverySnapshot: TransferRecoverySnapshot,
    ): String {
        return when {
            transferRecoverySnapshot.recoveredStaleDraftCount > 0 ||
                transferRecoverySnapshot.dueQueuedDraftCount > 0 -> buildString {
                append("Transfer recovery repaired ")
                append(transferRecoverySnapshot.recoveredStaleDraftCount)
                append(" stale sending draft(s)")
                if (transferRecoverySnapshot.dueQueuedDraftCount > 0) {
                    append(" and found ")
                    append(transferRecoverySnapshot.dueQueuedDraftCount)
                    append(" due queued draft(s); an immediate drain was requested.")
                } else {
                    append("; an immediate drain was requested.")
                }
            }
            transferRecoverySnapshot.delayedQueuedDraftCount > 0 -> buildString {
                append("Transfer recovery re-armed ")
                append(transferRecoverySnapshot.delayedQueuedDraftCount)
                append(" delayed queued draft(s)")
                transferRecoverySnapshot.nextAttemptAtEpochMillis?.let { nextAttemptAt ->
                    append("; next retry ")
                    append(relativeTimeLabel(nextAttemptAt))
                }
                append(".")
            }
            else -> "Transfer recovery found no stale or queued drafts to repair."
        }
    }

    private enum class RecoveryTrigger(
        val label: String,
        val auditSuccess: Boolean,
    ) {
        AppLaunch("App launch", false),
        ForegroundResume("Foreground", false),
        ManualRefresh("Manual", true),
    }

    companion object {
        private const val minRecoveryIntervalMs = 5_000L
        private const val maxRecoveredReplyPreviewLength = 240
        private val interruptedForegroundStates = setOf(
            AgentTaskStatus.Queued,
            AgentTaskStatus.Planning,
            AgentTaskStatus.Running,
        )
        private val terminalTaskStates = setOf(
            AgentTaskStatus.Succeeded,
            AgentTaskStatus.Failed,
            AgentTaskStatus.Cancelled,
        )
    }
}

private data class AgentTaskRecoverySnapshot(
    val interruptedTaskCount: Int = 0,
    val waitingUserTaskCount: Int = 0,
    val waitingResourceTaskCount: Int = 0,
    val retryScheduledTaskCount: Int = 0,
    val cancelledTaskCount: Int = 0,
    val restoredExecutionTaskCount: Int = 0,
    val retryWorkScheduled: Boolean = false,
)
