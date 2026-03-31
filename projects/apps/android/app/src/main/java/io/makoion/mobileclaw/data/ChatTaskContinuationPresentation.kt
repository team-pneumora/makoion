package io.makoion.mobileclaw.data

object ChatTaskContinuationPresentation {
    fun followUpNote(
        task: AgentTaskRecord?,
        approval: ApprovalInboxItem?,
    ): String? {
        approval?.let { inboxItem ->
            if (inboxItem.status == ApprovalInboxStatus.Pending) {
                return if (prefersKorean(task, inboxItem)) {
                    "여기서 바로 승인하거나 거절할 수 있습니다."
                } else {
                    "You can approve or deny this here."
                }
            }
        }
        task ?: return null
        val korean = prefersKorean(task, approval)
        return when (task.status) {
            AgentTaskStatus.WaitingUser -> if (korean) {
                "다음 확인이나 승인 처리가 필요합니다."
            } else {
                "This needs your next confirmation or approval."
            }
            AgentTaskStatus.WaitingResource -> if (task.actionKey.isCompanionActionKey()) {
                if (korean) {
                    "채팅에서 필요한 연결을 이어서 진행한 뒤 다시 시도하면 됩니다."
                } else {
                    "Continue the missing connection from chat, then try again."
                }
            } else if (task.actionKey.isMcpActionKey()) {
                if (korean) {
                    "MCP 준비가 아직 덜 끝났습니다. 아래 버튼으로 다음 단계를 이어가면 됩니다."
                } else {
                    "The MCP setup is still incomplete. Use the buttons below for the next step."
                }
            } else {
                if (korean) {
                    "필요한 자원이 준비되면 이어서 실행할 수 있습니다."
                } else {
                    "This can continue once the missing resource is ready."
                }
            }
            AgentTaskStatus.RetryScheduled -> if (korean) {
                "재시도가 이미 예약되어 있습니다."
            } else {
                "A retry is already scheduled."
            }
            AgentTaskStatus.Failed -> if (task.actionKey.isCompanionActionKey()) {
                if (korean) {
                    "companion 상태를 다시 확인한 뒤 같은 작업을 재실행해 보세요."
                } else {
                    "Check companion health, then rerun the same action."
                }
            } else if (task.actionKey.isMcpActionKey()) {
                if (korean) {
                    "연결 준비 상태를 다시 확인한 뒤 같은 MCP 작업을 다시 요청하면 됩니다."
                } else {
                    "Check the connection readiness, then rerun the same MCP action."
                }
            } else {
                null
            }
            AgentTaskStatus.Succeeded -> if (task.actionKey.isCompanionActionKey()) {
                if (korean) {
                    "필요하면 바로 다음 companion 작업으로 이어갈 수 있습니다."
                } else {
                    "You can continue with the next companion action right away."
                }
            } else if (task.actionKey.isMcpActionKey()) {
                if (korean) {
                    "채팅에서 바로 다음 MCP 점검이나 동기화로 이어갈 수 있습니다."
                } else {
                    "You can continue with the next MCP check or sync right from chat."
                }
            } else {
                null
            }
            else -> null
        }
    }

    fun continuationPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.actionKey) {
            companionHealthProbeActionKey -> companionHealthPrompts(task)
            companionSessionNotifyActionKey -> companionNotifyPrompts(task)
            companionAppOpenActionKey -> companionAppOpenPrompts(task)
            companionWorkflowRunActionKey -> companionWorkflowPrompts(task)
            mcpSetupGuideActionKey -> mcpGuidePrompts(task)
            mcpBridgeConnectActionKey -> mcpBridgePrompts(task)
            mcpConnectorStatusActionKey -> mcpStatusPrompts(task)
            mcpToolsShowActionKey -> mcpToolsPrompts(task)
            mcpSkillSyncActionKey -> mcpSkillPrompts(task)
            mcpSkillShowActionKey -> mcpSkillCatalogPrompts(task)
            else -> emptyList()
        }.distinct()
    }

    fun isManualRetryEligible(task: AgentTaskRecord): Boolean {
        val retryableStatus = task.status == AgentTaskStatus.RetryScheduled ||
            task.status == AgentTaskStatus.Failed ||
            task.status == AgentTaskStatus.WaitingResource
        if (!retryableStatus) {
            return false
        }
        return when (task.actionKey) {
            filesOrganizeExecuteActionKey -> task.maxRetryCount > 0
            filesTransferExecuteActionKey -> !task.approvalRequestId.isNullOrBlank()
            else -> false
        }
    }

    fun appOpenTaskSummary(
        korean: Boolean,
        result: CompanionAppOpenResult,
    ): String {
        val targetName = targetLabel(korean, result.targetKind)
        return buildString {
            append(targetName)
            append(if (korean) " 요청을 " else " request ")
            append(
                when (result.status) {
                    CompanionAppOpenStatus.Opened -> if (korean) "바로 열었습니다" else "opened immediately"
                    CompanionAppOpenStatus.Recorded -> if (korean) "기록했습니다" else "was recorded"
                    CompanionAppOpenStatus.Failed -> if (korean) "실행하지 못했습니다" else "failed"
                    CompanionAppOpenStatus.Misconfigured -> if (korean) "실행할 수 없습니다" else "is unavailable"
                    CompanionAppOpenStatus.Skipped -> if (korean) "건너뛰었습니다" else "was skipped"
                },
            )
            append(". ")
            append(result.summary)
            if (result.detail.isNotBlank()) {
                append(" ")
                append(result.detail)
            }
            append(" ")
            append(
                if (korean) {
                    "대상 ${result.targetKind}, ${result.sentAtLabel} 업데이트."
                } else {
                    "Target ${result.targetKind}, updated ${result.sentAtLabel}."
                },
            )
        }.trim()
    }

    fun healthTaskSummary(
        korean: Boolean,
        result: CompanionHealthCheckResult,
    ): String {
        return buildString {
            append(result.summary)
            if (result.detail.isNotBlank()) {
                append(" ")
                append(result.detail)
            }
            append(" ")
            append(
                if (korean) {
                    "Health 확인 시각 ${result.checkedAtLabel}."
                } else {
                    "Health checked ${result.checkedAtLabel}."
                },
            )
        }.trim()
    }

    fun sessionNotifyTaskSummary(
        korean: Boolean,
        result: CompanionSessionNotifyResult,
    ): String {
        return buildString {
            append(result.summary)
            if (result.detail.isNotBlank()) {
                append(" ")
                append(result.detail)
            }
            append(" ")
            append(
                if (korean) {
                    "session.notify 전송 시각 ${result.sentAtLabel}."
                } else {
                    "session.notify sent ${result.sentAtLabel}."
                },
            )
        }.trim()
    }

    fun workflowTaskSummary(
        korean: Boolean,
        workflowLabel: String,
        result: CompanionWorkflowRunResult,
    ): String {
        return buildString {
            append(workflowLabel)
            append(if (korean) " workflow를 " else " workflow ")
            append(
                when (result.status) {
                    CompanionWorkflowRunStatus.Completed -> if (korean) "실행했습니다" else "completed"
                    CompanionWorkflowRunStatus.Recorded -> if (korean) "기록했습니다" else "was recorded"
                    CompanionWorkflowRunStatus.Failed -> if (korean) "실행하지 못했습니다" else "failed"
                    CompanionWorkflowRunStatus.Misconfigured -> if (korean) "실행할 수 없습니다" else "is unavailable"
                    CompanionWorkflowRunStatus.Skipped -> if (korean) "건너뛰었습니다" else "was skipped"
                },
            )
            append(". ")
            append(result.summary)
            if (result.detail.isNotBlank()) {
                append(" ")
                append(result.detail)
            }
            append(" ")
            append(
                if (korean) {
                    "워크플로 ${result.workflowId}, ${result.sentAtLabel} 업데이트."
                } else {
                    "Workflow ${result.workflowId}, updated ${result.sentAtLabel}."
                },
            )
        }.trim()
    }

    private fun companionHealthPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.SendDesktopNotification,
                ChatContinuationPromptId.OpenCompanionInbox,
                ChatContinuationPromptId.RunOpenLatestActionWorkflow,
            )
            AgentTaskStatus.Failed -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.OpenSettingsAndResources,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            else -> emptyList()
        }
    }

    private fun companionNotifyPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.OpenCompanionInbox,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            AgentTaskStatus.Failed -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.OpenSettingsAndResources,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            else -> emptyList()
        }
    }

    private fun companionAppOpenPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> ChatContinuationPromptCatalog.appOpenSuccessPrompts(
                inferTargetKind(task),
            )
            AgentTaskStatus.Failed -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.OpenSettingsAndResources,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            else -> emptyList()
        }
    }

    private fun companionWorkflowPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        val workflowId = inferWorkflowId(task)
        return when (task.status) {
            AgentTaskStatus.Succeeded -> ChatContinuationPromptCatalog.workflowSuccessPrompts(workflowId)
            AgentTaskStatus.Failed -> ChatContinuationPromptCatalog.workflowRetryPrompts(workflowId)
            AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.OpenSettingsAndResources,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            else -> emptyList()
        }
    }

    private fun mcpGuidePrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.ShowMcpStatus,
                ChatContinuationPromptId.ShowMcpTools,
            )
            else -> emptyList()
        }
    }

    private fun mcpBridgePrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.ShowMcpStatus,
                ChatContinuationPromptId.ShowMcpTools,
                ChatContinuationPromptId.UpdateMcpSkills,
            )
            AgentTaskStatus.Failed, AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            else -> emptyList()
        }
    }

    private fun mcpStatusPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.ShowMcpTools,
                ChatContinuationPromptId.UpdateMcpSkills,
                ChatContinuationPromptId.ConnectMcpBridge,
            )
            AgentTaskStatus.Failed, AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            else -> emptyList()
        }
    }

    private fun mcpToolsPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.UpdateMcpSkills,
                ChatContinuationPromptId.ShowMcpStatus,
                ChatContinuationPromptId.ConnectMcpBridge,
            )
            AgentTaskStatus.Failed, AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            else -> emptyList()
        }
    }

    private fun mcpSkillPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.ShowMcpTools,
                ChatContinuationPromptId.ShowMcpStatus,
                ChatContinuationPromptId.UpdateMcpSkills,
            )
            AgentTaskStatus.Failed, AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            else -> emptyList()
        }
    }

    private fun mcpSkillCatalogPrompts(task: AgentTaskRecord): List<ChatContinuationPromptId> {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> listOf(
                ChatContinuationPromptId.UpdateMcpSkills,
                ChatContinuationPromptId.ShowMcpTools,
                ChatContinuationPromptId.ShowMcpStatus,
            )
            AgentTaskStatus.Failed, AgentTaskStatus.WaitingResource -> listOf(
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            else -> emptyList()
        }
    }

    private fun inferTargetKind(task: AgentTaskRecord): String {
        val content = "${task.prompt}\n${task.summary}".lowercase()
        return when {
            containsAny(content, "latest action", "recent action", "최근 액션", "방금 액션") ->
                companionAppOpenTargetLatestAction
            containsAny(content, "latest transfer", "recent transfer", "최근 전송", "전송 폴더") ->
                companionAppOpenTargetLatestTransfer
            containsAny(content, "actions folder", "action folder", "액션 폴더", "actions") ->
                companionAppOpenTargetActionsFolder
            containsAny(content, "inbox", "받은", "수신") ->
                companionAppOpenTargetInbox
            else -> ""
        }
    }

    private fun inferWorkflowId(task: AgentTaskRecord): String {
        val content = "${task.prompt}\n${task.summary}".lowercase()
        return when {
            containsAny(content, "latest action", "recent action", "최근 액션", "방금 액션") ->
                companionWorkflowIdOpenLatestAction
            containsAny(content, "latest transfer", "recent transfer", "최근 전송", "전송 폴더") ->
                companionWorkflowIdOpenLatestTransfer
            containsAny(content, "actions folder", "action folder", "액션 폴더", "actions") ->
                companionWorkflowIdOpenActionsFolder
            else -> ""
        }
    }

    private fun targetLabel(
        korean: Boolean,
        targetKind: String,
    ): String {
        return when (targetKind) {
            companionAppOpenTargetInbox -> if (korean) "Companion inbox" else "Companion inbox"
            companionAppOpenTargetLatestTransfer -> if (korean) "최근 전송 폴더" else "Latest transfer folder"
            companionAppOpenTargetActionsFolder -> if (korean) "Actions 폴더" else "Actions folder"
            companionAppOpenTargetLatestAction -> if (korean) "최근 액션 폴더" else "Latest action folder"
            else -> targetKind.ifBlank {
                if (korean) "원격 surface" else "Remote surface"
            }
        }
    }

    private fun prefersKorean(
        task: AgentTaskRecord?,
        approval: ApprovalInboxItem?,
    ): Boolean {
        val content = buildString {
            append(task?.prompt.orEmpty())
            append('\n')
            append(task?.summary.orEmpty())
            append('\n')
            append(approval?.title.orEmpty())
            append('\n')
            append(approval?.summary.orEmpty())
        }
        return content.any { it in '\uAC00'..'\uD7A3' }
    }

    private fun String.isCompanionActionKey(): Boolean {
        return this == companionHealthProbeActionKey ||
            this == companionSessionNotifyActionKey ||
            this == companionAppOpenActionKey ||
            this == companionWorkflowRunActionKey
    }

    private fun String.isMcpActionKey(): Boolean {
        return this == mcpSetupGuideActionKey ||
            this == mcpBridgeConnectActionKey ||
            this == mcpConnectorStatusActionKey ||
            this == mcpToolsShowActionKey ||
            this == mcpSkillSyncActionKey ||
            this == mcpSkillShowActionKey
    }

    private fun containsAny(
        normalizedContent: String,
        vararg terms: String,
    ): Boolean {
        return terms.any { term -> normalizedContent.contains(term) }
    }
}
