package io.makoion.mobileclaw.data

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.makoion.mobileclaw.notifications.ShellNotificationCenter
import io.makoion.mobileclaw.sync.ScheduledAutomationWorker
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScheduledAutomationCoordinator(
    private val context: Context,
    private val scheduledAutomationRepository: ScheduledAutomationRepository,
    private val auditTrailRepository: AuditTrailRepository,
    private val scheduledAgentRunner: ScheduledAgentRunner,
    private val deliveryRouter: DeliveryRouter,
) {
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        coordinatorScope.launch {
            syncScheduledWork()
        }
    }

    suspend fun activateAutomation(automationId: String): ScheduledAutomationRecord? {
        val updated = scheduledAutomationRepository.setStatus(
            automationId = automationId,
            status = ScheduledAutomationStatus.Active,
        ) ?: return null
        val schedule = scheduleFor(updated)
        enqueueScheduledWork(updated.id, schedule)
        return scheduledAutomationRepository.updateScheduleWindow(
            automationId = updated.id,
            nextRunAtEpochMillis = schedule.initialRunAtEpochMillis,
        )
    }

    suspend fun pauseAutomation(automationId: String): ScheduledAutomationRecord? {
        WorkManager.getInstance(context).cancelUniqueWork(ScheduledAutomationWorker.workName(automationId))
        val updated = scheduledAutomationRepository.setStatus(
            automationId = automationId,
            status = ScheduledAutomationStatus.Paused,
        ) ?: return null
        return scheduledAutomationRepository.updateScheduleWindow(
            automationId = updated.id,
            nextRunAtEpochMillis = null,
        )
    }

    suspend fun runAutomationNow(automationId: String): ScheduledAutomationRecord? {
        return executeAutomation(automationId, manualTrigger)
    }

    suspend fun syncScheduledWork(): ScheduledAutomationSyncSnapshot {
        scheduledAutomationRepository.refresh()
        val automations = scheduledAutomationRepository.automations.value
        var snapshot = ScheduledAutomationSyncSnapshot(
            automationCount = automations.size,
        )
        automations.forEach { automation ->
            if (automation.status == ScheduledAutomationStatus.Active) {
                snapshot = snapshot.copy(
                    activeCount = snapshot.activeCount + 1,
                )
                val schedule = scheduleFor(automation)
                enqueueScheduledWork(automation.id, schedule)
                if (automation.nextRunAtEpochMillis != schedule.initialRunAtEpochMillis) {
                    scheduledAutomationRepository.updateScheduleWindow(
                        automationId = automation.id,
                        nextRunAtEpochMillis = schedule.initialRunAtEpochMillis,
                    )
                    snapshot = snapshot.copy(
                        repairedScheduleWindowCount = snapshot.repairedScheduleWindowCount + 1,
                    )
                }
            } else {
                snapshot = when (automation.status) {
                    ScheduledAutomationStatus.Planned -> snapshot.copy(
                        plannedCount = snapshot.plannedCount + 1,
                    )
                    ScheduledAutomationStatus.Paused -> snapshot.copy(
                        pausedCount = snapshot.pausedCount + 1,
                    )
                    ScheduledAutomationStatus.Blocked,
                    ScheduledAutomationStatus.Degraded -> snapshot.copy(
                        pausedCount = snapshot.pausedCount + 1,
                    )
                    ScheduledAutomationStatus.Active -> snapshot
                }
                WorkManager.getInstance(context).cancelUniqueWork(
                    ScheduledAutomationWorker.workName(automation.id),
                )
                if (automation.nextRunAtEpochMillis != null) {
                    scheduledAutomationRepository.updateScheduleWindow(
                        automationId = automation.id,
                        nextRunAtEpochMillis = null,
                    )
                    snapshot = snapshot.copy(
                        repairedScheduleWindowCount = snapshot.repairedScheduleWindowCount + 1,
                    )
                }
            }
        }
        return snapshot
    }

    suspend fun executeAutomation(
        automationId: String,
        trigger: String,
    ): ScheduledAutomationRecord? {
        val automation = scheduledAutomationRepository.findById(automationId) ?: return null
        if (trigger == scheduledTrigger && automation.status != ScheduledAutomationStatus.Active) {
            auditTrailRepository.logAction(
                action = "automation.execute",
                result = "skipped",
                details = "Skipped scheduled run for ${automation.title} because it is ${automation.status.name.lowercase()}.",
                requestId = automation.id,
            )
            return automation
        }

        val schedule = scheduleFor(automation)
        val executedAt = System.currentTimeMillis()
        val nextRunAt = if (automation.status == ScheduledAutomationStatus.Active) {
            executedAt + schedule.repeatIntervalMs
        } else {
            automation.nextRunAtEpochMillis
        }
        val updated = scheduledAutomationRepository.noteExecution(
            automationId = automation.id,
            lastRunAtEpochMillis = executedAt,
            nextRunAtEpochMillis = nextRunAt,
            status = ScheduledAutomationStatus.Active,
        ) ?: automation
        val executionResult = scheduledAgentRunner.execute(updated)
        val receipt = if (executionResult.status == ScheduledAutomationStatus.Blocked || !executionResult.deliverToUser) {
            DeliveryReceipt(
                channelId = "chat-only",
                channelLabel = "Chat summary",
                delivered = false,
                detail = executionResult.blockedReason ?: executionResult.summary,
            )
        } else {
            deliveryRouter.deliverAutomationAlert(updated, executionResult)
        }
        val finalized = scheduledAutomationRepository.noteExecution(
            automationId = updated.id,
            lastRunAtEpochMillis = executedAt,
            nextRunAtEpochMillis = nextRunAt,
            status = executionResult.status,
            resultSummary = executionResult.summary,
            blockedReason = executionResult.blockedReason,
            deliveryReceiptLabel = receipt.detail,
        ) ?: updated
        val deliveryMode = resolveDeliveryMode(receipt.channelId)
        auditTrailRepository.logAction(
            action = "automation.execute",
            result = deliveryMode.auditResult,
            details = buildExecutionDetails(
                automation = finalized,
                deliveryMode = deliveryMode,
                trigger = trigger,
                executionResult = executionResult,
                receipt = receipt,
            ),
            requestId = finalized.id,
        )
        return finalized
    }

    private fun enqueueScheduledWork(
        automationId: String,
        schedule: AutomationSchedule,
    ) {
        val request = PeriodicWorkRequestBuilder<ScheduledAutomationWorker>(
            schedule.repeatIntervalMs,
            TimeUnit.MILLISECONDS,
        )
            .setInitialDelay(schedule.initialDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduledAutomationWorker.inputAutomationId to automationId))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ScheduledAutomationWorker.workName(automationId),
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleFor(automation: ScheduledAutomationRecord): AutomationSchedule {
        val repeatIntervalMs = when (automation.scheduleLabel) {
            "Hourly" -> Duration.ofHours(1).toMillis()
            "Weekly" -> Duration.ofDays(7).toMillis()
            "Daily morning" -> Duration.ofDays(1).toMillis()
            else -> Duration.ofDays(1).toMillis()
        }
        val initialRunAt = when (automation.scheduleLabel) {
            "Hourly" -> nextHourBoundary()
            "Weekly" -> nextWeeklyWindow()
            "Daily morning" -> nextDailyMorningWindow()
            else -> System.currentTimeMillis() + Duration.ofMinutes(15).toMillis()
        }
        return AutomationSchedule(
            repeatIntervalMs = repeatIntervalMs,
            initialDelayMs = (initialRunAt - System.currentTimeMillis()).coerceAtLeast(0L),
            initialRunAtEpochMillis = initialRunAt,
        )
    }

    private fun nextHourBoundary(): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return now.plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    }

    private fun nextDailyMorningWindow(): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        return next.toInstant().toEpochMilli()
    }

    private fun nextWeeklyWindow(): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var next = now.with(DayOfWeek.MONDAY).withHour(9).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) {
            next = next.plusWeeks(1)
        }
        return next.toInstant().toEpochMilli()
    }

    private fun resolveDeliveryMode(channelIdOrLabel: String): AutomationDeliveryMode {
        return when (channelIdOrLabel) {
            "telegram-bot-delivery",
            "Telegram" -> AutomationDeliveryMode.Telegram
            "chat-only" -> AutomationDeliveryMode.Blocked
            else -> AutomationDeliveryMode.LocalNotification
        }
    }

    private fun buildExecutionDetails(
        automation: ScheduledAutomationRecord,
        deliveryMode: AutomationDeliveryMode,
        trigger: String,
        executionResult: AutomationExecutionResult,
        receipt: DeliveryReceipt,
    ): String {
        val deliveryDetails = when (deliveryMode) {
            AutomationDeliveryMode.LocalNotification ->
                "Delivered through the on-device notification channel."
            AutomationDeliveryMode.Telegram ->
                "Delivered through the Telegram relay."
            AutomationDeliveryMode.Blocked ->
                "The run stayed blocked and no delivery channel was used."
        }
        return "Executed ${automation.title} via ${automation.scheduleLabel} ($trigger trigger). ${executionResult.summary} $deliveryDetails Receipt: ${receipt.detail}"
    }

    companion object {
        private const val manualTrigger = "manual"
        private const val scheduledTrigger = "scheduled"
    }
}

private data class AutomationSchedule(
    val repeatIntervalMs: Long,
    val initialDelayMs: Long,
    val initialRunAtEpochMillis: Long,
)

enum class AutomationDeliveryMode(
    val auditResult: String,
) {
    LocalNotification("delivered"),
    Telegram("telegram_delivered"),
    Blocked("blocked"),
}

data class ScheduledAutomationSyncSnapshot(
    val automationCount: Int = 0,
    val activeCount: Int = 0,
    val plannedCount: Int = 0,
    val pausedCount: Int = 0,
    val repairedScheduleWindowCount: Int = 0,
)
