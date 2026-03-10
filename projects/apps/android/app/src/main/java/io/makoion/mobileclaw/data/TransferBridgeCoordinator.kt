package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.makoion.mobileclaw.sync.TransferOutboxWorker
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferBridgeCoordinator(
    private val context: Context,
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
) : TransferOutboxScheduler {
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
        scheduleDrain()
    }

    suspend fun drainOutbox() {
        val now = System.currentTimeMillis()
        val recoveredCount = withContext(Dispatchers.IO) { recoverStaleSendingDrafts(now) }
        if (recoveredCount > 0) {
            auditTrailRepository.logAction(
                action = "files.send_to_device",
                result = "recovered",
                details = "Recovered $recoveredCount interrupted transfer drafts back into the queue.",
            )
        }
        val drafts = withContext(Dispatchers.IO) { queryQueuedDrafts(now) }
        if (drafts.isEmpty()) {
            scheduleNextQueuedDrain(now)
            return
        }
        drafts.forEach { draft ->
            if (!markSending(draft)) {
                return@forEach
            }
            when (val result = deliver(draft)) {
                is DeliveryResult.Delivered -> markDelivered(
                    draft = draft,
                    endpoint = result.endpoint,
                    deliveryMode = result.deliveryMode,
                    receiptJson = result.receiptJson,
                    receiptWarning = result.receiptWarning,
                )
                is DeliveryResult.Failed -> {
                    val attemptNumber = draft.attemptCount + 1
                    if (result.retryable && attemptNumber < maxDeliveryAttempts) {
                        markRetryScheduled(
                            draft = draft,
                            message = result.message,
                            retryDelayMs = result.retryAfterMillis ?: computeRetryDelayMs(attemptNumber),
                        )
                    } else {
                        markFailed(draft, result.message)
                    }
                }
            }
        }
        scheduleNextQueuedDrain(System.currentTimeMillis())
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
            val fileRefsIndex = cursor.getColumnIndexOrThrow("file_refs_json")
            val attemptCountIndex = cursor.getColumnIndexOrThrow("attempt_count")

            while (cursor.moveToNext()) {
                val fileRefsRaw = cursor.getString(fileRefsIndex)
                drafts += PendingTransferDraft(
                    id = cursor.getString(idIndex),
                    deviceId = cursor.getString(deviceIdIndex),
                    deviceName = cursor.getString(deviceNameIndex),
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

    private fun scheduleNextQueuedDrain(now: Long) {
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
        if (nextAttemptAt > now) {
            scheduleDrain(nextAttemptAt - now)
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
        private const val minRetryDelayMs = 15_000L
        private val acceptedReceiptStatuses = setOf("accepted", "ok", "completed")
        private val retryDelayStepsMs = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    }
}

private data class PendingTransferDraft(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val fileNames: List<String>,
    val fileReferences: List<TransferFileReference>,
    val attemptCount: Int,
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
