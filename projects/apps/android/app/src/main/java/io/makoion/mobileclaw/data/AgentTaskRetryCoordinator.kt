package io.makoion.mobileclaw.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.makoion.mobileclaw.notifications.ShellNotificationCenter
import io.makoion.mobileclaw.sync.AgentTaskRetryWorker
import java.util.concurrent.TimeUnit

data class AgentTaskRetryExecution(
    val task: AgentTaskRecord,
    val organizeExecution: PersistedOrganizeExecution? = null,
)

class AgentTaskRetryCoordinator(
    private val context: Context,
    private val agentTaskRepository: AgentTaskRepository,
    private val approvalInboxRepository: ApprovalInboxRepository,
    private val organizeExecutionRepository: OrganizeExecutionRepository,
    private val fileActionExecutor: FileActionExecutor,
    private val auditTrailRepository: AuditTrailRepository,
) {
    suspend fun schedulePendingRetryWork() {
        val now = System.currentTimeMillis()
        val nextRetryAt = agentTaskRepository.nextRetryAtEpochMillis(now) ?: return
        enqueueRetryWork(delayMs = (nextRetryAt - now).coerceAtLeast(0L))
    }

    suspend fun markTaskRetryScheduled(
        taskId: String,
        summary: String,
        errorMessage: String,
        replyPreview: String? = null,
        immediate: Boolean = false,
    ): AgentTaskRecord? {
        val task = agentTaskRepository.findTaskById(taskId) ?: return null
        if (task.maxRetryCount <= 0) {
            return updateTaskAndNotify(
                taskId = task.id,
                status = AgentTaskStatus.Failed,
                summary = "$summary Retry is not configured for this task.",
                replyPreview = replyPreview ?: summary.take(maxReplyPreviewLength),
                lastError = errorMessage,
            )
        }

        val nextRetryCount = task.retryCount + 1
        if (nextRetryCount > task.maxRetryCount) {
            return updateTaskAndNotify(
                taskId = task.id,
                status = AgentTaskStatus.Failed,
                summary = "$summary Retry budget exhausted after ${task.retryCount} attempt(s).",
                replyPreview = replyPreview ?: summary.take(maxReplyPreviewLength),
                lastError = errorMessage,
            )
        }

        val retryAt = if (immediate) {
            System.currentTimeMillis()
        } else {
            System.currentTimeMillis() + computeRetryDelayMs(nextRetryCount)
        }
        return updateTaskAndNotify(
            taskId = task.id,
            status = AgentTaskStatus.RetryScheduled,
            summary = summary,
            replyPreview = replyPreview ?: summary.take(maxReplyPreviewLength),
            retryCount = nextRetryCount,
            nextRetryAtEpochMillis = retryAt,
            lastError = errorMessage,
        )
    }

    suspend fun drainRetryQueue() {
        agentTaskRepository.refresh()
        approvalInboxRepository.refresh()
        organizeExecutionRepository.refresh()

        val dueRetryTasks = agentTaskRepository.dueRetryTasks()
        if (dueRetryTasks.isEmpty()) {
            schedulePendingRetryWork()
            return
        }

        var executedCount = 0
        var restoredCount = 0
        var rescheduledCount = 0
        var cancelledCount = 0
        var waitingCount = 0
        var failedCount = 0

        dueRetryTasks.forEach { task ->
            when (processRetryTask(task)) {
                RetryOutcome.Executed -> executedCount += 1
                RetryOutcome.Restored -> restoredCount += 1
                RetryOutcome.Rescheduled -> rescheduledCount += 1
                RetryOutcome.Cancelled -> cancelledCount += 1
                RetryOutcome.Waiting -> waitingCount += 1
                RetryOutcome.Failed -> failedCount += 1
            }
        }

        approvalInboxRepository.refresh()
        organizeExecutionRepository.refresh()
        agentTaskRepository.refresh()
        schedulePendingRetryWork()
        auditTrailRepository.logAction(
            action = "agent.task.retry",
            result = "drain_completed",
            details = "Retry worker processed ${dueRetryTasks.size} task(s): $executedCount executed, $restoredCount restored, $rescheduledCount rescheduled, $waitingCount waiting, $cancelledCount cancelled, $failedCount failed.",
        )
    }

    suspend fun retryNow(
        taskId: String,
        summary: String,
        replyPreview: String? = null,
        errorMessage: String,
    ): AgentTaskRetryExecution? {
        agentTaskRepository.refresh()
        approvalInboxRepository.refresh()
        organizeExecutionRepository.refresh()

        val currentTask = agentTaskRepository.findTaskById(taskId) ?: return null
        val preparedTask = when (currentTask.status) {
            AgentTaskStatus.RetryScheduled -> agentTaskRepository.updateTask(
                taskId = currentTask.id,
                status = AgentTaskStatus.RetryScheduled,
                summary = summary,
                replyPreview = replyPreview ?: summary.take(maxReplyPreviewLength),
                nextRetryAtEpochMillis = System.currentTimeMillis(),
                lastError = currentTask.lastError ?: errorMessage,
            ) ?: currentTask
            AgentTaskStatus.Failed,
            AgentTaskStatus.WaitingResource -> markTaskRetryScheduled(
                taskId = currentTask.id,
                summary = summary,
                errorMessage = errorMessage,
                replyPreview = replyPreview,
                immediate = true,
            ) ?: currentTask
            else -> currentTask
        }
        if (preparedTask.status == AgentTaskStatus.RetryScheduled) {
            processRetryTask(preparedTask)
            approvalInboxRepository.refresh()
            organizeExecutionRepository.refresh()
            agentTaskRepository.refresh()
            schedulePendingRetryWork()
            auditTrailRepository.logAction(
                action = "agent.task.retry",
                result = "manual_retry_completed",
                details = "Manual retry processed for task ${preparedTask.id}.",
            )
        }

        val updatedTask = agentTaskRepository.findTaskById(taskId) ?: preparedTask
        return AgentTaskRetryExecution(
            task = updatedTask,
            organizeExecution = loadOrganizeExecution(updatedTask),
        )
    }

    private suspend fun processRetryTask(task: AgentTaskRecord): RetryOutcome {
        return when (task.actionKey) {
            filesOrganizeActionKey -> processOrganizeRetry(task)
            else -> {
                updateTaskAndNotify(
                    taskId = task.id,
                    status = AgentTaskStatus.Failed,
                    summary = "Retry worker does not support ${task.actionKey} yet.",
                    replyPreview = task.replyPreview,
                    lastError = "Unsupported retry action ${task.actionKey}",
                )
                RetryOutcome.Failed
            }
        }
    }

    private suspend fun processOrganizeRetry(task: AgentTaskRecord): RetryOutcome {
        val approvalId = task.approvalRequestId ?: return recoverMissingApproval(task)
        val approval = approvalInboxRepository.items.value.firstOrNull { it.id == approvalId }
            ?: return recoverMissingApproval(task)
        return when (approval.status) {
            ApprovalInboxStatus.Pending -> {
                updateTaskAndNotify(
                    taskId = task.id,
                    status = AgentTaskStatus.WaitingUser,
                    summary = "Retry worker found the request still waiting for user approval.",
                    replyPreview = task.replyPreview,
                    lastError = task.lastError,
                )
                RetryOutcome.Waiting
            }
            ApprovalInboxStatus.Denied -> {
                updateTaskAndNotify(
                    taskId = task.id,
                    status = AgentTaskStatus.Cancelled,
                    summary = "Retry worker cancelled this task because the linked approval was denied.",
                    replyPreview = task.replyPreview,
                    lastError = task.lastError,
                )
                RetryOutcome.Cancelled
            }
            ApprovalInboxStatus.Approved -> {
                val persisted = organizeExecutionRepository.findByApprovalId(approvalId)
                if (persisted != null) {
                    restoreTaskFromExecution(task, persisted)
                    return RetryOutcome.Restored
                }
                if (approval.action != filesOrganizeActionKey || approval.payloadJson.isNullOrBlank()) {
                    updateTaskAndNotify(
                        taskId = task.id,
                        status = AgentTaskStatus.Failed,
                        summary = "Retry worker only supports approved organize executions right now.",
                        replyPreview = task.replyPreview,
                        lastError = "Unsupported approval payload for organize retry.",
                    )
                    return RetryOutcome.Failed
                }

                updateTaskAndNotify(
                    taskId = task.id,
                    status = AgentTaskStatus.Running,
                    summary = "Retry worker resumed an approved organize execution after recovery.",
                    replyPreview = task.replyPreview,
                    lastError = task.lastError,
                )
                return runCatching {
                    fileActionExecutor.executeApprovedOrganize(approval)
                }.fold(
                    onSuccess = { result ->
                        organizeExecutionRepository.save(approval.id, result)
                        approvalInboxRepository.recordExecutionOutcome(
                            id = approval.id,
                            note = result.summaryWithStatusNote,
                        )
                        updateTaskFromExecution(task, result)
                        RetryOutcome.Executed
                    },
                    onFailure = { error ->
                        val scheduled = markTaskRetryScheduled(
                            taskId = task.id,
                            summary = "Retry worker failed to execute the recovered organize task and scheduled another attempt.",
                            replyPreview = "Recovered execution failed: ${error.message ?: error::class.java.simpleName}",
                            errorMessage = error.message ?: error::class.java.simpleName,
                        )
                        if (scheduled?.status == AgentTaskStatus.RetryScheduled) {
                            RetryOutcome.Rescheduled
                        } else {
                            RetryOutcome.Failed
                        }
                    },
                )
            }
        }
    }

    private suspend fun recoverMissingApproval(task: AgentTaskRecord): RetryOutcome {
        updateTaskAndNotify(
            taskId = task.id,
            status = AgentTaskStatus.WaitingResource,
            summary = "Retry worker could not find the linked approval. Recreate or inspect the request before trying again.",
            replyPreview = task.replyPreview,
            lastError = task.lastError ?: "Linked approval missing.",
        )
        return RetryOutcome.Waiting
    }

    private suspend fun restoreTaskFromExecution(
        task: AgentTaskRecord,
        persisted: PersistedOrganizeExecution,
    ) {
        updateTaskFromExecution(
            task = task,
            result = persisted.result,
        )
    }

    private suspend fun updateTaskFromExecution(
        task: AgentTaskRecord,
        result: OrganizeExecutionResult,
    ) {
        val (status, summary) = organizeTaskOutcome(result)
        updateTaskAndNotify(
            taskId = task.id,
            status = status,
            summary = summary,
            replyPreview = result.summaryWithStatusNote.take(maxReplyPreviewLength),
            lastError = if (status == AgentTaskStatus.Failed) result.summaryWithStatusNote else null,
        )
    }

    private suspend fun updateTaskAndNotify(
        taskId: String,
        status: AgentTaskStatus,
        summary: String? = null,
        replyPreview: String? = null,
        retryCount: Int? = null,
        nextRetryAtEpochMillis: Long? = null,
        lastError: String? = null,
    ): AgentTaskRecord? {
        val updatedTask = agentTaskRepository.updateTask(
            taskId = taskId,
            status = status,
            summary = summary,
            replyPreview = replyPreview,
            retryCount = retryCount,
            nextRetryAtEpochMillis = nextRetryAtEpochMillis,
            lastError = lastError,
        )
        updatedTask?.let { task ->
            ShellNotificationCenter.maybeShowTaskFollowUp(context, task)
        }
        return updatedTask
    }

    private suspend fun loadOrganizeExecution(task: AgentTaskRecord): PersistedOrganizeExecution? {
        if (task.actionKey != filesOrganizeActionKey) {
            return null
        }
        val approvalId = task.approvalRequestId ?: return null
        return organizeExecutionRepository.findByApprovalId(approvalId)
    }

    private fun organizeTaskOutcome(result: OrganizeExecutionResult): Pair<AgentTaskStatus, String> {
        return when {
            result.deleteConsentRequiredCount > 0 -> AgentTaskStatus.WaitingUser to (
                result.statusNote
                    ?: "Recovered organize execution still needs Android delete consent."
                )
            result.failedCount == 0 && result.copiedOnlyCount == 0 ->
                AgentTaskStatus.Succeeded to "Recovered organize execution completed successfully. ${result.summary}"
            else ->
                AgentTaskStatus.Failed to "Recovered organize execution finished with issues. ${result.summaryWithStatusNote}"
        }
    }

    private fun enqueueRetryWork(delayMs: Long) {
        val requestBuilder = OneTimeWorkRequestBuilder<AgentTaskRetryWorker>()
        if (delayMs > 0L) {
            requestBuilder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            AgentTaskRetryWorker.workName,
            ExistingWorkPolicy.REPLACE,
            requestBuilder.build(),
        )
    }

    private fun computeRetryDelayMs(retryCount: Int): Long {
        val boundedRetry = retryCount.coerceIn(1, retryDelayStepsMs.size)
        return retryDelayStepsMs[boundedRetry - 1]
    }

    companion object {
        private const val filesOrganizeActionKey = filesOrganizeExecuteActionKey
        private const val maxReplyPreviewLength = 240
        private val retryDelayStepsMs = listOf(15_000L, 30_000L, 60_000L, 120_000L)
    }
}

private enum class RetryOutcome {
    Executed,
    Restored,
    Rescheduled,
    Cancelled,
    Waiting,
    Failed,
}
