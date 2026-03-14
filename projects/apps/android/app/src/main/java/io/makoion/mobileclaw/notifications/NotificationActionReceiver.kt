package io.makoion.mobileclaw.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.ApprovalActionResult
import io.makoion.mobileclaw.data.TaskRetryActionResult
import io.makoion.mobileclaw.ui.ShellSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        val application = context.applicationContext as MobileClawApplication
        receiverScope.launch {
            try {
                when (intent.action) {
                    ShellNotificationCenter.actionStartVoice -> {
                        application.appContainer.auditTrailRepository.logAction(
                            action = "notifications.quick_action",
                            result = "voice",
                            details = "Quick actions notification started voice capture.",
                        )
                        application.appContainer.voiceEntryCoordinator.startCapture()
                    }
                    ShellNotificationCenter.actionStopVoice -> {
                        application.appContainer.voiceEntryCoordinator.stopCapture()
                    }
                    ShellNotificationCenter.actionOpenApprovals -> {
                        application.appContainer.auditTrailRepository.logAction(
                            action = "notifications.quick_action",
                            result = "approvals",
                            details = "Quick actions notification opened the approval inbox.",
                        )
                        val openIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(ShellNotificationCenter.extraOpenSection, ShellSection.Dashboard.routeKey)
                        }
                        context.startActivity(openIntent)
                    }
                    ShellNotificationCenter.actionApproveTask -> {
                        handleApproveTaskAction(
                            context = context.applicationContext,
                            application = application,
                            approvalId = intent.getStringExtra(ShellNotificationCenter.extraApprovalId),
                            taskId = intent.getStringExtra(ShellNotificationCenter.extraTaskId),
                        )
                    }
                    ShellNotificationCenter.actionDenyTask -> {
                        handleDenyTaskAction(
                            context = context.applicationContext,
                            application = application,
                            approvalId = intent.getStringExtra(ShellNotificationCenter.extraApprovalId),
                            taskId = intent.getStringExtra(ShellNotificationCenter.extraTaskId),
                        )
                    }
                    ShellNotificationCenter.actionRetryTask -> {
                        handleRetryTaskAction(
                            context = context.applicationContext,
                            application = application,
                            taskId = intent.getStringExtra(ShellNotificationCenter.extraTaskId),
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleApproveTaskAction(
        context: Context,
        application: MobileClawApplication,
        approvalId: String?,
        taskId: String?,
    ) {
        taskId?.let { ShellNotificationCenter.cancelTaskNotification(context, it) }
        val result = application.appContainer.phoneAgentActionCoordinator.approveApproval(approvalId)
        when (result) {
            is ApprovalActionResult.Completed -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "approved",
                    details = "Approved task ${result.execution.linkedTask?.id ?: taskId ?: "unknown"} from notification.",
                    requestId = result.execution.linkedTask?.id ?: taskId ?: approvalId,
                )
                result.execution.linkedTask?.let {
                    ShellNotificationCenter.maybeShowTaskFollowUp(context, it)
                }
            }
            is ApprovalActionResult.AlreadyResolved -> {
                val linkedTask = application.appContainer.agentTaskRepository.findTaskByApprovalRequestId(result.approval.id)
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "already_resolved",
                    details = "Approval ${result.approval.id} was already ${result.approval.status.name.lowercase()} when notification approve was tapped.",
                    requestId = linkedTask?.id ?: result.approval.id,
                )
                linkedTask?.let {
                    ShellNotificationCenter.maybeShowTaskFollowUp(context, it)
                }
            }
            is ApprovalActionResult.Missing -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "missing",
                    details = "Notification approve could not find approval ${result.requestedApprovalId ?: "unknown"}.",
                    requestId = taskId ?: result.requestedApprovalId,
                )
            }
        }
    }

    private suspend fun handleDenyTaskAction(
        context: Context,
        application: MobileClawApplication,
        approvalId: String?,
        taskId: String?,
    ) {
        taskId?.let { ShellNotificationCenter.cancelTaskNotification(context, it) }
        val result = application.appContainer.phoneAgentActionCoordinator.denyApproval(approvalId)
        when (result) {
            is ApprovalActionResult.Completed -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "denied",
                    details = "Denied task ${result.execution.linkedTask?.id ?: taskId ?: "unknown"} from notification.",
                    requestId = result.execution.linkedTask?.id ?: taskId ?: approvalId,
                )
                result.execution.linkedTask?.let {
                    ShellNotificationCenter.maybeShowTaskFollowUp(context, it)
                }
            }
            is ApprovalActionResult.AlreadyResolved -> {
                val linkedTask = application.appContainer.agentTaskRepository.findTaskByApprovalRequestId(result.approval.id)
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "already_resolved",
                    details = "Approval ${result.approval.id} was already ${result.approval.status.name.lowercase()} when notification deny was tapped.",
                    requestId = linkedTask?.id ?: result.approval.id,
                )
                linkedTask?.let {
                    ShellNotificationCenter.maybeShowTaskFollowUp(context, it)
                }
            }
            is ApprovalActionResult.Missing -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "missing",
                    details = "Notification deny could not find approval ${result.requestedApprovalId ?: "unknown"}.",
                    requestId = taskId ?: result.requestedApprovalId,
                )
            }
        }
    }

    private suspend fun handleRetryTaskAction(
        context: Context,
        application: MobileClawApplication,
        taskId: String?,
    ) {
        taskId?.let { ShellNotificationCenter.cancelTaskNotification(context, it) }
        val result = application.appContainer.phoneAgentActionCoordinator.retryTask(taskId)
        when (result) {
            is TaskRetryActionResult.Completed -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "retried",
                    details = "Retried task ${result.execution.task.id} from notification.",
                    requestId = result.execution.task.id,
                )
                ShellNotificationCenter.maybeShowTaskFollowUp(context, result.execution.task)
            }
            is TaskRetryActionResult.NotEligible -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "not_eligible",
                    details = "Notification retry was not eligible for task ${result.task.id} (${result.task.status.name.lowercase()}).",
                    requestId = result.task.id,
                )
                ShellNotificationCenter.maybeShowTaskFollowUp(context, result.task)
            }
            is TaskRetryActionResult.Missing -> {
                application.appContainer.auditTrailRepository.logAction(
                    action = "notifications.task_follow_up_action",
                    result = "missing",
                    details = "Notification retry could not find task ${result.requestedTaskId ?: "unknown"}.",
                    requestId = result.requestedTaskId,
                )
            }
        }
    }
}
