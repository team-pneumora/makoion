package io.makoion.mobileclaw.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.makoion.mobileclaw.MobileClawApplication

class AgentTaskRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as MobileClawApplication
        return try {
            application.appContainer.agentTaskRetryCoordinator.drainRetryQueue()
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
        const val workName = "agent_task_retry"
    }
}
