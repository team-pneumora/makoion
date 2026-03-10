package io.makoion.mobileclaw.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.makoion.mobileclaw.notifications.ShellNotificationCenter
import io.makoion.mobileclaw.service.VoiceCaptureService

data class VoiceTranscriptEntry(
    val id: String,
    val text: String,
    val capturedAtLabel: String,
)

data class VoiceEntryState(
    val isActive: Boolean = false,
    val headline: String = "Voice quick entry idle",
    val summary: String = "Use the notification or the shell button to start a foreground voice capture session.",
    val partialTranscript: String? = null,
    val finalTranscript: String? = null,
    val recentTranscripts: List<VoiceTranscriptEntry> = emptyList(),
)

class VoiceEntryCoordinator(
    private val context: Context,
) {
    private val _state = MutableStateFlow(VoiceEntryState())
    val state: StateFlow<VoiceEntryState> = _state.asStateFlow()

    fun startCapture() {
        if (!hasAudioPermission()) {
            markIdle("Microphone permission is required before voice capture can start.")
            return
        }
        _state.update {
            it.copy(
                isActive = true,
                headline = "Starting voice capture",
                summary = "Foreground voice capture is preparing the Android speech recognizer.",
                partialTranscript = null,
            )
        }
        ContextCompat.startForegroundService(
            context,
            VoiceCaptureService.startIntent(context),
        )
    }

    fun stopCapture() {
        context.startService(VoiceCaptureService.stopIntent(context))
        markIdle("Voice entry stopped.")
    }

    fun postQuickActionsNotification() {
        ShellNotificationCenter.showQuickActions(context)
    }

    fun markListening(summary: String) {
        _state.update {
            it.copy(
                isActive = true,
                headline = "Voice quick entry active",
                summary = summary,
            )
        }
    }

    fun updatePartialTranscript(transcript: String) {
        _state.update {
            it.copy(
                isActive = true,
                headline = "Listening for speech",
                summary = "Partial speech recognition is updating in real time.",
                partialTranscript = transcript,
            )
        }
    }

    fun completeTranscript(transcript: String) {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            markIdle("Speech was captured but no transcript text was returned.")
            return
        }

        val capturedAt = DateFormat.getTimeFormat(context).format(System.currentTimeMillis())
        _state.update { current ->
            current.copy(
                isActive = false,
                headline = "Voice transcript ready",
                summary = "Speech recognition completed and the transcript is ready for routing into tasks or notes.",
                partialTranscript = null,
                finalTranscript = trimmed,
                recentTranscripts = listOf(
                    VoiceTranscriptEntry(
                        id = "voice-${UUID.randomUUID()}",
                        text = trimmed,
                        capturedAtLabel = capturedAt,
                    ),
                ) + current.recentTranscripts
                    .filterNot { entry -> entry.text == trimmed }
                    .take(maxHistory - 1),
            )
        }
    }

    fun markFailure(summary: String) {
        _state.update { current ->
            current.copy(
                isActive = false,
                headline = "Voice capture unavailable",
                summary = summary,
                partialTranscript = null,
            )
        }
    }

    fun markIdle(summary: String) {
        _state.update { current ->
            current.copy(
                isActive = false,
                headline = "Voice quick entry idle",
                summary = summary,
                partialTranscript = null,
            )
        }
    }

    fun relativeTimeLabel(timestamp: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val maxHistory = 5
    }
}
