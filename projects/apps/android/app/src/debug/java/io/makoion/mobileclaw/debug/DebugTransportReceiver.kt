package io.makoion.mobileclaw.debug

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.makoion.mobileclaw.BuildConfig
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.IndexedFileItem
import io.makoion.mobileclaw.data.PairingSessionStatus
import io.makoion.mobileclaw.data.ShellDatabaseHelper
import io.makoion.mobileclaw.data.TransferDraftStatus
import io.makoion.mobileclaw.data.TransportValidationMode
import io.makoion.mobileclaw.data.companionAppOpenTargetActionsFolder
import io.makoion.mobileclaw.data.companionAppOpenTargetInbox
import io.makoion.mobileclaw.data.companionAppOpenTargetLatestAction
import io.makoion.mobileclaw.data.companionAppOpenTargetLatestTransfer
import io.makoion.mobileclaw.notifications.ShellNotificationCenter
import io.makoion.mobileclaw.ui.ShellSection
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DebugTransportReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val application = context.applicationContext as MobileClawApplication
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleCommand(
                    context = context,
                    application = application,
                    intent = intent,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleCommand(
        context: Context,
        application: MobileClawApplication,
        intent: Intent,
    ) {
        val command = intent.getStringExtra(extraCommand)?.trim()?.lowercase() ?: return
        val appContainer = application.appContainer
        val deviceRepository = appContainer.devicePairingRepository
        val auditRepository = appContainer.auditTrailRepository
        when (command) {
            commandBootstrapDirectHttpDevice,
            commandBootstrapAdbReverseDevice,
            -> {
                deviceRepository.startPairing()
                val sessionId = deviceRepository.pairingSessions.value
                    .firstOrNull { it.status == PairingSessionStatus.Pending }
                    ?.id
                if (sessionId == null) {
                    return
                }
                deviceRepository.approvePairing(sessionId)
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                )
                if (deviceId == null) {
                    return
                }
                deviceRepository.armDirectHttpBridge(deviceId)
                val endpointPreset = intent
                    .getStringExtra(extraEndpointPreset)
                    ?.trim()
                    ?.lowercase()
                    ?: endpointPresetAdbReverse
                val endpoint = resolveEndpointUrl(
                    intent = intent,
                    fallbackPreset = endpointPreset,
                )
                deviceRepository.setDirectHttpEndpoint(deviceId, endpoint)
                parseValidationMode(intent.getStringExtra(extraValidationMode))?.let { mode ->
                    deviceRepository.setTransportValidationMode(deviceId, mode)
                }
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "bootstrapped",
                    details = "Bootstrapped a Direct HTTP test device with $endpointPreset endpoint preset.",
                )
                openDevices(context)
            }
            commandUseAdbReverseEndpoint -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                deviceRepository.setDirectHttpEndpoint(
                    deviceId,
                    resolveEndpointUrl(intent, endpointPresetAdbReverse),
                )
            }
            commandUseEmulatorEndpoint -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                deviceRepository.setDirectHttpEndpoint(
                    deviceId,
                    resolveEndpointUrl(intent, endpointPresetEmulatorHost),
                )
            }
            commandSetValidationMode -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                parseValidationMode(intent.getStringExtra(extraValidationMode))?.let { mode ->
                    deviceRepository.setTransportValidationMode(deviceId, mode)
                }
            }
            commandProbeHealth -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                deviceRepository.probeCompanion(deviceId)
                openDevices(context)
            }
            commandRequestShellRecovery -> {
                appContainer.shellRecoveryCoordinator.requestManualRecovery()
                if (shouldOpenDevices(intent)) {
                    openDevices(context)
                }
            }
            commandSendSessionNotification -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val title = intent.getStringExtra(extraTitle)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultSessionNotificationTitle
                val body = intent.getStringExtra(extraBody)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultSessionNotificationBody
                deviceRepository.sendSessionNotification(
                    deviceId = deviceId,
                    title = title,
                    body = body,
                )
                openDevices(context)
            }
            commandSendAppOpen -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val targetKind = intent.getStringExtra(extraTargetKind)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultAppOpenTargetKind
                val targetLabel = intent.getStringExtra(extraTargetLabel)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultAppOpenLabelFor(targetKind)
                val openMode = intent.getStringExtra(extraOpenMode)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultRemoteActionMode
                deviceRepository.sendAppOpen(
                    deviceId = deviceId,
                    targetKind = targetKind,
                    targetLabel = targetLabel,
                    openMode = openMode,
                )
                openDevices(context)
            }
            commandRunWorkflow -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val workflowId = intent.getStringExtra(extraWorkflowId)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultWorkflowId
                val workflowLabel = intent.getStringExtra(extraWorkflowLabel)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultWorkflowLabelFor(workflowId)
                val runMode = intent.getStringExtra(extraRunMode)?.trim()?.takeIf { it.isNotBlank() }
                    ?: defaultRemoteActionMode
                deviceRepository.sendWorkflowRun(
                    deviceId = deviceId,
                    workflowId = workflowId,
                    workflowLabel = workflowLabel,
                    runMode = runMode,
                )
                openDevices(context)
            }
            commandQueueStaleSendingDraft -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val fileCount = intent
                    .getIntExtra(extraFileCount, defaultDebugFileCount)
                    .coerceIn(1, maxDebugFileCount)
                val filePrefix = intent
                    .getStringExtra(extraFilePrefix)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultRecoveryFilePrefix
                insertDebugRecoveryDraft(
                    context = context,
                    application = application,
                    deviceId = deviceId,
                    files = buildDebugTransferFiles(
                        prefix = filePrefix,
                        fileCount = fileCount,
                    ),
                    status = TransferDraftStatus.Sending,
                    attemptCount = 1,
                    nextAttemptAt = 0L,
                    updatedAt = System.currentTimeMillis() - staleRecoveryDraftAgeMs,
                    lastError = "Forced stale sending draft for shell recovery validation.",
                )
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "stale_sending_queued",
                    details = "Queued a stale sending draft for shell recovery validation using prefix $filePrefix.",
                )
                if (shouldOpenDevices(intent)) {
                    openDevices(context)
                }
            }
            commandQueueDueRetryDraft -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val fileCount = intent
                    .getIntExtra(extraFileCount, defaultDebugFileCount)
                    .coerceIn(1, maxDebugFileCount)
                val filePrefix = intent
                    .getStringExtra(extraFilePrefix)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultRecoveryFilePrefix
                insertDebugRecoveryDraft(
                    context = context,
                    application = application,
                    deviceId = deviceId,
                    files = buildDebugTransferFiles(
                        prefix = filePrefix,
                        fileCount = fileCount,
                    ),
                    status = TransferDraftStatus.Queued,
                    attemptCount = 1,
                    nextAttemptAt = System.currentTimeMillis() - 1_000L,
                    updatedAt = System.currentTimeMillis() - 60_000L,
                    lastError = "Forced due retry draft for shell recovery validation.",
                )
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "due_retry_queued",
                    details = "Queued a due retry draft for shell recovery validation using prefix $filePrefix.",
                )
                if (shouldOpenDevices(intent)) {
                    openDevices(context)
                }
            }
            commandQueueDelayedRetryDraft -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val fileCount = intent
                    .getIntExtra(extraFileCount, defaultDebugFileCount)
                    .coerceIn(1, maxDebugFileCount)
                val filePrefix = intent
                    .getStringExtra(extraFilePrefix)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultRecoveryFilePrefix
                val delaySeconds = intent
                    .getIntExtra(extraRetryDelaySeconds, defaultRecoveryDelaySeconds)
                    .coerceIn(minRecoveryDelaySeconds, maxRecoveryDelaySeconds)
                insertDebugRecoveryDraft(
                    context = context,
                    application = application,
                    deviceId = deviceId,
                    files = buildDebugTransferFiles(
                        prefix = filePrefix,
                        fileCount = fileCount,
                    ),
                    status = TransferDraftStatus.Queued,
                    attemptCount = 1,
                    nextAttemptAt = System.currentTimeMillis() + delaySeconds * 1_000L,
                    updatedAt = System.currentTimeMillis(),
                    lastError = "Forced delayed retry draft for shell recovery validation.",
                )
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "delayed_retry_queued",
                    details = "Queued a delayed retry draft for shell recovery validation using prefix $filePrefix with ${delaySeconds}s delay.",
                )
                if (shouldOpenDevices(intent)) {
                    openDevices(context)
                }
            }
            commandDrainOutbox -> {
                appContainer.transferBridgeCoordinator.scheduleDrain()
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "drain_requested",
                    details = "Requested an immediate transport drain from adb debug automation.",
                )
            }
            commandCleanupValidationDevice -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                cleanupValidationDevice(
                    context = context,
                    application = application,
                    deviceId = deviceId,
                )
            }
            commandRequeueFailed -> {
                deviceRepository.retryFailedTransfers(
                    intent.getStringExtra(extraDeviceId),
                )
            }
            commandQueueDebugTransfer -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val fileCount = intent
                    .getIntExtra(extraFileCount, defaultDebugFileCount)
                    .coerceIn(1, maxDebugFileCount)
                val filePrefix = intent
                    .getStringExtra(extraFilePrefix)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultDebugFilePrefix
                deviceRepository.queueTransfer(
                    deviceId = deviceId,
                    files = buildDebugTransferFiles(
                        prefix = filePrefix,
                        fileCount = fileCount,
                    ),
                )
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "draft_queued",
                    details = "Queued $fileCount synthetic debug transfer files using prefix $filePrefix.",
                )
                openDevices(context)
            }
            commandQueueDebugArchiveTransfer -> {
                val deviceId = resolveTargetDeviceId(
                    requestedDeviceId = intent.getStringExtra(extraDeviceId),
                    application = application,
                ) ?: return
                val fileCount = intent
                    .getIntExtra(extraFileCount, defaultDebugFileCount)
                    .coerceIn(1, maxDebugFileCount)
                val filePrefix = intent
                    .getStringExtra(extraFilePrefix)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultDebugArchivePrefix
                val fileSizeKiB = intent
                    .getIntExtra(extraFileSizeKiB, defaultDebugArchiveFileSizeKiB)
                    .coerceIn(minDebugArchiveFileSizeKiB, maxDebugArchiveFileSizeKiB)
                deviceRepository.queueTransfer(
                    deviceId = deviceId,
                    files = buildDebugArchiveTransferFiles(
                        context = context,
                        prefix = filePrefix,
                        fileCount = fileCount,
                        fileSizeKiB = fileSizeKiB,
                    ),
                )
                auditRepository.logAction(
                    action = "devices.debug_transport",
                    result = "archive_draft_queued",
                    details = "Queued $fileCount synthetic archive payload files using prefix $filePrefix at ${fileSizeKiB}KiB each.",
                )
                openDevices(context)
            }
            commandOpenDevices -> {
                openDevices(context)
            }
        }
        deviceRepository.refresh()
        auditRepository.refresh()
    }

    private fun resolveTargetDeviceId(
        requestedDeviceId: String?,
        application: MobileClawApplication,
    ): String? {
        val devices = application.appContainer.devicePairingRepository.pairedDevices.value
        return requestedDeviceId ?: devices.firstOrNull()?.id
    }

    private fun parseValidationMode(raw: String?): TransportValidationMode? {
        val normalized = raw
            ?.trim()
            ?.replace('-', '_')
            ?.replace(' ', '_')
            ?.uppercase()
            ?: return null
        return TransportValidationMode.entries.firstOrNull { mode ->
            mode.name == normalized || mode.wireValue.uppercase() == normalized
        }
    }

    private fun openDevices(context: Context) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ShellNotificationCenter.extraOpenSection, ShellSection.Settings.routeKey)
        }
        context.startActivity(openIntent)
    }

    private fun shouldOpenDevices(intent: Intent): Boolean {
        return intent.getBooleanExtra(extraOpenDevicesAfterCommand, true)
    }

    private fun resolveEndpointUrl(
        intent: Intent,
        fallbackPreset: String,
    ): String {
        return intent.getStringExtra(extraEndpointUrl)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: when (fallbackPreset) {
                endpointPresetEmulatorHost -> emulatorHostEndpoint
                else -> adbReverseEndpoint
            }
    }

    private fun defaultWorkflowLabelFor(workflowId: String): String {
        return when (workflowId) {
            workflowIdOpenLatestAction -> "Open latest action"
            workflowIdOpenActionsFolder -> "Open actions folder"
            else -> "Open latest transfer"
        }
    }

    private fun defaultAppOpenLabelFor(targetKind: String): String {
        return when (targetKind) {
            companionAppOpenTargetLatestAction -> "Latest action folder"
            companionAppOpenTargetLatestTransfer -> "Latest transfer folder"
            companionAppOpenTargetActionsFolder -> "Actions folder"
            else -> "Desktop companion inbox"
        }
    }

    private fun insertDebugRecoveryDraft(
        context: Context,
        application: MobileClawApplication,
        deviceId: String,
        files: List<IndexedFileItem>,
        status: TransferDraftStatus,
        attemptCount: Int,
        nextAttemptAt: Long,
        updatedAt: Long,
        lastError: String,
    ) {
        val device = application.appContainer.devicePairingRepository.pairedDevices.value
            .firstOrNull { it.id == deviceId } ?: return
        val databaseHelper = ShellDatabaseHelper(context)
        val fileNames = files.map { it.name }
        val fileReferencesJson = JSONArray(
            files.map { file ->
                JSONObject()
                    .put("source_id", file.id)
                    .put("name", file.name)
                    .put("mime_type", file.mimeType)
            },
        ).toString()
        val createdAt = updatedAt
        databaseHelper.writableDatabase.insert(
            "transfer_outbox",
            null,
            ContentValues().apply {
                put("id", "transfer-${UUID.randomUUID()}")
                put("device_id", deviceId)
                put("device_name", device.name)
                put("file_names_json", JSONArray(fileNames).toString())
                put("file_refs_json", fileReferencesJson)
                put("status", status.name)
                put("transport_endpoint", "")
                put("attempt_count", attemptCount)
                put("next_attempt_at", nextAttemptAt)
                put("created_at", createdAt)
                put("updated_at", updatedAt)
                put("last_error", lastError)
            },
        )
        databaseHelper.writableDatabase.update(
            "paired_devices",
            ContentValues().apply {
                put(
                    "status",
                    when (status) {
                        TransferDraftStatus.Sending -> "Sending"
                        TransferDraftStatus.Queued -> "Retry scheduled"
                        TransferDraftStatus.Delivered -> "Bridge active"
                        TransferDraftStatus.Failed -> "Attention needed"
                    },
                )
            },
            "id = ?",
            arrayOf(deviceId),
        )
    }

    private suspend fun cleanupValidationDevice(
        context: Context,
        application: MobileClawApplication,
        deviceId: String,
    ) {
        val databaseHelper = ShellDatabaseHelper(context)
        val deviceName = application.appContainer.devicePairingRepository.pairedDevices.value
            .firstOrNull { it.id == deviceId }
            ?.name
            ?: deviceId
        val trustedSecret = databaseHelper.readableDatabase.query(
            "paired_devices",
            arrayOf("trusted_secret"),
            "id = ?",
            arrayOf(deviceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(cursor.getColumnIndexOrThrow("trusted_secret"))
            }
        }
        val removedDraftCount = databaseHelper.writableDatabase.delete(
            "transfer_outbox",
            "device_id = ?",
            arrayOf(deviceId),
        )
        val removedDeviceCount = databaseHelper.writableDatabase.delete(
            "paired_devices",
            "id = ?",
            arrayOf(deviceId),
        )
        val removedSessionCount = trustedSecret?.let { secret ->
            databaseHelper.writableDatabase.delete(
                "pairing_sessions",
                "LOWER(qr_secret) = ?",
                arrayOf(secret.lowercase()),
            )
        } ?: 0
        application.appContainer.devicePairingRepository.refresh()
        application.appContainer.auditTrailRepository.logAction(
            action = "devices.debug_transport",
            result = if (removedDeviceCount > 0) "validation_device_cleaned" else "validation_device_cleanup_skipped",
            details = if (removedDeviceCount > 0) {
                "Cleaned validation device $deviceName and removed $removedDraftCount draft(s), $removedSessionCount pairing session(s)."
            } else {
                "No validation device matched $deviceId for cleanup."
            },
        )
    }

    private fun buildDebugTransferFiles(
        prefix: String,
        fileCount: Int,
    ): List<IndexedFileItem> {
        val seed = System.currentTimeMillis()
        return buildList {
            repeat(fileCount) { index ->
                val suffix = index + 1
                add(
                    IndexedFileItem(
                        id = "debug-source-$seed-$suffix",
                        name = "$prefix-$suffix.txt",
                        mimeType = "text/plain",
                        sizeLabel = "debug",
                        modifiedLabel = "now",
                        sourceLabel = "Debug automation",
                    ),
                )
            }
        }
    }

    private fun buildDebugArchiveTransferFiles(
        context: Context,
        prefix: String,
        fileCount: Int,
        fileSizeKiB: Int,
    ): List<IndexedFileItem> {
        val seed = System.currentTimeMillis()
        val payloadDir = File(context.cacheDir, debugArchiveDirName).apply {
            mkdirs()
        }
        return buildList {
            repeat(fileCount) { index ->
                val suffix = index + 1
                val file = File(payloadDir, "$prefix-$seed-$suffix.bin")
                writeDebugPayloadFile(
                    file = file,
                    prefix = prefix,
                    fileIndex = suffix,
                    fileSizeBytes = fileSizeKiB * 1024L,
                )
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.debug.fileprovider",
                    file,
                )
                add(
                    IndexedFileItem(
                        id = contentUri.toString(),
                        name = "$prefix-$suffix.bin",
                        mimeType = "application/octet-stream",
                        sizeLabel = "${fileSizeKiB} KiB",
                        modifiedLabel = "now",
                        sourceLabel = "Debug archive payload",
                    ),
                )
            }
        }
    }

    private fun writeDebugPayloadFile(
        file: File,
        prefix: String,
        fileIndex: Int,
        fileSizeBytes: Long,
    ) {
        val pattern = buildString {
            append("Makoion archive validation payload\n")
            append("prefix=")
            append(prefix)
            append('\n')
            append("file_index=")
            append(fileIndex)
            append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
        FileOutputStream(file, false).use { outputStream ->
            var remaining = fileSizeBytes
            while (remaining > 0L) {
                val bytesToWrite = minOf(pattern.size.toLong(), remaining).toInt()
                outputStream.write(pattern, 0, bytesToWrite)
                remaining -= bytesToWrite
            }
            outputStream.fd.sync()
        }
    }

    companion object {
        val actionDebugTransport: String = "${BuildConfig.APPLICATION_ID}.DEBUG_TRANSPORT"

        const val extraCommand = "command"
        const val extraDeviceId = "device_id"
        const val extraValidationMode = "validation_mode"
        const val extraEndpointPreset = "endpoint_preset"
        const val extraEndpointUrl = "endpoint_url"
        const val extraFileCount = "file_count"
        const val extraFilePrefix = "file_prefix"
        const val extraFileSizeKiB = "file_size_kib"
        const val extraTitle = "title"
        const val extraBody = "body"
        const val extraTargetKind = "target_kind"
        const val extraTargetLabel = "target_label"
        const val extraOpenMode = "open_mode"
        const val extraRunMode = "run_mode"
        const val extraWorkflowId = "workflow_id"
        const val extraWorkflowLabel = "workflow_label"
        const val extraRetryDelaySeconds = "retry_delay_seconds"
        const val extraOpenDevicesAfterCommand = "open_devices_after_command"

        const val commandBootstrapDirectHttpDevice = "bootstrap_direct_http_device"
        const val commandBootstrapAdbReverseDevice = "bootstrap_adb_reverse_device"
        const val commandUseAdbReverseEndpoint = "use_adb_reverse_endpoint"
        const val commandUseEmulatorEndpoint = "use_emulator_endpoint"
        const val commandSetValidationMode = "set_validation_mode"
        const val commandProbeHealth = "probe_health"
        const val commandRequestShellRecovery = "request_shell_recovery"
        const val commandSendSessionNotification = "send_session_notification"
        const val commandSendAppOpen = "send_app_open"
        const val commandRunWorkflow = "run_workflow"
        const val commandQueueStaleSendingDraft = "queue_stale_sending_draft"
        const val commandQueueDueRetryDraft = "queue_due_retry_draft"
        const val commandQueueDelayedRetryDraft = "queue_delayed_retry_draft"
        const val commandDrainOutbox = "drain_outbox"
        const val commandCleanupValidationDevice = "cleanup_validation_device"
        const val commandRequeueFailed = "requeue_failed"
        const val commandQueueDebugTransfer = "queue_debug_transfer"
        const val commandQueueDebugArchiveTransfer = "queue_debug_archive_transfer"
        const val commandOpenDevices = "open_devices"

        private const val endpointPresetAdbReverse = "adb_reverse"
        private const val endpointPresetEmulatorHost = "emulator_host"
        private const val adbReverseEndpoint = "http://127.0.0.1:8787/api/v1/transfers"
        private const val emulatorHostEndpoint = "http://10.0.2.2:8787/api/v1/transfers"
        private const val defaultRemoteActionMode = "best_effort"
        private const val defaultAppOpenTargetKind = companionAppOpenTargetInbox
        private const val defaultSessionNotificationTitle = "Makoion session ping"
        private const val defaultSessionNotificationBody = "Android debug automation delivered a session.notify probe."
        private const val defaultWorkflowId = "open_latest_transfer"
        private const val workflowIdOpenLatestAction = "open_latest_action"
        private const val workflowIdOpenLatestTransfer = "open_latest_transfer"
        private const val workflowIdOpenActionsFolder = "open_actions_folder"
        private const val staleRecoveryDraftAgeMs = 2 * 60 * 1000L
        private const val defaultRecoveryFilePrefix = "debug-recovery"
        private const val defaultRecoveryDelaySeconds = 15
        private const val minRecoveryDelaySeconds = 5
        private const val maxRecoveryDelaySeconds = 180
        private const val defaultDebugFileCount = 1
        private const val maxDebugFileCount = 4
        private const val defaultDebugFilePrefix = "debug-transfer"
        private const val defaultDebugArchivePrefix = "debug-archive"
        private const val defaultDebugArchiveFileSizeKiB = 64
        private const val minDebugArchiveFileSizeKiB = 4
        private const val maxDebugArchiveFileSizeKiB = 20 * 1024
        private const val debugArchiveDirName = "debug-transfer-payloads"
    }
}
