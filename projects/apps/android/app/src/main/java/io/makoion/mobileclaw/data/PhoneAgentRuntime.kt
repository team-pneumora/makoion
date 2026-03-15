package io.makoion.mobileclaw.data

data class AgentTurnContext(
    val fileIndexState: FileIndexState,
    val approvals: List<ApprovalInboxItem>,
    val tasks: List<AgentTaskRecord>,
    val auditEvents: List<AuditTrailEvent>,
    val pairedDevices: List<PairedDeviceState>,
    val selectedTargetDeviceId: String?,
    val cloudDriveConnections: List<CloudDriveConnectionState> = emptyList(),
    val modelPreference: AgentModelPreference = AgentModelPreference(),
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
    private val devicePairingRepository: DevicePairingRepository,
    private val scheduledAutomationRepository: ScheduledAutomationRepository,
    private val phoneAgentActionCoordinator: PhoneAgentActionCoordinator,
) {
    suspend fun processTurn(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val trimmedPrompt = prompt.trim()
        val plannerOutput = planTurn(trimmedPrompt)
        val rawResult = when (val intent = plannerOutput.intent) {
            is AgentIntent.ApprovePendingApproval -> approvePendingApproval(trimmedPrompt, context, intent.approvalId)
            is AgentIntent.DenyPendingApproval -> denyPendingApproval(trimmedPrompt, context, intent.approvalId)
            is AgentIntent.RetryTask -> retryAgentTask(trimmedPrompt, context, intent.taskId)
            AgentIntent.ShowDashboard -> buildDashboardResponse(trimmedPrompt, context)
            AgentIntent.ShowHistory -> buildHistoryResponse(trimmedPrompt, context)
            AgentIntent.ShowSettings -> buildSettingsResponse(trimmedPrompt, context)
            AgentIntent.RefreshResources -> refreshResources(trimmedPrompt)
            AgentIntent.PlanScheduledAutomation -> planScheduledAutomation(trimmedPrompt, context)
            AgentIntent.PlanBrowserResearch -> planBrowserResearch(trimmedPrompt, context)
            AgentIntent.SummarizeIndexedFiles -> summarizeIndexedFiles(trimmedPrompt, context)
            is AgentIntent.OrganizeIndexedFiles -> organizeIndexedFiles(trimmedPrompt, context, intent.strategy)
            AgentIntent.TransferIndexedFiles -> transferIndexedFiles(trimmedPrompt, context)
            AgentIntent.ProbeCompanionHealth -> probeCompanionHealth(trimmedPrompt, context)
            AgentIntent.SendCompanionSessionNotification -> sendCompanionSessionNotification(trimmedPrompt, context)
            is AgentIntent.OpenCompanionTarget -> openCompanionTarget(trimmedPrompt, context, intent.targetKind)
            is AgentIntent.RunCompanionWorkflow -> runCompanionWorkflow(trimmedPrompt, context, intent.workflowId)
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
                    append("cloud connector는 staged ${stagedCloudCount}개, mock-ready ${connectedCloudCount}개이고, 기본 모델 선호도는 $providerLabel 입니다.")
                    if (brief.recurringHint) {
                        append(" 반복 실행 힌트도 감지했기 때문에 automation scheduler skeleton 단계와 연결하기 좋은 요청입니다.")
                    }
                }
            } else {
                buildString {
                    append("I captured this as a browser research skeleton request. ")
                    append("The core query is \"${brief.query}\" and the requested delivery channel was interpreted as ${brief.requestedDelivery}. ")
                    append("Browser automation and live web collection do not have a real executor yet, so I cannot run it end-to-end today. ")
                    append("Cloud connectors are staged ${stagedCloudCount} / mock-ready ${connectedCloudCount}, and the current model preference is $providerLabel.")
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

    private suspend fun planScheduledAutomation(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val plan = buildScheduledAutomationPlan(prompt)
        val record = scheduledAutomationRepository.createSkeleton(
            prompt = prompt,
            plan = plan,
        )
        val recordedCount = context.scheduledAutomations.size + 1
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
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("반복 작업을 scheduled automation skeleton으로 기록했어요. ")
                    append("주기는 ${record.scheduleLabel}, 전달 방식은 ${record.deliveryLabel}로 해석했고, Dashboard에서 바로 확인할 수 있습니다. ")
                    append("현재 scheduler worker와 실제 delivery executor는 아직 연결 전이라 바로 자동 실행되지는 않습니다. ")
                    append("기록된 automation은 총 ${recordedCount}건입니다.")
                    if (browserLinked) {
                        append(" 이 요청은 이후 browser/news research capability와 연결될 수 있게 남겨뒀습니다.")
                    }
                }
            } else {
                buildString {
                    append("I recorded this recurring request as a scheduled automation skeleton. ")
                    append("The schedule was interpreted as ${record.scheduleLabel} and the delivery channel as ${record.deliveryLabel}, and you can review it on Dashboard. ")
                    append("The scheduler worker and real delivery executor are not wired yet, so it will not run end-to-end today. ")
                    append("There are now $recordedCount recorded automation skeleton(s).")
                    if (browserLinked) {
                        append(" I also kept it aligned with the upcoming browser/news research capability.")
                    }
                }
            },
            destination = AgentDestination.Dashboard,
            taskTitle = taskTitle(prompt),
            taskActionKey = scheduledAutomationPlanActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "${record.scheduleLabel} / ${record.deliveryLabel} automation skeleton을 기록했습니다."
            } else {
                "Recorded a ${record.scheduleLabel} / ${record.deliveryLabel} automation skeleton."
            },
            taskStatus = AgentTaskStatus.WaitingResource,
        )
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
                "Settings에서 연결 자원과 권한을 관리할 수 있어요. 미디어 권한은 $mediaState, 문서 루트는 ${context.fileIndexState.documentTreeCount}개, companion은 ${context.pairedDevices.size}대 연결돼 있고, cloud connector는 staged ${stagedCloudDrives}개 / mock-ready ${connectedCloudDrives}개입니다. 기본 모델 선호도는 $providerLabel 입니다. 구성된 provider credential은 ${configuredProviderCount}개예요."
            } else {
                "Settings is where resources and permissions are managed. Media access is $mediaState, there are ${context.fileIndexState.documentTreeCount} document roots, ${context.pairedDevices.size} companions are connected, cloud connectors are staged ${stagedCloudDrives} / mock-ready ${connectedCloudDrives}, and the current model preference is $providerLabel. There are $configuredProviderCount configured provider credential(s)."
            },
            destination = AgentDestination.Settings,
            taskTitle = taskTitle(prompt),
            taskActionKey = routeSettingsActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "Settings로 라우팅했습니다."
            } else {
                "Routed the session to Settings."
            },
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
                CompanionAppOpenStatus.Opened ->
                    if (prefersKorean(prompt)) "Companion에서 target surface를 열었습니다." else "Opened the target companion surface."
                CompanionAppOpenStatus.Recorded ->
                    if (prefersKorean(prompt)) "Companion이 요청을 기록했고 후속 확인이 필요합니다." else "The companion recorded the request and may need follow-up confirmation."
                CompanionAppOpenStatus.Failed ->
                    if (prefersKorean(prompt)) "Companion target 열기 요청이 실패했습니다." else "Opening the companion target failed."
                CompanionAppOpenStatus.Misconfigured ->
                    if (prefersKorean(prompt)) "Companion 설정 문제로 요청을 완료하지 못했습니다." else "Companion misconfiguration prevented completion."
                CompanionAppOpenStatus.Skipped ->
                    if (prefersKorean(prompt)) "현재 상태에서는 요청이 건너뛰어졌습니다." else "The request was skipped in the current companion state."
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
                CompanionHealthStatus.Healthy ->
                    if (prefersKorean(prompt)) "Companion health snapshot을 새로고침했습니다." else "Refreshed the companion health snapshot."
                CompanionHealthStatus.Unreachable ->
                    if (prefersKorean(prompt)) "Companion health probe가 endpoint에 닿지 못했습니다." else "The companion health probe could not reach the endpoint."
                CompanionHealthStatus.Misconfigured ->
                    if (prefersKorean(prompt)) "Companion 설정 문제로 health probe를 완료하지 못했습니다." else "Companion misconfiguration prevented the health probe."
                CompanionHealthStatus.Skipped ->
                    if (prefersKorean(prompt)) "현재 companion 상태에서는 health probe를 실행할 수 없습니다." else "The health probe was skipped in the current companion state."
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
                CompanionSessionNotifyStatus.Delivered ->
                    if (prefersKorean(prompt)) "Companion session.notify를 전달했습니다." else "Delivered session.notify to the companion."
                CompanionSessionNotifyStatus.Failed ->
                    if (prefersKorean(prompt)) "Companion session.notify 전달이 실패했습니다." else "Sending session.notify to the companion failed."
                CompanionSessionNotifyStatus.Misconfigured ->
                    if (prefersKorean(prompt)) "Companion 설정 문제로 session.notify를 보낼 수 없습니다." else "Companion misconfiguration prevented session.notify."
                CompanionSessionNotifyStatus.Skipped ->
                    if (prefersKorean(prompt)) "현재 companion 상태에서는 session.notify를 보낼 수 없습니다." else "session.notify was skipped in the current companion state."
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
                CompanionWorkflowRunStatus.Completed ->
                    if (prefersKorean(prompt)) "$workflowLabel workflow를 실행했습니다." else "Executed the $workflowLabel workflow."
                CompanionWorkflowRunStatus.Recorded ->
                    if (prefersKorean(prompt)) "$workflowLabel workflow 요청을 기록했습니다." else "Recorded the $workflowLabel workflow request."
                CompanionWorkflowRunStatus.Failed ->
                    if (prefersKorean(prompt)) "$workflowLabel workflow 실행이 실패했습니다." else "Running the $workflowLabel workflow failed."
                CompanionWorkflowRunStatus.Misconfigured ->
                    if (prefersKorean(prompt)) "Companion 설정 문제로 workflow.run을 실행할 수 없습니다." else "Companion misconfiguration prevented workflow.run."
                CompanionWorkflowRunStatus.Skipped ->
                    if (prefersKorean(prompt)) "현재 companion 상태에서는 workflow.run을 실행할 수 없습니다." else "workflow.run was skipped in the current companion state."
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

    private fun explainCapabilities(
        prompt: String,
        context: AgentTurnContext,
    ): AgentTurnResult {
        val indexedCount = context.fileIndexState.indexedCount
        val pendingApprovals = context.approvals.count { it.status == ApprovalInboxStatus.Pending }
        val retryableTasks = context.tasks.count { task ->
            task.actionKey == filesOrganizeActionKey &&
                (
                    task.status == AgentTaskStatus.RetryScheduled ||
                        task.status == AgentTaskStatus.Failed ||
                        task.status == AgentTaskStatus.WaitingResource
                    ) &&
                task.maxRetryCount > 0
        }
        val pairedDevices = context.pairedDevices.size
        val connectedCloudDrives = context.cloudDriveConnections.count {
            it.status == CloudDriveConnectionStatus.Connected
        }
        val preferredProviderLabel = context.modelPreference.preferredProviderLabel?.let { provider ->
            val model = context.modelPreference.preferredModel
            if (model.isNullOrBlank()) {
                provider
            } else {
                "$provider / $model"
            }
        } ?: if (prefersKorean(prompt)) {
            "아직 선택되지 않음"
        } else {
            "not selected yet"
        }
        return AgentTurnResult(
            reply = if (prefersKorean(prompt)) {
                buildString {
                    append("지금 이 채팅 루프에서 바로 할 수 있는 일은 열 가지예요.\n")
                    append("1. 인덱싱된 파일 요약 (${indexedCount}개 감지)\n")
                    append("2. 파일 정리 dry-run 계획 생성 후 승인 요청\n")
                    append("3. companion 전송 approval 생성\n")
                    append("4. 채팅에서 최신 승인 요청 승인 또는 거절\n")
                    append("5. 실패한 organize task 재시도 (${retryableTasks}건 가능)\n")
                    append("6. Dashboard / History / Settings로 이동\n")
                    append("7. 연결된 companion surface 열기 (${pairedDevices}대 연결)\n")
                    append("8. companion health snapshot 새로고침\n")
                    append("9. desktop companion notification 보내기\n")
                    append("10. allowlisted desktop workflow 실행\n")
                    append("현재 승인 대기는 ${pendingApprovals}건입니다.\n")
                    append("cloud connector mock-ready 상태는 ${connectedCloudDrives}개입니다.\n")
                    append("기본 모델 선호도는 $preferredProviderLabel 입니다.")
                }
            } else {
                buildString {
                    append("This first chat loop can do ten things right now.\n")
                    append("1. Summarize indexed files ($indexedCount detected)\n")
                    append("2. Create an organize dry-run and submit it for approval\n")
                    append("3. Create a companion transfer approval\n")
                    append("4. Approve or deny the latest pending approval from chat\n")
                    append("5. Retry a failed organize task ($retryableTasks retryable)\n")
                    append("6. Route you to Dashboard, History, or Settings\n")
                    append("7. Open a paired companion surface ($pairedDevices paired)\n")
                    append("8. Refresh the companion health snapshot\n")
                    append("9. Send a desktop companion notification\n")
                    append("10. Run an allowlisted desktop workflow\n")
                    append("There are $pendingApprovals pending approvals right now.\n")
                    append("Cloud connectors marked mock-ready: $connectedCloudDrives.\n")
                    append("The current model preference is $preferredProviderLabel.")
                }
            },
            taskTitle = taskTitle(prompt),
            taskActionKey = explainCapabilitiesActionKey,
            taskSummary = if (prefersKorean(prompt)) {
                "현재 chat loop의 capability 범위를 설명했습니다."
            } else {
                "Explained the current scope of the chat loop."
            },
        )
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
            desktopWorkflowIdOpenLatestTransfer -> "Open latest transfer"
            desktopWorkflowIdOpenActionsFolder -> "Open actions folder"
            desktopWorkflowIdOpenLatestAction -> "Open latest action"
            else -> workflowId
        }
    }

    private fun companionWorkflowDisplayName(
        prompt: String,
        workflowId: String,
    ): String {
        return if (prefersKorean(prompt)) {
            when (workflowId) {
                desktopWorkflowIdOpenLatestTransfer -> "최근 전송 열기"
                desktopWorkflowIdOpenActionsFolder -> "actions 폴더 열기"
                desktopWorkflowIdOpenLatestAction -> "최근 액션 열기"
                else -> workflowId
            }
        } else {
            companionWorkflowLabel(workflowId)
        }
    }

    private fun planTurn(prompt: String): AgentPlannerOutput {
        val normalized = prompt.lowercase()
        val approvalId = approvalIdFromPrompt(prompt)
        val taskId = taskIdFromPrompt(prompt)
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
                    intent = AgentIntent.RunCompanionWorkflow(desktopWorkflowIdOpenLatestAction),
                    auditResult = "companion_latest_action_workflow_run",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run the allowlisted desktop workflow that opens the latest companion action.",
                    capabilities = listOf("devices.workflow_run"),
                    resources = listOf("external.companion"),
                )
            containsAny(normalized, "workflow.run", "workflow", "워크플로") &&
                containsAny(normalized, "latest transfer", "recent transfer", "최근 전송", "전송 폴더") ->
                plannerOutput(
                    intent = AgentIntent.RunCompanionWorkflow(desktopWorkflowIdOpenLatestTransfer),
                    auditResult = "companion_latest_transfer_workflow_run",
                    mode = AgentPlannerMode.ActionIntent,
                    summary = "Run the allowlisted desktop workflow that opens the latest transfer.",
                    capabilities = listOf("devices.workflow_run"),
                    resources = listOf("external.companion"),
                )
            containsAny(normalized, "workflow.run", "workflow", "워크플로") &&
                containsAny(normalized, "actions folder", "action folder", "액션 폴더", "actions") ->
                plannerOutput(
                    intent = AgentIntent.RunCompanionWorkflow(desktopWorkflowIdOpenActionsFolder),
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
            looksLikeScheduledAutomationPrompt(normalized) ->
                plannerOutput(
                    intent = AgentIntent.PlanScheduledAutomation,
                    auditResult = "scheduled_automation_planned",
                    mode = AgentPlannerMode.Plan,
                    summary = "Capture a recurring or scheduled request and persist it as an automation skeleton until the scheduler executor is wired.",
                    capabilities = listOf("automation.schedule.plan"),
                    resources = listOf("task.runtime", "notifications.delivery", "audit.history"),
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
            containsAny(normalized, "approve", "approval", "review", "승인", "검토", "dashboard", "대시보드", "status", "현황") ->
                plannerOutput(
                    intent = AgentIntent.ShowDashboard,
                    auditResult = "dashboard_routed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Route the user to Dashboard to inspect approvals, task state, and resource status.",
                    capabilities = listOf("ui.route.dashboard"),
                    resources = listOf("shell.navigation"),
                )
            containsAny(normalized, "history", "audit", "log", "기록", "히스토리", "로그") ->
                plannerOutput(
                    intent = AgentIntent.ShowHistory,
                    auditResult = "history_routed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Route the user to History to inspect audit and prior task activity.",
                    capabilities = listOf("ui.route.history"),
                    resources = listOf("shell.navigation", "audit.history"),
                )
            containsAny(normalized, "settings", "connect", "connection", "permission", "권한", "설정", "연결", "drive", "mcp", "api", "companion", "device") ->
                plannerOutput(
                    intent = AgentIntent.ShowSettings,
                    auditResult = "settings_routed",
                    mode = AgentPlannerMode.Answer,
                    summary = "Route the user to Settings to inspect connections, permissions, and resource configuration.",
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
            else -> plannerOutput(
                intent = AgentIntent.ExplainCapabilities,
                auditResult = "capabilities_explained",
                mode = AgentPlannerMode.Answer,
                summary = "Explain the current capability envelope of the chat loop and connected resource stack.",
                capabilities = listOf("agent.capabilities.explain"),
                resources = listOf("resource.stack", "task.runtime"),
            )
        }
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
        private const val filesOrganizeActionKey = "files.organize.execute"
        private const val filesTransferActionKey = "files.transfer"
        private const val approvalsApproveActionKey = "approvals.approve"
        private const val approvalsDenyActionKey = "approvals.deny"
        private const val manualTaskRetryActionKey = "agent.task.retry.manual"
        private const val shellRefreshActionKey = "shell.refresh"
        private const val scheduledAutomationPlanActionKey = "automation.schedule.plan"
        private const val browserResearchPlanActionKey = "browser.research.plan"
        private const val routeDashboardActionKey = "ui.route.dashboard"
        private const val routeHistoryActionKey = "ui.route.history"
        private const val routeSettingsActionKey = "ui.route.settings"
        private const val companionHealthProbeActionKey = "devices.health_probe"
        private const val companionSessionNotifyActionKey = "devices.session_notify"
        private const val companionAppOpenActionKey = "devices.app_open"
        private const val companionWorkflowRunActionKey = "devices.workflow_run"
        private const val desktopWorkflowIdOpenLatestAction = "open_latest_action"
        private const val desktopWorkflowIdOpenLatestTransfer = "open_latest_transfer"
        private const val desktopWorkflowIdOpenActionsFolder = "open_actions_folder"
        private const val explainCapabilitiesActionKey = "agent.capabilities.explain"
        private val approvalIdPattern = Regex("""approval-[A-Za-z0-9-]+""")
        private val taskIdPattern = Regex("""task-[A-Za-z0-9-]+""")
    }
}

private data class AgentPlannerOutput(
    val intent: AgentIntent,
    val auditResult: String,
    val planningTrace: AgentPlanningTrace,
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
    data object RefreshResources : AgentIntent
    data object PlanScheduledAutomation : AgentIntent
    data object PlanBrowserResearch : AgentIntent
    data object SummarizeIndexedFiles : AgentIntent
    data class OrganizeIndexedFiles(
        val strategy: FileOrganizeStrategy,
    ) : AgentIntent
    data object TransferIndexedFiles : AgentIntent
    data object ProbeCompanionHealth : AgentIntent
    data object SendCompanionSessionNotification : AgentIntent
    data class OpenCompanionTarget(
        val targetKind: String,
    ) : AgentIntent
    data class RunCompanionWorkflow(
        val workflowId: String,
    ) : AgentIntent
    data object ExplainCapabilities : AgentIntent
}
