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
        return try {
            application.appContainer.transferBridgeCoordinator.drainOutbox()
            application.appContainer.devicePairingRepository.refresh()
            application.appContainer.auditTrailRepository.refresh()
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
        const val immediateWorkName = "transfer_bridge_drain"
        const val retryWorkName = "transfer_bridge_retry"
    }
}
