package io.makoion.mobileclaw.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface StoredCredentialRevealResult {
    data class Revealed(val secret: String) : StoredCredentialRevealResult

    data class Error(val message: String) : StoredCredentialRevealResult

    data object Cancelled : StoredCredentialRevealResult
}

internal suspend fun authenticateAndRevealStoredCredential(
    context: Context,
    providerLabel: String,
    prefersKorean: Boolean,
    revealSecret: suspend () -> String?,
): StoredCredentialRevealResult {
    val activity = context.findFragmentActivity()
        ?: return StoredCredentialRevealResult.Error(
            if (prefersKorean) {
                "생체인증을 열 수 있는 화면 컨텍스트를 찾지 못했습니다."
            } else {
                "Unable to open biometric authentication from this screen."
            },
        )
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val biometricManager = BiometricManager.from(activity)
    val canAuthenticate = biometricManager.canAuthenticate(authenticators)
    if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
        return StoredCredentialRevealResult.Error(
            biometricUnavailableMessage(
                prefersKorean = prefersKorean,
                canAuthenticate = canAuthenticate,
            ),
        )
    }
    val authResult = authenticateForStoredCredentialReveal(
        activity = activity,
        providerLabel = providerLabel,
        prefersKorean = prefersKorean,
        authenticators = authenticators,
    )
    if (authResult is StoredCredentialRevealResult.Error) {
        return authResult
    }
    if (authResult == StoredCredentialRevealResult.Cancelled) {
        return authResult
    }
    val secret = revealSecret()
    return if (secret.isNullOrBlank()) {
        StoredCredentialRevealResult.Error(
            if (prefersKorean) {
                "저장된 키를 읽지 못했습니다."
            } else {
                "Unable to read the saved key."
            },
        )
    } else {
        StoredCredentialRevealResult.Revealed(secret)
    }
}

private suspend fun authenticateForStoredCredentialReveal(
    activity: FragmentActivity,
    providerLabel: String,
    prefersKorean: Boolean,
    authenticators: Int,
): StoredCredentialRevealResult {
    return suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    if (continuation.isActive) {
                        continuation.resume(StoredCredentialRevealResult.Revealed(""))
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (!continuation.isActive) {
                        return
                    }
                    val result = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> StoredCredentialRevealResult.Cancelled
                        else -> StoredCredentialRevealResult.Error(
                            if (prefersKorean) {
                                "생체인증에 실패했습니다. ${errString}"
                            } else {
                                "Biometric authentication failed. $errString"
                            },
                        )
                    }
                    continuation.resume(result)
                }

                override fun onAuthenticationFailed() {
                    // Let the system keep the prompt open for another attempt.
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(
                if (prefersKorean) {
                    "저장된 키 보기"
                } else {
                    "Reveal saved key"
                },
            )
            .setSubtitle(
                if (prefersKorean) {
                    "$providerLabel 키를 보려면 인증하세요."
                } else {
                    "Authenticate to reveal your $providerLabel key."
                },
            )
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }
}

private fun biometricUnavailableMessage(
    prefersKorean: Boolean,
    canAuthenticate: Int,
): String {
    return when (canAuthenticate) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            if (prefersKorean) {
                "기기에 등록된 생체인증 또는 기기 잠금이 없습니다."
            } else {
                "No biometric or device credential is enrolled on this device."
            }
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
            if (prefersKorean) {
                "이 기기는 생체인증을 지원하지 않습니다."
            } else {
                "This device does not support biometric authentication."
            }
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            if (prefersKorean) {
                "생체인증 하드웨어를 지금 사용할 수 없습니다."
            } else {
                "Biometric hardware is currently unavailable."
            }
        else ->
            if (prefersKorean) {
                "생체인증을 사용할 수 없습니다."
            } else {
                "Biometric authentication is unavailable."
            }
    }
}

internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) {
            return current
        }
        current = current.baseContext
    }
    return null
}
