package io.makoion.mobileclaw.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.makoion.mobileclaw.MobileClawApplication

class TransferOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as MobileClawApplication
        val coordinator = application.appContainer.transferBridgeCoordinator
        return try {
            coordinator.drainOutbox()
            application.appContainer.devicePairingRepository.refresh()
            application.appContainer.auditTrailRepository.refresh()
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount >= 1) {
                coordinator.noteWorkerFailure(runAttemptCount)
                Result.failure()
            } else {
                coordinator.noteWorkerRetry(runAttemptCount)
                Result.retry()
            }
        }
    }

    companion object {
        const val immediateWorkName = "transfer_bridge_drain"
        const val retryWorkName = "transfer_bridge_retry"
    }
}
