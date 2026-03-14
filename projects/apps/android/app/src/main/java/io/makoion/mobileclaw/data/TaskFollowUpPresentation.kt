package io.makoion.mobileclaw.data

object TaskFollowUpPresentation {
    fun shouldSurface(task: AgentTaskRecord): Boolean {
        if (task.approvalRequestId == null) {
            return false
        }
        return when (task.status) {
            AgentTaskStatus.WaitingUser,
            AgentTaskStatus.WaitingResource,
            AgentTaskStatus.RetryScheduled,
            AgentTaskStatus.Succeeded,
            AgentTaskStatus.Failed,
            AgentTaskStatus.Cancelled -> true
            else -> false
        }
    }

    fun followUpKey(task: AgentTaskRecord): String {
        return "${task.id}:${task.status.name}:${task.updatedAtEpochMillis}"
    }

    fun notificationTitle(task: AgentTaskRecord): String {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> if (prefersKorean(task)) "작업 완료" else "Task completed"
            AgentTaskStatus.Failed -> if (prefersKorean(task)) "작업 실패" else "Task failed"
            AgentTaskStatus.WaitingUser -> if (prefersKorean(task)) "사용자 확인 필요" else "User action needed"
            AgentTaskStatus.WaitingResource -> if (prefersKorean(task)) "자원 대기" else "Resource needed"
            AgentTaskStatus.RetryScheduled -> if (prefersKorean(task)) "재시도 예정" else "Retry scheduled"
            AgentTaskStatus.Cancelled -> if (prefersKorean(task)) "작업 취소" else "Task cancelled"
            else -> if (prefersKorean(task)) "작업 업데이트" else "Task update"
        }
    }

    fun chatMessage(task: AgentTaskRecord): String {
        val prefersKorean = prefersKorean(task)
        return when (task.status) {
            AgentTaskStatus.Succeeded -> if (prefersKorean) {
                "작업 업데이트: ${task.title} 작업이 완료됐습니다. ${task.summary}"
            } else {
                "Task update: ${task.title} completed. ${task.summary}"
            }
            AgentTaskStatus.Failed -> if (prefersKorean) {
                "작업 업데이트: ${task.title} 작업이 실패했습니다. ${task.summary}"
            } else {
                "Task update: ${task.title} failed. ${task.summary}"
            }
            AgentTaskStatus.WaitingUser -> if (prefersKorean) {
                "작업 업데이트: ${task.title} 작업이 사용자 확인을 기다립니다. ${task.summary}"
            } else {
                "Task update: ${task.title} is waiting for your confirmation. ${task.summary}"
            }
            AgentTaskStatus.WaitingResource -> if (prefersKorean) {
                "작업 업데이트: ${task.title} 작업이 자원을 기다립니다. ${task.summary}"
            } else {
                "Task update: ${task.title} is waiting on a required resource. ${task.summary}"
            }
            AgentTaskStatus.RetryScheduled -> if (prefersKorean) {
                "작업 업데이트: ${task.title} 작업이 재시도 예정입니다. ${task.summary}"
            } else {
                "Task update: ${task.title} is scheduled to retry. ${task.summary}"
            }
            AgentTaskStatus.Cancelled -> if (prefersKorean) {
                "작업 업데이트: ${task.title} 작업이 취소됐습니다. ${task.summary}"
            } else {
                "Task update: ${task.title} was cancelled. ${task.summary}"
            }
            else -> task.summary
        }
    }

    fun approveActionLabel(task: AgentTaskRecord): String {
        return if (prefersKorean(task)) "승인" else "Approve"
    }

    fun denyActionLabel(task: AgentTaskRecord): String {
        return if (prefersKorean(task)) "거절" else "Deny"
    }

    fun retryActionLabel(task: AgentTaskRecord): String {
        return if (prefersKorean(task)) "재시도" else "Retry"
    }

    private fun prefersKorean(task: AgentTaskRecord): Boolean {
        return task.prompt.any { it in '\uAC00'..'\uD7A3' }
    }
}
