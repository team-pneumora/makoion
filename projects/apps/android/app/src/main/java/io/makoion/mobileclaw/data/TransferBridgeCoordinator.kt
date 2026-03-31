package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.makoion.mobileclaw.notifications.ShellNotificationCenter
import io.makoion.mobileclaw.sync.TransferOutboxWorker
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferBridgeCoordinator(
    private val context: Context,
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
    private val agentTaskRepository: AgentTaskRepository,
) : TransferOutboxScheduler {
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun scheduleDrain(delayMs: Long) {
        val requestBuilder = OneTimeWorkRequestBuilder<TransferOutboxWorker>()
        if (delayMs > 0L) {
            requestBuilder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        val request = requestBuilder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (delayMs > 0L) {
                TransferOutboxWorker.retryWorkName
            } else {
                TransferOutboxWorker.immediateWorkName
            },
            if (delayMs > 0L) {
                ExistingWorkPolicy.REPLACE
            } else {
                ExistingWorkPolicy.APPEND_OR_REPLACE
            },
            request,
        )
    }

    fun scheduleRecovery() {
        coordinatorScope.launch {
            recoverShellState()
        }
    }

    suspend fun recoverShellState(
        scheduleWork: Boolean = true,
    ): TransferRecoverySnapshot {
        val now = System.currentTimeMillis()
        val recoveredCount = withContext(Dispatchers.IO) { recoverStaleSendingDrafts(now) }
        if (recoveredCount > 0) {
            auditTrailRepository.logAction(
                action = "files.send_to_device",
                result = "recovered",
                details = "Recovered $recoveredCount interrupted transfer drafts back into the queue.",
            )
        }
        val manifestRecoverySnapshot = recoverManifestFallbackDrafts(now)
        if (manifestRecoverySnapshot.requeuedCount > 0) {
            auditTrailRepository.logAction(
                action = "files.send_to_device",
                result = "manifest_requeued",
                details = "Requeued ${manifestRecoverySnapshot.requeuedCount} manifest-only draft(s) after binary sources became available again.",
            )
        }
        val dueQueuedDraftCount = withContext(Dispatchers.IO) { queryDueQueuedDraftCount(now) }
        val delayedQueuedSnapshot = withContext(Dispatchers.IO) { queryDelayedQueuedRetrySnapshot(now) }
        val immediateDrainRequested =
            recoveredCount > 0 || manifestRecoverySnapshot.requeuedCount > 0 || dueQueuedDraftCount > 0
        when {
            scheduleWork && immediateDrainRequested -> scheduleDrain()
            scheduleWork -> scheduleFollowUpWork(
                now = now,
                delayedQueuedSnapshot = delayedQueuedSnapshot,
                pendingManifestDraftCount = manifestRecoverySnapshot.pendingCandidateCount,
            )
        }
        return TransferRecoverySnapshot(
            recoveredStaleDraftCount = recoveredCount,
            dueQueuedDraftCount = dueQueuedDraftCount,
            delayedQueuedDraftCount = delayedQueuedSnapshot.queuedCount,
            nextAttemptAtEpochMillis = delayedQueuedSnapshot.nextAttemptAtEpochMillis,
            recoveredManifestDraftCount = manifestRecoverySnapshot.requeuedCount,
            pendingManifestDraftCount = manifestRecoverySnapshot.pendingCandidateCount,
            immediateDrainRequested = immediateDrainRequested,
        )
    }

    suspend fun drainOutbox() {
        val now = System.currentTimeMillis()
        val recoverySnapshot = recoverShellState(scheduleWork = false)
        val recoveredCount = recoverySnapshot.recoveredStaleDraftCount
        val recoveredManifestCount = recoverySnapshot.recoveredManifestDraftCount
        val drafts = withContext(Dispatchers.IO) { queryQueuedDrafts(now) }
        if (drafts.isEmpty()) {
            scheduleFollowUpWork(
                now = now,
                pendingManifestDraftCount = queryManifestRecoveryPendingCount(),
            )
            return
        }
        var deliveredCount = 0
        var failedCount = 0
        var retryScheduledCount = 0
        var skippedCount = 0
        drafts.forEach { draft ->
            if (!markSending(draft)) {
                skippedCount += 1
                return@forEach
            }
            when (val result = deliver(draft)) {
                is DeliveryResult.Delivered -> {
                    markDelivered(
                        draft = draft,
                        endpoint = result.endpoint,
                        deliveryMode = result.deliveryMode,
                        receiptJson = result.receiptJson,
                        receiptWarning = result.receiptWarning,
                    )
                    deliveredCount += 1
                }
                is DeliveryResult.Failed -> {
                    val attemptNumber = draft.attemptCount + 1
                    if (result.retryable && attemptNumber < maxDeliveryAttempts) {
                        markRetryScheduled(
                            draft = draft,
                            message = result.message,
                            retryDelayMs = result.retryAfterMillis ?: computeRetryDelayMs(attemptNumber),
                        )
                        retryScheduledCount += 1
                    } else {
                        markFailed(draft, result.message)
                        failedCount += 1
                    }
                }
            }
        }
        val completedAt = System.currentTimeMillis()
        val followUpSnapshot = scheduleFollowUpWork(
            now = completedAt,
            pendingManifestDraftCount = queryManifestRecoveryPendingCount(),
        )
        auditTrailRepository.logAction(
            action = "devices.transport",
            result = "drain_completed",
            details = buildDrainSummary(
                processedCount = drafts.size,
                deliveredCount = deliveredCount,
                failedCount = failedCount,
                retryScheduledCount = retryScheduledCount,
                skippedCount = skippedCount,
                recoveredCount = recoveredCount,
                recoveredManifestCount = recoveredManifestCount,
                followUpSnapshot = followUpSnapshot,
            ),
        )
    }

    private suspend fun markSending(draft: PendingTransferDraft): Boolean {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updatedRows = databaseHelper.writableDatabase.update(
                "transfer_outbox",
                ContentValues().apply {
                    put("status", TransferDraftStatus.Sending.name)
                    put("updated_at", now)
                    put("attempt_count", draft.attemptCount + 1)
                    putNull("transport_endpoint")
                    putNull("delivery_mode")
                    putNull("receipt_json")
                    put("next_attempt_at", 0L)
                    putNull("last_error")
                },
                "id = ? AND status = ? AND next_attempt_at <= ?",
                arrayOf(draft.id, TransferDraftStatus.Queued.name, now.toString()),
            )
            if (updatedRows != 1) {
                return@withContext false
            }
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("status", "Sending")
                },
                "id = ?",
                arrayOf(draft.deviceId),
            )
            true
        }
            .also { claimed ->
                if (claimed) {
                    updateLinkedTask(
                        draft = draft,
                        status = AgentTaskStatus.Running,
                        summary = "Bridge delivery is sending ${draft.fileNames.size} files to ${draft.deviceName}.",
                        replyPreview = "Transfer delivery started for ${draft.deviceName}.",
                    )
                    auditTrailRepository.logAction(
                        action = "files.send_to_device",
                        result = "sending",
                        details = "Bridge transport started for ${draft.fileNames.size} files to ${draft.deviceName}.",
                    )
                }
            }
    }

    private suspend fun markDelivered(
        draft: PendingTransferDraft,
        endpoint: String,
        deliveryMode: String,
        receiptJson: String,
        receiptWarning: String?,
    ) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.update(
                "transfer_outbox",
                ContentValues().apply {
                    put("status", TransferDraftStatus.Delivered.name)
                    put("transport_endpoint", endpoint)
                    put("delivery_mode", deliveryMode)
                    put("receipt_json", receiptJson)
                    put("next_attempt_at", 0L)
                    put("updated_at", now)
                    put("delivered_at", now)
                    putNull("last_error")
                },
                "id = ?",
                arrayOf(draft.id),
            )
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("status", if (receiptWarning == null) "Bridge active" else "Receipt review")
                },
                "id = ?",
                arrayOf(draft.deviceId),
            )
        }
        auditTrailRepository.logAction(
            action = "files.send_to_device",
            result = if (receiptWarning == null) "delivered" else "delivered_receipt_warning",
            details = buildString {
                append("Delivered ${draft.fileNames.size} files to ${draft.deviceName} via $endpoint ($deliveryMode).")
                receiptWarning?.let {
                    append(" Receipt warning: ")
                    append(it)
                }
            },
        )
        updateLinkedTask(
            draft = draft,
            status = AgentTaskStatus.Succeeded,
            summary = "Bridge delivery completed for ${draft.fileNames.size} files to ${draft.deviceName}.",
            replyPreview = "Transfer delivered to ${draft.deviceName}.",
        )
    }

    private suspend fun markFailed(
        draft: PendingTransferDraft,
        message: String,
    ) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.update(
                "transfer_outbox",
                ContentValues().apply {
                    put("status", TransferDraftStatus.Failed.name)
                    put("updated_at", now)
                    putNull("transport_endpoint")
                    putNull("delivery_mode")
                    putNull("receipt_json")
                    put("next_attempt_at", 0L)
                    put("last_error", message)
                },
                "id = ?",
                arrayOf(draft.id),
            )
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("status", "Attention needed")
                },
                "id = ?",
                arrayOf(draft.deviceId),
            )
        }
        auditTrailRepository.logAction(
            action = "files.send_to_device",
            result = "failed",
            details = "Bridge delivery failed for ${draft.deviceName}: $message",
        )
        updateLinkedTask(
            draft = draft,
            status = AgentTaskStatus.Failed,
            summary = "Bridge delivery failed for ${draft.deviceName}: $message",
            replyPreview = "Transfer failed for ${draft.deviceName}.",
            lastError = message,
        )
    }

    private suspend fun markRetryScheduled(
        draft: PendingTransferDraft,
        message: String,
        retryDelayMs: Long,
    ) {
        val safeRetryDelayMs = retryDelayMs.coerceAtLeast(minRetryDelayMs)
        val retryAt = System.currentTimeMillis() + safeRetryDelayMs
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.update(
                "transfer_outbox",
                ContentValues().apply {
                    put("status", TransferDraftStatus.Queued.name)
                    put("updated_at", now)
                    putNull("transport_endpoint")
                    putNull("delivery_mode")
                    putNull("receipt_json")
                    put("next_attempt_at", retryAt)
                    put("last_error", message)
                },
                "id = ?",
                arrayOf(draft.id),
            )
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("status", "Retry scheduled")
                },
                "id = ?",
                arrayOf(draft.deviceId),
            )
        }
        auditTrailRepository.logAction(
            action = "files.send_to_device",
            result = "retry_scheduled",
            details = "Retry scheduled for ${draft.deviceName} in ${safeRetryDelayMs / 1000}s: $message",
        )
        updateLinkedTask(
            draft = draft,
            status = AgentTaskStatus.RetryScheduled,
            summary = "Bridge delivery will retry for ${draft.deviceName} in ${safeRetryDelayMs / 1000}s.",
            replyPreview = "Retry scheduled for ${draft.deviceName}.",
            nextRetryAtEpochMillis = retryAt,
            lastError = message,
        )
    }

    private suspend fun deliver(draft: PendingTransferDraft): DeliveryResult = withContext(Dispatchers.IO) {
        val device = queryPairedDevice(draft.deviceId) ?: return@withContext DeliveryResult.Failed(
            message = "Paired device is no longer available.",
        )
        if ("files.transfer" !in device.capabilities) {
            return@withContext DeliveryResult.Failed(
                message = "Selected device does not allow files.transfer.",
            )
        }
        when (device.transportMode) {
            DeviceTransportMode.Loopback -> DeliveryResult.Delivered(
                endpoint = "loopback://${device.id}/${draft.id}",
                deliveryMode = "loopback",
                receiptJson = syntheticReceipt(
                    deliveryMode = "loopback",
                    endpoint = "loopback://${device.id}/${draft.id}",
                ),
            )
            DeviceTransportMode.DirectHttp -> deliverOverHttp(device, draft)
        }
    }

    private fun queryQueuedDrafts(now: Long): List<PendingTransferDraft> {
        val drafts = mutableListOf<PendingTransferDraft>()
        databaseHelper.readableDatabase.query(
            "transfer_outbox",
            arrayOf(
                "id",
                "device_id",
                "device_name",
                "file_names_json",
                "approval_request_id",
                "file_refs_json",
                "attempt_count",
            ),
            "status = ? AND next_attempt_at <= ?",
            arrayOf(TransferDraftStatus.Queued.name, now.toString()),
            null,
            null,
            "next_attempt_at ASC, created_at ASC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val deviceIdIndex = cursor.getColumnIndexOrThrow("device_id")
            val deviceNameIndex = cursor.getColumnIndexOrThrow("device_name")
            val fileNamesIndex = cursor.getColumnIndexOrThrow("file_names_json")
            val approvalRequestIdIndex = cursor.getColumnIndexOrThrow("approval_request_id")
            val fileRefsIndex = cursor.getColumnIndexOrThrow("file_refs_json")
            val attemptCountIndex = cursor.getColumnIndexOrThrow("attempt_count")

            while (cursor.moveToNext()) {
                val fileRefsRaw = cursor.getString(fileRefsIndex)
                drafts += PendingTransferDraft(
                    id = cursor.getString(idIndex),
                    deviceId = cursor.getString(deviceIdIndex),
                    deviceName = cursor.getString(deviceNameIndex),
                    approvalRequestId = cursor.getString(approvalRequestIdIndex),
                    fileNames = jsonArrayToList(cursor.getString(fileNamesIndex)),
                    fileReferences = jsonArrayToFileReferences(fileRefsRaw),
                    attemptCount = cursor.getInt(attemptCountIndex),
                )
            }
        }
        return drafts
    }

    private fun recoverStaleSendingDrafts(now: Long): Int {
        return databaseHelper.writableDatabase.update(
            "transfer_outbox",
            ContentValues().apply {
                put("status", TransferDraftStatus.Queued.name)
                put("updated_at", now)
                put("next_attempt_at", now)
                put(
                    "last_error",
                    "Recovered an interrupted transfer attempt after the app or worker was restarted.",
                )
            },
            "status = ? AND updated_at <= ?",
            arrayOf(
                TransferDraftStatus.Sending.name,
                (now - staleSendingTimeoutMs).toString(),
            ),
        )
    }

    private suspend fun updateLinkedTask(
        draft: PendingTransferDraft,
        status: AgentTaskStatus,
        summary: String,
        replyPreview: String,
        nextRetryAtEpochMillis: Long? = null,
        lastError: String? = null,
    ) {
        val approvalRequestId = draft.approvalRequestId ?: return
        val updatedTask = agentTaskRepository.updateTaskByApprovalRequestId(
            approvalRequestId = approvalRequestId,
            status = status,
            summary = summary,
            replyPreview = replyPreview.take(maxReplyPreviewLength),
            nextRetryAtEpochMillis = nextRetryAtEpochMillis,
            lastError = lastError,
        ) ?: return
        ShellNotificationCenter.maybeShowTaskFollowUp(context, updatedTask)
    }

    private fun queryDueQueuedDraftCount(now: Long): Int {
        return databaseHelper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM transfer_outbox
            WHERE status = ? AND next_attempt_at <= ?
            """.trimIndent(),
            arrayOf(TransferDraftStatus.Queued.name, now.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        }
    }

    private suspend fun scheduleFollowUpWork(
        now: Long,
        delayedQueuedSnapshot: QueuedRetrySnapshot = queryDelayedQueuedRetrySnapshot(now),
        pendingManifestDraftCount: Int,
    ): TransferFollowUpSnapshot {
        val manifestPollAt = if (pendingManifestDraftCount > 0) {
            now + manifestRecoveryPollIntervalMs
        } else {
            null
        }
        val nextAttemptAt = listOfNotNull(
            delayedQueuedSnapshot.nextAttemptAtEpochMillis,
            manifestPollAt,
        ).minOrNull()
        nextAttemptAt?.let {
            scheduleDrain(it - now)
        }
        return TransferFollowUpSnapshot(
            queuedRetrySnapshot = delayedQueuedSnapshot,
            pendingManifestDraftCount = pendingManifestDraftCount,
            nextAttemptAtEpochMillis = nextAttemptAt,
        )
    }

    private fun queryDelayedQueuedRetrySnapshot(now: Long): QueuedRetrySnapshot {
        val queuedCount = databaseHelper.readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM transfer_outbox
            WHERE status = ? AND next_attempt_at > ?
            """.trimIndent(),
            arrayOf(TransferDraftStatus.Queued.name, now.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        }
        val nextAttemptAt = databaseHelper.readableDatabase.rawQuery(
            """
            SELECT MIN(next_attempt_at)
            FROM transfer_outbox
            WHERE status = ? AND next_attempt_at > ?
            """.trimIndent(),
            arrayOf(TransferDraftStatus.Queued.name, now.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else {
                0L
            }
        }
        return QueuedRetrySnapshot(
            queuedCount = queuedCount,
            nextAttemptAtEpochMillis = nextAttemptAt.takeIf { it > now },
        )
    }

    private suspend fun recoverManifestFallbackDrafts(now: Long): ManifestRecoverySnapshot {
        val candidates = withContext(Dispatchers.IO) { queryManifestRecoveryCandidates() }
        if (candidates.isEmpty()) {
            return ManifestRecoverySnapshot()
        }

        var pendingCandidateCount = 0
        var requeuedCount = 0
        candidates.groupBy { it.deviceId }.forEach { (deviceId, drafts) ->
            val device = queryPairedDevice(deviceId)
            if (
                device == null ||
                device.transportMode != DeviceTransportMode.DirectHttp ||
                device.endpointUrl.isNullOrBlank() ||
                device.trustedSecret.isNullOrBlank()
            ) {
                return@forEach
            }
            val pendingTransferIds = queryPendingTransferIds(device) ?: run {
                pendingCandidateCount += drafts.size
                return@forEach
            }
            drafts.forEach { draft ->
                if (draft.id !in pendingTransferIds) {
                    return@forEach
                }
                if (draft.fileReferences.all { sourceUriFor(it.sourceId) != null }) {
                    if (requeueManifestRecoveryDraft(draft, now)) {
                        requeuedCount += 1
                    } else {
                        pendingCandidateCount += 1
                    }
                } else {
                    pendingCandidateCount += 1
                }
            }
        }
        return ManifestRecoverySnapshot(
            requeuedCount = requeuedCount,
            pendingCandidateCount = pendingCandidateCount,
        )
    }

    private fun queryManifestRecoveryCandidates(): List<PendingTransferDraft> {
        val drafts = mutableListOf<PendingTransferDraft>()
        databaseHelper.readableDatabase.query(
            "transfer_outbox",
            arrayOf(
                "id",
                "device_id",
                "device_name",
                "file_names_json",
                "approval_request_id",
                "file_refs_json",
                "attempt_count",
            ),
            "status = ? AND delivery_mode = ?",
            arrayOf(TransferDraftStatus.Delivered.name, "manifest_only"),
            null,
            null,
            "delivered_at DESC, created_at DESC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val deviceIdIndex = cursor.getColumnIndexOrThrow("device_id")
            val deviceNameIndex = cursor.getColumnIndexOrThrow("device_name")
            val fileNamesIndex = cursor.getColumnIndexOrThrow("file_names_json")
            val approvalRequestIdIndex = cursor.getColumnIndexOrThrow("approval_request_id")
            val fileRefsIndex = cursor.getColumnIndexOrThrow("file_refs_json")
            val attemptCountIndex = cursor.getColumnIndexOrThrow("attempt_count")

            while (cursor.moveToNext()) {
                drafts += PendingTransferDraft(
                    id = cursor.getString(idIndex),
                    deviceId = cursor.getString(deviceIdIndex),
                    deviceName = cursor.getString(deviceNameIndex),
                    approvalRequestId = cursor.getString(approvalRequestIdIndex),
                    fileNames = jsonArrayToList(cursor.getString(fileNamesIndex)),
                    fileReferences = jsonArrayToFileReferences(cursor.getString(fileRefsIndex)),
                    attemptCount = cursor.getInt(attemptCountIndex),
                )
            }
        }
        return drafts
    }

    private suspend fun queryManifestRecoveryPendingCount(): Int {
        val candidates = withContext(Dispatchers.IO) { queryManifestRecoveryCandidates() }
        if (candidates.isEmpty()) {
            return 0
        }
        var pendingCandidateCount = 0
        candidates.groupBy { it.deviceId }.forEach { (deviceId, drafts) ->
            val device = queryPairedDevice(deviceId)
            if (
                device == null ||
                device.transportMode != DeviceTransportMode.DirectHttp ||
                device.endpointUrl.isNullOrBlank() ||
                device.trustedSecret.isNullOrBlank()
            ) {
                return@forEach
            }
            val pendingTransferIds = queryPendingTransferIds(device) ?: run {
                pendingCandidateCount += drafts.size
                return@forEach
            }
            pendingCandidateCount += drafts.count { it.id in pendingTransferIds }
        }
        return pendingCandidateCount
    }

    private fun queryPendingTransferIds(device: BridgeDeviceState): Set<String>? {
        val endpoint = device.endpointUrl ?: return emptySet()
        val secret = device.trustedSecret ?: return emptySet()
        return runCatching {
            val connection = (URL(pendingTransfersUrlFor(endpoint)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-MobileClaw-Trusted-Secret", secret)
            }
            val responseCode = connection.responseCode
            val responseBody = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            connection.disconnect()
            if (responseCode !in 200..299) {
                null
            } else {
                val json = JSONObject(responseBody)
                val pendingTransfers = json.optJSONArray("pending_transfers")
                if (pendingTransfers == null) {
                    emptySet<String>()
                } else {
                    buildSet {
                        for (index in 0 until pendingTransfers.length()) {
                            val item = pendingTransfers.optJSONObject(index) ?: continue
                            item.optString("transfer_id").takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
            }
        }.getOrNull()
    }

    private suspend fun requeueManifestRecoveryDraft(
        draft: PendingTransferDraft,
        now: Long,
    ): Boolean {
        val updated = withContext(Dispatchers.IO) {
            val updatedRows = databaseHelper.writableDatabase.update(
                "transfer_outbox",
                ContentValues().apply {
                    put("status", TransferDraftStatus.Queued.name)
                    put("updated_at", now)
                    put("next_attempt_at", now)
                    putNull("transport_endpoint")
                    putNull("delivery_mode")
                    putNull("receipt_json")
                    put(
                        "last_error",
                        "Recovered a manifest-only fallback after binary sources became available again.",
                    )
                },
                "id = ? AND status = ? AND delivery_mode = ?",
                arrayOf(draft.id, TransferDraftStatus.Delivered.name, "manifest_only"),
            )
            if (updatedRows == 1) {
                databaseHelper.writableDatabase.update(
                    "paired_devices",
                    ContentValues().apply {
                        put("status", "Retry scheduled")
                    },
                    "id = ?",
                    arrayOf(draft.deviceId),
                )
            }
            updatedRows == 1
        }
        if (updated) {
            updateLinkedTask(
                draft = draft,
                status = AgentTaskStatus.RetryScheduled,
                summary = "Binary sources became available again for ${draft.deviceName}; bridge delivery will retry now.",
                replyPreview = "Transfer recovery resumed for ${draft.deviceName}.",
                nextRetryAtEpochMillis = now,
                lastError = "Manifest-only fallback recovered and requeued for binary delivery.",
            )
        }
        return updated
    }

    private fun buildDrainSummary(
        processedCount: Int,
        deliveredCount: Int,
        failedCount: Int,
        retryScheduledCount: Int,
        skippedCount: Int,
        recoveredCount: Int,
        recoveredManifestCount: Int,
        followUpSnapshot: TransferFollowUpSnapshot,
    ): String {
        return buildString {
            append("Transfer drain processed ")
            append(processedCount)
            append(" draft(s): ")
            append(deliveredCount)
            append(" delivered, ")
            append(retryScheduledCount)
            append(" retry scheduled, ")
            append(failedCount)
            append(" failed")
            if (skippedCount > 0) {
                append(", ")
                append(skippedCount)
                append(" skipped claim")
            }
            if (recoveredCount > 0) {
                append(". Recovered ")
                append(recoveredCount)
                append(" stale sending draft(s) first")
            }
            if (recoveredManifestCount > 0) {
                append(". Requeued ")
                append(recoveredManifestCount)
                append(" manifest-only recovery draft(s)")
            }
            followUpSnapshot.queuedRetrySnapshot.nextAttemptAtEpochMillis?.let { nextAttemptAt ->
                append(". ")
                append(followUpSnapshot.queuedRetrySnapshot.queuedCount)
                append(" delayed queued draft(s) remain; next retry in ")
                append(((nextAttemptAt - System.currentTimeMillis()).coerceAtLeast(0L)) / 1000L)
                append("s")
            }
            if (followUpSnapshot.pendingManifestDraftCount > 0) {
                append(". ")
                append(followUpSnapshot.pendingManifestDraftCount)
                append(" manifest-only draft(s) remain pending recovery pull")
            }
            append(".")
        }
    }

    fun noteWorkerRetry(runAttemptCount: Int) {
        coordinatorScope.launch {
            auditTrailRepository.logAction(
                action = "devices.transport",
                result = "worker_retry",
                details = "Transfer worker requested retry after an unexpected drain failure on run attempt ${runAttemptCount + 1}.",
            )
        }
    }

    fun noteWorkerFailure(runAttemptCount: Int) {
        coordinatorScope.launch {
            auditTrailRepository.logAction(
                action = "devices.transport",
                result = "worker_failed",
                details = "Transfer worker failed after ${runAttemptCount + 1} attempt(s). Manual recovery or app foreground refresh may be required.",
            )
        }
    }

    private fun queryPairedDevice(deviceId: String): BridgeDeviceState? {
        databaseHelper.readableDatabase.query(
            "paired_devices",
            arrayOf("id", "capabilities_json", "transport_mode", "endpoint_url", "trusted_secret", "validation_mode"),
            "id = ?",
            arrayOf(deviceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return BridgeDeviceState(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                capabilities = jsonArrayToList(
                    cursor.getString(cursor.getColumnIndexOrThrow("capabilities_json")),
                ),
                transportMode = DeviceTransportMode.valueOf(
                    cursor.getString(cursor.getColumnIndexOrThrow("transport_mode")),
                ),
                endpointUrl = cursor.getString(cursor.getColumnIndexOrThrow("endpoint_url")),
                trustedSecret = cursor.getString(cursor.getColumnIndexOrThrow("trusted_secret")),
                validationMode = TransportValidationMode.valueOf(
                    cursor.getString(cursor.getColumnIndexOrThrow("validation_mode")),
                ),
            )
        }
    }

    private fun deliverOverHttp(
        device: BridgeDeviceState,
        draft: PendingTransferDraft,
    ): DeliveryResult {
        val endpoint = device.endpointUrl
            ?: return DeliveryResult.Failed(message = "No companion endpoint is configured.")
        val secret = device.trustedSecret
            ?: return DeliveryResult.Failed(message = "Trusted secret is missing for direct transport.")
        val payload = runCatching {
            prepareDeliveryPayload(draft)
        }.getOrElse { error ->
            return DeliveryResult.Failed(
                message = "Unable to prepare the transfer payload: ${error.message}",
            )
        }

        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                doOutput = true
                setChunkedStreamingMode(0)
                setRequestProperty("Content-Type", payload.contentType)
                setRequestProperty("X-MobileClaw-Trusted-Secret", secret)
                setRequestProperty("X-MobileClaw-Transfer-Id", draft.id)
                setRequestProperty("X-MobileClaw-Device-Name", draft.deviceName)
                setRequestProperty("X-MobileClaw-Delivery-Mode", payload.deliveryMode)
                setRequestProperty("X-MobileClaw-Response-Timeout-Ms", payload.responseTimeoutMs.toString())
                if (device.validationMode != TransportValidationMode.Normal) {
                    setRequestProperty("X-MobileClaw-Debug-Receipt-Mode", device.validationMode.wireValue)
                }
            }
            connection.readTimeout = payload.responseTimeoutMs
            try {
                payload.writeTo(connection)
            } finally {
                payload.close()
            }
            val responseCode = connection.responseCode
            val responseBody = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse {
                connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
            }
            connection.disconnect()
            if (responseCode in 200..299) {
                val normalizedReceipt = normalizeReceipt(
                    responseBody = responseBody,
                    draft = draft,
                    fallbackMode = payload.deliveryMode,
                    endpoint = endpoint,
                )
                DeliveryResult.Delivered(
                    endpoint = endpoint,
                    deliveryMode = payload.deliveryMode,
                    receiptJson = normalizedReceipt.receiptJson,
                    receiptWarning = normalizedReceipt.receiptWarning,
                )
            } else {
                DeliveryResult.Failed(
                    message = "Companion bridge returned HTTP $responseCode${responseBody.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}",
                    retryable = responseCode == 408 || responseCode == 425 || responseCode == 429 || responseCode in 500..599,
                    retryAfterMillis = retryDelayFrom(responseCode, responseBody),
                )
            }
        }.getOrElse { error ->
            DeliveryResult.Failed(
                message = "Companion bridge could not be reached: ${error.message}",
                retryable = true,
            )
        }
    }

    private fun jsonArrayToList(raw: String): List<String> {
        val jsonArray = JSONArray(raw)
        return buildList {
            for (index in 0 until jsonArray.length()) {
                add(jsonArray.getString(index))
            }
        }
    }

    private fun jsonArrayToFileReferences(raw: String?): List<TransferFileReference> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val jsonArray = JSONArray(raw)
        return buildList {
            for (index in 0 until jsonArray.length()) {
                val value = jsonArray.getJSONObject(index)
                add(
                    TransferFileReference(
                        sourceId = value.getString("source_id"),
                        name = value.getString("name"),
                        mimeType = value.optString("mime_type", "application/octet-stream"),
                    ),
                )
            }
        }
    }

    private fun prepareDeliveryPayload(draft: PendingTransferDraft): TransferPayload {
        if (draft.fileReferences.isEmpty()) {
            throw IllegalStateException("No file references were persisted for this transfer draft.")
        }

        val resolvedRefs = draft.fileReferences.map { fileReference ->
            val sourceUri = sourceUriFor(fileReference.sourceId)
            ResolvedTransferReference(
                reference = fileReference,
                sourceUri = sourceUri,
                sizeBytes = sourceUri?.let(::contentLengthFor) ?: -1L,
            )
        }

        val unresolved = resolvedRefs.any { it.sourceUri == null }
        val containsUnknownSizedSource = resolvedRefs.any { it.sizeBytes < 0L }
        val estimatedBytes = resolvedRefs
            .map { it.sizeBytes }
            .filter { it >= 0L }
            .sum()

        return when {
            unresolved -> ManifestTransferPayload(
                body = buildManifestPayload(
                    draft = draft,
                    resolvedRefs = resolvedRefs,
                    reason = "One or more file sources could not be resolved for binary upload.",
                    estimatedBytes = estimatedBytes,
                ),
            )
            containsUnknownSizedSource || estimatedBytes > maxArchiveBytes -> StreamingArchiveTransferPayload(
                archiveWriter = { zipStream ->
                    writeTransferArchive(
                        zipStream = zipStream,
                        draft = draft,
                        resolvedRefs = resolvedRefs,
                        enforceSizeLimit = false,
                    )
                },
            )
            else -> ArchiveTransferPayload(buildTransferArchive(draft, resolvedRefs))
        }
    }

    private fun buildManifestPayload(
        draft: PendingTransferDraft,
        resolvedRefs: List<ResolvedTransferReference>,
        reason: String,
        estimatedBytes: Long,
    ): String {
        val manifestFiles = JSONArray()
        resolvedRefs.forEach { ref ->
            manifestFiles.put(
                JSONObject()
                    .put("source_id", ref.reference.sourceId)
                    .put("name", ref.reference.name)
                    .put("mime_type", ref.reference.mimeType)
                    .put("estimated_bytes", ref.sizeBytes),
            )
        }
        return JSONObject()
            .put("transfer_id", draft.id)
            .put("device_id", draft.deviceId)
            .put("device_name", draft.deviceName)
            .put("capability", "files.transfer")
            .put("delivery_mode", "manifest_only")
            .put("reason", reason)
            .put("estimated_total_bytes", estimatedBytes)
            .put("file_names", JSONArray(draft.fileNames))
            .put("files", manifestFiles)
            .toString()
    }

    private fun contentLengthFor(uri: Uri): Long {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun normalizeReceipt(
        responseBody: String,
        draft: PendingTransferDraft,
        fallbackMode: String,
        endpoint: String,
    ): NormalizedReceipt {
        val issues = mutableListOf<String>()
        val json = if (responseBody.isBlank()) {
            issues += "Companion returned an empty receipt body."
            JSONObject()
        } else {
            runCatching { JSONObject(responseBody) }.getOrElse {
                issues += "Companion returned malformed JSON."
                JSONObject().put("raw_response", responseBody)
            }
        }

        val receivedTransferId = json.optString("transfer_id").takeIf { it.isNotBlank() }
        when {
            receivedTransferId == null -> {
                issues += "transfer_id missing from receipt."
                json.put("transfer_id", draft.id)
            }
            receivedTransferId != draft.id -> {
                issues += "transfer_id $receivedTransferId did not match ${draft.id}."
            }
        }

        val receivedMode = json.optString("delivery_mode").takeIf { it.isNotBlank() }
        when {
            receivedMode == null -> {
                issues += "delivery_mode missing from receipt."
                json.put("delivery_mode", fallbackMode)
            }
            receivedMode != fallbackMode -> {
                issues += "delivery_mode $receivedMode did not match $fallbackMode."
            }
        }

        val status = json.optString("status")
        if (status.isBlank()) {
            issues += "status missing from receipt."
            json.put("status", "accepted")
        } else if (status !in acceptedReceiptStatuses) {
            issues += "unexpected receipt status '$status'."
        }

        if (!json.has("receipt_version")) {
            issues += "receipt_version missing from receipt."
            json.put("receipt_version", 1)
        }
        if (!json.has("acknowledged_at")) {
            issues += "acknowledged_at missing from receipt."
            json.put("acknowledged_at", System.currentTimeMillis())
        }

        when (fallbackMode) {
            "manifest_only" -> {
                val requestedCount = json.optInt("requested_count", -1)
                when {
                    requestedCount < 0 -> issues += "requested_count missing from manifest receipt."
                    requestedCount != draft.fileNames.size -> {
                        issues += "requested_count $requestedCount did not match ${draft.fileNames.size}."
                    }
                }
            }
            else -> {
                val fileEntryCount = json.optInt("file_entry_count", -1)
                when {
                    fileEntryCount < 0 -> issues += "file_entry_count missing from archive receipt."
                    fileEntryCount != draft.fileNames.size -> {
                        issues += "file_entry_count $fileEntryCount did not match ${draft.fileNames.size}."
                    }
                }
            }
        }

        json.put("transport_endpoint", endpoint)
        json.put("receipt_valid", issues.isEmpty())
        if (issues.isNotEmpty()) {
            json.put("receipt_issue", issues.joinToString(" "))
        }
        return NormalizedReceipt(
            receiptJson = json.toString(),
            receiptWarning = issues.firstOrNull(),
        )
    }

    private fun retryDelayFrom(
        responseCode: Int,
        responseBody: String,
    ): Long? {
        if (responseCode !in setOf(408, 425, 429) && responseCode !in 500..599) {
            return null
        }
        return runCatching {
            val json = JSONObject(responseBody)
            when {
                json.has("retry_after_ms") -> json.optLong("retry_after_ms", 0L)
                json.has("retry_after_seconds") -> json.optLong("retry_after_seconds", 0L) * 1000L
                else -> 0L
            }.takeIf { it > 0L }
        }.getOrNull()
    }

    private fun pendingTransfersUrlFor(endpoint: String): String {
        val url = URL(endpoint)
        return URL(url.protocol, url.host, url.port, "/api/v1/transfers/pending").toString()
    }

    private fun computeRetryDelayMs(attemptNumber: Int): Long {
        val boundedAttempt = attemptNumber.coerceIn(1, retryDelayStepsMs.size)
        return retryDelayStepsMs[boundedAttempt - 1]
    }

    private fun syntheticReceipt(
        deliveryMode: String,
        endpoint: String,
    ): String {
        return JSONObject()
            .put("status", "accepted")
            .put("receipt_version", 1)
            .put("acknowledged_at", System.currentTimeMillis())
            .put("delivery_mode", deliveryMode)
            .put("transport_endpoint", endpoint)
            .put("receipt_valid", true)
            .put("status_detail", "Transfer completed without a remote acknowledgement payload.")
            .toString()
    }

    private fun buildTransferArchive(
        draft: PendingTransferDraft,
        resolvedRefs: List<ResolvedTransferReference>,
    ): Path {
        val archivePath = Files.createTempFile(
            context.cacheDir.toPath(),
            "mobileclaw-transfer-",
            ".zip",
        )

        return try {
            ZipOutputStream(Files.newOutputStream(archivePath)).use { zipStream ->
                writeTransferArchive(
                    zipStream = zipStream,
                    draft = draft,
                    resolvedRefs = resolvedRefs,
                    enforceSizeLimit = true,
                )
            }
            archivePath
        } catch (error: Throwable) {
            Files.deleteIfExists(archivePath)
            throw error
        }
    }

    private fun writeTransferArchive(
        zipStream: ZipOutputStream,
        draft: PendingTransferDraft,
        resolvedRefs: List<ResolvedTransferReference>,
        enforceSizeLimit: Boolean,
    ) {
        val manifestFiles = JSONArray()
        var totalBytes = 0L
        resolvedRefs.forEachIndexed { index, resolvedRef ->
            val entryName = "files/${"%02d".format(index + 1)}-${safePathSegment(resolvedRef.reference.name)}"
            val bytesCopied = writeFileEntry(
                zipStream = zipStream,
                entryName = entryName,
                sourceUri = resolvedRef.sourceUri,
            )
            totalBytes += bytesCopied
            if (enforceSizeLimit && totalBytes > maxArchiveBytes) {
                throw IllegalStateException("Transfer payload exceeds the ${maxArchiveBytes / (1024 * 1024)} MiB limit.")
            }
            manifestFiles.put(
                JSONObject()
                    .put("name", resolvedRef.reference.name)
                    .put("mime_type", resolvedRef.reference.mimeType)
                    .put("source_id", resolvedRef.reference.sourceId)
                    .put("entry_name", entryName)
                    .put("byte_count", bytesCopied),
            )
        }

        writeTextEntry(
            zipStream,
            "manifest.json",
            JSONObject()
                .put("transfer_id", draft.id)
                .put("device_id", draft.deviceId)
                .put("device_name", draft.deviceName)
                .put("capability", "files.transfer")
                .put("delivery_mode", if (enforceSizeLimit) "archive_zip" else "archive_zip_streaming")
                .put("file_count", resolvedRefs.size)
                .put("total_bytes", totalBytes)
                .put("files", manifestFiles)
                .toString(2),
        )
        writeTextEntry(
            zipStream,
            "summary.txt",
            buildSummary(
                draft = draft,
                resolvedRefs = resolvedRefs,
                totalBytes = totalBytes,
                deliveryMode = if (enforceSizeLimit) "archive_zip" else "archive_zip_streaming",
            ),
        )
    }

    private fun writeFileEntry(
        zipStream: ZipOutputStream,
        entryName: String,
        sourceUri: Uri?,
    ): Long {
        val resolvedUri = sourceUri ?: throw IllegalStateException("No content URI could be resolved for the transfer source.")
        zipStream.putNextEntry(ZipEntry(entryName))
        val bytesCopied = context.contentResolver.openInputStream(resolvedUri)?.use { input ->
            input.copyTo(zipStream)
        } ?: throw IllegalStateException("Unable to open the transfer source for reading.")
        zipStream.closeEntry()
        return bytesCopied
    }

    private fun writeTextEntry(
        zipStream: ZipOutputStream,
        entryName: String,
        body: String,
    ) {
        zipStream.putNextEntry(ZipEntry(entryName))
        zipStream.write(body.toByteArray(StandardCharsets.UTF_8))
        zipStream.closeEntry()
    }

    private fun sourceUriFor(sourceId: String): Uri? {
        return when {
            sourceId.startsWith("media-") -> {
                val mediaId = sourceId.removePrefix("media-").toLongOrNull() ?: return null
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    mediaId,
                )
            }
            sourceId.startsWith("content://") -> Uri.parse(sourceId)
            else -> null
        }
    }

    private fun safePathSegment(value: String): String {
        val sanitized = value
            .replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_')
        return if (sanitized.isBlank()) {
            "file.bin"
        } else {
            sanitized
        }
    }

    private fun buildSummary(
        draft: PendingTransferDraft,
        resolvedRefs: List<ResolvedTransferReference>,
        totalBytes: Long,
        deliveryMode: String,
    ): String {
        return buildString {
            appendLine("MobileClaw direct HTTP transfer archive")
            appendLine("Transfer: ${draft.id}")
            appendLine("Target device: ${draft.deviceName}")
            appendLine("Delivery mode: $deliveryMode")
            appendLine("Files: ${resolvedRefs.size}")
            appendLine("Total bytes: $totalBytes")
            resolvedRefs.forEach { resolvedRef ->
                appendLine("- ${resolvedRef.reference.name}")
            }
        }
    }

    companion object {
        private const val maxArchiveBytes = 16L * 1024L * 1024L
        private const val maxDeliveryAttempts = 5
        private const val staleSendingTimeoutMs = 90_000L
        private const val manifestRecoveryPollIntervalMs = 60_000L
        private const val minRetryDelayMs = 15_000L
        private const val maxReplyPreviewLength = 240
        private val acceptedReceiptStatuses = setOf("accepted", "ok", "completed")
        private val retryDelayStepsMs = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}

private data class PendingTransferDraft(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val approvalRequestId: String? = null,
    val fileNames: List<String>,
    val fileReferences: List<TransferFileReference>,
    val attemptCount: Int,
)

private data class QueuedRetrySnapshot(
    val queuedCount: Int,
    val nextAttemptAtEpochMillis: Long?,
)

private data class ManifestRecoverySnapshot(
    val requeuedCount: Int = 0,
    val pendingCandidateCount: Int = 0,
)

private data class TransferFollowUpSnapshot(
    val queuedRetrySnapshot: QueuedRetrySnapshot,
    val pendingManifestDraftCount: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
)

data class TransferRecoverySnapshot(
    val recoveredStaleDraftCount: Int = 0,
    val dueQueuedDraftCount: Int = 0,
    val delayedQueuedDraftCount: Int = 0,
    val nextAttemptAtEpochMillis: Long? = null,
    val recoveredManifestDraftCount: Int = 0,
    val pendingManifestDraftCount: Int = 0,
    val immediateDrainRequested: Boolean = false,
)

private data class BridgeDeviceState(
    val id: String,
    val capabilities: List<String>,
    val transportMode: DeviceTransportMode,
    val endpointUrl: String?,
    val trustedSecret: String?,
    val validationMode: TransportValidationMode,
)

private data class ResolvedTransferReference(
    val reference: TransferFileReference,
    val sourceUri: Uri?,
    val sizeBytes: Long,
)

private sealed interface DeliveryResult {
    data class Delivered(
        val endpoint: String,
        val deliveryMode: String,
        val receiptJson: String,
        val receiptWarning: String? = null,
    ) : DeliveryResult

    data class Failed(
        val message: String,
        val retryable: Boolean = false,
        val retryAfterMillis: Long? = null,
    ) : DeliveryResult
}

private data class NormalizedReceipt(
    val receiptJson: String,
    val receiptWarning: String?,
)

private sealed interface TransferPayload {
    val contentType: String
    val deliveryMode: String
    val responseTimeoutMs: Int

    fun writeTo(connection: HttpURLConnection)

    fun close()
}

private class ArchiveTransferPayload(
    private val archivePath: Path,
) : TransferPayload {
    override val contentType: String = "application/zip"
    override val deliveryMode: String = "archive_zip"
    override val responseTimeoutMs: Int = 30_000

    override fun writeTo(connection: HttpURLConnection) {
        Files.newInputStream(archivePath).use { archiveStream ->
            connection.outputStream.use { outputStream ->
                archiveStream.copyTo(outputStream)
            }
        }
    }

    override fun close() {
        Files.deleteIfExists(archivePath)
    }
}

private class StreamingArchiveTransferPayload(
    private val archiveWriter: (ZipOutputStream) -> Unit,
) : TransferPayload {
    override val contentType: String = "application/zip"
    override val deliveryMode: String = "archive_zip_streaming"
    override val responseTimeoutMs: Int = 180_000

    override fun writeTo(connection: HttpURLConnection) {
        connection.outputStream.use { outputStream ->
            ZipOutputStream(outputStream.buffered(), StandardCharsets.UTF_8).use { zipStream ->
                archiveWriter(zipStream)
            }
        }
    }

    override fun close() = Unit
}

private class ManifestTransferPayload(
    private val body: String,
) : TransferPayload {
    override val contentType: String = "application/json"
    override val deliveryMode: String = "manifest_only"
    override val responseTimeoutMs: Int = 4_000

    override fun writeTo(connection: HttpURLConnection) {
        connection.outputStream.bufferedWriter().use { writer ->
            writer.write(body)
        }
    }

    override fun close() = Unit
}
