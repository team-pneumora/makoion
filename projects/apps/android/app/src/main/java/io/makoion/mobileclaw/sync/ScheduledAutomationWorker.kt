package io.makoion.mobileclaw.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.makoion.mobileclaw.MobileClawApplication

class ScheduledAutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val automationId = inputData.getString(inputAutomationId) ?: return Result.failure()
        val application = applicationContext as MobileClawApplication
        return try {
            application.appContainer.scheduledAutomationCoordinator.executeAutomation(
                automationId = automationId,
                trigger = scheduledTrigger,
            )
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount >= 1) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val inputAutomationId = "automation_id"

        fun workName(automationId: String): String = "scheduled_automation_$automationId"

        private const val scheduledTrigger = "scheduled"
    }
}
