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
    private var requestedSection by mutableStateOf(ShellSection.Overview)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSection = resolveSection(intent)
        enableEdgeToEdge()
        setContent {
            MobileClawTheme {
                MobileClawShellApp(startSection = requestedSection)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSection = resolveSection(intent)
    }

    private fun resolveSection(intent: Intent?): ShellSection {
        return ShellSection.fromRouteKey(
            intent?.getStringExtra(ShellNotificationCenter.extraOpenSection),
        )
    }
}
