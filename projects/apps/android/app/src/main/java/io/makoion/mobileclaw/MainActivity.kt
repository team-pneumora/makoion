package io.makoion.mobileclaw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.makoion.mobileclaw.notifications.ShellNotificationCenter
import io.makoion.mobileclaw.ui.MobileClawShellApp
import io.makoion.mobileclaw.ui.ShellSection
import io.makoion.mobileclaw.ui.theme.MobileClawTheme

class MainActivity : ComponentActivity() {
    private var requestedSection by mutableStateOf(ShellSection.Chat)
    private var requestedTaskId by mutableStateOf<String?>(null)
    private var requestedTaskFollowUpKey by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSection = resolveSection(intent)
        requestedTaskId = resolveTaskId(intent)
        requestedTaskFollowUpKey = resolveTaskFollowUpKey(intent)
        enableEdgeToEdge()
        setContent {
            MobileClawTheme {
                MobileClawShellApp(
                    startSection = requestedSection,
                    startTaskId = requestedTaskId,
                    startTaskFollowUpKey = requestedTaskFollowUpKey,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSection = resolveSection(intent)
        requestedTaskId = resolveTaskId(intent)
        requestedTaskFollowUpKey = resolveTaskFollowUpKey(intent)
    }

    private fun resolveSection(intent: Intent?): ShellSection {
        return ShellSection.fromRouteKey(
            intent?.getStringExtra(ShellNotificationCenter.extraOpenSection),
        )
    }

    private fun resolveTaskId(intent: Intent?): String? {
        return intent?.getStringExtra(ShellNotificationCenter.extraOpenTaskId)
    }

    private fun resolveTaskFollowUpKey(intent: Intent?): String? {
        return intent?.getStringExtra(ShellNotificationCenter.extraOpenTaskFollowUpKey)
    }
}
