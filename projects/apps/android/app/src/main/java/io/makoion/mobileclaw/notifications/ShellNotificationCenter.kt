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
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.R
import io.makoion.mobileclaw.service.VoiceCaptureService
import io.makoion.mobileclaw.ui.ShellSection

object ShellNotificationCenter {
    const val extraOpenSection = "open_section"
    const val quickActionsNotificationId = 3001
    const val voiceNotificationId = 3002

    const val actionStartVoice = "io.makoion.mobileclaw.action.START_VOICE"
    const val actionStopVoice = "io.makoion.mobileclaw.action.STOP_VOICE"
    const val actionOpenApprovals = "io.makoion.mobileclaw.action.OPEN_APPROVALS"

    private const val quickActionsChannelId = "shell_quick_actions"
    private const val voiceChannelId = "voice_capture"

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
            .setContentIntent(mainActivityPendingIntent(context, ShellSection.Overview))
            .addAction(
                0,
                "Stop",
                receiverPendingIntent(context, actionStopVoice),
            )
            .build()
    }

    private fun buildQuickActionsNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, quickActionsChannelId)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle("MobileClaw quick actions")
            .setContentText("Jump into approvals or start a voice capture flow.")
            .setContentIntent(mainActivityPendingIntent(context, ShellSection.Overview))
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

    private fun mainActivityPendingIntent(
        context: Context,
        section: ShellSection,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(extraOpenSection, section.routeKey)
        }
        return PendingIntent.getActivity(
            context,
            section.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun receiverPendingIntent(
        context: Context,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        manager.createNotificationChannel(quickActionsChannel)
        manager.createNotificationChannel(voiceChannel)
    }
}
