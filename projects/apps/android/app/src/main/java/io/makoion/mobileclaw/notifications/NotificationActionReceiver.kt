package io.makoion.mobileclaw.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.ui.ShellSection

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val application = context.applicationContext as MobileClawApplication
        when (intent.action) {
            ShellNotificationCenter.actionStartVoice -> {
                application.appContainer.voiceEntryCoordinator.startCapture()
            }
            ShellNotificationCenter.actionStopVoice -> {
                application.appContainer.voiceEntryCoordinator.stopCapture()
            }
            ShellNotificationCenter.actionOpenApprovals -> {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ShellNotificationCenter.extraOpenSection, ShellSection.Approvals.routeKey)
                }
                context.startActivity(openIntent)
            }
        }
    }
}
