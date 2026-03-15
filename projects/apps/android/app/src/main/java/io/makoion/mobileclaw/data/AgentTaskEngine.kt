package io.makoion.mobileclaw.data

data class AgentTaskExecution(
    val task: AgentTaskRecord,
    val turnResult: AgentTurnResult,
)

class AgentTaskEngine(
    private val agentTaskRepository: AgentTaskRepository,
    private val phoneAgentRuntime: LocalPhoneAgentRuntime,
    private val auditTrailRepository: AuditTrailRepository,
) {
    suspend fun submitTurn(
        threadId: String,
        prompt: String,
        context: AgentTurnContext,
    ): AgentTaskExecution {
        val task = agentTaskRepository.createTask(
            threadId = threadId,
            prompt = prompt,
            title = queuedTitleFor(prompt),
            summary = "Queued from the chat-first shell.",
            actionKey = defaultTaskActionKey,
            maxRetryCount = 0,
        )
        agentTaskRepository.updateTask(
            taskId = task.id,
            status = AgentTaskStatus.Planning,
            summary = "The phone agent is interpreting the request and selecting capabilities.",
        )
        agentTaskRepository.updateTask(
            taskId = task.id,
            status = AgentTaskStatus.Running,
            summary = "The phone agent is executing this turn against connected resources.",
        )

        return try {
            val turnResult = phoneAgentRuntime.processTurn(
                prompt = prompt,
                context = context,
            )
            val updatedTask = agentTaskRepository.updateTask(
                taskId = task.id,
                status = turnResult.taskStatus,
                title = turnResult.taskTitle ?: queuedTitleFor(prompt),
                summary = turnResult.taskSummary ?: turnResult.reply.take(maxSummaryLength),
                replyPreview = turnResult.reply.take(maxReplyPreviewLength),
                destination = turnResult.destination,
                approvalRequestId = turnResult.approvalRequestId,
                actionKey = turnResult.taskActionKey,
                planningTrace = turnResult.planningTrace,
                maxRetryCount = turnResult.taskMaxRetryCount,
            ) ?: task
            AgentTaskExecution(
                task = updatedTask,
                turnResult = turnResult,
            )
        } catch (error: Throwable) {
            val summary = "Task failed before completion: ${error.message ?: error::class.java.simpleName}"
            agentTaskRepository.updateTask(
                taskId = task.id,
                status = AgentTaskStatus.Failed,
                title = queuedTitleFor(prompt),
                summary = summary,
                replyPreview = summary.take(maxReplyPreviewLength),
            )
            auditTrailRepository.logAction(
                action = "agent.task",
                result = "failed",
                details = "Task ${task.id} failed: ${error.message ?: error::class.java.simpleName}",
            )
            throw error
        }
    }

    suspend fun markApprovalDecision(
        approvalRequestId: String,
        approved: Boolean,
        summary: String,
    ) {
        agentTaskRepository.updateTaskByApprovalRequestId(
            approvalRequestId = approvalRequestId,
            status = if (approved) AgentTaskStatus.Running else AgentTaskStatus.Cancelled,
            summary = summary,
            replyPreview = summary.take(maxReplyPreviewLength),
            nextRetryAtEpochMillis = null,
            lastError = null,
        )
    }

    suspend fun recordOrganizeExecution(
        approvalRequestId: String,
        result: OrganizeExecutionResult,
    ) {
        agentTaskRepository.updateTaskByApprovalRequestId(
            approvalRequestId = approvalRequestId,
            status = organizeTaskStatusFor(result),
            summary = organizeTaskSummaryFor(result),
            replyPreview = result.summaryWithStatusNote.take(maxReplyPreviewLength),
            nextRetryAtEpochMillis = null,
            lastError = null,
        )
    }

    private fun queuedTitleFor(prompt: String): String {
        return prompt
            .trim()
            .replace('\n', ' ')
            .take(maxTitleLength)
            .ifBlank { "Agent task" }
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
        private const val maxTitleLength = 72
        private const val maxSummaryLength = 180
        private const val maxReplyPreviewLength = 240
    }
}
