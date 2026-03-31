package io.makoion.mobileclaw.data

import kotlinx.coroutines.delay
import org.json.JSONObject

data class AgentTurnContext(
    val fileIndexState: FileIndexState,
    val approvals: List<ApprovalInboxItem>,
    val tasks: List<AgentTaskRecord>,
    val auditEvents: List<AuditTrailEvent>,
    val chatMessages: List<ChatMessage> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    val pairedDevices: List<PairedDeviceState>,
    val selectedTargetDeviceId: String?,
    val cloudDriveConnections: List<CloudDriveConnectionState> = emptyList(),
    val modelPreference: AgentModelPreference = AgentModelPreference(),
    val externalEndpoints: List<ExternalEndpointProfileState> = emptyList(),
    val deliveryChannels: List<DeliveryChannelProfileState> = emptyList(),
    val mailboxConnections: List<MailboxConnectionProfileState> = emptyList(),
    val emailTriageRecords: List<EmailTriageRecord> = emptyList(),
    val scheduledAutomations: List<ScheduledAutomationRecord> = emptyList(),
    val selectedFileId: String? = null,
)

enum class AgentDestination {
    Chat,
    Dashboard,
    History,
    Settings,
}

data class AgentTurnResult(
    val reply: String,
    val destination: AgentDestination = AgentDestination.Chat,
    val taskTitle: String? = null,
    val taskActionKey: String = defaultTaskActionKey,
    val taskSummary: String? = null,
    val taskStatus: AgentTaskStatus = AgentTaskStatus.Succeeded,
    val taskMaxRetryCount: Int = 0,
    val approvalRequestId: String? = null,
    val refreshedFileIndexState: FileIndexState? = null,
    val fileSummary: FileSummaryDetail? = null,
    val organizePlan: FileOrganizePlan? = null,
    val fileActionNote: String? = null,
    val persistedOrganizeExecution: PersistedOrganizeExecution? = null,
    val trackedTask: AgentTaskRecord? = null,
    val companionHealthCheckResult: CompanionHealthCheckResult? = null,
    val companionSessionNotifyResult: CompanionSessionNotifyResult? = null,
    val companionAppOpenResult: CompanionAppOpenResult? = null,
    val companionWorkflowRunResult: CompanionWorkflowRunResult? = null,
    val planningTrace: AgentPlanningTrace? = null,
)

class LocalPhoneAgentRuntime(
    private val fileIndexRepository: FileIndexRepository,
    private val fileGraphActionPlanner: LocalFileGraphActionPlanner,
    private val approvalInboxRepository: ApprovalInboxRepository,
    private val auditTrailRepository: AuditTrailRepository,
    private val cloudDriveConnectionRepository: CloudDriveConnectionRepository,
    private val devicePairingRepository: DevicePairingRepository,
    private val deliveryChannelRepository: DeliveryChannelRegistryRepository,
    private val mailboxConnectionRepository: MailboxConnectionRepository,
    private val emailTriageRepository: EmailTriageRepository,
    private val externalEndpointRepository: ExternalEndpointRegistryRepository,
    private val mcpSkillRepository: McpSkillRepository,
    private val scheduledAutomationRepository: ScheduledAutomationRepository,
    private val scheduledAutomationCoordinator: ScheduledAutomationCoordinator,
    private val shellRecoveryCoordinator: ShellRecoveryCoordinator,
    private val codeGenerationProjectRepository: CodeGenerationProjectRepository,
    private val codeGenerationWorkspaceExecutor: CodeGenerationWorkspaceExecutor,
    private val phoneAgentActionCoordinator: PhoneAgentActionCoordinator,
    private val providerConversationClient: ProviderConversationClient,
    private val deliveryChannelCredentialVault: DeliveryChannelCredentialVault,
    private val mailboxCredentialVault: MailboxCredentialVault,
    private val mailboxGateway: MailboxGateway,
    private val telegramDeliveryGateway: TelegramDeliveryGateway,
) {
    suspend fun processTurn(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank() && context.attachments.isNotEmpty()) {
            return attachmentOnlyReply(context.attachments)
        }
        val plannerOutput = planTurn(trimmedPrompt, context)
        val rawResult = when (val intent = plannerOutput.intent) {
            is AgentIntent.ApprovePendingApproval -> approvePendingApproval(trimmedPrompt, context, intent.approvalId)
            is AgentIntent.DenyPendingApproval -> denyPendingApproval(trimmedPrompt, context, intent.approvalId)
            is AgentIntent.RetryTask -> retryAgentTask(trimmedPrompt, context, intent.taskId)
            AgentIntent.ShowDashboard -> buildDashboardResponse(trimmedPrompt, context)
            AgentIntent.ShowHistory -> buildHistoryResponse(trimmedPrompt, context)
            AgentIntent.ShowSettings -> buildSettingsResponse(trimmedPrompt, context)
            AgentIntent.ExplainInitialSetup -> explainInitialSetup(trimmedPrompt, context)
            AgentIntent.ExplainMcpSetup -> explainMcpSetup(trimmedPrompt, context)
            AgentIntent.ExplainEmailSetup -> explainEmailSetup(trimmedPrompt, context)
            AgentIntent.ShowMailboxStatus -> showMailboxStatus(trimmedPrompt)
            AgentIntent.ShowResourceStack -> showResourceStack(trimmedPrompt, context)
            AgentIntent.RefreshResources -> refreshResources(trimmedPrompt)
            AgentIntent.RunShellRecovery -> runShellRecovery(trimmedPrompt)
            AgentIntent.ShowShellRecoveryStatus -> showShellRecoveryStatus(trimmedPrompt)
            is AgentIntent.StageCloudDrive -> stageCloudDrive(trimmedPrompt, intent.provider)
            is AgentIntent.ConnectCloudDrive -> connectCloudDrive(trimmedPrompt, intent.provider)
            is AgentIntent.StageExternalEndpoint -> stageExternalEndpoint(trimmedPrompt, intent.endpointId)
            is AgentIntent.ConnectExternalEndpoint -> connectExternalEndpoint(trimmedPrompt, intent.endpointId)
            is AgentIntent.StageDeliveryChannel -> stageDeliveryChannel(trimmedPrompt, intent.channelId)
            is AgentIntent.ConnectDeliveryChannel -> connectDeliveryChannel(trimmedPrompt, intent.channelId)
            AgentIntent.ConnectMailbox -> connectMailbox(trimmedPrompt)
            AgentIntent.PlanScheduledAutomation -> planScheduledAutomation(trimmedPrompt, context)
            is AgentIntent.ActivateScheduledAutomation -> activateScheduledAutomation(trimmedPrompt, context, intent.automationId)
            is AgentIntent.PauseScheduledAutomation -> pauseScheduledAutomation(trimmedPrompt, context, intent.automationId)
            is AgentIntent.RunScheduledAutomationNow -> runScheduledAutomationNow(trimmedPrompt, context, intent.automationId)
            AgentIntent.PlanCodeGeneration -> planCodeGeneration(trimmedPrompt, context)
            AgentIntent.PlanBrowserResearch -> planBrowserResearch(trimmedPrompt, context)
            is AgentIntent.BrowseWebPage -> browseWebPage(trimmedPrompt, context, intent.url)
            AgentIntent.SummarizeIndexedFiles -> summarizeIndexedFiles(trimmedPrompt, context)
            is AgentIntent.OrganizeIndexedFiles -> organizeIndexedFiles(trimmedPrompt, context, intent.strategy)
            AgentIntent.TransferIndexedFiles -> transferIndexedFiles(trimmedPrompt, context)
            AgentIntent.ConnectMcpBridge -> connectMcpBridge(trimmedPrompt, context)
            AgentIntent.ShowMcpConnectorStatus -> showMcpConnectorStatus(trimmedPrompt)
            AgentIntent.ShowMcpTools -> showMcpTools(trimmedPrompt)
            AgentIntent.SyncMcpSkills -> syncMcpSkills(trimmedPrompt, context)
            AgentIntent.ShowMcpSkills -> showMcpSkills(trimmedPrompt)
            AgentIntent.ProbeCompanionHealth -> probeCompanionHealth(trimmedPrompt, context)
            AgentIntent.SendCompanionSessionNotification -> sendCompanionSessionNotification(trimmedPrompt, context)
            is AgentIntent.OpenCompanionTarget -> openCompanionTarget(trimmedPrompt, context, intent.targetKind)
            is AgentIntent.RunCompanionWorkflow -> runCompanionWorkflow(trimmedPrompt, context, intent.workflowId)
            AgentIntent.RespondWithProviderConversation -> respondWithProviderConversation(trimmedPrompt, context)
            AgentIntent.ExplainCapabilities -> explainCapabilities(trimmedPrompt, context)
        }
        val result = rawResult.copy(planningTrace = plannerOutput.planningTrace)
        auditTrailRepository.logAction(
            action = "agent.turn",
            result = plannerOutput.auditResult,
            details = buildString {
                append("Mode: ")
                append(plannerOutput.planningTrace.mode.name)
                append(" | Plan: ")
                append(plannerOutput.planningTrace.summary)
                if (plannerOutput.planningTrace.capabilities.isNotEmpty()) {
                    append(" | Capabilities: ")
                    append(plannerOutput.planningTrace.capabilities.joinToString())
                }
                if (plannerOutput.planningTrace.resources.isNotEmpty()) {
                    append(" | Resources: ")
                    append(plannerOutput.planningTrace.resources.joinToString())
                }
                if (context.modelPreference.preferredProviderLabel != null) {
                    append(" | Model preference: ")
                    append(context.modelPreference.preferredProviderLabel)
                    context.modelPreference.preferredModel?.let { model ->
                        append(" / ")
                        append(model)
                    }
                    append(" (enabled ")
                    append(context.modelPreference.enabledProviderIds.size)
                    append(", configured ")
                    append(context.modelPreference.configuredProviderIds.size)
                    append(")")
                }
                append(" | ")
                append("Prompt: ")
                append(trimmedPrompt.take(maxAuditPromptLength))
                append(" | ")
                append(result.reply.take(maxAuditReplyLength))
            },
        )
        return result
    }

    private fun attachmentOnlyReply(attachments: List<ChatAttachment>): AgentTurnResult {
        val summary = chatAttachmentSummaryLine(attachments)
        return AgentTurnResult(
            reply = "I received $summary Tell me whether to summarize, compare, transfer, or organize them next.",
            destination = AgentDestination.Chat,
            taskTitle = "Review attachments",
            taskSummary = summary,
            taskStatus = AgentTaskStatus.Succeeded,
        )
    }

    private suspend fun approvePendingApproval(
        prompt: String,
        context: AgentTurnContext,
        approvalId: String?,
    ): AgentTurnResult {
        val pendingApprovals = context.approvals.count { it.status == ApprovalInboxStatus.Pending }
        return when (val result = phoneAgentActionCoordinator.approveApproval(approvalId)) {
            is ApprovalActionResult.Missing -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    if (pendingApprovals == 0) {
                        "지금 승인할 요청이 없습니다. 먼저 승인이 필요한 작업을 요청해 주세요."
                    } else {
                        "지정한 승인 요청을 찾지 못했어요. Dashboard에서 승인 대기 ${pendingApprovals}건을 확인해 주세요."
                    }
                } else {
                    if (pendingApprovals == 0) {
                        "There is nothing to approve right now. Ask me to prepare a task that needs approval first."
                    } else {
                        "I could not find that approval request. Check Dashboard for the $pendingApprovals pending item(s)."
                    }
                },
                destination = if (pendingApprovals == 0) AgentDestination.Chat else AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = approvalsApproveActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "승인할 요청을 찾지 못했습니다."
                } else {
                    "No pending approval matched the request."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
            )
            is ApprovalActionResult.AlreadyResolved -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "${result.approval.title} 요청은 이미 ${approvalStatusLabel(prompt, result.approval.status)} 상태입니다."
                } else {
                    "${result.approval.title} is already ${approvalStatusLabel(prompt, result.approval.status)}."
                },
                destination = AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = approvalsApproveActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "승인 요청은 이미 처리된 상태였습니다."
                } else {
                    "The approval request had already been resolved."
                },
            )
            is ApprovalActionResult.Completed -> {
                val execution = result.execution
                val linkedTask = execution.linkedTask
                val organizeExecution = execution.organizeExecution
                AgentTurnResult(
                    reply = approvalReply(
                        prompt = prompt,
                        approval = execution.approval,
                        linkedTask = linkedTask,
                        organizeExecution = organizeExecution,
                        transferQueuedFileCount = execution.transferQueuedFileCount,
                        transferTargetLabel = execution.transferTargetLabel,
                    ),
                    destination = if (linkedTask?.status == AgentTaskStatus.WaitingUser || linkedTask?.status == AgentTaskStatus.Failed) {
                        AgentDestination.Dashboard
                    } else {
                        AgentDestination.Chat
                    },
                    taskTitle = taskTitle(prompt),
                    taskActionKey = approvalsApproveActionKey,
                    taskSummary = if (prefersKorean(prompt)) {
                        "채팅에서 승인 요청을 승인했습니다."
                    } else {
                        "Approved the request from the chat-first shell."
                    },
                    refreshedFileIndexState = execution.refreshedFileIndexState,
                    fileActionNote = organizeExecution?.result?.summaryWithStatusNote ?: linkedTask?.summary,
                    persistedOrganizeExecution = organizeExecution,
                    trackedTask = linkedTask,
                )
            }
        }
    }

    private suspend fun denyPendingApproval(
        prompt: String,
        context: AgentTurnContext,
        approvalId: String?,
    ): AgentTurnResult {
        val pendingApprovals = context.approvals.count { it.status == ApprovalInboxStatus.Pending }
        return when (val result = phoneAgentActionCoordinator.denyApproval(approvalId)) {
            is ApprovalActionResult.Missing -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    if (pendingApprovals == 0) {
                        "거절할 승인 요청이 없습니다."
                    } else {
                        "지정한 승인 요청을 찾지 못했어요. Dashboard에서 승인 대기 ${pendingApprovals}건을 확인해 주세요."
                    }
                } else {
                    if (pendingApprovals == 0) {
                        "There is no pending approval to deny right now."
                    } else {
                        "I could not find that approval request. Check Dashboard for the $pendingApprovals pending item(s)."
                    }
                },
                destination = if (pendingApprovals == 0) AgentDestination.Chat else AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = approvalsDenyActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "거절할 승인 요청을 찾지 못했습니다."
                } else {
                    "No pending approval matched the deny request."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
            )
            is ApprovalActionResult.AlreadyResolved -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "${result.approval.title} 요청은 이미 ${approvalStatusLabel(prompt, result.approval.status)} 상태입니다."
                } else {
                    "${result.approval.title} is already ${approvalStatusLabel(prompt, result.approval.status)}."
                },
                destination = AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = approvalsDenyActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "승인 요청은 이미 처리된 상태였습니다."
                } else {
                    "The approval request had already been resolved."
                },
            )
            is ApprovalActionResult.Completed -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "${result.execution.approval.title} 요청을 거절했고 연결된 작업은 취소 상태로 정리했습니다."
                } else {
                    "I denied ${result.execution.approval.title} and marked the linked task as cancelled."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = approvalsDenyActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "채팅에서 승인 요청을 거절했습니다."
                } else {
                    "Denied the approval request from chat."
                },
                trackedTask = result.execution.linkedTask,
            )
        }
    }

    private suspend fun retryAgentTask(
        prompt: String,
        context: AgentTurnContext,
        taskId: String?,
    ): AgentTurnResult {
        val retryableCount = context.tasks.count { task ->
            task.actionKey == filesOrganizeActionKey &&
                (
                    task.status == AgentTaskStatus.RetryScheduled ||
                        task.status == AgentTaskStatus.Failed ||
                        task.status == AgentTaskStatus.WaitingResource
                    ) &&
                task.maxRetryCount > 0
        }
        return when (val result = phoneAgentActionCoordinator.retryTask(taskId)) {
            is TaskRetryActionResult.Missing -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    if (retryableCount == 0) {
                        "지금 바로 재시도할 organize task가 없습니다. 실패한 작업이 생기면 그때 다시 요청해 주세요."
                    } else {
                        "지정한 task를 찾지 못했어요. Dashboard에서 재시도 가능한 task ${retryableCount}건을 확인해 주세요."
                    }
                } else {
                    if (retryableCount == 0) {
                        "There is no retryable organize task right now. Ask again after a task actually fails."
                    } else {
                        "I could not find that task. Check Dashboard for the $retryableCount retryable task(s)."
                    }
                },
                destination = if (retryableCount == 0) AgentDestination.Chat else AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = manualTaskRetryActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "재시도 가능한 task를 찾지 못했습니다."
                } else {
                    "No retryable task matched the request."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
            )
            is TaskRetryActionResult.NotEligible -> AgentTurnResult(
                reply = retryNotEligibleReply(prompt, result.task),
                destination = AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = manualTaskRetryActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "선택한 task는 지금 재시도할 수 없습니다."
                } else {
                    "The selected task is not eligible for manual retry right now."
                },
                taskStatus = if (result.task.status == AgentTaskStatus.WaitingUser) {
                    AgentTaskStatus.WaitingUser
                } else {
                    AgentTaskStatus.Failed
                },
            )
            is TaskRetryActionResult.Completed -> AgentTurnResult(
                reply = retryReply(
                    prompt = prompt,
                    task = result.execution.task,
                    organizeExecution = result.execution.organizeExecution,
                ),
                destination = if (result.execution.task.status == AgentTaskStatus.Succeeded) {
                    AgentDestination.Chat
                } else {
                    AgentDestination.Dashboard
                },
                taskTitle = taskTitle(prompt),
                taskActionKey = manualTaskRetryActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "채팅에서 task 재시도를 요청했습니다."
                } else {
                    "Requested a manual task retry from chat."
                },
                refreshedFileIndexState = result.execution.refreshedFileIndexState,
                fileActionNote = result.execution.organizeExecution?.result?.summaryWithStatusNote ?: result.execution.task.summary,
                persistedOrganizeExecution = result.execution.organizeExecution,
                trackedTask = result.execution.task,
            )
        }
    }

    private suspend fun summarizeIndexedFiles(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val (indexedState, refreshed) = ensureIndexedFiles(context.fileIndexState)
        if (indexedState.indexedItems.isEmpty()) {
            return AgentTurnResult(
                reply = noIndexedFilesReply(prompt),
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = filesSummarizeActionKey,
                taskSummary = noIndexedFilesNote(prompt),
                taskStatus = AgentTaskStatus.WaitingResource,
                refreshedFileIndexState = refreshed,
                fileActionNote = noIndexedFilesNote(prompt),
            )
        }

        val summary = fileGraphActionPlanner.summarize(indexedState.indexedItems)
        return AgentTurnResult(
            reply = summaryReply(prompt, summary, indexedState),
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = filesSummarizeActionKey,
            taskSummary = summary.headline,
            refreshedFileIndexState = refreshed,
            fileSummary = summary,
            taskStatus = AgentTaskStatus.Succeeded,
            fileActionNote = summary.headline,
        )
    }

    private fun planBrowserResearch(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val brief = buildBrowserResearchBrief(prompt)
        val stagedCloudCount = context.cloudDriveConnections.count {
            it.status == CloudDriveConnectionStatus.Staged
        }
        val connectedCloudCount = context.cloudDriveConnections.count {
            it.status == CloudDriveConnectionStatus.Connected
        }
        val stagedExternalEndpointCount = context.externalEndpoints.count {
            it.status == ExternalEndpointStatus.Staged
        }
        val connectedExternalEndpointCount = context.externalEndpoints.count {
            it.status == ExternalEndpointStatus.Connected
        }
        val connectedDeliveryChannels = context.deliveryChannels.count {
            it.status == DeliveryChannelStatus.Connected
        }
        val stagedDeliveryChannels = context.deliveryChannels.count {
            it.status == DeliveryChannelStatus.Staged
        }
        val providerLabel = context.modelPreference.preferredProviderLabel?.let { provider ->
            context.modelPreference.preferredModel?.let { model ->
                "$provider / $model"
            } ?: provider
        } ?: if (prefersKorean(prompt)) {
            "미선택"
        } else {
            "not selected"
        }
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("browser research skeleton으로 요청을 기록했어요. ")
                    append("핵심 질의는 \"${brief.query}\" 이고, 전달 방식은 ${brief.requestedDelivery} 기준으로 해석했습니다. ")
                    append("현재 브라우저 자동화와 웹 수집 capability는 아직 실제 executor가 없어서 바로 실행되지는 않습니다. ")
                    append("cloud connector는 staged ${stagedCloudCount}개, mock-ready ${connectedCloudCount}개입니다. ")
                    append("MCP/API endpoint는 staged ${stagedExternalEndpointCount}개, mock-ready ${connectedExternalEndpointCount}개입니다. ")
                    append("delivery channel은 staged ${stagedDeliveryChannels}개, mock-ready ${connectedDeliveryChannels}개이고, 기본 모델 선호도는 $providerLabel 입니다.")
                    if (brief.recurringHint) {
                        append(" 반복 실행 힌트도 감지했기 때문에 automation scheduler skeleton 단계와 연결하기 좋은 요청입니다.")
                    }
                }
            } else {
                buildString {
                    append("I captured this as a browser research skeleton request. ")
                    append("The core query is \"${brief.query}\" and the requested delivery channel was interpreted as ${brief.requestedDelivery}. ")
                    append("Browser automation and live web collection do not have a real executor yet, so I cannot run it end-to-end today. ")
                    append("Cloud connectors are staged ${stagedCloudCount} / mock-ready ${connectedCloudCount}. ")
                    append("MCP/API endpoints are staged ${stagedExternalEndpointCount} / mock-ready ${connectedExternalEndpointCount}. ")
                    append("Delivery channels are staged ${stagedDeliveryChannels} / mock-ready ${connectedDeliveryChannels}, and the current model preference is $providerLabel.")
                    if (brief.recurringHint) {
                        append(" I also detected a recurring hint, which makes this a good candidate for the upcoming automation scheduler skeleton.")
                    }
                }
            },
            destination = AgentDestination.Settings,
            taskTitle = taskTitle(prompt),
            taskActionKey = browserResearchPlanActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "browser research skeleton task를 기록했고 필요한 자원 연결을 기다리는 상태로 남겼습니다."
            } else {
                "Recorded a browser research skeleton task and left it waiting for browser/web resource wiring."
            },
            taskStatus = AgentTaskStatus.WaitingResource,
        )
    }

    private suspend fun browseWebPage(
        prompt: String,
        context: AgentTurnContext,
        url: String,
    ): AgentTurnResult {
        val browserExecution = resolveBrowserExecution(context)
        if (browserExecution == null || browserExecution.deviceId == null || !browserExecution.canBrowseWebPages) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "연결된 companion MCP bridge에서 웹 페이지 접근 tool을 아직 찾지 못했어요. 먼저 MCP bridge discovery가 성공해야 URL 접근을 실행할 수 있습니다."
                } else {
                    "I could not find a connected companion MCP bridge with webpage access tools yet. MCP bridge discovery needs to succeed before I can open the URL."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = browserPageAccessActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "웹 페이지 접근에 필요한 MCP/browser tool 연결이 아직 준비되지 않았습니다."
                } else {
                    "Webpage access is still waiting for an MCP/browser bridge."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val toolName = when {
            browserExecution.toolNames.contains("browser.navigate") -> "browser.navigate"
            browserExecution.toolNames.contains("browser.extract") -> "browser.extract"
            else -> null
        }
        if (toolName == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "MCP bridge는 연결됐지만 browser.navigate/browser.extract tool inventory가 아직 광고되지 않았어요."
                } else {
                    "The MCP bridge is connected, but it still is not advertising browser.navigate or browser.extract."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = browserPageAccessActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "브라우저 실행 tool inventory가 아직 비어 있습니다."
                } else {
                    "Browser execution tools are not advertised yet."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val result = devicePairingRepository.callMcpTool(
            deviceId = browserExecution.deviceId,
            toolName = toolName,
            arguments = JSONObject()
                .put("url", url)
                .put("max_chars", webPagePreviewMaxChars),
        )
        return when (result.status) {
            CompanionMcpToolCallStatus.Completed -> AgentTurnResult(
                reply = webPageAccessReply(
                    prompt = prompt,
                    requestedUrl = url,
                    result = result,
                    bridgeLabel = browserExecution.label,
                ),
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = browserPageAccessActionKey,
                taskSummary = result.pageTitle ?: result.finalUrl ?: url,
            )
            CompanionMcpToolCallStatus.Failed -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "연결된 MCP/browser bridge로 $url 페이지 접근을 시도했지만 실패했어요. ${result.detail}"
                } else {
                    "I tried to open $url through the connected MCP/browser bridge, but it failed. ${result.detail}"
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = browserPageAccessActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "웹 페이지 접근 요청이 실패했습니다."
                } else {
                    "The webpage access request failed."
                },
                taskStatus = AgentTaskStatus.Failed,
            )
            CompanionMcpToolCallStatus.Misconfigured,
            CompanionMcpToolCallStatus.Skipped,
            -> AgentTurnResult(
                reply = result.detail.ifBlank {
                    if (prefersKorean(prompt)) {
                        "웹 페이지 접근에 필요한 companion MCP 설정이 아직 완료되지 않았어요."
                    } else {
                        "The companion MCP setup required for webpage access is still incomplete."
                    }
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = browserPageAccessActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "웹 페이지 접근을 위한 companion 연결이 필요합니다."
                } else {
                    "Webpage access still needs a reachable companion bridge."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }
    }

    private suspend fun planScheduledAutomation(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val goalPlan = planAgentGoal(prompt, context)
        val plan = buildScheduledAutomationPlan(prompt)
        val runSpec = buildScheduledAgentRunSpec(prompt)
        val record = scheduledAutomationRepository.createSkeleton(
            prompt = prompt,
            plan = plan,
            runSpecJson = encodeRunSpec(runSpec),
            blockedReason = goalPlan?.blockedReason,
        )
        val recordedCount = context.scheduledAutomations.size + 1
        val connectedDeliveryChannels = context.deliveryChannels.count {
            it.status == DeliveryChannelStatus.Connected
        }
        val browserLinked = containsAny(
            prompt.lowercase(),
            "browser",
            "browse",
            "web",
            "research",
            "news",
            "article",
            "브라우저",
            "웹",
            "조사",
            "검색",
            "뉴스",
            "기사",
        )
        val taskStatus = if (record.blockedReason.isNullOrBlank()) {
            AgentTaskStatus.Succeeded
        } else {
            AgentTaskStatus.WaitingResource
        }
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    goalPlan?.let {
                        append("${it.recipe.summary} task graph로 해석해 scheduled automation으로 기록했어요. ")
                        if (it.missingRequirements.isNotEmpty()) {
                            append("아직 필요한 연결: ${it.missingRequirements.joinToString { requirement -> requirement.label }}. ")
                        }
                    } ?: append("반복 작업을 scheduled automation으로 기록했어요. ")
                    append("주기는 ${record.scheduleLabel}, 전달 방식은 ${record.deliveryLabel}로 해석했습니다. ")
                    append("채팅에서 바로 '활성화해', '지금 실행해', '일시정지해'라고 이어서 제어할 수 있습니다. ")
                    append("현재 mock-ready delivery channel은 ${connectedDeliveryChannels}개이고, 기록된 automation은 총 ${recordedCount}건입니다.")
                    if (browserLinked) {
                        append(" 이 요청은 이후 browser/news research capability와 연결될 수 있게 남겨뒀습니다.")
                    }
                    record.blockedReason?.let {
                        append(" 현재는 $it")
                    }
                }
            } else {
                buildString {
                    goalPlan?.let {
                        append("I interpreted this as the ${it.recipe.summary} task graph and recorded it as a scheduled automation. ")
                        if (it.missingRequirements.isNotEmpty()) {
                            append("Missing requirements: ${it.missingRequirements.joinToString { requirement -> requirement.label }}. ")
                        }
                    } ?: append("I recorded this recurring request as a scheduled automation. ")
                    append("The schedule was interpreted as ${record.scheduleLabel} and the delivery channel as ${record.deliveryLabel}. ")
                    append("You can keep controlling it from chat by asking me to activate it, run it now, or pause it. ")
                    append("$connectedDeliveryChannels delivery channel(s) are currently mock-ready, and there are now $recordedCount recorded automation(s).")
                    if (browserLinked) {
                        append(" I also kept it aligned with the upcoming browser/news research capability.")
                    }
                    record.blockedReason?.let {
                        append(" It is currently blocked because $it")
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = scheduledAutomationPlanActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "${record.scheduleLabel} / ${record.deliveryLabel} automation을 기록했습니다."
            } else {
                "Recorded a ${record.scheduleLabel} / ${record.deliveryLabel} automation."
            },
            taskStatus = taskStatus,
        )
    }

    private suspend fun activateScheduledAutomation(
        prompt: String,
        context: AgentTurnContext,
        automationId: String?,
    ): AgentTurnResult {
        val automation = resolveScheduledAutomation(prompt, context, automationId)
        if (automation == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "활성화할 automation을 찾지 못했어요. 먼저 반복 작업을 기록하거나 Dashboard에서 automation 상태를 확인해 주세요."
                } else {
                    "I could not find an automation to activate. Record a recurring task first or check Dashboard."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = scheduledAutomationActivateActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "활성화할 automation이 없습니다."
                } else {
                    "No scheduled automation matched the activation request."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }
        if (!automation.blockedReason.isNullOrBlank()) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "${automation.title} automation은 아직 ${automation.blockedReason} 상태라 바로 활성화할 수 없어요. 필요한 연결을 먼저 채팅에서 진행해 주세요."
                } else {
                    "${automation.title} is still blocked because ${automation.blockedReason}, so I cannot activate it yet. Finish the missing connection step from chat first."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = scheduledAutomationActivateActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "blocked automation 때문에 활성화를 보류했습니다."
                } else {
                    "Activation is waiting on a blocked automation prerequisite."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }
        val updated = scheduledAutomationCoordinator.activateAutomation(automation.id) ?: automation
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("${updated.title} automation을 활성화했어요. ")
                    append("다음 실행은 ${updated.nextRunAtLabel ?: "곧"} 예정입니다. ")
                    append("이후에는 채팅에서 '지금 실행해' 또는 '일시정지해'라고 이어서 제어할 수 있습니다.")
                }
            } else {
                buildString {
                    append("I activated ${updated.title}. ")
                    append("The next run is ${updated.nextRunAtLabel ?: "scheduled soon"}. ")
                    append("You can keep controlling it from chat by asking me to run it now or pause it.")
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = scheduledAutomationActivateActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "채팅에서 automation 일정을 활성화했습니다."
            } else {
                "Activated the scheduled automation from chat."
            },
        )
    }

    private suspend fun pauseScheduledAutomation(
        prompt: String,
        context: AgentTurnContext,
        automationId: String?,
    ): AgentTurnResult {
        val automation = resolveScheduledAutomation(prompt, context, automationId)
        if (automation == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "일시정지할 automation을 찾지 못했어요."
                } else {
                    "I could not find an automation to pause."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = scheduledAutomationPauseActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "일시정지할 automation이 없습니다."
                } else {
                    "No scheduled automation matched the pause request."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }
        val updated = scheduledAutomationCoordinator.pauseAutomation(automation.id) ?: automation
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "${updated.title} automation을 일시정지했어요. 다시 시작하려면 채팅에서 활성화해 달라고 말해 주세요."
            } else {
                "I paused ${updated.title}. Ask me in chat to activate it again whenever you want to resume the schedule."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = scheduledAutomationPauseActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "채팅에서 automation 일정을 일시정지했습니다."
            } else {
                "Paused the scheduled automation from chat."
            },
        )
    }

    private suspend fun runScheduledAutomationNow(
        prompt: String,
        context: AgentTurnContext,
        automationId: String?,
    ): AgentTurnResult {
        val automation = resolveScheduledAutomation(prompt, context, automationId)
        if (automation == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "즉시 실행할 automation을 찾지 못했어요."
                } else {
                    "I could not find an automation to run right now."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = scheduledAutomationRunNowActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "즉시 실행할 automation이 없습니다."
                } else {
                    "No scheduled automation matched the immediate run request."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }
        val updated = scheduledAutomationCoordinator.runAutomationNow(automation.id) ?: automation
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("${updated.title} automation을 바로 실행했어요. ")
                    append("최근 실행 시각은 ${updated.lastRunAtLabel ?: "방금"}이고, 다음 일정은 ${updated.nextRunAtLabel ?: "현재 상태 기준으로 유지"} 입니다.")
                    updated.lastResultSummary?.let {
                        append(" ")
                        append(it)
                    }
                    updated.deliveryReceiptLabel?.let {
                        append(" 전달 결과: ")
                        append(it)
                    }
                }
            } else {
                buildString {
                    append("I ran ${updated.title} immediately. ")
                    append("The last run was ${updated.lastRunAtLabel ?: "just now"}, and the next schedule is ${updated.nextRunAtLabel ?: "preserved from the current state"}.")
                    updated.lastResultSummary?.let {
                        append(" ")
                        append(it)
                    }
                    updated.deliveryReceiptLabel?.let {
                        append(" Delivery: ")
                        append(it)
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = scheduledAutomationRunNowActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "채팅에서 automation을 즉시 실행했습니다."
            } else {
                "Executed the scheduled automation immediately from chat."
            },
        )
    }

    private suspend fun connectMcpBridge(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val targetDevice = resolveMcpCompanion(context)
        if (targetDevice == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "Direct HTTP companion이 아직 없어 MCP bridge를 실제로 연결할 수 없어요. 채팅에서 companion 상태를 확인하거나, 필요할 때만 Settings에서 페어링을 마치면 됩니다."
                } else {
                    "There is no Direct HTTP companion ready yet, so I cannot connect the MCP bridge for real. Check companion readiness from chat, and only open Settings if you need to pair one manually."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mcpBridgeConnectActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "Direct HTTP companion이 없어 MCP bridge 연결이 보류되었습니다."
                } else {
                    "MCP bridge connection is waiting for a Direct HTTP companion."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val discovery = devicePairingRepository.discoverMcpBridge(targetDevice.id)
        if (discovery.status != McpBridgeDiscoveryStatus.Ready) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    buildString {
                        append("${targetDevice.name}에서 MCP bridge discovery를 완료하지 못했어요. ")
                        append(discovery.summary)
                        append(" ")
                        append(discovery.detail)
                    }
                } else {
                    buildString {
                        append("I could not finish MCP bridge discovery against ${targetDevice.name}. ")
                        append(discovery.summary)
                        append(" ")
                        append(discovery.detail)
                    }
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mcpBridgeConnectActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "실제 MCP bridge discovery가 아직 완료되지 않았습니다."
                } else {
                    "The live MCP bridge discovery is not ready yet."
                },
                taskStatus = if (discovery.status == McpBridgeDiscoveryStatus.Unreachable) {
                    AgentTaskStatus.Failed
                } else {
                    AgentTaskStatus.WaitingResource
                },
            )
        }

        externalEndpointRepository.markConnected(
            mcpBridgeEndpointId,
            ExternalEndpointConnectionSnapshot(
                endpointLabel = discovery.serverLabel ?: targetDevice.name,
                summary = discovery.summary,
                transportLabel = discovery.transportLabel,
                authLabel = discovery.authLabel,
                toolNames = discovery.toolNames,
                toolSchemas = discovery.toolSchemas,
                skillBundles = discovery.skillBundles,
                workflowIds = discovery.workflowIds,
                healthDetails = discovery.detail,
            ),
        )
        externalEndpointRepository.refresh()
        val endpoint = externalEndpointRepository.profiles.value.firstOrNull {
            it.endpointId == mcpBridgeEndpointId
        }
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("${targetDevice.name}에서 MCP bridge를 연결했어요.")
                    endpoint?.transportLabel?.let {
                        append(" transport는 ")
                        append(it)
                        append(" 입니다.")
                    }
                    if (endpoint?.toolNames?.isNotEmpty() == true) {
                        append(" 현재 ")
                        append(endpoint.toolNames.size)
                        append("개 MCP tool이 광고돼 있어요.")
                    }
                    if (endpoint?.skillBundles?.isNotEmpty() == true) {
                        append(" skill bundle은 ")
                        append(endpoint.skillBundles.size)
                        append("개입니다.")
                    }
                    append(" 이제 채팅에서 MCP status, MCP tools, MCP skill 업데이트를 바로 요청할 수 있습니다.")
                }
            } else {
                buildString {
                    append("I connected the MCP bridge from ${targetDevice.name}.")
                    endpoint?.transportLabel?.let {
                        append(" The transport is ")
                        append(it)
                        append(".")
                    }
                    if (endpoint?.toolNames?.isNotEmpty() == true) {
                        append(" It is advertising ")
                        append(endpoint.toolNames.size)
                        append(" MCP tool(s).")
                    }
                    if (endpoint?.skillBundles?.isNotEmpty() == true) {
                        append(" ")
                        append(endpoint.skillBundles.size)
                        append(" skill bundle(s) are available.")
                    }
                    append(" You can now ask for MCP status, MCP tools, or an MCP skill sync from chat.")
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = mcpBridgeConnectActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "채팅에서 companion MCP bridge discovery를 완료했습니다."
            } else {
                "Completed companion-backed MCP bridge discovery from chat."
            },
        )
    }

    private suspend fun syncMcpSkills(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        refreshMcpBridgeFromCompanion(context)
        val mcpEndpoint = refreshMcpEndpoint()
        val syncResult = mcpSkillRepository.syncFromMcpBridge(mcpEndpoint)
        if (syncResult.updatedSkillCount > 0) {
            externalEndpointRepository.markConnected(
                mcpBridgeEndpointId,
                ExternalEndpointConnectionSnapshot(
                    syncedSkillCount = syncResult.updatedSkillCount,
                    lastSyncAtEpochMillis = syncResult.syncedAtEpochMillis,
                    summary = syncResult.summary,
                    healthDetails = if (prefersKorean(prompt)) {
                        "채팅에서 동기화한 skill 카탈로그가 connector 프로필에 반영됐습니다."
                    } else {
                        "The connector profile now reflects the latest skill sync from chat."
                    },
                ),
            )
        }
        externalEndpointRepository.refresh()
        val refreshedMcpEndpoint = externalEndpointRepository.profiles.value.firstOrNull {
            it.endpointId == mcpBridgeEndpointId
        }
        val installedSkills = mcpSkillRepository.skills.value
        val topSkills = installedSkills.take(3)
        val failedToSync = syncResult.updatedSkillCount == 0
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append(
                        if (failedToSync) {
                            "아직 MCP skill을 동기화하지 못했어요. ${syncResult.summary}"
                        } else {
                            "${syncResult.sourceLabel ?: "MCP bridge"}에서 MCP skill ${syncResult.updatedSkillCount}개를 동기화했어요. "
                        },
                    )
                    if (!failedToSync && syncResult.toolCount > 0) {
                        append("광고된 MCP tool은 ${syncResult.toolCount}개입니다. ")
                    }
                    if (topSkills.isNotEmpty()) {
                        append("현재 스킬은 ")
                        append(topSkills.joinToString { "${it.title} (${it.versionLabel})" })
                        append(" 순서로 기록돼 있습니다.")
                    } else {
                        append("먼저 MCP bridge를 연결한 뒤 다시 요청해 주세요.")
                    }
                    refreshedMcpEndpoint?.lastSyncAtLabel?.let {
                        append(" 마지막 동기화는 $it 입니다.")
                    }
                }
            } else {
                buildString {
                    append(
                        if (failedToSync) {
                            "I could not sync MCP skills yet. ${syncResult.summary}"
                        } else {
                            "I synced ${syncResult.updatedSkillCount} MCP skill(s) from ${syncResult.sourceLabel ?: "the MCP bridge"}. "
                        },
                    )
                    if (!failedToSync && syncResult.toolCount > 0) {
                        append("The connector advertised ${syncResult.toolCount} MCP tool(s). ")
                    }
                    if (topSkills.isNotEmpty()) {
                        append("The current catalog includes ")
                        append(topSkills.joinToString { "${it.title} (${it.versionLabel})" })
                        append(".")
                    } else {
                        append("Connect the MCP bridge first and ask again.")
                    }
                    refreshedMcpEndpoint?.lastSyncAtLabel?.let {
                        append(" Last sync: $it.")
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = mcpSkillSyncActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                if (failedToSync) {
                    "MCP skill 동기화가 아직 준비되지 않았습니다."
                } else {
                    "채팅에서 MCP skill 카탈로그를 동기화했습니다."
                }
            } else {
                if (failedToSync) {
                    "The MCP skill sync is not ready yet."
                } else {
                    "Synced the MCP skill catalog from chat."
                }
            },
            taskStatus = if (failedToSync) AgentTaskStatus.WaitingResource else AgentTaskStatus.Succeeded,
        )
    }

    private suspend fun showMcpSkills(
        prompt: String,
    ): AgentTurnResult {
        externalEndpointRepository.refresh()
        mcpSkillRepository.refresh()
        val installedSkills = mcpSkillRepository.skills.value
        val endpoint = externalEndpointRepository.profiles.value.firstOrNull {
            it.endpointId == mcpBridgeEndpointId
        }
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                if (installedSkills.isEmpty()) {
                    "아직 설치된 MCP skill이 없습니다. 먼저 MCP bridge를 연결하고 skill 업데이트를 요청해 주세요."
                } else {
                    buildString {
                        append("현재 MCP skill ${installedSkills.size}개가 설치되어 있어요.\n")
                        endpoint?.lastSyncAtLabel?.let {
                            append("마지막 skill sync: $it\n")
                        }
                        append(
                            installedSkills.joinToString(separator = "\n") { skill ->
                                "- ${skill.title} ${skill.versionLabel}: ${skill.summary}"
                            },
                        )
                    }
                }
            } else {
                if (installedSkills.isEmpty()) {
                    "There are no installed MCP skills yet. Connect the MCP bridge and ask me to update MCP skills first."
                } else {
                    buildString {
                        append("There are ${installedSkills.size} installed MCP skill(s).\n")
                        endpoint?.lastSyncAtLabel?.let {
                            append("Last skill sync: $it\n")
                        }
                        append(
                            installedSkills.joinToString(separator = "\n") { skill ->
                                "- ${skill.title} ${skill.versionLabel}: ${skill.summary}"
                            },
                        )
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = mcpSkillShowActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "현재 MCP skill 카탈로그를 요약했습니다."
            } else {
                "Summarized the installed MCP skills."
            },
        )
    }

    private suspend fun showMcpConnectorStatus(
        prompt: String,
    ): AgentTurnResult {
        val endpoint = refreshMcpEndpoint()
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                if (endpoint == null || endpoint.status != ExternalEndpointStatus.Connected) {
                    "아직 연결된 MCP bridge가 없습니다. 먼저 MCP bridge 연결을 요청해 주세요."
                } else {
                    buildString {
                        append("${endpoint.endpointLabel ?: endpoint.displayName} 상태입니다.\n")
                        append("transport: ${endpoint.transportLabel ?: "미기록"}\n")
                        append("auth: ${endpoint.authLabel ?: "미기록"}\n")
                        append("advertised tools: ${endpoint.toolNames.size}개\n")
                        append("tool schemas: ${endpoint.toolSchemas.size}개\n")
                        append("skill bundles: ${endpoint.skillBundles.size}개\n")
                        append("workflows: ${endpoint.workflowIds.size}개\n")
                        append("synced skills: ${endpoint.syncedSkillCount}개")
                        endpoint.lastSyncAtLabel?.let {
                            append("\nlast sync: ")
                            append(it)
                        }
                        endpoint.healthDetails?.let {
                            append("\n")
                            append(it)
                        }
                    }
                }
            } else {
                if (endpoint == null || endpoint.status != ExternalEndpointStatus.Connected) {
                    "There is no connected MCP bridge yet. Ask me to connect the MCP bridge first."
                } else {
                    buildString {
                        append("MCP connector status for ${endpoint.endpointLabel ?: endpoint.displayName}.\n")
                        append("Transport: ${endpoint.transportLabel ?: "not recorded"}\n")
                        append("Auth: ${endpoint.authLabel ?: "not recorded"}\n")
                        append("Advertised tools: ${endpoint.toolNames.size}\n")
                        append("Tool schemas: ${endpoint.toolSchemas.size}\n")
                        append("Skill bundles: ${endpoint.skillBundles.size}\n")
                        append("Workflows: ${endpoint.workflowIds.size}\n")
                        append("Synced skills: ${endpoint.syncedSkillCount}")
                        endpoint.lastSyncAtLabel?.let {
                            append("\nLast sync: ")
                            append(it)
                        }
                        endpoint.healthDetails?.let {
                            append("\n")
                            append(it)
                        }
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = mcpConnectorStatusActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "MCP connector 상태를 채팅에 요약했습니다."
            } else {
                "Summarized the MCP connector status in chat."
            },
        )
    }

    private suspend fun showMcpTools(
        prompt: String,
    ): AgentTurnResult {
        val endpoint = refreshMcpEndpoint()
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                if (endpoint == null || endpoint.toolNames.isEmpty()) {
                    "광고된 MCP tool이 아직 없습니다. 먼저 MCP bridge를 연결해 주세요."
                } else {
                    buildString {
                        append("현재 MCP tool ${endpoint.toolNames.size}개입니다.\n")
                        append(
                            endpoint.toolNames.joinToString(separator = "\n") { toolName ->
                                val schema = endpoint.toolSchemas.firstOrNull { it.name == toolName }
                                buildString {
                                    append("- ")
                                    append(toolName)
                                    schema?.let {
                                        append(": ")
                                        append(it.summary)
                                        it.inputSchemaSummary?.let { inputSummary ->
                                            append(" [")
                                            append(inputSummary)
                                            append("]")
                                        }
                                        if (it.requiresConfirmation) {
                                            append(" (approval)")
                                        }
                                    }
                                }
                            },
                        )
                        if (endpoint.skillBundles.isNotEmpty()) {
                            append("\n\nskill bundles:\n")
                            append(
                                endpoint.skillBundles.joinToString(separator = "\n") { bundle ->
                                    "- ${bundle.title}: ${bundle.summary}"
                                },
                            )
                        }
                    }
                }
            } else {
                if (endpoint == null || endpoint.toolNames.isEmpty()) {
                    "There are no advertised MCP tools yet. Connect the MCP bridge first."
                } else {
                    buildString {
                        append("The MCP connector is advertising ${endpoint.toolNames.size} tool(s).\n")
                        append(
                            endpoint.toolNames.joinToString(separator = "\n") { toolName ->
                                val schema = endpoint.toolSchemas.firstOrNull { it.name == toolName }
                                buildString {
                                    append("- ")
                                    append(toolName)
                                    schema?.let {
                                        append(": ")
                                        append(it.summary)
                                        it.inputSchemaSummary?.let { inputSummary ->
                                            append(" [")
                                            append(inputSummary)
                                            append("]")
                                        }
                                        if (it.requiresConfirmation) {
                                            append(" (approval)")
                                        }
                                    }
                                }
                            },
                        )
                        if (endpoint.skillBundles.isNotEmpty()) {
                            append("\n\nSkill bundles:\n")
                            append(
                                endpoint.skillBundles.joinToString(separator = "\n") { bundle ->
                                    "- ${bundle.title}: ${bundle.summary}"
                                },
                            )
                        }
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = mcpToolsShowActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "MCP tool inventory를 채팅에 요약했습니다."
            } else {
                "Summarized the MCP tool inventory in chat."
            },
        )
    }

    private suspend fun planCodeGeneration(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val plan = buildCodeGenerationProjectPlan(
            prompt = prompt,
            companionAvailable = context.pairedDevices.isNotEmpty(),
        )
        val configuredProviderCount = context.modelPreference.configuredProviderIds.size
        val connectedExternalEndpoints = context.externalEndpoints.count {
            it.status == ExternalEndpointStatus.Connected
        }
        val connectedDeliveryChannels = context.deliveryChannels.count {
            it.status == DeliveryChannelStatus.Connected
        }
        return runCatching {
            val artifact = codeGenerationWorkspaceExecutor.generateScaffold(
                prompt = prompt,
                plan = plan,
            )
            val record = codeGenerationProjectRepository.createProject(
                prompt = prompt,
                plan = plan,
                artifact = artifact,
            )
            AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    buildString {
                        append("phone-local code scaffold를 생성했어요. ")
                        append("대상은 ${record.targetLabel}, 작업 공간은 ${record.workspaceLabel}, 예상 출력은 ${record.outputLabel}입니다. ")
                        append("${record.generatedFileCount}개 파일을 ${record.workspacePath?.let(::compactCodeGenerationPath) ?: "workspace"}에 만들었고, 시작 파일은 ${record.entryFilePath?.let(::compactCodeGenerationPath) ?: "README.md"}입니다. ")
                        append("현재 구성된 provider credential은 ${configuredProviderCount}개, mock-ready MCP/API endpoint는 ${connectedExternalEndpoints}개, mock-ready delivery channel은 ${connectedDeliveryChannels}개입니다. ")
                        append("Dashboard에서 이 초안을 계속 추적하고 다음 빌드 단계로 이어갈 수 있습니다.")
                    }
                } else {
                    buildString {
                        append("I generated a phone-local code scaffold. ")
                        append("The target is ${record.targetLabel}, the workspace is ${record.workspaceLabel}, and the expected output is ${record.outputLabel}. ")
                        append("I wrote ${record.generatedFileCount} file(s) into ${record.workspacePath?.let(::compactCodeGenerationPath) ?: "the workspace"}, and the starting file is ${record.entryFilePath?.let(::compactCodeGenerationPath) ?: "README.md"}. ")
                        append("There are ${configuredProviderCount} configured provider credential(s), ${connectedExternalEndpoints} mock-ready MCP/API endpoint(s), and ${connectedDeliveryChannels} mock-ready delivery channel(s) available for the next iteration. ")
                        append("You can keep tracking this draft from Dashboard.")
                    }
                },
                destination = AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = codeGenerationPlanActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "phone-local code scaffold를 생성했고 Dashboard에서 추적할 수 있게 기록했습니다."
                } else {
                    "Generated a phone-local code scaffold and recorded it for Dashboard tracking."
                },
                taskStatus = AgentTaskStatus.Succeeded,
            )
        }.getOrElse { error ->
            val record = codeGenerationProjectRepository.createProject(
                prompt = prompt,
                plan = plan,
            )
            codeGenerationProjectRepository.setStatus(
                projectId = record.id,
                status = CodeGenerationProjectStatus.Blocked,
            )
            AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    buildString {
                        append("code generation 요청은 기록했지만 phone-local scaffold 생성은 실패했습니다. ")
                        append("대상은 ${record.targetLabel}, 작업 공간은 ${record.workspaceLabel}, 예상 출력은 ${record.outputLabel}로 남겨뒀고, 실패 이유는 ${error.message ?: error::class.java.simpleName} 입니다. ")
                        append("Dashboard에서 blocked 상태로 추적하면서 다음 executor 복구를 이어갈 수 있습니다.")
                    }
                } else {
                    buildString {
                        append("I recorded the code generation request, but the phone-local scaffold generation failed. ")
                        append("The target is ${record.targetLabel}, the workspace is ${record.workspaceLabel}, and the expected output is ${record.outputLabel}. The failure reason was ${error.message ?: error::class.java.simpleName}. ")
                        append("The project is left blocked on Dashboard so the next executor pass can recover it.")
                    }
                },
                destination = AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = codeGenerationPlanActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "code generation project는 기록했지만 local scaffold 생성에 실패해 blocked 상태로 남겼습니다."
                } else {
                    "Recorded the code generation project, but local scaffold generation failed and the project was left blocked."
                },
                taskStatus = AgentTaskStatus.Failed,
            )
        }
    }

    private suspend fun organizeIndexedFiles(
        prompt: String,
        context: AgentTurnContext,
        strategy: FileOrganizeStrategy,
    ): AgentTurnResult {
        val (indexedState, refreshed) = ensureIndexedFiles(context.fileIndexState)
        if (indexedState.indexedItems.isEmpty()) {
            return AgentTurnResult(
                reply = noIndexedFilesReply(prompt),
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = filesOrganizeActionKey,
                taskSummary = noIndexedFilesNote(prompt),
                taskStatus = AgentTaskStatus.WaitingResource,
                refreshedFileIndexState = refreshed,
                fileActionNote = noIndexedFilesNote(prompt),
            )
        }

        val plan = fileGraphActionPlanner.planOrganize(
            items = indexedState.indexedItems,
            strategy = strategy,
        )
        val approvalRequest = approvalInboxRepository.submitOrganizeApproval(
            plan = plan,
            items = indexedState.indexedItems,
        )
        val reply = if (approvalRequest == null) {
            if (prefersKorean(prompt)) {
                "정리 계획은 만들었지만 승인 요청으로 올릴 step이 없어서 여기서 멈췄어요. Settings에서 인덱싱 상태를 먼저 확인해 주세요."
            } else {
                "I drafted an organize plan, but there were no actionable steps to submit for approval. Check indexing in Settings first."
            }
        } else {
            organizeApprovalReply(prompt, plan)
        }
        return AgentTurnResult(
            reply = reply,
            destination = if (approvalRequest == null) AgentDestination.Settings else AgentDestination.Dashboard,
            taskTitle = taskTitle(prompt),
            taskActionKey = filesOrganizeActionKey,
            taskSummary = if (approvalRequest == null) {
                if (prefersKorean(prompt)) {
                    "정리 계획은 준비됐지만 승인 요청을 만들지 못했습니다."
                } else {
                    "The organize plan was prepared, but no approval request was created."
                }
            } else {
                if (prefersKorean(prompt)) {
                    "정리 approval이 생성됐고 사용자 검토를 기다리는 중입니다."
                } else {
                    "An organize approval request was created and is now waiting for user review."
                }
            },
            taskStatus = if (approvalRequest == null) {
                AgentTaskStatus.Failed
            } else {
                AgentTaskStatus.WaitingUser
            },
            taskMaxRetryCount = if (approvalRequest == null) 0 else organizeRetryBudget,
            approvalRequestId = approvalRequest?.id,
            refreshedFileIndexState = refreshed,
            organizePlan = plan,
            fileActionNote = if (approvalRequest == null) {
                if (prefersKorean(prompt)) {
                    "정리 승인 요청을 만들지 못했습니다."
                } else {
                    "Organize approval could not be created."
                }
            } else {
                if (prefersKorean(prompt)) {
                    "채팅에서 정리 dry-run 계획을 만들고 Dashboard에 승인 요청을 올렸습니다."
                } else {
                    "Created an organize dry-run from chat and submitted it to Dashboard for approval."
                }
            },
        )
    }

    private suspend fun refreshResources(prompt: String): AgentTurnResult {
        val refreshedIndex = fileIndexRepository.refreshIndex()
        devicePairingRepository.refresh()
        approvalInboxRepository.refresh()
        auditTrailRepository.refresh()
        return AgentTurnResult(
            reply = refreshReply(
                prompt = prompt,
                refreshedIndex = refreshedIndex,
                pairedDeviceCount = devicePairingRepository.pairedDevices.value.size,
                pendingApprovalCount = approvalInboxRepository.items.value.count { it.status == ApprovalInboxStatus.Pending },
            ),
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = shellRefreshActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "연결 자원과 승인 상태를 새로고침했습니다."
            } else {
                "Connected resources and approval state were refreshed."
            },
            refreshedFileIndexState = refreshedIndex,
        )
    }

    private fun showResourceStack(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val environment = buildAgentEnvironmentSnapshot(context)
        val connectedCloudDrives = context.cloudDriveConnections.filter {
            it.status == CloudDriveConnectionStatus.Connected
        }
        val stagedCloudDrives = context.cloudDriveConnections.filter {
            it.status == CloudDriveConnectionStatus.Staged
        }
        val connectedEndpoints = context.externalEndpoints.filter {
            it.status == ExternalEndpointStatus.Connected
        }
        val stagedEndpoints = context.externalEndpoints.filter {
            it.status == ExternalEndpointStatus.Staged
        }
        val connectedChannels = context.deliveryChannels.filter {
            it.status == DeliveryChannelStatus.Connected
        }
        val stagedChannels = context.deliveryChannels.filter {
            it.status == DeliveryChannelStatus.Staged
        }
        val installedMcpSkills = mcpSkillRepository.skills.value
        val connectedMcpEndpoint = connectedEndpoints.firstOrNull { it.endpointId == mcpBridgeEndpointId }
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("현재 resource stack 요약입니다.\n")
                    append("cloud drive: 연결 ${connectedCloudDrives.size}개, staged ${stagedCloudDrives.size}개\n")
                    append("external endpoint: 연결 ${connectedEndpoints.size}개, staged ${stagedEndpoints.size}개\n")
                    append("delivery channel: 연결 ${connectedChannels.size}개, staged ${stagedChannels.size}개\n")
                    append("paired companion: ${context.pairedDevices.size}대, MCP skill: ${installedMcpSkills.size}개\n")
                    append("agent capability inventory:\n")
                    environment.capabilitySummaryLines().forEach { line ->
                        append("- ")
                        append(line)
                        append("\n")
                    }
                    connectedMcpEndpoint?.let { endpoint ->
                        append("MCP connector: ${endpoint.toolNames.size}개 tool")
                        if (endpoint.syncedSkillCount > 0) {
                            append(", synced skill ${endpoint.syncedSkillCount}개")
                        }
                        endpoint.lastSyncAtLabel?.let {
                            append(", last sync $it")
                        }
                        append("\n")
                    }
                    append("연결된 항목: ")
                    append(
                        (
                            connectedCloudDrives.map { it.provider.displayName } +
                                connectedEndpoints.map { it.displayName } +
                                connectedChannels.map { it.displayName }
                            ).ifEmpty { listOf("없음") }.joinToString(),
                    )
                    if (stagedEndpoints.isNotEmpty() || stagedChannels.isNotEmpty() || stagedCloudDrives.isNotEmpty()) {
                        append("\nstaged 항목: ")
                        append(
                            (
                                stagedCloudDrives.map { it.provider.displayName } +
                                    stagedEndpoints.map { it.displayName } +
                                    stagedChannels.map { it.displayName }
                                ).joinToString(),
                        )
                    }
                }
            } else {
                buildString {
                    append("Here is the current resource stack.\n")
                    append("Cloud drives: ${connectedCloudDrives.size} connected, ${stagedCloudDrives.size} staged\n")
                    append("External endpoints: ${connectedEndpoints.size} connected, ${stagedEndpoints.size} staged\n")
                    append("Delivery channels: ${connectedChannels.size} connected, ${stagedChannels.size} staged\n")
                    append("Paired companions: ${context.pairedDevices.size}, MCP skills: ${installedMcpSkills.size}\n")
                    append("Agent capability inventory:\n")
                    environment.capabilitySummaryLines().forEach { line ->
                        append("- ")
                        append(line)
                        append("\n")
                    }
                    connectedMcpEndpoint?.let { endpoint ->
                        append("MCP connector: ${endpoint.toolNames.size} tool(s)")
                        if (endpoint.syncedSkillCount > 0) {
                            append(", ${endpoint.syncedSkillCount} synced skill(s)")
                        }
                        endpoint.lastSyncAtLabel?.let {
                            append(", last sync $it")
                        }
                        append("\n")
                    }
                    append("Connected items: ")
                    append(
                        (
                            connectedCloudDrives.map { it.provider.displayName } +
                                connectedEndpoints.map { it.displayName } +
                                connectedChannels.map { it.displayName }
                            ).ifEmpty { listOf("none") }.joinToString(),
                    )
                    if (stagedEndpoints.isNotEmpty() || stagedChannels.isNotEmpty() || stagedCloudDrives.isNotEmpty()) {
                        append("\nStaged items: ")
                        append(
                            (
                                stagedCloudDrives.map { it.provider.displayName } +
                                    stagedEndpoints.map { it.displayName } +
                                    stagedChannels.map { it.displayName }
                                ).joinToString(),
                        )
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceStackShowActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "현재 resource stack을 채팅에 요약했습니다."
            } else {
                "Summarized the current resource stack in chat."
            },
        )
    }

    private suspend fun stageCloudDrive(
        prompt: String,
        provider: CloudDriveProviderKind,
    ): AgentTurnResult {
        cloudDriveConnectionRepository.stageConnection(provider)
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "${provider.displayName} connector를 staged 상태로 기록했어요. 실제 OAuth와 토큰 보관 연동은 아직 남아 있습니다."
            } else {
                "I staged the ${provider.displayName} connector. Real OAuth handoff and token storage are still pending."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceCloudDriveStageActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "${provider.displayName} connector를 staged로 전환했습니다."
            } else {
                "Marked the cloud drive connector as staged."
            },
        )
    }

    private suspend fun connectCloudDrive(
        prompt: String,
        provider: CloudDriveProviderKind,
    ): AgentTurnResult {
        val accountLabel = "${provider.displayName} placeholder"
        cloudDriveConnectionRepository.markConnected(provider, accountLabel)
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "${provider.displayName} connector를 mock-ready로 연결했어요. 계정 라벨은 $accountLabel 로 기록했습니다."
            } else {
                "I marked the ${provider.displayName} connector as mock-ready and recorded $accountLabel as its placeholder account."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceCloudDriveConnectActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "${provider.displayName} connector를 채팅에서 연결 상태로 기록했습니다."
            } else {
                "Marked the cloud drive connector as connected from chat."
            },
        )
    }

    private suspend fun stageExternalEndpoint(
        prompt: String,
        endpointId: String,
    ): AgentTurnResult {
        externalEndpointRepository.stageEndpoint(endpointId)
        val endpointLabel = externalEndpointDisplayName(endpointId)
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "$endpointLabel endpoint를 staged 상태로 기록했어요. 실제 auth/transport wiring은 아직 남아 있습니다."
            } else {
                "I staged the $endpointLabel endpoint. Real auth and transport wiring are still pending."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceEndpointStageActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "$endpointLabel endpoint를 staged로 전환했습니다."
            } else {
                "Marked the external endpoint as staged."
            },
        )
    }

    private suspend fun connectExternalEndpoint(
        prompt: String,
        endpointId: String,
    ): AgentTurnResult {
        val endpointLabel = "${externalEndpointDisplayName(endpointId)} placeholder"
        externalEndpointRepository.markConnected(
            endpointId,
            ExternalEndpointConnectionSnapshot(
                endpointLabel = endpointLabel,
            ),
        )
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "${externalEndpointDisplayName(endpointId)} endpoint를 mock-ready로 연결했어요."
            } else {
                "I marked ${externalEndpointDisplayName(endpointId)} as a mock-ready endpoint."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceEndpointConnectActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "외부 endpoint 연결 상태를 채팅에서 갱신했습니다."
            } else {
                "Updated the external endpoint connection state from chat."
            },
        )
    }

    private suspend fun stageDeliveryChannel(
        prompt: String,
        channelId: String,
    ): AgentTurnResult {
        deliveryChannelRepository.stageChannel(channelId)
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "${deliveryChannelDisplayName(channelId)} delivery를 staged 상태로 기록했어요."
            } else {
                "I staged ${deliveryChannelDisplayName(channelId)} as a delivery target."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceDeliveryStageActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "delivery channel을 staged로 전환했습니다."
            } else {
                "Marked the delivery channel as staged."
            },
        )
    }

    private suspend fun connectDeliveryChannel(
        prompt: String,
        channelId: String,
    ): AgentTurnResult {
        if (channelId == telegramDeliveryChannelId) {
            val botToken = extractTelegramBotToken(prompt)
            val chatId = extractTelegramChatId(prompt)
            val hasStoredToken = deliveryChannelCredentialVault.hasCredential(channelId)
            if (botToken == null && !hasStoredToken || chatId == null) {
                return AgentTurnResult(
                    reply = if (prefersKorean(prompt)) {
                        buildString {
                            append("Telegram 연결에는 bot token 과 target chat id가 필요해요. ")
                            append("채팅에서 `텔레그램 연결 token <BOT_TOKEN> chat <CHAT_ID>` 형식으로 보내 주세요. ")
                            if (hasStoredToken) {
                                append("이미 저장된 token은 있어서 chat id만 다시 보내도 됩니다.")
                            }
                        }
                    } else {
                        buildString {
                            append("Telegram setup needs a bot token and a target chat ID. ")
                            append("Send them in chat as `connect telegram token <BOT_TOKEN> chat <CHAT_ID>`. ")
                            if (hasStoredToken) {
                                append("A token is already stored, so you can resend only the chat ID.")
                            }
                        }
                    },
                    destination = AgentDestination.Chat,
                    taskTitle = taskTitle(prompt),
                    taskActionKey = resourceDeliveryConnectActionKey,
                    taskSummary = if (prefersKorean(prompt)) {
                        "Telegram 연결에 필요한 secret / chat binding 정보를 기다리고 있습니다."
                    } else {
                        "Waiting for the Telegram secret and target chat binding."
                    },
                    taskStatus = AgentTaskStatus.WaitingUser,
                )
            }
            val resolvedChatId = chatId!!
            botToken?.let { deliveryChannelCredentialVault.store(channelId, it) }
            val resolvedToken = deliveryChannelCredentialVault.read(channelId)?.trim().orEmpty()
            if (resolvedToken.isBlank()) {
                return AgentTurnResult(
                    reply = if (prefersKorean(prompt)) {
                        "Telegram bot token이 아직 비어 있어 검증을 시작할 수 없어요. `텔레그램 연결 token <BOT_TOKEN> chat <CHAT_ID>` 형식으로 다시 보내 주세요."
                    } else {
                        "The Telegram bot token is still missing, so I cannot validate the relay yet. Send `connect telegram token <BOT_TOKEN> chat <CHAT_ID>` again."
                    },
                    destination = AgentDestination.Chat,
                    taskTitle = taskTitle(prompt),
                    taskActionKey = resourceDeliveryConnectActionKey,
                    taskSummary = if (prefersKorean(prompt)) {
                        "Telegram secret이 없어 연결 검증을 시작하지 못했습니다."
                    } else {
                        "Telegram relay validation could not start because the secret is missing."
                    },
                    taskStatus = AgentTaskStatus.WaitingUser,
                )
            }
            deliveryChannelRepository.stageChannel(channelId)
            return when (
                val validation = telegramDeliveryGateway.sendMessage(
                    botToken = resolvedToken,
                    chatId = resolvedChatId,
                    text = buildTelegramValidationMessage(prompt, resolvedChatId),
                )
            ) {
                is TelegramDeliveryResult.Delivered -> {
                    deliveryChannelRepository.configureTelegramBinding(
                        channelId = channelId,
                        chatId = resolvedChatId,
                        destinationLabel = "Chat $resolvedChatId",
                    )
                    deliveryChannelRepository.noteDeliveryAttempt(
                        channelId = channelId,
                        deliveredAtEpochMillis = System.currentTimeMillis(),
                    )
                    AgentTurnResult(
                        reply = if (prefersKorean(prompt)) {
                            "Telegram bot relay를 chat $resolvedChatId 로 검증하고 연결했어요. 테스트 메시지도 전달됐고, 이제 중요한 automation alert는 Telegram을 먼저 시도하고 실패하면 폰 알림으로 남깁니다."
                        } else {
                            "I validated and connected the Telegram bot relay to chat $resolvedChatId. The test message was delivered, and important automation alerts will now try Telegram first before falling back to local phone notifications."
                        },
                        destination = AgentDestination.Chat,
                        taskTitle = taskTitle(prompt),
                        taskActionKey = resourceDeliveryConnectActionKey,
                        taskSummary = if (prefersKorean(prompt)) {
                            "Telegram delivery channel을 검증 후 활성화했습니다."
                        } else {
                            "Validated and activated the Telegram delivery channel."
                        },
                    )
                }
                is TelegramDeliveryResult.Failed -> {
                    deliveryChannelRepository.stageChannel(channelId)
                    deliveryChannelRepository.noteDeliveryAttempt(
                        channelId = channelId,
                        deliveredAtEpochMillis = System.currentTimeMillis(),
                        error = validation.detail,
                    )
                    AgentTurnResult(
                        reply = if (prefersKorean(prompt)) {
                            buildString {
                                append("Telegram 테스트 전송이 실패해서 아직 활성화하지 않았어요. ")
                                append("bot을 대상 chat에서 시작했는지, chat id가 맞는지, bot이 메시지 보낼 권한이 있는지 확인해 주세요.")
                                if (validation.detail.isNotBlank()) {
                                    append("\n오류: ")
                                    append(validation.detail)
                                }
                            }
                        } else {
                            buildString {
                                append("The Telegram validation send failed, so I left the relay staged instead of activating it. ")
                                append("Check that the bot has started in the target chat, the chat ID is correct, and the bot can post messages there.")
                                if (validation.detail.isNotBlank()) {
                                    append("\nError: ")
                                    append(validation.detail)
                                }
                            }
                        },
                        destination = AgentDestination.Chat,
                        taskTitle = taskTitle(prompt),
                        taskActionKey = resourceDeliveryConnectActionKey,
                        taskSummary = if (prefersKorean(prompt)) {
                            "Telegram delivery 검증이 실패해 staged 상태로 남겼습니다."
                        } else {
                            "Telegram delivery validation failed, so the relay was left staged."
                        },
                        taskStatus = AgentTaskStatus.Failed,
                    )
                }
            }
        }
        val destinationLabel = "${deliveryChannelDisplayName(channelId)} placeholder"
        deliveryChannelRepository.markConnected(channelId, destinationLabel)
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "${deliveryChannelDisplayName(channelId)} delivery를 mock-ready로 연결했어요."
            } else {
                "I marked ${deliveryChannelDisplayName(channelId)} as a mock-ready delivery target."
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = resourceDeliveryConnectActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "delivery channel 연결 상태를 채팅에서 갱신했습니다."
            } else {
                "Updated the delivery channel connection state from chat."
            },
        )
    }

    private suspend fun connectMailbox(prompt: String): AgentTurnResult {
        val korean = prefersKorean(prompt)
        val existing = mailboxConnectionRepository.primaryMailbox()
        val host = extractStructuredPromptField(prompt, listOf("host", "server", "서버")) ?: existing?.host
        val username = extractStructuredPromptField(
            prompt,
            listOf("user", "username", "account", "사용자", "계정"),
        ) ?: existing?.username
        val password = extractStructuredPromptField(
            prompt,
            listOf("password", "pass", "app_password", "secret", "비밀번호", "패스워드", "앱비밀번호"),
        )
        val port = extractStructuredPromptField(prompt, listOf("port", "포트"))
            ?.toIntOrNull()
            ?: existing?.port
            ?: 993
        val inboxFolder = extractStructuredPromptField(prompt, listOf("inbox", "inbox_folder", "받은편지함"))
            ?: existing?.inboxFolder
            ?: "INBOX"
        val promotionsFolder = extractStructuredPromptField(
            prompt,
            listOf("promotions", "promo", "archive", "광고함", "프로모션함", "보관함"),
        ) ?: existing?.promotionsFolder ?: "Promotions"

        if (host.isNullOrBlank() || username.isNullOrBlank() || (password.isNullOrBlank() && !mailboxCredentialVault.hasCredential(primaryMailboxConnectionId))) {
            return AgentTurnResult(
                reply = if (korean) {
                    buildString {
                        append("메일 연결에는 host, user, password가 필요해요. ")
                        append("예: `메일 연결 host imap.gmail.com user me@example.com password \"app password\"` ")
                        append("선택값으로 `port 993`, `inbox INBOX`, `promotions Promotions`를 붙일 수 있습니다.")
                    }
                } else {
                    buildString {
                        append("Mailbox setup needs host, user, and password. ")
                        append("Example: `connect mailbox host imap.gmail.com user me@example.com password \"app password\"`. ")
                        append("Optional fields are `port 993`, `inbox INBOX`, and `promotions Promotions`.")
                    }
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mailboxConnectActionKey,
                taskSummary = if (korean) {
                    "메일 연결에 필요한 host/user/password 입력을 기다리고 있습니다."
                } else {
                    "Waiting for mailbox host, user, and password."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
            )
        }

        password?.let { mailboxCredentialVault.store(primaryMailboxConnectionId, it) }
        val resolvedPassword = mailboxCredentialVault.read(primaryMailboxConnectionId)?.trim().orEmpty()
        if (resolvedPassword.isBlank()) {
            return AgentTurnResult(
                reply = if (korean) {
                    "메일함 비밀번호가 비어 있어 검증을 시작할 수 없어요. app password를 다시 보내 주세요."
                } else {
                    "The mailbox password is missing, so I cannot validate the inbox yet. Send the app password again."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mailboxConnectActionKey,
                taskSummary = if (korean) {
                    "메일함 secret이 없어 연결 검증을 시작하지 못했습니다."
                } else {
                    "Mailbox validation could not start because the secret is missing."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
            )
        }
        val config = MailboxConnectionConfig(
            host = host,
            port = port,
            username = username,
            inboxFolder = inboxFolder,
            promotionsFolder = promotionsFolder,
        )
        mailboxConnectionRepository.upsertMailbox(
            config = config,
            status = MailboxConnectionStatus.Staged,
            summary = "Mailbox credentials were recorded and validation is in progress.",
        )
        val validation = mailboxGateway.validate(config, resolvedPassword)
        return if (validation.connected) {
            mailboxConnectionRepository.upsertMailbox(
                config = config,
                status = MailboxConnectionStatus.Connected,
                summary = validation.summary,
                lastError = null,
                lastSyncAtEpochMillis = System.currentTimeMillis(),
            )
            AgentTurnResult(
                reply = if (korean) {
                    "메일함 연결을 검증했어요. 이제 채팅에서 이메일 triage 정책을 기록하고 바로 활성화할 수 있습니다. 현재 inbox는 ${validation.inboxCount}건으로 확인됐고, 광고 메일 보관함은 ${config.promotionsFolder}로 준비했습니다."
                } else {
                    "I validated the mailbox connection. You can now record and activate an email triage policy from chat. The inbox currently has ${validation.inboxCount} message(s), and the promotions folder is set to ${config.promotionsFolder}."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mailboxConnectActionKey,
                taskSummary = if (korean) {
                    "메일함 연결을 검증하고 활성화했습니다."
                } else {
                    "Validated and activated the mailbox connection."
                },
            )
        } else {
            mailboxConnectionRepository.upsertMailbox(
                config = config,
                status = MailboxConnectionStatus.Staged,
                summary = "Mailbox validation failed and the connector was left staged.",
                lastError = validation.lastError,
            )
            AgentTurnResult(
                reply = if (korean) {
                    buildString {
                        append("메일 연결 검증이 실패해서 staged 상태로 남겨뒀어요. ")
                        validation.lastError?.takeIf(String::isNotBlank)?.let { error ->
                            append("오류: ")
                            append(error)
                        }
                    }
                } else {
                    buildString {
                        append("Mailbox validation failed, so I left the connector staged. ")
                        validation.lastError?.takeIf(String::isNotBlank)?.let { error ->
                            append("Error: ")
                            append(error)
                        }
                    }
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mailboxConnectActionKey,
                taskSummary = if (korean) {
                    "메일 연결 검증이 실패해 staged 상태로 남겼습니다."
                } else {
                    "Mailbox validation failed, so the connector was left staged."
                },
                taskStatus = AgentTaskStatus.Failed,
            )
        }
    }

    private suspend fun showMailboxStatus(prompt: String): AgentTurnResult {
        val korean = prefersKorean(prompt)
        val mailbox = mailboxConnectionRepository.primaryMailbox()
        return if (mailbox == null) {
            AgentTurnResult(
                reply = if (korean) {
                    "아직 연결된 메일함이 없습니다. `메일 연결 host <HOST> user <USER> password <APP_PASSWORD>` 형식으로 바로 연결할 수 있어요."
                } else {
                    "No mailbox is connected yet. You can connect one in chat with `connect mailbox host <HOST> user <USER> password <APP_PASSWORD>`."
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mailboxStatusActionKey,
                taskSummary = if (korean) {
                    "연결된 메일함이 아직 없습니다."
                } else {
                    "No mailbox is connected yet."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
            )
        } else {
            AgentTurnResult(
                reply = if (korean) {
                    buildString {
                        append("현재 메일함 상태는 ${mailbox.status.name.lowercase()} 입니다.\n")
                        append("연결: ${mailbox.connectionLabel}\n")
                        append("Inbox: ${mailbox.inboxFolder}, Promotions: ${mailbox.promotionsFolder}\n")
                        mailbox.lastSyncAtLabel?.let { append("최근 검증: $it\n") }
                        mailbox.lastError?.takeIf(String::isNotBlank)?.let { append("최근 오류: $it") }
                    }.trim()
                } else {
                    buildString {
                        append("Mailbox status is ${mailbox.status.name.lowercase()}.\n")
                        append("Connection: ${mailbox.connectionLabel}\n")
                        append("Inbox: ${mailbox.inboxFolder}, Promotions: ${mailbox.promotionsFolder}\n")
                        mailbox.lastSyncAtLabel?.let { append("Last validated: $it\n") }
                        mailbox.lastError?.takeIf(String::isNotBlank)?.let { append("Last error: $it") }
                    }.trim()
                },
                destination = AgentDestination.Chat,
                taskTitle = taskTitle(prompt),
                taskActionKey = mailboxStatusActionKey,
                taskSummary = if (korean) {
                    "현재 메일함 연결 상태를 채팅에 정리했습니다."
                } else {
                    "Summarized the current mailbox connection state in chat."
                },
                taskStatus = if (mailbox.status == MailboxConnectionStatus.Connected) {
                    AgentTaskStatus.Succeeded
                } else {
                    AgentTaskStatus.WaitingResource
                },
            )
        }
    }

    private suspend fun runShellRecovery(prompt: String): AgentTurnResult {
        shellRecoveryCoordinator.requestManualRecovery()
        val recoveryState = awaitRecoveryCompletion()
        val taskStatus = when (recoveryState.status) {
            ShellRecoveryStatus.Success -> AgentTaskStatus.Succeeded
            ShellRecoveryStatus.Failed -> AgentTaskStatus.Failed
            ShellRecoveryStatus.Running -> AgentTaskStatus.WaitingResource
            ShellRecoveryStatus.Idle -> AgentTaskStatus.WaitingResource
        }
        return AgentTurnResult(
            reply = shellRecoveryReply(prompt, recoveryState),
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = shellRecoveryRunActionKey,
            taskSummary = when (recoveryState.status) {
                ShellRecoveryStatus.Success -> if (prefersKorean(prompt)) {
                    "채팅에서 수동 shell recovery를 완료했습니다."
                } else {
                    "Completed a manual shell recovery from chat."
                }
                ShellRecoveryStatus.Failed -> if (prefersKorean(prompt)) {
                    "채팅에서 요청한 shell recovery가 실패했습니다."
                } else {
                    "The shell recovery requested from chat failed."
                }
                else -> if (prefersKorean(prompt)) {
                    "채팅에서 shell recovery를 요청했고 아직 진행 중입니다."
                } else {
                    "Requested shell recovery from chat and it is still running."
                }
            },
            taskStatus = taskStatus,
        )
    }

    private fun showShellRecoveryStatus(prompt: String): AgentTurnResult {
        val recoveryState = shellRecoveryCoordinator.state.value
        return AgentTurnResult(
            reply = shellRecoveryReply(prompt, recoveryState),
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = shellRecoveryShowActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "현재 shell recovery 상태를 채팅에 요약했습니다."
            } else {
                "Summarized the current shell recovery state in chat."
            },
        )
    }

    private suspend fun awaitRecoveryCompletion(): ShellRecoveryState {
        repeat(shellRecoveryPollAttempts) {
            val state = shellRecoveryCoordinator.state.value
            if (state.triggerLabel == "Manual" && state.status != ShellRecoveryStatus.Running) {
                return state
            }
            delay(shellRecoveryPollIntervalMs)
        }
        return shellRecoveryCoordinator.state.value
    }

    private fun shellRecoveryReply(
        prompt: String,
        recoveryState: ShellRecoveryState,
    ): String {
        return if (prefersKorean(prompt)) {
            buildString {
                append("Shell recovery 상태는 ")
                append(shellRecoveryStatusLabel(prompt, recoveryState.status))
                append(" 입니다.")
                recoveryState.triggerLabel?.let { trigger ->
                    append(" 최근 트리거는 ")
                    append(trigger)
                    append("입니다.")
                }
                append("\n")
                append(recoveryState.summary)
                append("\n")
                append(recoveryState.detail)
            }
        } else {
            buildString {
                append("Shell recovery is ")
                append(shellRecoveryStatusLabel(prompt, recoveryState.status))
                append(".")
                recoveryState.triggerLabel?.let { trigger ->
                    append(" Latest trigger: ")
                    append(trigger)
                    append(".")
                }
                append("\n")
                append(recoveryState.summary)
                append("\n")
                append(recoveryState.detail)
            }
        }
    }

    private fun buildDashboardResponse(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val pendingApprovals = context.approvals.count { it.status == ApprovalInboxStatus.Pending }
        val connectedRoots = context.fileIndexState.documentTreeCount
        val pairedDevices = context.pairedDevices.size
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "Dashboard로 이동하면 현재 상태를 바로 볼 수 있어요. 승인 대기 ${pendingApprovals}건, 문서 루트 ${connectedRoots}개, 연결된 companion ${pairedDevices}대가 잡혀 있습니다."
            } else {
                "Dashboard is the right surface for current status. It has $pendingApprovals pending approvals, $connectedRoots document roots, and $pairedDevices paired companions right now."
            },
            destination = AgentDestination.Dashboard,
            taskTitle = taskTitle(prompt),
            taskActionKey = routeDashboardActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "Dashboard로 라우팅했습니다."
            } else {
                "Routed the session to Dashboard."
            },
        )
    }

    private fun buildHistoryResponse(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "History로 이동하면 최근 audit/event ${context.auditEvents.size}건을 볼 수 있어요. 어떤 요청이 어떻게 처리됐는지 추적할 때 쓰면 됩니다."
            } else {
                "History shows the recent ${context.auditEvents.size} audit events so you can inspect what the agent already did and why."
            },
            destination = AgentDestination.History,
            taskTitle = taskTitle(prompt),
            taskActionKey = routeHistoryActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "History로 라우팅했습니다."
            } else {
                "Routed the session to History."
            },
        )
    }

    private fun buildSettingsResponse(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val mediaState = if (context.fileIndexState.permissionGranted) {
            if (prefersKorean(prompt)) "허용됨" else "granted"
        } else {
            if (prefersKorean(prompt)) "미허용" else "missing"
        }
        val connectedCloudDrives = context.cloudDriveConnections.count {
            it.status == CloudDriveConnectionStatus.Connected
        }
        val stagedCloudDrives = context.cloudDriveConnections.count {
            it.status == CloudDriveConnectionStatus.Staged
        }
        val connectedExternalEndpoints = context.externalEndpoints.count {
            it.status == ExternalEndpointStatus.Connected
        }
        val stagedExternalEndpoints = context.externalEndpoints.count {
            it.status == ExternalEndpointStatus.Staged
        }
        val connectedDeliveryChannels = context.deliveryChannels.count {
            it.status == DeliveryChannelStatus.Connected
        }
        val stagedDeliveryChannels = context.deliveryChannels.count {
            it.status == DeliveryChannelStatus.Staged
        }
        val providerLabel = context.modelPreference.preferredProviderLabel?.let { provider ->
            val model = context.modelPreference.preferredModel
            if (model.isNullOrBlank()) {
                provider
            } else {
                "$provider / $model"
            }
        } ?: if (prefersKorean(prompt)) {
            "미선택"
        } else {
            "not selected"
        }
        val configuredProviderCount = context.modelPreference.configuredProviderIds.size
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                "세부 설정은 Settings에서 직접 볼 수 있어요. 다만 계속 여기 채팅에서 진행해도 됩니다. 현재 미디어 권한은 $mediaState, 문서 루트는 ${context.fileIndexState.documentTreeCount}개, companion은 ${context.pairedDevices.size}대, cloud connector는 staged ${stagedCloudDrives}개 / mock-ready ${connectedCloudDrives}개, MCP/API endpoint는 staged ${stagedExternalEndpoints}개 / mock-ready ${connectedExternalEndpoints}개, delivery channel은 staged ${stagedDeliveryChannels}개 / mock-ready ${connectedDeliveryChannels}개입니다. 기본 모델은 $providerLabel, 저장된 provider credential은 ${configuredProviderCount}개예요."
            } else {
                "You can inspect the details in Settings, but you can also keep working from chat. Media access is $mediaState, there are ${context.fileIndexState.documentTreeCount} document roots, ${context.pairedDevices.size} companions, cloud connectors are staged ${stagedCloudDrives} / mock-ready ${connectedCloudDrives}, MCP/API endpoints are staged ${stagedExternalEndpoints} / mock-ready ${connectedExternalEndpoints}, delivery channels are staged ${stagedDeliveryChannels} / mock-ready ${connectedDeliveryChannels}, the current model preference is $providerLabel, and there are $configuredProviderCount configured provider credential(s)."
            },
            destination = AgentDestination.Settings,
            taskTitle = taskTitle(prompt),
            taskActionKey = routeSettingsActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "Settings 세부 구성을 요약했습니다."
            } else {
                "Summarized the Settings-level resource state."
            },
        )
    }

    private fun explainInitialSetup(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val korean = prefersKorean(prompt)
        val hasConfiguredProvider = context.modelPreference.configuredProviderIds.isNotEmpty()
        val hasLocalFilesReady =
            context.fileIndexState.permissionGranted ||
                context.fileIndexState.documentTreeCount > 0 ||
                context.fileIndexState.indexedCount > 0
        val hasPairedCompanion = context.pairedDevices.isNotEmpty()
        val providerState = if (hasConfiguredProvider) {
            if (korean) "완료" else "ready"
        } else {
            if (korean) "필요" else "needed"
        }
        val fileState = if (hasLocalFilesReady) {
            if (korean) "완료" else "ready"
        } else {
            if (korean) "필요" else "needed"
        }
        val companionState = if (hasPairedCompanion) {
            if (korean) "연결됨" else "paired"
        } else {
            if (korean) "선택" else "optional"
        }
        return AgentTurnResult(
            reply = if (korean) {
                buildString {
                    append("처음에는 두 가지만 먼저 끝내면 됩니다.\n")
                    append("1. AI model provider에서 API key 또는 token 1개 저장\n")
                    append("2. Local files에서 미디어 권한 허용 또는 폴더 1개 연결\n")
                    append("3. Companion pairing은 선택입니다.\n")
                    append("현재 상태: provider $providerState, local files $fileState, companion $companionState.\n")
                    append("계속 채팅에서 요청해도 되고, 직접 보고 싶을 때만 Settings를 열면 됩니다.")
                }
            } else {
                buildString {
                    append("For the first run, finish these two steps first.\n")
                    append("1. Store one API key or token in an AI model provider card\n")
                    append("2. Grant media access or attach one local folder in Local files\n")
                    append("3. Companion pairing is optional.\n")
                    append("Current state: provider $providerState, local files $fileState, companion $companionState.\n")
                    append("You can keep going in chat and open Settings only if you want to inspect things manually.")
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = explainInitialSetupActionKey,
            taskSummary = if (korean) {
                "초기 설정 순서를 채팅 기준으로 정리했습니다."
            } else {
                "Explained the first-run setup order from chat."
            },
        )
    }

    private fun explainMcpSetup(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val korean = prefersKorean(prompt)
        val pairedCompanion = resolveMcpCompanion(context)
        val mcpEndpoint = context.externalEndpoints.firstOrNull { it.endpointId == mcpBridgeEndpointId }
        val installedSkillCount = mcpSkillRepository.skills.value.size
        val providerReady = context.modelPreference.configuredProviderIds.isNotEmpty()
        val companionReady = pairedCompanion != null
        val bridgeConnected = mcpEndpoint?.status == ExternalEndpointStatus.Connected
        return AgentTurnResult(
            reply = if (korean) {
                buildString {
                    append("MCP 연결은 채팅에서 이어가면 됩니다.\n")
                    append("1. companion 상태 확인\n")
                    append("2. MCP bridge 연결\n")
                    append("3. MCP skill 업데이트 또는 MCP tool 확인\n\n")
                    append("현재 상태: ")
                    append(if (providerReady) "provider 준비됨, " else "provider 미설정, ")
                    append(if (companionReady) "companion 준비됨, " else "companion 미연결, ")
                    append(if (bridgeConnected) "MCP bridge 연결됨, " else "MCP bridge 미연결, ")
                    append("설치된 MCP skill ${installedSkillCount}개.")
                    if (!companionReady) {
                        append("\n지금은 companion이 없어 실제 MCP bridge discovery를 못 합니다. 아래 버튼으로 다음 단계를 이어가면 됩니다.")
                    } else if (!bridgeConnected) {
                        append("\ncompanion은 준비돼 있으니 이제 채팅에서 바로 MCP bridge 연결을 시도하면 됩니다.")
                    } else if (installedSkillCount == 0) {
                        append("\nbridge는 연결돼 있으니 이제 MCP skill 업데이트를 실행하면 됩니다.")
                    } else {
                        append("\n이제 채팅에서 MCP status, MCP tools, MCP skill 업데이트를 계속 요청하면 됩니다.")
                    }
                }
            } else {
                buildString {
                    append("You can keep the MCP setup in chat.\n")
                    append("1. Check companion health\n")
                    append("2. Connect the MCP bridge\n")
                    append("3. Update MCP skills or inspect MCP tools\n\n")
                    append("Current state: ")
                    append(if (providerReady) "provider ready, " else "provider missing, ")
                    append(if (companionReady) "companion ready, " else "companion missing, ")
                    append(if (bridgeConnected) "MCP bridge connected, " else "MCP bridge not connected, ")
                    append("$installedSkillCount installed MCP skill(s).")
                    if (!companionReady) {
                        append("\nA companion is still missing, so live MCP discovery cannot run yet. Use the buttons below to continue.")
                    } else if (!bridgeConnected) {
                        append("\nThe companion is available, so the next step is to connect the MCP bridge right from chat.")
                    } else if (installedSkillCount == 0) {
                        append("\nThe bridge is ready, so the next step is to update MCP skills.")
                    } else {
                        append("\nYou can now keep asking for MCP status, MCP tools, or MCP skill updates in chat.")
                    }
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = mcpSetupGuideActionKey,
            taskSummary = if (korean) {
                "채팅 기준 MCP 연결 단계를 정리했습니다."
            } else {
                "Outlined the MCP setup flow from chat."
            },
            taskStatus = if (companionReady) {
                AgentTaskStatus.Succeeded
            } else {
                AgentTaskStatus.WaitingResource
            },
        )
    }

    private fun explainEmailSetup(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val korean = prefersKorean(prompt)
        val environment = buildAgentEnvironmentSnapshot(context)
        val mailboxCapability = environment.capabilities.firstOrNull { it.capabilityId == "mailbox.connector" }
        val telegramCapability = environment.capabilities.firstOrNull { it.capabilityId == "delivery.telegram" }
        return AgentTurnResult(
            reply = if (korean) {
                buildString {
                    append("이메일 자동화는 이제 generic IMAP mailbox 기준으로 chat에서 바로 연결할 수 있어요.\n")
                    append("현재 상태: ")
                    append(
                        when (mailboxCapability?.state) {
                            ResourceConnectionState.Connected -> "mailbox connector 준비됨"
                            ResourceConnectionState.Staged -> "mailbox connector staged"
                            ResourceConnectionState.NeedsSetup -> "mailbox connector 설정 필요"
                            ResourceConnectionState.Blocked, null -> "mailbox connector 정보 없음"
                        },
                    )
                    append(", ")
                    append(
                        when (telegramCapability?.state) {
                            ResourceConnectionState.Connected -> "Telegram 알림 준비됨"
                            ResourceConnectionState.Staged -> "Telegram 알림 검증 대기"
                            ResourceConnectionState.NeedsSetup -> "Telegram 알림 미설정"
                            ResourceConnectionState.Blocked, null -> "Telegram 알림 불가"
                        },
                    )
                    append(".\n")
                    append("연결 형식: `메일 연결 host imap.gmail.com user me@example.com password \"app password\"`\n")
                    append("선택값: `port 993`, `inbox INBOX`, `promotions Promotions`\n")
                    append("연결이 끝나면 `광고 메일은 보관함으로 옮기고 중요한 메일은 알림 줘`라고 바로 기록하고, 이어서 `활성화해`, `지금 실행해`로 실행할 수 있습니다.")
                }
            } else {
                buildString {
                    append("Email automation can now be connected from chat with a generic IMAP mailbox.\n")
                    append("Current state: ")
                    append(
                        when (mailboxCapability?.state) {
                            ResourceConnectionState.Connected -> "mailbox connector ready"
                            ResourceConnectionState.Staged -> "mailbox connector staged"
                            ResourceConnectionState.NeedsSetup -> "mailbox connector needs setup"
                            ResourceConnectionState.Blocked, null -> "mailbox connector unavailable"
                        },
                    )
                    append(", ")
                    append(
                        when (telegramCapability?.state) {
                            ResourceConnectionState.Connected -> "Telegram alerts ready"
                            ResourceConnectionState.Staged -> "Telegram alerts waiting for validation"
                            ResourceConnectionState.NeedsSetup -> "Telegram alerts not configured"
                            ResourceConnectionState.Blocked, null -> "Telegram alerts unavailable"
                        },
                    )
                    append(".\n")
                    append("Connection format: `connect mailbox host imap.gmail.com user me@example.com password \"app password\"`\n")
                    append("Optional fields: `port 993`, `inbox INBOX`, `promotions Promotions`\n")
                    append("After that, ask `move promotional mail and alert on important mail`, then keep controlling it from chat with `activate it` or `run it now`.")
                }
            },
            destination = AgentDestination.Chat,
            taskTitle = taskTitle(prompt),
            taskActionKey = emailSetupGuideActionKey,
            taskSummary = if (korean) {
                "이메일 자동화의 현재 blocker와 다음 구현 단계를 채팅에서 설명했습니다."
            } else {
                "Explained the current email automation blocker and next implementation steps in chat."
            },
            taskStatus = AgentTaskStatus.WaitingResource,
        )
    }

    private suspend fun openCompanionTarget(
        prompt: String,
        context: AgentTurnContext,
        targetKind: String,
    ): AgentTurnResult {
        val targetDeviceId = context.selectedTargetDeviceId
            ?: context.pairedDevices.firstOrNull()?.id
        if (targetDeviceId == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "연결된 companion이 아직 없어서 원격 surface를 열 수 없어요. Settings에서 먼저 페어링을 완료해 주세요."
                } else {
                    "There is no paired companion yet, so I cannot open a remote surface. Pair a companion from Settings first."
                },
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = companionAppOpenActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "Companion 연결이 없어 원격 surface를 열 수 없습니다."
                } else {
                    "No paired companion is available for remote surface opening."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val targetLabel = companionTargetLabel(targetKind)
        val result = devicePairingRepository.sendAppOpen(
            deviceId = targetDeviceId,
            targetKind = targetKind,
            targetLabel = targetLabel,
        )
        return AgentTurnResult(
            reply = companionOpenReply(prompt, targetKind, result),
            destination = when (result.status) {
                CompanionAppOpenStatus.Opened,
                CompanionAppOpenStatus.Recorded,
                CompanionAppOpenStatus.Failed -> AgentDestination.Chat
                CompanionAppOpenStatus.Misconfigured,
                CompanionAppOpenStatus.Skipped -> AgentDestination.Settings
            },
            taskTitle = taskTitle(prompt),
            taskActionKey = companionAppOpenActionKey,
            taskSummary = when (result.status) {
                else -> ChatTaskContinuationPresentation.appOpenTaskSummary(
                    korean = prefersKorean(prompt),
                    result = result,
                )
            },
            taskStatus = when (result.status) {
                CompanionAppOpenStatus.Opened,
                CompanionAppOpenStatus.Recorded -> AgentTaskStatus.Succeeded
                CompanionAppOpenStatus.Failed -> AgentTaskStatus.Failed
                CompanionAppOpenStatus.Misconfigured,
                CompanionAppOpenStatus.Skipped -> AgentTaskStatus.WaitingResource
            },
            companionAppOpenResult = result,
        )
    }

    private suspend fun probeCompanionHealth(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val targetDeviceId = context.selectedTargetDeviceId
            ?: context.pairedDevices.firstOrNull()?.id
        if (targetDeviceId == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "연결된 companion이 아직 없어서 health probe를 실행할 수 없어요. Settings에서 먼저 페어링을 완료해 주세요."
                } else {
                    "There is no paired companion yet, so I cannot run a health probe. Pair a companion from Settings first."
                },
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = companionHealthProbeActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "Companion 연결이 없어 health probe를 실행할 수 없습니다."
                } else {
                    "No paired companion is available for a health probe."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val result = devicePairingRepository.probeCompanion(targetDeviceId)
        return AgentTurnResult(
            reply = companionHealthProbeReply(prompt, result),
            destination = when (result.status) {
                CompanionHealthStatus.Healthy,
                CompanionHealthStatus.Unreachable -> AgentDestination.Chat
                CompanionHealthStatus.Misconfigured,
                CompanionHealthStatus.Skipped -> AgentDestination.Settings
            },
            taskTitle = taskTitle(prompt),
            taskActionKey = companionHealthProbeActionKey,
            taskSummary = when (result.status) {
                else -> ChatTaskContinuationPresentation.healthTaskSummary(
                    korean = prefersKorean(prompt),
                    result = result,
                )
            },
            taskStatus = when (result.status) {
                CompanionHealthStatus.Healthy -> AgentTaskStatus.Succeeded
                CompanionHealthStatus.Unreachable -> AgentTaskStatus.Failed
                CompanionHealthStatus.Misconfigured,
                CompanionHealthStatus.Skipped -> AgentTaskStatus.WaitingResource
            },
            companionHealthCheckResult = result,
        )
    }

    private suspend fun sendCompanionSessionNotification(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val targetDeviceId = context.selectedTargetDeviceId
            ?: context.pairedDevices.firstOrNull()?.id
        if (targetDeviceId == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "연결된 companion이 아직 없어서 desktop notification을 보낼 수 없어요. Settings에서 먼저 페어링을 완료해 주세요."
                } else {
                    "There is no paired companion yet, so I cannot send a desktop notification. Pair a companion from Settings first."
                },
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = companionSessionNotifyActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "Companion 연결이 없어 session.notify를 보낼 수 없습니다."
                } else {
                    "No paired companion is available for session.notify."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val result = devicePairingRepository.sendSessionNotification(
            deviceId = targetDeviceId,
            title = "Makoion session ping",
            body = if (prefersKorean(prompt)) {
                "채팅에서 시작한 companion 알림입니다."
            } else {
                "Chat-started companion notification from the Android shell."
            },
        )
        return AgentTurnResult(
            reply = companionSessionNotifyReply(prompt, result),
            destination = when (result.status) {
                CompanionSessionNotifyStatus.Delivered,
                CompanionSessionNotifyStatus.Failed -> AgentDestination.Chat
                CompanionSessionNotifyStatus.Misconfigured,
                CompanionSessionNotifyStatus.Skipped -> AgentDestination.Settings
            },
            taskTitle = taskTitle(prompt),
            taskActionKey = companionSessionNotifyActionKey,
            taskSummary = when (result.status) {
                else -> ChatTaskContinuationPresentation.sessionNotifyTaskSummary(
                    korean = prefersKorean(prompt),
                    result = result,
                )
            },
            taskStatus = when (result.status) {
                CompanionSessionNotifyStatus.Delivered -> AgentTaskStatus.Succeeded
                CompanionSessionNotifyStatus.Failed -> AgentTaskStatus.Failed
                CompanionSessionNotifyStatus.Misconfigured,
                CompanionSessionNotifyStatus.Skipped -> AgentTaskStatus.WaitingResource
            },
            companionSessionNotifyResult = result,
        )
    }

    private suspend fun runCompanionWorkflow(
        prompt: String,
        context: AgentTurnContext,
        workflowId: String,
    ): AgentTurnResult {
        val targetDeviceId = context.selectedTargetDeviceId
            ?: context.pairedDevices.firstOrNull()?.id
        if (targetDeviceId == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "연결된 companion이 아직 없어서 desktop workflow를 실행할 수 없어요. Settings에서 먼저 페어링을 완료해 주세요."
                } else {
                    "There is no paired companion yet, so I cannot run a desktop workflow. Pair a companion from Settings first."
                },
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = companionWorkflowRunActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "Companion 연결이 없어 workflow.run을 실행할 수 없습니다."
                } else {
                    "No paired companion is available for workflow.run."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val workflowLabel = companionWorkflowLabel(workflowId)
        val result = devicePairingRepository.sendWorkflowRun(
            deviceId = targetDeviceId,
            workflowId = workflowId,
            workflowLabel = workflowLabel,
        )
        return AgentTurnResult(
            reply = companionWorkflowReply(prompt, workflowId, result),
            destination = when (result.status) {
                CompanionWorkflowRunStatus.Completed,
                CompanionWorkflowRunStatus.Recorded,
                CompanionWorkflowRunStatus.Failed -> AgentDestination.Chat
                CompanionWorkflowRunStatus.Misconfigured,
                CompanionWorkflowRunStatus.Skipped -> AgentDestination.Settings
            },
            taskTitle = taskTitle(prompt),
            taskActionKey = companionWorkflowRunActionKey,
            taskSummary = when (result.status) {
                else -> ChatTaskContinuationPresentation.workflowTaskSummary(
                    korean = prefersKorean(prompt),
                    workflowLabel = workflowLabel,
                    result = result,
                )
            },
            taskStatus = when (result.status) {
                CompanionWorkflowRunStatus.Completed,
                CompanionWorkflowRunStatus.Recorded -> AgentTaskStatus.Succeeded
                CompanionWorkflowRunStatus.Failed -> AgentTaskStatus.Failed
                CompanionWorkflowRunStatus.Misconfigured,
                CompanionWorkflowRunStatus.Skipped -> AgentTaskStatus.WaitingResource
            },
            companionWorkflowRunResult = result,
        )
    }

    private suspend fun transferIndexedFiles(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val (indexedState, refreshed) = ensureIndexedFiles(context.fileIndexState)
        if (indexedState.indexedItems.isEmpty()) {
            return AgentTurnResult(
                reply = noIndexedFilesReply(prompt),
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = filesTransferActionKey,
                taskSummary = noIndexedFilesNote(prompt),
                taskStatus = AgentTaskStatus.WaitingResource,
                refreshedFileIndexState = refreshed,
                fileActionNote = noIndexedFilesNote(prompt),
            )
        }

        val targetDevice = context.selectedTargetDeviceId?.let { selectedId ->
            context.pairedDevices.firstOrNull { it.id == selectedId }
        } ?: context.pairedDevices.firstOrNull()
        if (targetDevice == null) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "전송할 companion이 아직 없어서 approval을 만들 수 없습니다. Settings에서 먼저 페어링을 완료해 주세요."
                } else {
                    "I cannot create a transfer approval yet because there is no paired companion. Pair a device from Settings first."
                },
                destination = AgentDestination.Settings,
                taskTitle = taskTitle(prompt),
                taskActionKey = filesTransferActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "연결된 companion이 없어 전송 approval을 만들 수 없습니다."
                } else {
                    "No paired companion is available for transfer approval."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
        }

        val transferItems = resolveTransferItems(indexedState, context)
        if (transferItems.isEmpty()) {
            return AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    "전송 대상을 자동으로 정하지 않았어요. 현재 인덱싱 파일이 ${indexedState.indexedItems.size}개라 범위가 너무 넓습니다. 먼저 하나를 선택한 뒤 다시 요청해 주세요."
                } else {
                    "I did not auto-pick transfer files because ${indexedState.indexedItems.size} indexed files is too broad. Select a file first, then ask again."
                },
                destination = AgentDestination.Dashboard,
                taskTitle = taskTitle(prompt),
                taskActionKey = filesTransferActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "전송 범위가 넓어 먼저 파일 선택이 필요합니다."
                } else {
                    "Transfer scope is too broad; select a file first."
                },
                taskStatus = AgentTaskStatus.WaitingUser,
                refreshedFileIndexState = refreshed,
            )
        }

        val approvalRequest = approvalInboxRepository.submitTransferApproval(
            device = targetDevice,
            files = transferItems,
        )
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                if (approvalRequest == null) {
                    "전송 approval을 만들지 못했어요. Dashboard에서 장치와 파일 범위를 다시 확인해 주세요."
                } else {
                    "전송 approval을 만들었습니다. ${targetDevice.name}로 ${transferItems.size}개 파일을 보내려면 Dashboard에서 승인해 주세요."
                }
            } else {
                if (approvalRequest == null) {
                    "I could not create the transfer approval. Recheck the selected device and file scope from Dashboard."
                } else {
                    "I created a transfer approval. Approve it from Dashboard to send ${transferItems.size} file(s) to ${targetDevice.name}."
                }
            },
            destination = AgentDestination.Dashboard,
            taskTitle = taskTitle(prompt),
            taskActionKey = filesTransferActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                if (approvalRequest == null) {
                    "전송 approval을 만들지 못했습니다."
                } else {
                    "전송 approval을 만들고 사용자 승인을 기다리는 중입니다."
                }
            } else {
                if (approvalRequest == null) {
                    "The transfer approval could not be created."
                } else {
                    "Created a transfer approval and waiting for user confirmation."
                }
            },
            taskStatus = if (approvalRequest == null) AgentTaskStatus.Failed else AgentTaskStatus.WaitingUser,
            approvalRequestId = approvalRequest?.id,
            refreshedFileIndexState = refreshed,
            fileActionNote = if (approvalRequest == null) {
                if (prefersKorean(prompt)) {
                    "전송 approval 생성에 실패했습니다."
                } else {
                    "Transfer approval could not be created."
                }
            } else {
                if (prefersKorean(prompt)) {
                    "채팅에서 companion 전송 approval을 만들었습니다."
                } else {
                    "Created a companion transfer approval from chat."
                }
            },
        )
    }

    private fun resolveTransferItems(
        indexedState: FileIndexState,
        context: AgentTurnContext,
    ): List<IndexedFileItem> {
        context.selectedFileId?.let { selectedId ->
            indexedState.indexedItems.firstOrNull { it.id == selectedId }?.let { selectedItem ->
                return listOf(selectedItem)
            }
        }
        return if (indexedState.indexedItems.size <= maxChatTransferItemsWithoutSelection) {
            indexedState.indexedItems
        } else {
            emptyList()
        }
    }

    private suspend fun explainCapabilities(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val prefersKorean = prefersKorean(prompt)
        val browserExecution = if (looksLikeBrowserCapabilityQuestion(prompt.lowercase())) {
            resolveBrowserExecution(context)
        } else {
            null
        }
        return AgentTurnResult(
            reply = explainCapabilitiesReply(prompt, context, browserExecution),
            taskTitle = taskTitle(prompt),
            taskActionKey = explainCapabilitiesActionKey,
            taskSummary = if (prefersKorean) {
                "현재 chat 시작 방법과 setup 위치를 짧게 안내했습니다."
            } else {
                "Explained how to start in chat and where setup lives."
            },
        )
    }

    private fun explainCapabilitiesReply(
        prompt: String,
        context: AgentTurnContext,
        browserExecution: BrowserExecutionSnapshot?,
    ): String {
        val korean = prefersKorean(prompt)
        val hasConfiguredProvider = context.modelPreference.configuredProviderIds.isNotEmpty()
        val mentionsBrowserCapability = looksLikeBrowserCapabilityQuestion(prompt.lowercase())
        if (mentionsBrowserCapability) {
            return if (korean) {
                buildString {
                    append("지금은 로컬 파일, 첨부, 채팅, 승인, companion 연동 같은 앱 내부 작업은 가능합니다. ")
                    if (browserExecution?.canBrowseWebPages == true) {
                        append("그리고 연결된 companion MCP bridge를 통해 웹 페이지도 직접 열어 읽을 수 있어요. ")
                        append("URL을 보내주면 페이지 제목과 본문 요약까지 바로 가져올 수 있습니다. ")
                        append("다만 일반 웹 검색이나 브라우저 상호작용 자동화는 아직 최소 경로만 연결돼 있어요.")
                    } else {
                        append("다만 웹 페이지를 직접 열어 읽는 MCP/browser executor는 아직 연결되지 않았어요. ")
                        append("그래서 웹 관련 요청은 현재 계획 수준으로만 남길 수 있고, 실제 페이지 접근/수집은 아직 수행하지 못합니다.")
                    }
                    if (!hasConfiguredProvider) {
                        append(" 일반 설명 응답 품질도 provider를 연결하면 더 좋아집니다.")
                    }
                }
            } else {
                buildString {
                    append("I can handle app-local work like files, attachments, chat turns, approvals, and companion actions. ")
                    if (browserExecution?.canBrowseWebPages == true) {
                        append("A connected companion MCP bridge is also available, so I can open and read a webpage when you send a URL. ")
                        append("I can return the page title and a readable summary from that page. ")
                        append("General live web search is still only partially wired beyond direct page access.")
                    } else {
                        append("But there is no live MCP/browser executor wired yet for opening webpages or running real-time web research. ")
                        append("That means I can only keep web requests at the planning level for now, not actually access or collect from the page.")
                    }
                    if (!hasConfiguredProvider) {
                        append(" A configured model provider would also improve general explanation quality.")
                    }
                }
            }
        }
        return if (korean) {
            if (hasConfiguredProvider) {
                "지금은 채팅에서 파일 요약, 첨부 전송, 승인 처리, companion 액션, 설정 요약 같은 작업을 진행할 수 있어요. 더 구체적으로 원하는 작업을 바로 말해 주면 그 흐름으로 이어서 처리합니다."
            } else {
                "지금은 채팅에서 setup 안내와 로컬 자원 상태 확인은 할 수 있어요. 자유 입력 응답 품질을 높이려면 먼저 provider key를 연결한 뒤 이어서 요청해 주세요."
            }
        } else {
            if (hasConfiguredProvider) {
                "I can continue from chat with file summaries, attachments, approvals, companion actions, and setup/resource guidance. Ask for the concrete next action and I will route it there."
            } else {
                "I can still explain setup and local resource state from chat, but connecting a provider key first will unlock stronger freeform answers."
            }
        }
    }

    private suspend fun respondWithProviderConversation(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        return when (
            val result = providerConversationClient.generateReply(
                prompt = prompt,
                recentMessages = context.chatMessages,
                context = context,
            )
        ) {
            is ProviderConversationResult.Reply -> AgentTurnResult(
                reply = result.text,
                taskTitle = taskTitle(prompt),
                taskActionKey = providerConversationReplyActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "${result.providerLabel} ${result.model}로 자유 입력 대화 응답을 생성했습니다."
                } else {
                    "Generated a freeform chat reply with ${result.providerLabel} ${result.model}."
                },
            )
            is ProviderConversationResult.Failure -> AgentTurnResult(
                reply = if (prefersKorean(prompt)) {
                    buildString {
                        append("자유 대화 응답을 만들려고 ")
                        append(result.providerLabel ?: "모델 provider")
                        result.model?.let {
                            append(" ")
                            append(it)
                        }
                        append("에 연결했지만 실패했습니다. ")
                        append("Settings에서 API key와 기본 provider를 확인해 주세요.")
                        if (result.detail.isNotBlank()) {
                            append("\n오류: ")
                            append(result.detail)
                        }
                    }
                } else {
                    buildString {
                        append("I tried to generate a freeform chat reply with ")
                        append(result.providerLabel ?: "the configured model provider")
                        result.model?.let {
                            append(" ")
                            append(it)
                        }
                        append(", but the request failed. Check the API key and default provider in Settings.")
                        if (result.detail.isNotBlank()) {
                            append("\nError: ")
                            append(result.detail)
                        }
                    }
                },
                taskTitle = taskTitle(prompt),
                taskActionKey = providerConversationReplyActionKey,
                taskSummary = if (prefersKorean(prompt)) {
                    "Provider 기반 자유 대화 응답 생성에 실패했습니다."
                } else {
                    "Provider-backed freeform chat failed."
                },
                taskStatus = AgentTaskStatus.WaitingResource,
            )
            ProviderConversationResult.Unavailable -> explainCapabilities(prompt, context)
        }
    }

    private suspend fun ensureIndexedFiles(currentState: FileIndexState): Pair<FileIndexState, FileIndexState?> {
        if (currentState.indexedItems.isNotEmpty()) {
            return currentState to null
        }
        val refreshed = fileIndexRepository.refreshIndex()
        return refreshed to refreshed
    }

    private fun summaryReply(
        prompt: String,
        summary: FileSummaryDetail,
        indexedState: FileIndexState,
    ): String {
        return if (prefersKorean(prompt)) {
            buildString {
                append(summary.headline)
                append("\n")
                append(summary.body)
                append("\n")
                append("현재 자원: ")
                append(indexedState.scanSource)
                if (summary.highlights.isNotEmpty()) {
                    append("\n")
                    append(summary.highlights.joinToString(separator = "\n") { highlight ->
                        "- $highlight"
                    })
                }
            }
        } else {
            buildString {
                append(summary.headline)
                append("\n")
                append(summary.body)
                append("\n")
                append("Current resource scan: ")
                append(indexedState.scanSource)
                if (summary.highlights.isNotEmpty()) {
                    append("\n")
                    append(summary.highlights.joinToString(separator = "\n") { highlight ->
                        "- $highlight"
                    })
                }
            }
        }
    }

    private fun organizeApprovalReply(
        prompt: String,
        plan: FileOrganizePlan,
    ): String {
        val exampleDestinations = plan.steps
            .take(3)
            .joinToString { step -> step.destinationFolder }
        return if (prefersKorean(prompt)) {
            buildString {
                append("정리 dry-run 계획을 만들고 승인 요청까지 올려뒀어요. ")
                append("${plan.steps.size}개 파일, 전략 ${strategyLabel(prompt, plan.strategy)}, 위험도 ${plan.riskLabel}입니다. ")
                append("예상 대상 폴더는 ")
                append(exampleDestinations.ifBlank { "없음" })
                append(" 입니다. 실제 이동은 Dashboard에서 승인된 뒤에만 실행돼요.")
            }
        } else {
            buildString {
                append("I created an organize dry-run and submitted it for approval. ")
                append("${plan.steps.size} files, ${strategyLabel(prompt, plan.strategy)} strategy, ${plan.riskLabel} risk. ")
                append("Example destinations: ")
                append(exampleDestinations.ifBlank { "none" })
                append(". Real file moves will only happen after approval in Dashboard.")
            }
        }
    }

    private fun refreshReply(
        prompt: String,
        refreshedIndex: FileIndexState,
        pairedDeviceCount: Int,
        pendingApprovalCount: Int,
    ): String {
        return if (prefersKorean(prompt)) {
            "리소스를 새로고침했어요. 인덱싱 파일 ${refreshedIndex.indexedCount}개, 문서 루트 ${refreshedIndex.documentTreeCount}개, companion ${pairedDeviceCount}대, 승인 대기 ${pendingApprovalCount}건입니다."
        } else {
            "Resources refreshed. ${refreshedIndex.indexedCount} files indexed, ${refreshedIndex.documentTreeCount} document roots, ${pairedDeviceCount} companions, and ${pendingApprovalCount} pending approvals are available."
        }
    }

    private fun noIndexedFilesReply(prompt: String): String {
        return if (prefersKorean(prompt)) {
            "지금은 인덱싱된 파일이 없어서 그 요청을 실행할 수 없어요. Settings에서 미디어 권한을 주거나 문서 루트를 연결한 뒤 다시 요청해 주세요."
        } else {
            "I cannot run that yet because there are no indexed files. Grant media access or attach a document root from Settings, then ask again."
        }
    }

    private fun noIndexedFilesNote(prompt: String): String {
        return if (prefersKorean(prompt)) {
            "인덱싱된 파일이 없어 Settings에서 권한 또는 문서 루트 연결이 필요합니다."
        } else {
            "No indexed files are available yet; Settings needs media permission or a document root connection."
        }
    }

    private fun companionOpenReply(
        prompt: String,
        targetKind: String,
        result: CompanionAppOpenResult,
    ): String {
        val targetLabel = companionTargetDisplayName(prompt, targetKind)
        return if (prefersKorean(prompt)) {
            when (result.status) {
                CompanionAppOpenStatus.Opened ->
                    "$targetLabel 열기를 companion에 요청했고 실제로 열렸어요. ${result.detail}"
                CompanionAppOpenStatus.Recorded ->
                    "$targetLabel 열기 요청은 기록됐지만 아직 실제 실행 확인은 못 했어요. ${result.detail}"
                CompanionAppOpenStatus.Failed ->
                    "$targetLabel 열기 요청이 실패했어요. ${result.detail}"
                CompanionAppOpenStatus.Misconfigured ->
                    "$targetLabel 열기 요청을 보내기 전에 companion 설정을 먼저 바로잡아야 해요. ${result.detail}"
                CompanionAppOpenStatus.Skipped ->
                    "$targetLabel 열기 요청은 이번 상태에서 건너뛰었어요. ${result.detail}"
            }
        } else {
            when (result.status) {
                CompanionAppOpenStatus.Opened ->
                    "I asked the companion to open $targetLabel and it reported success. ${result.detail}"
                CompanionAppOpenStatus.Recorded ->
                    "The request to open $targetLabel was recorded, but execution has not been confirmed yet. ${result.detail}"
                CompanionAppOpenStatus.Failed ->
                    "Opening $targetLabel failed on the companion. ${result.detail}"
                CompanionAppOpenStatus.Misconfigured ->
                    "The companion configuration needs work before I can open $targetLabel. ${result.detail}"
                CompanionAppOpenStatus.Skipped ->
                    "The request to open $targetLabel was skipped in the current companion state. ${result.detail}"
            }
        }
    }

    private fun companionSessionNotifyReply(
        prompt: String,
        result: CompanionSessionNotifyResult,
    ): String {
        return if (prefersKorean(prompt)) {
            when (result.status) {
                CompanionSessionNotifyStatus.Delivered ->
                    "Companion notification을 보냈고 전달 확인을 받았어요. ${result.detail}"
                CompanionSessionNotifyStatus.Failed ->
                    "Companion notification 전송이 실패했어요. ${result.detail}"
                CompanionSessionNotifyStatus.Misconfigured ->
                    "Companion notification을 보내기 전에 설정을 먼저 바로잡아야 해요. ${result.detail}"
                CompanionSessionNotifyStatus.Skipped ->
                    "Companion notification 요청은 이번 상태에서 건너뛰었어요. ${result.detail}"
            }
        } else {
            when (result.status) {
                CompanionSessionNotifyStatus.Delivered ->
                    "I sent the companion notification and the delivery request was accepted. ${result.detail}"
                CompanionSessionNotifyStatus.Failed ->
                    "Sending the companion notification failed. ${result.detail}"
                CompanionSessionNotifyStatus.Misconfigured ->
                    "The companion configuration needs work before I can send a notification. ${result.detail}"
                CompanionSessionNotifyStatus.Skipped ->
                    "The companion notification request was skipped in the current state. ${result.detail}"
            }
        }
    }

    private fun companionHealthProbeReply(
        prompt: String,
        result: CompanionHealthCheckResult,
    ): String {
        return if (prefersKorean(prompt)) {
            when (result.status) {
                CompanionHealthStatus.Healthy ->
                    buildCompanionHealthSuccessReply(
                        summary = result.summary,
                        detail = result.detail,
                        korean = true,
                    )
                CompanionHealthStatus.Unreachable ->
                    "Companion health probe가 endpoint에 닿지 못했어요. ${result.detail}"
                CompanionHealthStatus.Misconfigured ->
                    "Companion health probe를 실행하기 전에 설정을 먼저 바로잡아야 해요. ${result.detail}"
                CompanionHealthStatus.Skipped ->
                    "Companion health probe는 이번 상태에서 건너뛰었어요. ${result.detail}"
            }
        } else {
            when (result.status) {
                CompanionHealthStatus.Healthy ->
                    buildCompanionHealthSuccessReply(
                        summary = result.summary,
                        detail = result.detail,
                        korean = false,
                    )
                CompanionHealthStatus.Unreachable ->
                    "The companion health probe could not reach the endpoint. ${result.detail}"
                CompanionHealthStatus.Misconfigured ->
                    "The companion configuration needs work before I can run the health probe. ${result.detail}"
                CompanionHealthStatus.Skipped ->
                    "The companion health probe was skipped in the current state. ${result.detail}"
            }
        }
    }

    private fun buildCompanionHealthSuccessReply(
        summary: String,
        detail: String,
        korean: Boolean,
    ): String {
        return buildString {
            append(
                if (korean) {
                    "Companion online이에요."
                } else {
                    "Companion is online."
                },
            )
            summary.takeIf { it.isNotBlank() }?.let { value ->
                append(' ')
                append(value)
                if (!value.endsWith('.')) {
                    append('.')
                }
            }
            companionHealthLocationDetail(detail, korean)?.let { labeledDetail ->
                append(' ')
                append(labeledDetail)
            }
        }
    }

    private fun companionHealthLocationDetail(
        detail: String,
        korean: Boolean,
    ): String? {
        val normalizedDetail = detail.trim()
        if (normalizedDetail.isBlank()) {
            return null
        }
        val label = if (normalizedDetail.startsWith("http", ignoreCase = true)) {
            "Endpoint"
        } else if (korean) {
            "Inbox 경로"
        } else {
            "Inbox"
        }
        return "$label: $normalizedDetail"
    }

    private fun companionWorkflowReply(
        prompt: String,
        workflowId: String,
        result: CompanionWorkflowRunResult,
    ): String {
        val workflowLabel = companionWorkflowDisplayName(prompt, workflowId)
        return if (prefersKorean(prompt)) {
            when (result.status) {
                CompanionWorkflowRunStatus.Completed ->
                    "$workflowLabel workflow 실행을 companion에 요청했고 완료 확인을 받았어요. ${result.detail}"
                CompanionWorkflowRunStatus.Recorded ->
                    "$workflowLabel workflow 요청은 기록됐지만 아직 실제 실행 확인은 못 했어요. ${result.detail}"
                CompanionWorkflowRunStatus.Failed ->
                    "$workflowLabel workflow 실행이 실패했어요. ${result.detail}"
                CompanionWorkflowRunStatus.Misconfigured ->
                    "$workflowLabel workflow를 실행하기 전에 companion 설정을 먼저 바로잡아야 해요. ${result.detail}"
                CompanionWorkflowRunStatus.Skipped ->
                    "$workflowLabel workflow 요청은 이번 상태에서 건너뛰었어요. ${result.detail}"
            }
        } else {
            when (result.status) {
                CompanionWorkflowRunStatus.Completed ->
                    "I asked the companion to run the $workflowLabel workflow and it reported completion. ${result.detail}"
                CompanionWorkflowRunStatus.Recorded ->
                    "The $workflowLabel workflow request was recorded, but execution has not been confirmed yet. ${result.detail}"
                CompanionWorkflowRunStatus.Failed ->
                    "Running the $workflowLabel workflow failed on the companion. ${result.detail}"
                CompanionWorkflowRunStatus.Misconfigured ->
                    "The companion configuration needs work before I can run the $workflowLabel workflow. ${result.detail}"
                CompanionWorkflowRunStatus.Skipped ->
                    "The $workflowLabel workflow request was skipped in the current companion state. ${result.detail}"
            }
        }
    }

    private fun strategyLabel(
        prompt: String,
        strategy: FileOrganizeStrategy,
    ): String {
        return if (prefersKorean(prompt)) {
            when (strategy) {
                FileOrganizeStrategy.ByType -> "유형 기준"
                FileOrganizeStrategy.BySource -> "출처 기준"
            }
        } else {
            when (strategy) {
                FileOrganizeStrategy.ByType -> "by-type"
                FileOrganizeStrategy.BySource -> "by-source"
            }
        }
    }

    private fun companionTargetLabel(targetKind: String): String {
        return when (targetKind) {
            companionAppOpenTargetInbox -> "Desktop companion inbox"
            companionAppOpenTargetLatestTransfer -> "Latest transfer folder"
            companionAppOpenTargetActionsFolder -> "Actions folder"
            companionAppOpenTargetLatestAction -> "Latest action folder"
            else -> targetKind
        }
    }

    private fun companionTargetDisplayName(
        prompt: String,
        targetKind: String,
    ): String {
        return if (prefersKorean(prompt)) {
            when (targetKind) {
                companionAppOpenTargetInbox -> "companion inbox"
                companionAppOpenTargetLatestTransfer -> "최근 전송 폴더"
                companionAppOpenTargetActionsFolder -> "actions 폴더"
                companionAppOpenTargetLatestAction -> "최근 액션 폴더"
                else -> targetKind
            }
        } else {
            companionTargetLabel(targetKind)
        }
    }

    private fun companionWorkflowLabel(workflowId: String): String {
        return when (workflowId) {
            companionWorkflowIdOpenLatestTransfer -> "Open latest transfer"
            companionWorkflowIdOpenActionsFolder -> "Open actions folder"
            companionWorkflowIdOpenLatestAction -> "Open latest action"
            else -> workflowId
        }
    }

    private fun companionWorkflowDisplayName(
        prompt: String,
        workflowId: String,
    ): String {
        return if (prefersKorean(prompt)) {
            when (workflowId) {
                companionWorkflowIdOpenLatestTransfer -> "최근 전송 열기"
                companionWorkflowIdOpenActionsFolder -> "actions 폴더 열기"
                companionWorkflowIdOpenLatestAction -> "최근 액션 열기"
                else -> workflowId
            }
        } else {
            companionWorkflowLabel(workflowId)
        }
    }

    private fun externalEndpointDisplayName(endpointId: String): String {
        return when (endpointId) {
            mcpBridgeEndpointId -> "Companion MCP bridge"
            browserAutomationEndpointId -> "Browser automation profile"
            thirdPartyApiEndpointId -> "Third-party API profile"
            else -> endpointId
        }
    }

    private suspend fun refreshMcpEndpoint(): ExternalEndpointProfileState? {
        externalEndpointRepository.refresh()
        return externalEndpointRepository.profiles.value.firstOrNull {
            it.endpointId == mcpBridgeEndpointId
        }
    }

    private suspend fun refreshMcpBridgeFromCompanion(context: AgentTurnContext) {
        val targetDevice = resolveMcpCompanion(context) ?: return
        val discovery = devicePairingRepository.discoverMcpBridge(targetDevice.id)
        if (discovery.status != McpBridgeDiscoveryStatus.Ready) {
            return
        }
        externalEndpointRepository.markConnected(
            mcpBridgeEndpointId,
            ExternalEndpointConnectionSnapshot(
                endpointLabel = discovery.serverLabel ?: targetDevice.name,
                summary = discovery.summary,
                transportLabel = discovery.transportLabel,
                authLabel = discovery.authLabel,
                toolNames = discovery.toolNames,
                toolSchemas = discovery.toolSchemas,
                skillBundles = discovery.skillBundles,
                workflowIds = discovery.workflowIds,
                healthDetails = discovery.detail,
            ),
        )
    }

    private suspend fun resolveBrowserExecution(context: AgentTurnContext): BrowserExecutionSnapshot? {
        val targetDevice = resolveMcpCompanion(context)
        val connectedEndpoint = externalEndpointRepository.profiles.value.firstOrNull {
            it.endpointId == mcpBridgeEndpointId && it.status == ExternalEndpointStatus.Connected
        }
        if (connectedEndpoint != null && connectedEndpoint.toolNames.any(::isBrowserExecutionTool)) {
            return BrowserExecutionSnapshot(
                deviceId = targetDevice?.id,
                label = connectedEndpoint.endpointLabel ?: connectedEndpoint.displayName,
                toolNames = connectedEndpoint.toolNames,
                canBrowseWebPages = true,
            )
        }
        if (targetDevice == null) {
            return null
        }
        val discovery = devicePairingRepository.discoverMcpBridge(targetDevice.id)
        if (discovery.status != McpBridgeDiscoveryStatus.Ready) {
            return null
        }
        externalEndpointRepository.markConnected(
            mcpBridgeEndpointId,
            ExternalEndpointConnectionSnapshot(
                endpointLabel = discovery.serverLabel ?: targetDevice.name,
                summary = discovery.summary,
                transportLabel = discovery.transportLabel,
                authLabel = discovery.authLabel,
                toolNames = discovery.toolNames,
                toolSchemas = discovery.toolSchemas,
                skillBundles = discovery.skillBundles,
                workflowIds = discovery.workflowIds,
                healthDetails = discovery.detail,
            ),
        )
        externalEndpointRepository.refresh()
        return if (discovery.toolNames.any(::isBrowserExecutionTool)) {
            BrowserExecutionSnapshot(
                deviceId = targetDevice.id,
                label = discovery.serverLabel ?: targetDevice.name,
                toolNames = discovery.toolNames,
                canBrowseWebPages = true,
            )
        } else {
            null
        }
    }

    private fun isBrowserExecutionTool(toolName: String): Boolean {
        return toolName == "browser.navigate" || toolName == "browser.extract"
    }

    private fun webPageAccessReply(
        prompt: String,
        requestedUrl: String,
        result: CompanionMcpToolCallResult,
        bridgeLabel: String,
    ): String {
        val finalUrl = result.finalUrl ?: requestedUrl
        val pageTitle = result.pageTitle
        val contentPreview = result.contentText?.trim().orEmpty().takeIf { it.isNotBlank() }
        return if (prefersKorean(prompt)) {
            buildString {
                append("$bridgeLabel MCP bridge로 웹 페이지에 접근했습니다. ")
                pageTitle?.let {
                    append("제목은 \"$it\" 입니다. ")
                }
                append("최종 URL은 $finalUrl 입니다. ")
                if (!contentPreview.isNullOrBlank()) {
                    append("읽은 내용 요약: ")
                    append(contentPreview)
                } else {
                    append(result.detail)
                }
            }
        } else {
            buildString {
                append("I opened the webpage through the $bridgeLabel MCP bridge. ")
                pageTitle?.let {
                    append("The page title is \"$it\". ")
                }
                append("The final URL is $finalUrl. ")
                if (!contentPreview.isNullOrBlank()) {
                    append("Readable summary: ")
                    append(contentPreview)
                } else {
                    append(result.detail)
                }
            }
        }
    }

    private fun resolveMcpCompanion(context: AgentTurnContext): PairedDeviceState? {
        return context.selectedTargetDeviceId?.let { selectedId ->
            context.pairedDevices.firstOrNull { it.id == selectedId }
        }?.takeIf { it.transportMode == DeviceTransportMode.DirectHttp }
            ?: context.pairedDevices.firstOrNull { it.transportMode == DeviceTransportMode.DirectHttp }
    }

    private fun deliveryChannelDisplayName(channelId: String): String {
        return when (channelId) {
            localNotificationChannelId -> "Phone local notification"
            telegramDeliveryChannelId -> "Telegram bot relay"
            desktopCompanionDeliveryChannelId -> "Desktop companion relay"
            webhookDeliveryChannelId -> "Custom webhook relay"
            else -> channelId
        }
    }

    private fun extractTelegramBotToken(prompt: String): String? {
        return telegramTokenPattern.find(prompt)?.value
    }

    private fun extractTelegramChatId(prompt: String): String? {
        telegramChatPattern.find(prompt)?.groupValues?.getOrNull(1)?.trim()?.let { return it }
        telegramNumericChatPattern.find(prompt)?.value?.trim()?.let { return it }
        return null
    }

    private fun extractStructuredPromptField(
        prompt: String,
        keys: List<String>,
    ): String? {
        keys.forEach { key ->
            val pattern = Regex("""(?:^|\s)${Regex.escape(key)}\s+(?:"([^"]+)"|'([^']+)'|([^\s]+))""", RegexOption.IGNORE_CASE)
            val match = pattern.find(prompt) ?: return@forEach
            val value = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun buildTelegramValidationMessage(
        prompt: String,
        chatId: String,
    ): String {
        return if (prefersKorean(prompt)) {
            "Makoion Telegram 연결 검증입니다.\n대상 chat: $chatId\n이 메시지가 보이면 Telegram relay가 정상적으로 연결됐습니다."
        } else {
            "This is a Makoion Telegram delivery validation.\nTarget chat: $chatId\nIf you can read this message, the Telegram relay is connected correctly."
        }
    }

    private fun planTurn(
        prompt: String,
        context: AgentTurnContext,
    ): AgentPlannerOutput {
        val normalized = prompt.lowercase()
        val requestedWebUrl = extractFirstWebUrl(prompt)
        val approvalId = approvalIdFromPrompt(prompt)
        val taskId = taskIdFromPrompt(prompt)
        val automationId = automationIdFromPrompt(prompt)
        val connectedMcpEndpoint = context.externalEndpoints.firstOrNull {
            it.endpointId == mcpBridgeEndpointId && it.status == ExternalEndpointStatus.Connected
        }
        val wantsOpen = containsAny(
            normalized,
            "open",
            "show",
            "launch",
            "열어",
            "열기",
            "보여",
        )
        val wantsApproveAction = containsAny(
            normalized,
            "approve",
            "승인해",
            "승인해줘",
            "승인 처리",
            "허가해",
            "허가해줘",
        )
        val wantsDenyAction = containsAny(
            normalized,
            "deny",
            "reject",
            "decline",
            "거절",
            "반려",
            "승인하지마",
            "취소해",
        )
        val wantsRetryAction = containsAny(
            normalized,
            "retry",
            "rerun",
            "run again",
            "재시도",
            "다시 시도",
            "다시 실행",
            "다시 해",
        )
        val mentionsRecovery = containsAny(
            normalized,
            "recovery",
            "recover",
            "shell recovery",
            "복구",
            "리커버리",
        )
        val wantsRunShellRecovery = mentionsRecovery && containsAny(
            normalized,
            "run recovery",
            "start recovery",
            "recover now",
            "refresh shell state",
            "run shell recovery",
            "복구 실행",
            "복구해",
            "리커버리 실행",
        )
        val wantsShowShellRecoveryStatus = mentionsRecovery && containsAny(
            normalized,
            "recovery status",
            "show recovery",
            "show shell recovery",
            "recovery detail",
            "복구 상태",
            "리커버리 상태",
            "복구 보여",
        )
        val wantsShowResourceStack = containsAny(
            normalized,
            "resource stack",
            "connected resources",
            "resource summary",
            "resource status",
            "delivery status",
            "notification channel",
            "alert channel",
            "알림 채널",
            "알림 어디",
            "보낼 수 있",
            "리소스 스택",
            "연결 자원",
            "자원 상태",
            "리소스 상태",
        )
        val goalPlan = planAgentGoal(prompt, context)
        val mentionsMailbox = containsAny(
            normalized,
            "email",
            "mailbox",
            "imap",
            "gmail",
            "이메일",
            "메일함",
            "메일",
        )
        val wantsMailboxStatus = mentionsMailbox && containsAny(
            normalized,
            "mailbox status",
            "email status",
            "show mailbox",
            "mailbox summary",
            "상태",
            "요약",
            "보여",
            "현재",
        )
        val wantsConnectMailbox = mentionsMailbox &&
            (
                containsAny(
                    normalized,
                    "connect mailbox",
                    "mailbox connect",
                    "setup mailbox",
                    "메일 연결",
                    "메일함 연결",
                    "이메일 연결",
                ) ||
                    containsAny(normalized, "host", "server", "user", "username", "password", "비밀번호")
                )
        val wantsInitialSetup = containsAny(
            normalized,
            "initial setup",
            "start setup",
            "first run",
            "first setup",
            "setup first",
            "api key",
            "provider key",
            "model key",
            "credential setup",
            "token setup",
            "초기 설정",
            "초기세팅",
            "처음 설정",
            "처음 세팅",
            "api 키",
            "모델 키",
            "provider 키",
            "어떻게 시작",
            "무엇부터 해야",
            "뭘 먼저 해야",
            "처음 뭐 해야",
        )
        val wantsShowDashboard = containsAny(
            normalized,
            "open dashboard",
            "show dashboard",
            "dashboard",
            "대시보드",
        )
        val wantsShowHistory = containsAny(
            normalized,
            "open history",
            "show history",
            "history",
            "audit",
            "log",
            "기록",
            "히스토리",
            "로그",
        )
        val wantsShowSettings = containsAny(
            normalized,
            "open settings",
            "show settings",
            "settings",
            "설정 열어",
            "설정 보여",
            "설정으로",
        )
        val cloudDriveProvider = cloudDriveProviderFromPrompt(normalized)
        val externalEndpointId = externalEndpointIdFromPrompt(normalized)
        val deliveryChannelId = deliveryChannelIdFromPrompt(normalized)
        val wantsStageResource = containsAny(
            normalized,
            "stage",
            "prepare",
            "seed",
            "reserve",
            "준비",
            "스테이지",
            "스테이징",
        )
        val wantsConnectResource = containsAny(
            normalized,
            "connect",
            "enable",
            "activate",
            "mock-ready",
            "연결",
            "활성화",
        )
        val mentionsAutomation = containsAny(
            normalized,
            "automation",
            "automations",
            "schedule",
            "scheduled",
            "반복",
            "자동화",
        )
        val wantsRunAutomationNow = mentionsAutomation && containsAny(
            normalized,
            "run automation",
            "run the automation",
            "run latest automation",
            "run the latest automation",
            "automation now",
            "run schedule now",
            "지금 automation",
            "자동화 실행",
            "반복 작업 실행",
        )
        val wantsPauseAutomation = mentionsAutomation && containsAny(
            normalized,
            "pause automation",
            "pause the automation",
            "stop automation",
            "disable automation",
            "일시정지",
            "멈춰",
            "중지",
        )
        val wantsActivateAutomation = mentionsAutomation && containsAny(
            normalized,
            "activate automation",
            "start automation",
            "resume automation",
            "enable automation",
            "automation on",
            "활성화",
            "다시 켜",
            "재개",
        )
        val wantsConnectMcpBridge = containsAny(
            normalized,
            "connect mcp bridge",
            "setup mcp bridge",
            "enable mcp bridge",
            "mcp bridge 연결",
            "mcp 연결",
            "mcp 브리지 연결",
        )
        val wantsSyncMcpSkills = containsAny(
            normalized,
            "update mcp skills",
            "sync mcp skills",
            "install mcp skills",
            "refresh mcp skills",
            "mcp skill 업데이트",
            "mcp 스킬 업데이트",
            "mcp skill 동기화",
            "mcp 스킬 동기화",
        )
        val wantsShowMcpSkills = containsAny(
            normalized,
            "show mcp skills",
            "list mcp skills",
            "what mcp skills",
            "mcp skill 목록",
            "mcp 스킬 목록",
            "mcp 스킬 보여",
        )
        val wantsShowMcpConnectorStatus = containsAny(
            normalized,
            "show mcp status",
            "mcp connector status",
            "mcp bridge status",
            "mcp status",
            "mcp 상태",
            "mcp 브리지 상태",
        )
        val wantsShowMcpTools = containsAny(
            normalized,
            "show mcp tools",
            "list mcp tools",
            "what mcp tools",
            "mcp tools",
            "mcp tool 목록",
            "mcp 도구",
        )
        val wantsExplainMcpSetup = containsAny(
            normalized,
            "mcp",
            "model context protocol",
            "mcp 서버",
            "mcp 브리지",
        ) && containsAny(
            normalized,
            "how",
            "help",
            "setup",
            "start",
            "guide",
            "what do i need",
            "how do i",
            "how should i",
            "연동",
            "어떻게",
            "설명",
            "도움",
            "뭐부터",
            "무엇부터",
            "처음",
        )
        val wantsExplainEmailSetup = containsAny(
            normalized,
            "email",
            "mailbox",
            "gmail",
            "이메일",
            "메일",
        ) && containsAny(
            normalized,
            "how",
            "help",
            "setup",
            "start",
            "guide",
            "possible",
            "available",
            "connect",
            "연동",
            "가능",
            "어떻게",
            "설명",
            "도움",
            "뭐부터",
            "무엇부터",
            "처음",
        )
        return when {
            wantsDenyAction ->
                plannerOutput(
                    intent = AgentIntent.DenyPendingApproval(approvalId),
                    auditResult = "approval_denied_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Resolve a pending approval by denying it from the chat loop.",
                    capabilities = listOf("approvals.resolve"),
                    resources = listOf("approval.inbox"),
                )
            wantsApproveAction ->
                plannerOutput(
                    intent = AgentIntent.ApprovePendingApproval(approvalId),
                    auditResult = "approval_approved_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Resolve a pending approval by approving it from the chat loop.",
                    capabilities = listOf("approvals.resolve"),
                    resources = listOf("approval.inbox"),
                )
            wantsRetryAction ->
                plannerOutput(
                    intent = AgentIntent.RetryTask(taskId),
                    auditResult = "task_retried_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Retry a previously failed or waiting task from chat.",
                    capabilities = listOf("task.retry"),
                    resources = listOf("task.runtime"),
                )
            wantsRunShellRecovery ->
                plannerOutput(
                    intent = AgentIntent.RunShellRecovery,
                    auditResult = "shell_recovery_requested_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run manual shell recovery from the chat loop and wait for the latest state to settle.",
                    capabilities = listOf("shell.recovery.run"),
                    resources = listOf("task.runtime", "audit.history"),
                )
            wantsShowShellRecoveryStatus ->
                plannerOutput(
                    intent = AgentIntent.ShowShellRecoveryStatus,
                    auditResult = "shell_recovery_status_shown",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize the most recent shell recovery status and detail in chat.",
                    capabilities = listOf("shell.recovery.read"),
                    resources = listOf("task.runtime", "audit.history"),
                )
            wantsInitialSetup ->
                plannerOutput(
                    intent = AgentIntent.ExplainInitialSetup,
                    auditResult = "initial_setup_explained",
                    mode = AgentPlannerMode.Answer,
                    summary = "Explain the first-run setup path while keeping the user in chat.",
                    capabilities = listOf("model.providers", "phone.local_storage"),
                    resources = listOf("resource.stack"),
                )
            wantsShowResourceStack ->
                plannerOutput(
                    intent = AgentIntent.ShowResourceStack,
                    auditResult = "resource_stack_shown",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize the current connected and staged resource stack from chat.",
                    capabilities = listOf("resource.stack.read"),
                    resources = listOf("resource.stack"),
                )
            cloudDriveProvider != null && wantsStageResource ->
                plannerOutput(
                    intent = AgentIntent.StageCloudDrive(cloudDriveProvider),
                    auditResult = "cloud_drive_staged_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Stage a cloud drive connector profile from chat.",
                    capabilities = listOf("cloud.connectors.stage"),
                    resources = listOf("cloud.drives"),
                )
            cloudDriveProvider != null && wantsConnectResource ->
                plannerOutput(
                    intent = AgentIntent.ConnectCloudDrive(cloudDriveProvider),
                    auditResult = "cloud_drive_connected_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Mark a cloud drive connector as mock-ready from chat.",
                    capabilities = listOf("cloud.connectors.connect"),
                    resources = listOf("cloud.drives"),
                )
            wantsSyncMcpSkills ->
                plannerOutput(
                    intent = AgentIntent.SyncMcpSkills,
                    auditResult = "mcp_skills_synced",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = mcpSkillSyncPlannerSummary(connectedMcpEndpoint),
                    capabilities = listOf("mcp.skills.sync"),
                    resources = listOf("mcp.api_endpoints", "mcp.skill_bundles"),
                )
            wantsShowMcpTools ->
                plannerOutput(
                    intent = AgentIntent.ShowMcpTools,
                    auditResult = "mcp_tools_listed",
                    mode = AgentPlannerMode.Answer,
                    summary = mcpToolPlannerSummary(connectedMcpEndpoint),
                    capabilities = listOf("mcp.tools.list"),
                    resources = listOf("mcp.api_endpoints", "mcp.tool_schemas"),
                )
            wantsShowMcpConnectorStatus ->
                plannerOutput(
                    intent = AgentIntent.ShowMcpConnectorStatus,
                    auditResult = "mcp_connector_status_shown",
                    mode = AgentPlannerMode.Answer,
                    summary = mcpStatusPlannerSummary(connectedMcpEndpoint),
                    capabilities = listOf("mcp.connect", "mcp.tools.list"),
                    resources = listOf("mcp.api_endpoints", "mcp.skill_bundles", "mcp.tool_schemas"),
                )
            wantsShowMcpSkills ->
                plannerOutput(
                    intent = AgentIntent.ShowMcpSkills,
                    auditResult = "mcp_skills_listed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize the installed MCP skill catalog.",
                    capabilities = listOf("mcp.skills.sync"),
                    resources = listOf("mcp.api_endpoints"),
                )
            wantsConnectMcpBridge ->
                plannerOutput(
                    intent = AgentIntent.ConnectMcpBridge,
                    auditResult = "mcp_bridge_connected",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = mcpConnectPlannerSummary(connectedMcpEndpoint),
                    capabilities = listOf("mcp.connect"),
                    resources = listOf("mcp.api_endpoints", "external.companion"),
                )
            wantsExplainMcpSetup ->
                plannerOutput(
                    intent = AgentIntent.ExplainMcpSetup,
                    auditResult = "mcp_setup_explained",
                    mode = AgentPlannerMode.Answer,
                    summary = "Explain how MCP connection works inside the chat-first shell and show the next step.",
                    capabilities = listOf("mcp.connect", "mcp.skills.sync"),
                    resources = listOf("external.companion", "mcp.api_endpoints"),
                )
            wantsMailboxStatus ->
                plannerOutput(
                    intent = AgentIntent.ShowMailboxStatus,
                    auditResult = "mailbox_status_shown",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize the currently recorded mailbox connection in chat.",
                    capabilities = listOf("mail.connect", "mail.read"),
                    resources = listOf("mailbox.connector"),
                )
            wantsConnectMailbox ->
                plannerOutput(
                    intent = AgentIntent.ConnectMailbox,
                    auditResult = "mailbox_connect_requested",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Store mailbox credentials, validate the IMAP inbox, and activate the mailbox connector from chat.",
                    capabilities = listOf("mail.connect", "mail.read", "mail.move"),
                    resources = listOf("mailbox.connector"),
                )
            wantsExplainEmailSetup ->
                plannerOutput(
                    intent = AgentIntent.ExplainEmailSetup,
                    auditResult = "email_setup_explained",
                    mode = AgentPlannerMode.Answer,
                    summary = "Explain the email automation connection format and current mailbox state from chat.",
                    capabilities = listOf("mail.connect", "mail.classify", "delivery.alert"),
                    resources = listOf("mailbox.connector", "delivery.channels"),
                )
            externalEndpointId != null && wantsStageResource ->
                plannerOutput(
                    intent = AgentIntent.StageExternalEndpoint(externalEndpointId),
                    auditResult = "external_endpoint_staged_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Stage an external endpoint profile from chat.",
                    capabilities = listOf("external.endpoint.stage"),
                    resources = listOf("mcp.api_endpoints"),
                )
            externalEndpointId != null && wantsConnectResource ->
                plannerOutput(
                    intent = AgentIntent.ConnectExternalEndpoint(externalEndpointId),
                    auditResult = "external_endpoint_connected_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Mark an external endpoint profile as mock-ready from chat.",
                    capabilities = listOf("external.endpoint.connect"),
                    resources = listOf("mcp.api_endpoints"),
                )
            deliveryChannelId != null && wantsStageResource ->
                plannerOutput(
                    intent = AgentIntent.StageDeliveryChannel(deliveryChannelId),
                    auditResult = "delivery_channel_staged_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Stage a delivery channel profile from chat.",
                    capabilities = listOf("delivery.channel.stage"),
                    resources = listOf("delivery.channels"),
                )
            deliveryChannelId != null && wantsConnectResource ->
                plannerOutput(
                    intent = AgentIntent.ConnectDeliveryChannel(deliveryChannelId),
                    auditResult = "delivery_channel_connected_from_chat",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Mark a delivery channel profile as mock-ready from chat.",
                    capabilities = listOf("delivery.channel.connect"),
                    resources = listOf("delivery.channels"),
                )
            goalPlan != null ->
                plannerOutput(
                    intent = when (goalPlan.type) {
                        AgentGoalType.TelegramConnect -> AgentIntent.ConnectDeliveryChannel(telegramDeliveryChannelId)
                        AgentGoalType.MarketNewsWatch,
                        AgentGoalType.MorningBriefing,
                        AgentGoalType.EmailTriage -> AgentIntent.PlanScheduledAutomation
                    },
                    auditResult = when (goalPlan.type) {
                        AgentGoalType.TelegramConnect -> "telegram_connect_requested"
                        AgentGoalType.MarketNewsWatch -> "market_news_goal_planned"
                        AgentGoalType.MorningBriefing -> "morning_briefing_goal_planned"
                        AgentGoalType.EmailTriage -> "email_triage_goal_planned"
                    },
                    mode = AgentPlannerMode.Plan,
                    summary = goalPlan.summary,
                    capabilities = goalPlan.recipe.capabilities,
                    resources = goalPlan.requirements.map { it.capabilityId }.ifEmpty { listOf("task.runtime") },
                )
            wantsRunAutomationNow ->
                plannerOutput(
                    intent = AgentIntent.RunScheduledAutomationNow(automationId),
                    auditResult = "scheduled_automation_run_now",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run a scheduled automation immediately from the chat loop.",
                    capabilities = listOf("automation.schedule.run"),
                    resources = listOf("task.runtime", "notifications.delivery"),
                )
            wantsPauseAutomation ->
                plannerOutput(
                    intent = AgentIntent.PauseScheduledAutomation(automationId),
                    auditResult = "scheduled_automation_paused",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Pause a scheduled automation from chat.",
                    capabilities = listOf("automation.schedule.pause"),
                    resources = listOf("task.runtime"),
                )
            wantsActivateAutomation ->
                plannerOutput(
                    intent = AgentIntent.ActivateScheduledAutomation(automationId),
                    auditResult = "scheduled_automation_activated",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Activate a scheduled automation from chat.",
                    capabilities = listOf("automation.schedule.activate"),
                    resources = listOf("task.runtime"),
                )
            containsAny(
                normalized,
                "check companion health",
                "probe companion health",
                "health probe",
                "check health",
                "companion health",
                "헬스 체크",
                "헬스체크",
                "상태 확인",
                "건강 확인",
            ) ->
                plannerOutput(
                    intent = AgentIntent.ProbeCompanionHealth,
                    auditResult = "companion_health_probed",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Probe the selected companion health endpoint and refresh its capability snapshot.",
                    capabilities = listOf("devices.health_probe"),
                    resources = listOf("external.companion"),
                )
            containsAny(
                normalized,
                "session.notify",
                "desktop notification",
                "companion notification",
                "notify companion",
                "notify desktop",
                "send a desktop notification",
                "send desktop notification",
                "세션 알림",
                "데스크톱 알림",
                "컴패니언 알림",
                "알림 보내",
            ) ->
                plannerOutput(
                    intent = AgentIntent.SendCompanionSessionNotification,
                    auditResult = "companion_session_notified",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Send a session notification to the selected companion.",
                    capabilities = listOf("devices.session_notify"),
                    resources = listOf("external.companion"),
                )
            containsAny(normalized, "workflow.run", "workflow", "워크플로") &&
                containsAny(normalized, "latest action", "recent action", "last action", "최근 액션", "방금 액션") ->
                plannerOutput(
                    intent = AgentIntent.RunCompanionWorkflow(companionWorkflowIdOpenLatestAction),
                    auditResult = "companion_latest_action_workflow_run",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run the allowlisted desktop workflow that opens the latest companion action.",
                    capabilities = listOf("devices.workflow_run"),
                    resources = listOf("external.companion"),
                )
            containsAny(normalized, "workflow.run", "workflow", "워크플로") &&
                containsAny(normalized, "latest transfer", "recent transfer", "최근 전송", "전송 폴더") ->
                plannerOutput(
                    intent = AgentIntent.RunCompanionWorkflow(companionWorkflowIdOpenLatestTransfer),
                    auditResult = "companion_latest_transfer_workflow_run",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run the allowlisted desktop workflow that opens the latest transfer.",
                    capabilities = listOf("devices.workflow_run"),
                    resources = listOf("external.companion"),
                )
            containsAny(normalized, "workflow.run", "workflow", "워크플로") &&
                containsAny(normalized, "actions folder", "action folder", "액션 폴더", "actions") ->
                plannerOutput(
                    intent = AgentIntent.RunCompanionWorkflow(companionWorkflowIdOpenActionsFolder),
                    auditResult = "companion_actions_workflow_run",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run the allowlisted desktop workflow that opens the companion actions folder.",
                    capabilities = listOf("devices.workflow_run"),
                    resources = listOf("external.companion"),
                )
            wantsOpen && containsAny(normalized, "inbox", "받은", "수신") ->
                plannerOutput(
                    intent = AgentIntent.OpenCompanionTarget(companionAppOpenTargetInbox),
                    auditResult = "companion_inbox_open",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Open the companion inbox surface.",
                    capabilities = listOf("devices.app_open"),
                    resources = listOf("external.companion"),
                )
            wantsOpen &&
                containsAny(normalized, "latest action", "recent action", "last action", "최근 액션", "방금 액션") ->
                plannerOutput(
                    intent = AgentIntent.OpenCompanionTarget(companionAppOpenTargetLatestAction),
                    auditResult = "companion_latest_action_open",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Open the latest companion action surface.",
                    capabilities = listOf("devices.app_open"),
                    resources = listOf("external.companion"),
                )
            wantsOpen && containsAny(normalized, "latest transfer", "recent transfer", "최근 전송", "전송 폴더") ->
                plannerOutput(
                    intent = AgentIntent.OpenCompanionTarget(companionAppOpenTargetLatestTransfer),
                    auditResult = "companion_latest_transfer_open",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Open the latest companion transfer surface.",
                    capabilities = listOf("devices.app_open"),
                    resources = listOf("external.companion"),
                )
            wantsOpen && containsAny(normalized, "actions folder", "action folder", "액션 폴더", "actions") ->
                plannerOutput(
                    intent = AgentIntent.OpenCompanionTarget(companionAppOpenTargetActionsFolder),
                    auditResult = "companion_actions_open",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Open the companion actions folder surface.",
                    capabilities = listOf("devices.app_open"),
                    resources = listOf("external.companion"),
                )
            containsAny(normalized, "refresh", "rescan", "reindex", "scan", "새로고침", "스캔", "인덱스") ->
                plannerOutput(
                    intent = AgentIntent.RefreshResources,
                    auditResult = "resources_refreshed",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Refresh indexed phone resources, approvals, audits, and paired companions.",
                    capabilities = listOf("resources.refresh"),
                    resources = listOf("phone.local_storage", "approval.inbox", "external.companion"),
                )
            looksLikeCodeGenerationPrompt(normalized) ->
                plannerOutput(
                    intent = AgentIntent.PlanCodeGeneration,
                    auditResult = "code_generation_planned",
                    mode = AgentPlannerMode.Plan,
                    summary = "Generate a phone-local starter scaffold for a code, app, or automation request and persist it as a durable dashboard project.",
                    capabilities = listOf("code.generate.plan"),
                    resources = listOf("phone.local_storage", "external.companion", "model.providers", "delivery.channels"),
                )
            looksLikeScheduledAutomationPrompt(normalized) ->
                plannerOutput(
                    intent = AgentIntent.PlanScheduledAutomation,
                    auditResult = "scheduled_automation_planned",
                    mode = AgentPlannerMode.Plan,
                    summary = "Capture a recurring request, persist it as a scheduled automation, and stage it for local dashboard activation.",
                    capabilities = listOf("automation.schedule.plan"),
                    resources = listOf("task.runtime", "notifications.delivery", "audit.history"),
                )
            requestedWebUrl != null ->
                plannerOutput(
                    intent = AgentIntent.BrowseWebPage(requestedWebUrl),
                    auditResult = "browser_page_access_requested",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Open the referenced webpage through the connected companion MCP bridge and return a readable summary.",
                    capabilities = listOf("browser.navigate", "browser.extract"),
                    resources = listOf("external.companion", "mcp.api_endpoints"),
                )
            looksLikeBrowserCapabilityQuestion(normalized) ->
                plannerOutput(
                    intent = AgentIntent.ExplainCapabilities,
                    auditResult = "capabilities_explained",
                    mode = AgentPlannerMode.Answer,
                    summary = "Explain the current browser and web capability limits instead of creating a blocked research task.",
                    capabilities = listOf("agent.capabilities.explain"),
                    resources = listOf("resource.stack", "model.providers"),
                )
            containsAny(
                normalized,
                "browser",
                "browse",
                "web",
                "research",
                "search web",
                "news",
                "article",
                "브라우저",
                "웹",
                "조사",
                "검색",
                "뉴스",
                "기사",
            ) ->
                plannerOutput(
                    intent = AgentIntent.PlanBrowserResearch,
                    auditResult = "browser_research_planned",
                    mode = AgentPlannerMode.Plan,
                    summary = "Capture a browser or web research request and mark it as waiting for browser automation resources.",
                    capabilities = listOf("browser.research.plan"),
                    resources = listOf("cloud.drives", "mcp.api_endpoints", "model.providers"),
                )
            containsAny(normalized, "summarize", "summary", "요약") ->
                plannerOutput(
                    intent = AgentIntent.SummarizeIndexedFiles,
                    auditResult = "files_summarized",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Summarize the files currently indexed from the phone resource stack.",
                    capabilities = listOf("files.summarize"),
                    resources = listOf("phone.local_storage", "phone.document_roots"),
                )
            containsAny(normalized, "organize", "정리", "분류") ->
                plannerOutput(
                    intent = AgentIntent.OrganizeIndexedFiles(strategyForPrompt(normalized)),
                    auditResult = "organize_requested",
                    mode = AgentPlannerMode.Plan,
                    summary = "Create a dry-run organize plan and raise an approval request before any destructive action.",
                    capabilities = listOf("files.organize", "approvals.request"),
                    resources = listOf("phone.local_storage", "phone.document_roots", "approval.inbox"),
                )
            containsAny(normalized, "approve", "approval", "review", "승인", "검토") || wantsShowDashboard ->
                plannerOutput(
                    intent = AgentIntent.ShowDashboard,
                    auditResult = "dashboard_routed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize dashboard-oriented approvals and task state for the user.",
                    capabilities = listOf("ui.route.dashboard"),
                    resources = listOf("shell.navigation"),
                )
            wantsShowHistory ->
                plannerOutput(
                    intent = AgentIntent.ShowHistory,
                    auditResult = "history_routed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize how to inspect audit and prior task activity.",
                    capabilities = listOf("ui.route.history"),
                    resources = listOf("shell.navigation", "audit.history"),
                )
            wantsShowSettings ->
                plannerOutput(
                    intent = AgentIntent.ShowSettings,
                    auditResult = "settings_routed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Summarize the Settings-level resource and permission state without leaving chat.",
                    capabilities = listOf("ui.route.settings"),
                    resources = listOf("shell.navigation", "resource.stack"),
                )
            containsAny(normalized, "send", "transfer", "share", "보내", "전송") ->
                plannerOutput(
                    intent = AgentIntent.TransferIndexedFiles,
                    auditResult = "transfer_requested",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Prepare a companion transfer approval for indexed files and the selected remote device.",
                    capabilities = listOf("files.transfer", "approvals.request"),
                    resources = listOf("phone.local_storage", "external.companion", "approval.inbox"),
                )
            context.modelPreference.configuredProviderIds.isNotEmpty() -> plannerOutput(
                intent = AgentIntent.RespondWithProviderConversation,
                auditResult = "provider_conversation_replied",
                mode = AgentPlannerMode.Answer,
                summary = "Use the configured model provider to answer a freeform chat turn that does not map to a built-in capability.",
                capabilities = listOf("model.providers.chat"),
                resources = listOf("model.providers", "task.runtime"),
            )
            else -> plannerOutput(
                intent = AgentIntent.ExplainCapabilities,
                auditResult = "capabilities_explained",
                mode = AgentPlannerMode.Answer,
                summary = "Explain how to start in chat and where setup or connected resources live.",
                capabilities = listOf("agent.capabilities.explain"),
                resources = listOf("resource.stack", "task.runtime"),
            )
        }
    }

    private fun resolveScheduledAutomation(
        prompt: String,
        context: AgentTurnContext,
        automationId: String?,
    ): ScheduledAutomationRecord? {
        automationId?.let { explicitId ->
            context.scheduledAutomations.firstOrNull { it.id == explicitId }?.let { return it }
        }
        val normalized = prompt.lowercase()
        context.scheduledAutomations.firstOrNull { automation ->
            normalized.contains(automation.title.lowercase())
        }?.let { return it }
        return context.scheduledAutomations.firstOrNull()
    }

    private fun plannerOutput(
        intent: AgentIntent,
        auditResult: String,
        mode: AgentPlannerMode,
        summary: String,
        capabilities: List<String>,
        resources: List<String>,
    ): AgentPlannerOutput {
        return AgentPlannerOutput(
            intent = intent,
            auditResult = auditResult,
            planningTrace = AgentPlanningTrace(
                mode = mode,
                summary = summary,
                capabilities = capabilities,
                resources = resources,
            ),
        )
    }

    private fun mcpConnectPlannerSummary(endpoint: ExternalEndpointProfileState?): String {
        val base = "Discover the MCP bridge from the selected direct HTTP companion and record its live tool inventory."
        return endpoint?.let { connected ->
            "$base ${mcpInventoryPlannerHint(connected)}"
        } ?: base
    }

    private fun mcpStatusPlannerSummary(endpoint: ExternalEndpointProfileState?): String {
        val base = "Summarize the MCP connector transport, auth, sync status, and cached execution inventory."
        return endpoint?.let { connected ->
            "$base ${mcpInventoryPlannerHint(connected)}"
        } ?: base
    }

    private fun mcpToolPlannerSummary(endpoint: ExternalEndpointProfileState?): String {
        val base = "Summarize the currently advertised MCP tools with schema hints and approval requirements."
        return endpoint?.let { connected ->
            "$base ${mcpInventoryPlannerHint(connected)}"
        } ?: base
    }

    private fun mcpSkillSyncPlannerSummary(endpoint: ExternalEndpointProfileState?): String {
        val base = "Sync the MCP skill catalog from the connected MCP bridge, preferring advertised skill bundles before tool-name fallback."
        return endpoint?.let { connected ->
            "$base ${mcpInventoryPlannerHint(connected)}"
        } ?: base
    }

    private fun mcpInventoryPlannerHint(endpoint: ExternalEndpointProfileState): String {
        val hintParts = mutableListOf<String>()
        if (endpoint.toolNames.isNotEmpty()) {
            hintParts += "Cached tools: ${endpoint.toolNames.take(3).joinToString()}${if (endpoint.toolNames.size > 3) " +" + (endpoint.toolNames.size - 3) else ""}."
        }
        val approvalTools = endpoint.toolSchemas
            .filter { it.requiresConfirmation }
            .map { it.name }
        if (approvalTools.isNotEmpty()) {
            hintParts += "Approval-gated tools: ${approvalTools.joinToString()}."
        }
        if (endpoint.skillBundles.isNotEmpty()) {
            hintParts += "Skill bundles: ${endpoint.skillBundles.joinToString { it.title }}."
        }
        if (endpoint.workflowIds.isNotEmpty()) {
            hintParts += "Workflows: ${endpoint.workflowIds.joinToString()}."
        }
        return hintParts.joinToString(" ").ifBlank {
            "No cached MCP schema inventory is available yet."
        }
    }

    private fun strategyForPrompt(normalizedPrompt: String): FileOrganizeStrategy {
        return if (containsAny(normalizedPrompt, "source", "출처", "원본", "폴더별")) {
            FileOrganizeStrategy.BySource
        } else {
            FileOrganizeStrategy.ByType
        }
    }

    private fun taskTitle(prompt: String): String {
        return prompt
            .trim()
            .replace('\n', ' ')
            .take(maxTaskTitleLength)
            .ifBlank { "Agent task" }
    }

    private fun prefersKorean(prompt: String): Boolean {
        return prompt.any { it in '\uAC00'..'\uD7A3' }
    }

    private fun containsAny(
        normalizedPrompt: String,
        vararg terms: String,
    ): Boolean {
        return terms.any { term -> normalizedPrompt.contains(term) }
    }

    private fun approvalIdFromPrompt(prompt: String): String? {
        return approvalIdPattern.find(prompt)?.value
    }

    private fun taskIdFromPrompt(prompt: String): String? {
        return taskIdPattern.find(prompt)?.value
    }

    private fun automationIdFromPrompt(prompt: String): String? {
        return automationIdPattern.find(prompt)?.value
    }

    private fun cloudDriveProviderFromPrompt(normalizedPrompt: String): CloudDriveProviderKind? {
        return when {
            containsAny(normalizedPrompt, "google drive", "gdrive", "구글 드라이브") ->
                CloudDriveProviderKind.GoogleDrive
            containsAny(normalizedPrompt, "onedrive", "원드라이브") ->
                CloudDriveProviderKind.OneDrive
            containsAny(normalizedPrompt, "dropbox", "드롭박스") ->
                CloudDriveProviderKind.Dropbox
            else -> null
        }
    }

    private fun externalEndpointIdFromPrompt(normalizedPrompt: String): String? {
        return when {
            containsAny(normalizedPrompt, "mcp bridge", "mcp server", "mcp 브리지", "mcp 서버") ->
                mcpBridgeEndpointId
            containsAny(normalizedPrompt, "browser automation", "browser profile", "브라우저 자동화") ->
                browserAutomationEndpointId
            containsAny(normalizedPrompt, "third-party api", "third party api", "api profile", "외부 api") ->
                thirdPartyApiEndpointId
            else -> null
        }
    }

    private fun deliveryChannelIdFromPrompt(normalizedPrompt: String): String? {
        return when {
            containsAny(normalizedPrompt, "phone notification", "local notification", "폰 알림", "로컬 알림") ->
                localNotificationChannelId
            containsAny(normalizedPrompt, "telegram", "텔레그램") ->
                telegramDeliveryChannelId
            containsAny(normalizedPrompt, "desktop companion relay", "desktop relay", "데스크톱 릴레이") ->
                desktopCompanionDeliveryChannelId
            containsAny(normalizedPrompt, "webhook", "웹훅") ->
                webhookDeliveryChannelId
            else -> null
        }
    }

    private fun approvalReply(
        prompt: String,
        approval: ApprovalInboxItem,
        linkedTask: AgentTaskRecord?,
        organizeExecution: PersistedOrganizeExecution?,
        transferQueuedFileCount: Int?,
        transferTargetLabel: String?,
    ): String {
        if (transferQueuedFileCount != null && transferTargetLabel != null) {
            return if (prefersKorean(prompt)) {
                "${approval.title} 요청을 승인했고 ${transferTargetLabel}로 ${transferQueuedFileCount}개 파일 전송을 큐에 올렸어요. 브리지 전송은 백그라운드에서 계속됩니다."
            } else {
                "I approved ${approval.title} and queued ${transferQueuedFileCount} file(s) for ${transferTargetLabel}. Bridge delivery will continue in the background."
            }
        }
        if (organizeExecution == null) {
            return if (prefersKorean(prompt)) {
                "${approval.title} 요청을 승인했습니다. 연결된 작업은 ${linkedTask?.status?.let { taskStatusLabel(prompt, it) } ?: "running"} 상태로 넘어갔어요."
            } else {
                "I approved ${approval.title}. The linked task is now ${linkedTask?.status?.let { taskStatusLabel(prompt, it) } ?: "running"}."
            }
        }

        return when (linkedTask?.status) {
            AgentTaskStatus.Succeeded -> if (prefersKorean(prompt)) {
                "${approval.title} 요청을 승인했고 정리 실행까지 끝냈어요. ${organizeExecution.result.summary}"
            } else {
                "I approved ${approval.title} and completed the organize execution. ${organizeExecution.result.summary}"
            }
            AgentTaskStatus.WaitingUser -> if (prefersKorean(prompt)) {
                "${approval.title} 요청을 승인했고 복사까지 진행했지만 Android delete consent가 더 필요해요. ${organizeExecution.result.summaryWithStatusNote}"
            } else {
                "I approved ${approval.title} and copied the files, but Android delete consent is still required. ${organizeExecution.result.summaryWithStatusNote}"
            }
            AgentTaskStatus.Failed -> if (prefersKorean(prompt)) {
                "${approval.title} 요청은 승인했지만 실행 중 문제가 있었습니다. ${organizeExecution.result.summaryWithStatusNote}"
            } else {
                "I approved ${approval.title}, but the execution ran into issues. ${organizeExecution.result.summaryWithStatusNote}"
            }
            else -> if (prefersKorean(prompt)) {
                "${approval.title} 요청을 승인했습니다. ${organizeExecution.result.summaryWithStatusNote}"
            } else {
                "I approved ${approval.title}. ${organizeExecution.result.summaryWithStatusNote}"
            }
        }
    }

    private fun retryReply(
        prompt: String,
        task: AgentTaskRecord,
        organizeExecution: PersistedOrganizeExecution?,
    ): String {
        return when (task.status) {
            AgentTaskStatus.Succeeded -> if (prefersKorean(prompt)) {
                "재시도를 바로 실행했고 작업이 완료됐어요. ${organizeExecution?.result?.summary ?: task.summary}"
            } else {
                "I retried the task immediately and it completed. ${organizeExecution?.result?.summary ?: task.summary}"
            }
            AgentTaskStatus.RetryScheduled -> if (prefersKorean(prompt)) {
                "재시도를 등록했고 다음 실행은 ${task.nextRetryAtLabel ?: "곧"} 예정입니다. ${task.summary}"
            } else {
                "I queued another retry. The next attempt is ${task.nextRetryAtLabel ?: "soon"}. ${task.summary}"
            }
            AgentTaskStatus.WaitingUser -> if (prefersKorean(prompt)) {
                "재시도를 시도했지만 아직 사용자 조치가 더 필요합니다. ${task.summary}"
            } else {
                "I attempted a retry, but user action is still required. ${task.summary}"
            }
            AgentTaskStatus.WaitingResource -> if (prefersKorean(prompt)) {
                "재시도를 시도했지만 연결 자원이나 approval 기록이 아직 부족합니다. ${task.summary}"
            } else {
                "I attempted a retry, but a required resource or approval record is still missing. ${task.summary}"
            }
            AgentTaskStatus.Failed -> if (prefersKorean(prompt)) {
                "재시도를 실행했지만 아직 실패 상태입니다. ${task.summary}"
            } else {
                "I ran the retry, but the task is still failing. ${task.summary}"
            }
            else -> if (prefersKorean(prompt)) {
                "재시도 결과 상태는 ${taskStatusLabel(prompt, task.status)} 입니다. ${task.summary}"
            } else {
                "The retry finished in ${taskStatusLabel(prompt, task.status)} state. ${task.summary}"
            }
        }
    }

    private fun retryNotEligibleReply(
        prompt: String,
        task: AgentTaskRecord,
    ): String {
        return when (task.status) {
            AgentTaskStatus.WaitingUser -> if (prefersKorean(prompt)) {
                "이 task는 재시도보다 먼저 사용자 승인이나 delete consent가 필요합니다. Dashboard에서 상태를 확인해 주세요."
            } else {
                "This task needs user approval or delete consent before another retry makes sense. Check Dashboard first."
            }
            AgentTaskStatus.Running -> if (prefersKorean(prompt)) {
                "이 task는 이미 실행 중이라 지금 다시 재시도할 수 없습니다."
            } else {
                "This task is already running, so I cannot retry it again right now."
            }
            AgentTaskStatus.Succeeded -> if (prefersKorean(prompt)) {
                "이 task는 이미 성공적으로 끝났습니다."
            } else {
                "This task has already completed successfully."
            }
            AgentTaskStatus.Cancelled -> if (prefersKorean(prompt)) {
                "이 task는 이미 취소 상태라 바로 재시도하지 않습니다."
            } else {
                "This task is already cancelled, so I will not retry it automatically."
            }
            else -> if (prefersKorean(prompt)) {
                "이 task는 현재 상태(${taskStatusLabel(prompt, task.status)})에서는 채팅 재시도를 지원하지 않습니다."
            } else {
                "Chat retry is not supported for this task while it is ${taskStatusLabel(prompt, task.status)}."
            }
        }
    }

    private fun taskStatusLabel(
        prompt: String,
        status: AgentTaskStatus,
    ): String {
        return if (prefersKorean(prompt)) {
            when (status) {
                AgentTaskStatus.Queued -> "queued"
                AgentTaskStatus.Planning -> "planning"
                AgentTaskStatus.WaitingUser -> "사용자 대기"
                AgentTaskStatus.WaitingResource -> "자원 대기"
                AgentTaskStatus.Running -> "실행 중"
                AgentTaskStatus.Paused -> "일시정지"
                AgentTaskStatus.RetryScheduled -> "재시도 예정"
                AgentTaskStatus.Succeeded -> "성공"
                AgentTaskStatus.Failed -> "실패"
                AgentTaskStatus.Cancelled -> "취소"
            }
        } else {
            status.name.lowercase()
        }
    }

    private fun shellRecoveryStatusLabel(
        prompt: String,
        status: ShellRecoveryStatus,
    ): String {
        return if (prefersKorean(prompt)) {
            when (status) {
                ShellRecoveryStatus.Idle -> "대기"
                ShellRecoveryStatus.Running -> "실행 중"
                ShellRecoveryStatus.Success -> "성공"
                ShellRecoveryStatus.Failed -> "실패"
            }
        } else {
            when (status) {
                ShellRecoveryStatus.Idle -> "idle"
                ShellRecoveryStatus.Running -> "running"
                ShellRecoveryStatus.Success -> "successful"
                ShellRecoveryStatus.Failed -> "failed"
            }
        }
    }

    private fun approvalStatusLabel(
        prompt: String,
        status: ApprovalInboxStatus,
    ): String {
        return if (prefersKorean(prompt)) {
            when (status) {
                ApprovalInboxStatus.Pending -> "대기"
                ApprovalInboxStatus.Approved -> "승인됨"
                ApprovalInboxStatus.Denied -> "거절됨"
            }
        } else {
            status.name.lowercase()
        }
    }

    companion object {
        private const val maxAuditPromptLength = 160
        private const val maxAuditReplyLength = 220
        private const val maxTaskTitleLength = 72
        private const val organizeRetryBudget = 3
        private const val maxChatTransferItemsWithoutSelection = 5
        private const val filesSummarizeActionKey = "files.summarize"
        private const val filesOrganizeActionKey = filesOrganizeExecuteActionKey
        private const val filesTransferActionKey = filesTransferExecuteActionKey
        private const val shellRefreshActionKey = "shell.refresh"
        private const val resourceStackShowActionKey = "resource.stack.show"
        private const val resourceCloudDriveStageActionKey = "resource.cloud.stage"
        private const val resourceCloudDriveConnectActionKey = "resource.cloud.connect"
        private const val resourceEndpointStageActionKey = "resource.endpoint.stage"
        private const val resourceEndpointConnectActionKey = "resource.endpoint.connect"
        private const val resourceDeliveryStageActionKey = "resource.delivery.stage"
        private const val resourceDeliveryConnectActionKey = "resource.delivery.connect"
        private const val mailboxConnectActionKey = "resource.mailbox.connect"
        private const val mailboxStatusActionKey = "resource.mailbox.status"
        private const val shellRecoveryRunActionKey = "shell.recovery.run"
        private const val shellRecoveryShowActionKey = "shell.recovery.show"
        private const val scheduledAutomationPlanActionKey = "automation.schedule.plan"
        private const val scheduledAutomationActivateActionKey = "automation.schedule.activate"
        private const val scheduledAutomationPauseActionKey = "automation.schedule.pause"
        private const val scheduledAutomationRunNowActionKey = "automation.schedule.run_now"
        private const val codeGenerationPlanActionKey = "code.generate.plan"
        private const val browserResearchPlanActionKey = "browser.research.plan"
        private const val browserPageAccessActionKey = "browser.page.access"
        private const val routeDashboardActionKey = "ui.route.dashboard"
        private const val routeHistoryActionKey = "ui.route.history"
        private const val routeSettingsActionKey = "ui.route.settings"
        private const val explainInitialSetupActionKey = "agent.setup.explain"
        private const val emailSetupGuideActionKey = "agent.email_setup.explain"
        private const val providerConversationReplyActionKey = providerConversationActionKey
        private const val browserAutomationEndpointId = "browser-automation-profile"
        private const val thirdPartyApiEndpointId = "third-party-api-profile"
        private const val localNotificationChannelId = "phone-local-notification"
        private const val telegramDeliveryChannelId = "telegram-bot-delivery"
        private const val desktopCompanionDeliveryChannelId = "desktop-companion-delivery"
        private const val webhookDeliveryChannelId = "custom-webhook-delivery"
        private const val mcpBridgeEndpointId = "companion-mcp-bridge"
        private const val explainCapabilitiesActionKey = "agent.capabilities.explain"
        private const val shellRecoveryPollAttempts = 50
        private const val shellRecoveryPollIntervalMs = 200L
        private val approvalIdPattern = Regex("""approval-[A-Za-z0-9-]+""")
        private val taskIdPattern = Regex("""task-[A-Za-z0-9-]+""")
        private val telegramTokenPattern = Regex("""\b\d{6,12}:[A-Za-z0-9_-]{20,}\b""")
        private val telegramChatPattern = Regex("""(?:chat|chat_id)\s+(@?[A-Za-z0-9_-]+|-?\d+)""", RegexOption.IGNORE_CASE)
        private val telegramNumericChatPattern = Regex("""-?\d{6,}""")
        private val automationIdPattern = Regex("""automation-[A-Za-z0-9-]+""")
        private const val webPagePreviewMaxChars = 2200
    }
}

private val webPageUrlPattern = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)
private val webPageBareDomainPattern = Regex(
    """\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?:/[^\s<>"')\]]*)?""",
    RegexOption.IGNORE_CASE,
)

private data class AgentPlannerOutput(
    val intent: AgentIntent,
    val auditResult: String,
    val planningTrace: AgentPlanningTrace,
)

private data class BrowserExecutionSnapshot(
    val deviceId: String?,
    val label: String,
    val toolNames: List<String>,
    val canBrowseWebPages: Boolean,
)

private sealed interface AgentIntent {
    data class ApprovePendingApproval(
        val approvalId: String? = null,
    ) : AgentIntent
    data class DenyPendingApproval(
        val approvalId: String? = null,
    ) : AgentIntent
    data class RetryTask(
        val taskId: String? = null,
    ) : AgentIntent
    data object ShowDashboard : AgentIntent
    data object ShowHistory : AgentIntent
    data object ShowSettings : AgentIntent
    data object ExplainInitialSetup : AgentIntent
    data object ExplainMcpSetup : AgentIntent
    data object ExplainEmailSetup : AgentIntent
    data object ShowMailboxStatus : AgentIntent
    data object ShowResourceStack : AgentIntent
    data object RefreshResources : AgentIntent
    data object RunShellRecovery : AgentIntent
    data object ShowShellRecoveryStatus : AgentIntent
    data class StageCloudDrive(
        val provider: CloudDriveProviderKind,
    ) : AgentIntent
    data class ConnectCloudDrive(
        val provider: CloudDriveProviderKind,
    ) : AgentIntent
    data class StageExternalEndpoint(
        val endpointId: String,
    ) : AgentIntent
    data class ConnectExternalEndpoint(
        val endpointId: String,
    ) : AgentIntent
    data class StageDeliveryChannel(
        val channelId: String,
    ) : AgentIntent
    data class ConnectDeliveryChannel(
        val channelId: String,
    ) : AgentIntent
    data object ConnectMailbox : AgentIntent
    data object PlanScheduledAutomation : AgentIntent
    data class ActivateScheduledAutomation(
        val automationId: String? = null,
    ) : AgentIntent
    data class PauseScheduledAutomation(
        val automationId: String? = null,
    ) : AgentIntent
    data class RunScheduledAutomationNow(
        val automationId: String? = null,
    ) : AgentIntent
    data object PlanCodeGeneration : AgentIntent
    data object PlanBrowserResearch : AgentIntent
    data class BrowseWebPage(
        val url: String,
    ) : AgentIntent
    data object SummarizeIndexedFiles : AgentIntent
    data class OrganizeIndexedFiles(
        val strategy: FileOrganizeStrategy,
    ) : AgentIntent
    data object TransferIndexedFiles : AgentIntent
    data object ConnectMcpBridge : AgentIntent
    data object ShowMcpConnectorStatus : AgentIntent
    data object ShowMcpTools : AgentIntent
    data object SyncMcpSkills : AgentIntent
    data object ShowMcpSkills : AgentIntent
    data object ProbeCompanionHealth : AgentIntent
    data object SendCompanionSessionNotification : AgentIntent
    data class OpenCompanionTarget(
        val targetKind: String,
    ) : AgentIntent
    data class RunCompanionWorkflow(
        val workflowId: String,
    ) : AgentIntent
    data object RespondWithProviderConversation : AgentIntent
    data object ExplainCapabilities : AgentIntent
}

internal fun looksLikeBrowserCapabilityQuestion(normalizedPrompt: String): Boolean {
    val mentionsWebCapability = browserPromptContainsAny(
        normalizedPrompt,
        "browser",
        "web",
        "웹",
        "브라우저",
        "웹 페이지",
        "웹페이지",
        "사이트",
        "page access",
        "website access",
    )
    if (!mentionsWebCapability) {
        return false
    }
    val asksCapability = browserPromptContainsAny(
        normalizedPrompt,
        "?",
        "가능",
        "할 수 있",
        "되나",
        "돼",
        "접근",
        "지원",
        "can you",
        "can i",
        "able to",
        "support",
        "access",
    )
    val actionRequest = browserPromptContainsAny(
        normalizedPrompt,
        "research",
        "search",
        "look up",
        "find",
        "collect",
        "summarize",
        "news",
        "article",
        "조사",
        "검색",
        "찾아",
        "수집",
        "요약",
        "뉴스",
        "기사",
        "열어줘",
        "가져와",
    )
    return asksCapability && !actionRequest
}

internal fun extractFirstWebUrl(prompt: String): String? {
    return webPageUrlPattern.find(prompt)?.value?.trim()
        ?: webPageBareDomainPattern.find(prompt)?.value?.trim()
}

private fun browserPromptContainsAny(
    normalizedPrompt: String,
    vararg terms: String,
): Boolean {
    return terms.any { term -> normalizedPrompt.contains(term) }
}
