package io.makoion.mobileclaw.data

import android.content.Context
import io.makoion.mobileclaw.notifications.ShellNotificationCenter

data class DeliveryReceipt(
    val channelId: String,
    val channelLabel: String,
    val delivered: Boolean,
    val detail: String,
)

interface DeliveryRouter {
    suspend fun deliverAutomationAlert(
        automation: ScheduledAutomationRecord,
        executionResult: AutomationExecutionResult,
    ): DeliveryReceipt
}

class DefaultDeliveryRouter(
    private val context: Context,
    private val deliveryChannelRepository: DeliveryChannelRegistryRepository,
    private val deliveryChannelCredentialVault: DeliveryChannelCredentialVault,
    private val telegramDeliveryGateway: TelegramDeliveryGateway,
) : DeliveryRouter {
    override suspend fun deliverAutomationAlert(
        automation: ScheduledAutomationRecord,
        executionResult: AutomationExecutionResult,
    ): DeliveryReceipt {
        val spec = automation.runSpecJson?.let(::decodeRunSpec) ?: buildScheduledAgentRunSpec(automation.prompt)
        if (spec.deliveryPolicy == DeliveryTargetPolicy.ChatSummary) {
            return DeliveryReceipt(
                channelId = "chat-only",
                channelLabel = "Chat summary",
                delivered = false,
                detail = "Recorded in chat and dashboard only.",
            )
        }
        if (spec.deliveryPolicy == DeliveryTargetPolicy.PreferTelegramThenLocalPush) {
            val telegramReceipt = sendTelegramIfAvailable(automation, executionResult)
            if (telegramReceipt?.delivered == true) {
                return telegramReceipt
            }
        }
        ShellNotificationCenter.showAgentAlert(
            context = context,
            automation = automation,
            title = executionResult.alertTitle,
            body = executionResult.alertBody,
            severity = executionResult.severity,
        )
        deliveryChannelRepository.noteDeliveryAttempt(
            channelId = localNotificationChannelId,
            deliveredAtEpochMillis = System.currentTimeMillis(),
        )
        return DeliveryReceipt(
            channelId = localNotificationChannelId,
            channelLabel = "Phone local notification",
            delivered = true,
            detail = "Delivered on-device through the Android notification channel.",
        )
    }

    private suspend fun sendTelegramIfAvailable(
        automation: ScheduledAutomationRecord,
        executionResult: AutomationExecutionResult,
    ): DeliveryReceipt? {
        val binding = deliveryChannelRepository.telegramBinding(telegramChannelId) ?: return null
        val token = deliveryChannelCredentialVault.read(telegramChannelId)?.trim().orEmpty()
        if (token.isBlank()) {
            return null
        }
        return when (
            val result = telegramDeliveryGateway.sendMessage(
                botToken = token,
                chatId = binding.chatId,
                text = buildTelegramMessage(automation, executionResult),
            )
        ) {
            is TelegramDeliveryResult.Delivered -> {
                deliveryChannelRepository.noteDeliveryAttempt(
                    channelId = telegramChannelId,
                    deliveredAtEpochMillis = System.currentTimeMillis(),
                    error = null,
                )
                DeliveryReceipt(
                    channelId = telegramChannelId,
                    channelLabel = "Telegram bot relay",
                    delivered = true,
                    detail = "Delivered to ${binding.destinationLabel ?: binding.chatId}.",
                )
            }
            is TelegramDeliveryResult.Failed -> {
                deliveryChannelRepository.noteDeliveryAttempt(
                    channelId = telegramChannelId,
                    deliveredAtEpochMillis = System.currentTimeMillis(),
                    error = result.detail,
                )
                null
            }
        }
    }

    private fun buildTelegramMessage(
        automation: ScheduledAutomationRecord,
        executionResult: AutomationExecutionResult,
    ): String {
        return buildString {
            append(executionResult.alertTitle)
            append("\n\n")
            append(executionResult.alertBody.take(telegramMessageMaxLength))
            if (executionResult.alertBody.length > telegramMessageMaxLength) {
                append("...")
            }
        }
    }

    companion object {
        private const val localNotificationChannelId = "phone-local-notification"
        private const val telegramChannelId = "telegram-bot-delivery"
        private const val telegramMessageMaxLength = 3500
    }
}
