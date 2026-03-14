package io.makoion.mobileclaw.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.R
import io.makoion.mobileclaw.data.AgentTaskStatus
import io.makoion.mobileclaw.data.ApprovalInboxStatus
import io.makoion.mobileclaw.data.AgentTaskRecord
import io.makoion.mobileclaw.data.TaskFollowUpPresentation
import io.makoion.mobileclaw.service.VoiceCaptureService
import io.makoion.mobileclaw.ui.ShellSection

object ShellNotificationCenter {
    const val extraOpenSection = "open_section"
    const val extraOpenTaskId = "open_task_id"
    const val extraOpenTaskFollowUpKey = "open_task_follow_up_key"
    const val extraTaskId = "task_id"
    const val extraApprovalId = "approval_id"
    const val quickActionsNotificationId = 3001
    const val voiceNotificationId = 3002

    const val actionStartVoice = "io.makoion.mobileclaw.action.START_VOICE"
    const val actionStopVoice = "io.makoion.mobileclaw.action.STOP_VOICE"
    const val actionOpenApprovals = "io.makoion.mobileclaw.action.OPEN_APPROVALS"
    const val actionApproveTask = "io.makoion.mobileclaw.action.APPROVE_TASK"
    const val actionDenyTask = "io.makoion.mobileclaw.action.DENY_TASK"
    const val actionRetryTask = "io.makoion.mobileclaw.action.RETRY_TASK"

    private const val quickActionsChannelId = "shell_quick_actions"
    private const val voiceChannelId = "voice_capture"
    private const val taskFollowUpChannelId = "task_follow_ups"
    private const val taskFollowUpPreferencesName = "makoion_task_follow_up_notifications"
    private const val taskFollowUpKeyName = "posted_keys"

    fun showQuickActions(context: Context) {
        ensureChannels(context)
        NotificationManagerCompat.from(context).notify(
            quickActionsNotificationId,
            buildQuickActionsNotification(context),
        )
    }

    fun buildVoiceCaptureNotification(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, voiceChannelId)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle("Voice quick entry active")
            .setContentText("Foreground voice capture scaffold is active.")
            .setOngoing(true)
            .setContentIntent(mainActivityPendingIntent(context, ShellSection.Chat))
            .addAction(
                0,
                "Stop",
                receiverPendingIntent(context, actionStopVoice),
            )
            .build()
    }

    fun maybeShowTaskFollowUp(
        context: Context,
        task: AgentTaskRecord,
    ) {
        ensureChannels(context)
        if (!TaskFollowUpPresentation.shouldSurface(task)) {
            return
        }
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return
        }

        val key = TaskFollowUpPresentation.followUpKey(task)
        val preferences = context.getSharedPreferences(
            taskFollowUpPreferencesName,
            Context.MODE_PRIVATE,
        )
        val postedKeys = preferences.getStringSet(taskFollowUpKeyName, emptySet()) ?: emptySet()
        if (key in postedKeys) {
            return
        }

        NotificationManagerCompat.from(context).notify(
            taskNotificationId(task.id),
            buildTaskFollowUpNotification(context, task),
        )
        preferences.edit()
            .putStringSet(taskFollowUpKeyName, postedKeys + key)
            .apply()
    }

    fun cancelTaskNotification(
        context: Context,
        taskId: String,
    ) {
        NotificationManagerCompat.from(context).cancel(taskNotificationId(taskId))
    }

    private fun buildQuickActionsNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, quickActionsChannelId)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle("Makoion quick actions")
            .setContentText("Jump into approvals or start a voice capture flow.")
            .setContentIntent(mainActivityPendingIntent(context, ShellSection.Chat))
            .setAutoCancel(true)
            .addAction(
                0,
                "Voice",
                receiverPendingIntent(context, actionStartVoice),
            )
            .addAction(
                0,
                "Approvals",
                receiverPendingIntent(context, actionOpenApprovals),
            )
            .build()
    }

    private fun buildTaskFollowUpNotification(
        context: Context,
        task: AgentTaskRecord,
    ): Notification {
        val builder = NotificationCompat.Builder(context, taskFollowUpChannelId)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle(TaskFollowUpPresentation.notificationTitle(task))
            .setContentText(task.summary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(TaskFollowUpPresentation.chatMessage(task)),
            )
            .setContentIntent(
                mainActivityPendingIntent(
                    context = context,
                    section = ShellSection.Chat,
                    taskId = task.id,
                    taskFollowUpKey = TaskFollowUpPresentation.followUpKey(task),
                ),
            )
            .setAutoCancel(true)

        if (shouldOfferApprovalActions(context, task)) {
            builder.addAction(
                0,
                TaskFollowUpPresentation.approveActionLabel(task),
                receiverPendingIntent(
                    context = context,
                    action = actionApproveTask,
                    taskId = task.id,
                    approvalId = task.approvalRequestId,
                ),
            )
            builder.addAction(
                0,
                TaskFollowUpPresentation.denyActionLabel(task),
                receiverPendingIntent(
                    context = context,
                    action = actionDenyTask,
                    taskId = task.id,
                    approvalId = task.approvalRequestId,
                ),
            )
        }
        if (shouldOfferRetryAction(task)) {
            builder.addAction(
                0,
                TaskFollowUpPresentation.retryActionLabel(task),
                receiverPendingIntent(
                    context = context,
                    action = actionRetryTask,
                    taskId = task.id,
                ),
            )
        }

        return builder.build()
    }

    private fun mainActivityPendingIntent(
        context: Context,
        section: ShellSection,
        taskId: String? = null,
        taskFollowUpKey: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(extraOpenSection, section.routeKey)
            taskId?.let { putExtra(extraOpenTaskId, it) }
            taskFollowUpKey?.let { putExtra(extraOpenTaskFollowUpKey, it) }
        }
        return PendingIntent.getActivity(
            context,
            section.ordinal * 31 + (taskFollowUpKey?.hashCode() ?: taskId?.hashCode() ?: 0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun receiverPendingIntent(
        context: Context,
        action: String,
        taskId: String? = null,
        approvalId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            taskId?.let { putExtra(extraTaskId, it) }
            approvalId?.let { putExtra(extraApprovalId, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            listOfNotNull(action, taskId, approvalId).joinToString(":").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun shouldOfferApprovalActions(
        context: Context,
        task: AgentTaskRecord,
    ): Boolean {
        if (task.status != AgentTaskStatus.WaitingUser || task.approvalRequestId.isNullOrBlank()) {
            return false
        }
        val application = context.applicationContext as? MobileClawApplication ?: return false
        val approval = application.appContainer.approvalInboxRepository.items.value.firstOrNull {
            it.id == task.approvalRequestId
        } ?: return false
        return approval.status == ApprovalInboxStatus.Pending
    }

    private fun shouldOfferRetryAction(task: AgentTaskRecord): Boolean {
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

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val quickActionsChannel = NotificationChannel(
            quickActionsChannelId,
            "Shell quick actions",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val voiceChannel = NotificationChannel(
            voiceChannelId,
            "Voice capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        val taskFollowUpChannel = NotificationChannel(
            taskFollowUpChannelId,
            "Task follow-ups",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(quickActionsChannel)
        manager.createNotificationChannel(voiceChannel)
        manager.createNotificationChannel(taskFollowUpChannel)
    }

    private fun taskNotificationId(taskId: String): Int {
        return 5_000 + taskId.hashCode().let { hash ->
            if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
        }
    }

    private const val filesOrganizeActionKey = "files.organize.execute"
    private const val filesTransferActionKey = "files.transfer.execute"
}
