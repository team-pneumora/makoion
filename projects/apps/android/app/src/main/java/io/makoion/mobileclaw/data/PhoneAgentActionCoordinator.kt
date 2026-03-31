package io.makoion.mobileclaw.data

data class ApprovalActionExecution(
    val approval: ApprovalInboxItem,
    val linkedTask: AgentTaskRecord? = null,
    val organizeExecution: PersistedOrganizeExecution? = null,
    val refreshedFileIndexState: FileIndexState? = null,
    val transferQueuedFileCount: Int? = null,
    val transferTargetLabel: String? = null,
)

sealed interface ApprovalActionResult {
    data class Missing(
        val requestedApprovalId: String? = null,
    ) : ApprovalActionResult

    data class AlreadyResolved(
        val approval: ApprovalInboxItem,
    ) : ApprovalActionResult

    data class Completed(
        val execution: ApprovalActionExecution,
    ) : ApprovalActionResult
}

data class TaskRetryActionExecution(
    val task: AgentTaskRecord,
    val organizeExecution: PersistedOrganizeExecution? = null,
    val refreshedFileIndexState: FileIndexState? = null,
)

sealed interface TaskRetryActionResult {
    data class Missing(
        val requestedTaskId: String? = null,
    ) : TaskRetryActionResult

    data class NotEligible(
        val task: AgentTaskRecord,
    ) : TaskRetryActionResult

    data class Completed(
        val execution: TaskRetryActionExecution,
    ) : TaskRetryActionResult
}

class PhoneAgentActionCoordinator(
    private val fileIndexRepository: FileIndexRepository,
    private val approvalInboxRepository: ApprovalInboxRepository,
    private val agentTaskRepository: AgentTaskRepository,
    private val organizeExecutionRepository: OrganizeExecutionRepository,
    private val fileActionExecutor: FileActionExecutor,
    private val devicePairingRepository: DevicePairingRepository,
    private val agentTaskRetryCoordinator: AgentTaskRetryCoordinator,
) {
    suspend fun approveApproval(approvalId: String? = null): ApprovalActionResult {
        approvalInboxRepository.refresh()
        agentTaskRepository.refresh()

        val target = selectApproval(approvalId) ?: return ApprovalActionResult.Missing(approvalId)
        if (target.status != ApprovalInboxStatus.Pending) {
            return ApprovalActionResult.AlreadyResolved(target)
        }

        val approval = approvalInboxRepository.approve(target.id)
            ?: return ApprovalActionResult.Missing(target.id)
        if (approval.action == filesOrganizeActionKey && !approval.payloadJson.isNullOrBlank()) {
            val runningTask = agentTaskRepository.updateTaskByApprovalRequestId(
                approvalRequestId = approval.id,
                status = AgentTaskStatus.Running,
                summary = "Approval granted. Organize execution is now running.",
                replyPreview = "Approval granted. Organize execution is now running.",
                nextRetryAtEpochMillis = null,
                lastError = null,
            )
            val executionResult = fileActionExecutor.executeApprovedOrganize(approval)
            val persisted = organizeExecutionRepository.save(approval.id, executionResult)
            approvalInboxRepository.recordExecutionOutcome(
                id = approval.id,
                note = executionResult.summaryWithStatusNote,
            )
            val updatedTask = agentTaskRepository.updateTaskByApprovalRequestId(
                approvalRequestId = approval.id,
                status = organizeTaskStatusFor(executionResult),
                summary = organizeTaskSummaryFor(executionResult),
                replyPreview = executionResult.summaryWithStatusNote.take(maxReplyPreviewLength),
                nextRetryAtEpochMillis = null,
                lastError = null,
            ) ?: runningTask
            return ApprovalActionResult.Completed(
                ApprovalActionExecution(
                    approval = approval,
                    linkedTask = updatedTask,
                    organizeExecution = persisted,
                    refreshedFileIndexState = fileIndexRepository.refreshIndex(),
                ),
            )
        }
        if (approval.action == filesTransferActionKey && !approval.payloadJson.isNullOrBlank()) {
            devicePairingRepository.refresh()
            val payload = TransferApprovalPayload.fromJson(approval.payloadJson)
            val files = payload.fileReferences.map { file ->
                IndexedFileItem(
                    id = file.sourceId,
                    name = file.name,
                    mimeType = file.mimeType,
                    sizeLabel = "",
                    modifiedLabel = "",
                    sourceLabel = "Approval payload",
                )
            }
            val device = devicePairingRepository.pairedDevices.value.firstOrNull { it.id == payload.deviceId }
            if (device == null) {
                val waitingTask = agentTaskRepository.updateTaskByApprovalRequestId(
                    approvalRequestId = approval.id,
                    status = AgentTaskStatus.WaitingResource,
                    summary = "Approved transfer could not be queued because the selected device is no longer available.",
                    replyPreview = "Selected device is no longer paired for this transfer.",
                    nextRetryAtEpochMillis = null,
                    lastError = "Selected device is no longer paired.",
                )
                return ApprovalActionResult.Completed(
                    ApprovalActionExecution(
                        approval = approval,
                        linkedTask = waitingTask,
                    ),
                )
            }
            devicePairingRepository.queueTransfer(
                deviceId = payload.deviceId,
                files = files,
                approvalRequestId = approval.id,
            )
            val outcomeNote = "Queued ${files.size} files for ${device.name}. Bridge delivery will continue in the background."
            approvalInboxRepository.recordExecutionOutcome(
                id = approval.id,
                note = outcomeNote,
            )
            val updatedTask = agentTaskRepository.updateTaskByApprovalRequestId(
                approvalRequestId = approval.id,
                status = AgentTaskStatus.Running,
                summary = outcomeNote,
                replyPreview = outcomeNote.take(maxReplyPreviewLength),
                nextRetryAtEpochMillis = null,
                lastError = null,
            )
            return ApprovalActionResult.Completed(
                ApprovalActionExecution(
                    approval = approval,
                    linkedTask = updatedTask,
                    transferQueuedFileCount = files.size,
                    transferTargetLabel = device.name,
                ),
            )
        }

        return ApprovalActionResult.Completed(
            ApprovalActionExecution(
                approval = approval,
                linkedTask = agentTaskRepository.updateTaskByApprovalRequestId(
                    approvalRequestId = approval.id,
                    status = AgentTaskStatus.Running,
                    summary = "Approval granted by the user.",
                    replyPreview = "Approval granted by the user.",
                    nextRetryAtEpochMillis = null,
                    lastError = null,
                ),
            ),
        )
    }

    suspend fun denyApproval(approvalId: String? = null): ApprovalActionResult {
        approvalInboxRepository.refresh()
        agentTaskRepository.refresh()

        val target = selectApproval(approvalId) ?: return ApprovalActionResult.Missing(approvalId)
        if (target.status != ApprovalInboxStatus.Pending) {
            return ApprovalActionResult.AlreadyResolved(target)
        }

        val approval = approvalInboxRepository.deny(target.id)
            ?: return ApprovalActionResult.Missing(target.id)
        return ApprovalActionResult.Completed(
            ApprovalActionExecution(
                approval = approval,
                linkedTask = agentTaskRepository.updateTaskByApprovalRequestId(
                    approvalRequestId = approval.id,
                    status = AgentTaskStatus.Cancelled,
                    summary = "Approval denied by the user.",
                    replyPreview = "Approval denied by the user.",
                    nextRetryAtEpochMillis = null,
                    lastError = null,
                ),
            ),
        )
    }

    suspend fun retryTask(taskId: String? = null): TaskRetryActionResult {
        agentTaskRepository.refresh()
        approvalInboxRepository.refresh()
        organizeExecutionRepository.refresh()
        devicePairingRepository.refresh()

        val target = selectRetryTask(taskId) ?: return TaskRetryActionResult.Missing(taskId)
        if (!isManualRetryEligible(target)) {
            return TaskRetryActionResult.NotEligible(target)
        }

        if (target.actionKey == filesTransferActionKey) {
            val approvalRequestId = target.approvalRequestId ?: return TaskRetryActionResult.NotEligible(target)
            val requeuedCount = devicePairingRepository.retryTransferApproval(approvalRequestId)
            if (requeuedCount <= 0) {
                return TaskRetryActionResult.NotEligible(target)
            }
            val updatedTask = agentTaskRepository.updateTaskByApprovalRequestId(
                approvalRequestId = approvalRequestId,
                status = AgentTaskStatus.Running,
                summary = "Manual retry requeued $requeuedCount transfer draft(s). Bridge delivery is resuming.",
                replyPreview = "Manual retry requeued transfer delivery.",
                nextRetryAtEpochMillis = null,
                lastError = null,
            ) ?: target
            return TaskRetryActionResult.Completed(
                TaskRetryActionExecution(
                    task = updatedTask,
                ),
            )
        }

        val retryExecution = agentTaskRetryCoordinator.retryNow(
            taskId = target.id,
            summary = "Manual retry requested from the chat-first shell.",
            replyPreview = "Manual retry requested for ${target.title}.",
            errorMessage = target.lastError ?: "Manual retry requested by the user.",
        ) ?: return TaskRetryActionResult.Missing(target.id)

        return TaskRetryActionResult.Completed(
            TaskRetryActionExecution(
                task = retryExecution.task,
                organizeExecution = retryExecution.organizeExecution,
                refreshedFileIndexState = retryExecution.organizeExecution?.let {
                    fileIndexRepository.refreshIndex()
                },
            ),
        )
    }

    private fun selectApproval(approvalId: String?): ApprovalInboxItem? {
        val approvals = approvalInboxRepository.items.value
        return if (approvalId != null) {
            approvals.firstOrNull { it.id == approvalId }
        } else {
            approvals.firstOrNull { it.status == ApprovalInboxStatus.Pending }
        }
    }

    private fun selectRetryTask(taskId: String?): AgentTaskRecord? {
        val tasks = agentTaskRepository.tasks.value
        if (taskId != null) {
            return tasks.firstOrNull { it.id == taskId }
        }

        val preferredStatuses = listOf(
            AgentTaskStatus.RetryScheduled,
            AgentTaskStatus.Failed,
            AgentTaskStatus.WaitingResource,
        )
        preferredStatuses.forEach { status ->
            tasks.firstOrNull { task ->
                isManualRetryEligible(task) &&
                    task.status == status
            }?.let { return it }
        }
        return null
    }

    private fun isManualRetryEligible(task: AgentTaskRecord): Boolean {
        val retryableStatus = task.status == AgentTaskStatus.RetryScheduled ||
            task.status == AgentTaskStatus.Failed ||
            task.status == AgentTaskStatus.WaitingResource
        if (!retryableStatus) {
            return false
        }
        return when (task.actionKey) {
            filesOrganizeActionKey -> task.maxRetryCount > 0
            filesTransferActionKey -> !task.approvalRequestId.isNullOrBlank()
            else -> false
        }
    }

    private fun organizeTaskStatusFor(result: OrganizeExecutionResult): AgentTaskStatus {
        return when {
            result.deleteConsentRequiredCount > 0 -> AgentTaskStatus.WaitingUser
            result.failedCount == 0 && result.copiedOnlyCount == 0 -> AgentTaskStatus.Succeeded
            else -> AgentTaskStatus.Failed
        }
    }

    private fun organizeTaskSummaryFor(result: OrganizeExecutionResult): String {
        return when {
            result.deleteConsentRequiredCount > 0 ->
                result.statusNote ?: "Execution copied the files, but Android delete consent is still required."
            result.failedCount == 0 && result.copiedOnlyCount == 0 ->
                "Execution completed successfully. ${result.summary}"
            else ->
                "Execution finished with issues. ${result.summaryWithStatusNote}"
        }
    }

    companion object {
        private const val filesOrganizeActionKey = filesOrganizeExecuteActionKey
        private const val filesTransferActionKey = filesTransferExecuteActionKey
        private const val maxReplyPreviewLength = 240
    }
}
