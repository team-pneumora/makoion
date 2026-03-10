package io.makoion.mobileclaw.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.notifications.ShellNotificationCenter

class VoiceCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var stoppedByUser = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val application = application as MobileClawApplication
        return when (intent?.action) {
            actionStop -> {
                stoppedByUser = true
                stopRecognition()
                application.appContainer.voiceEntryCoordinator.markIdle(
                    "Voice entry stopped from the notification tray.",
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startForeground(
                    ShellNotificationCenter.voiceNotificationId,
                    ShellNotificationCenter.buildVoiceCaptureNotification(this),
                )
                startRecognition()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        destroyRecognizer()
        val application = application as MobileClawApplication
        if (application.appContainer.voiceEntryCoordinator.state.value.isActive) {
            application.appContainer.voiceEntryCoordinator.markIdle(
                "Voice quick entry is idle.",
            )
        }
        super.onDestroy()
    }

    private fun startRecognition() {
        val application = application as MobileClawApplication
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            application.appContainer.voiceEntryCoordinator.markFailure(
                "Android speech recognition is unavailable on this device.",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        mainHandler.post {
            destroyRecognizer()
            stoppedByUser = false
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer.setRecognitionListener(
                VoiceRecognitionListener(
                    onReady = {
                        application.appContainer.voiceEntryCoordinator.markListening(
                            "Recognizer is ready. Start speaking now.",
                        )
                    },
                    onSpeechBegin = {
                        application.appContainer.voiceEntryCoordinator.markListening(
                            "Listening and transcribing speech from the microphone.",
                        )
                    },
                    onPartial = { transcript ->
                        application.appContainer.voiceEntryCoordinator.updatePartialTranscript(
                            transcript,
                        )
                    },
                    onComplete = { transcript ->
                        application.appContainer.voiceEntryCoordinator.completeTranscript(
                            transcript,
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                    onFailure = { message ->
                        application.appContainer.voiceEntryCoordinator.markFailure(message)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                ),
            )
            speechRecognizer = recognizer
            application.appContainer.voiceEntryCoordinator.markListening(
                "Preparing Android speech recognition.",
            )
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                },
            )
        }
    }

    private fun stopRecognition() {
        mainHandler.post {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            destroyRecognizer()
        }
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private inner class VoiceRecognitionListener(
        private val onReady: () -> Unit,
        private val onSpeechBegin: () -> Unit,
        private val onPartial: (String) -> Unit,
        private val onComplete: (String) -> Unit,
        private val onFailure: (String) -> Unit,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onReady()
        }

        override fun onBeginningOfSpeech() {
            onSpeechBegin()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (stoppedByUser && error == SpeechRecognizer.ERROR_CLIENT) {
                return
            }
            onFailure(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (transcript.isNullOrBlank()) {
                onFailure("Speech ended but no transcript text was returned.")
                return
            }
            onComplete(transcript)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                onPartial(partial)
            }
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private fun errorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio could not be captured."
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is missing for speech recognition."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition could not reach the recognition service."
            SpeechRecognizer.ERROR_NO_MATCH ->
                "No recognizable speech was detected. Try again with a shorter utterance."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "The speech recognizer is busy. Wait a moment and retry."
            SpeechRecognizer.ERROR_SERVER -> "The speech recognition service returned an error."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "No speech was detected before the recognizer timed out."
            else -> "Speech recognition failed with error code $error."
        }
    }

    companion object {
        private const val actionStart = "io.makoion.mobileclaw.service.START"
        const val actionStop = "io.makoion.mobileclaw.service.STOP"

        fun startIntent(context: Context): Intent {
            return Intent(context, VoiceCaptureService::class.java).apply {
                action = actionStart
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, VoiceCaptureService::class.java).apply {
                action = actionStop
            }
        }
    }
}
