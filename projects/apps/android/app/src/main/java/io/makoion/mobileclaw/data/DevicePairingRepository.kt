package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.text.format.DateUtils
import io.makoion.mobileclaw.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface TransferOutboxScheduler {
    fun scheduleDrain(delayMs: Long = 0L)
}

enum class PairingSessionStatus {
    Pending,
    Approved,
    Denied,
}

enum class DeviceTransportMode {
    Loopback,
    DirectHttp,
}

enum class TransportValidationMode {
    Normal,
    PartialReceipt,
    MalformedReceipt,
    RetryOnce,
    DelayedAck,
    TimeoutOnce,
    DisconnectOnce,
    ;

    val wireValue: String
        get() = when (this) {
            Normal -> "normal"
            PartialReceipt -> "partial_receipt"
            MalformedReceipt -> "malformed_receipt"
            RetryOnce -> "retry_once"
            DelayedAck -> "delayed_ack"
            TimeoutOnce -> "timeout_once"
            DisconnectOnce -> "disconnect_once"
        }
}

enum class TransferDraftStatus {
    Queued,
    Sending,
    Delivered,
    Failed,
}

data class PairedDeviceState(
    val id: String,
    val name: String,
    val role: String,
    val health: String,
    val capabilities: List<String>,
    val transportMode: DeviceTransportMode,
    val endpointLabel: String?,
    val validationMode: TransportValidationMode,
)

data class PairingSessionState(
    val id: String,
    val requestedRole: String,
    val qrSecret: String,
    val requestedCapabilities: List<String>,
    val status: PairingSessionStatus,
    val createdAtLabel: String,
    val expiresAtLabel: String,
)

data class TransferDraftState(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val status: TransferDraftStatus,
    val createdAtLabel: String,
    val updatedAtLabel: String,
    val fileNames: List<String>,
    val detail: String,
    val attemptCount: Int,
    val deliveryModeLabel: String?,
    val nextAttemptAtEpochMillis: Long? = null,
    val nextAttemptAtLabel: String? = null,
    val receiptReviewRequired: Boolean = false,
    val receiptIssue: String? = null,
)

data class TransferFileReference(
    val sourceId: String,
    val name: String,
    val mimeType: String,
)

enum class CompanionHealthStatus {
    Healthy,
    Unreachable,
    Misconfigured,
    Skipped,
}

data class CompanionHealthCheckResult(
    val deviceId: String,
    val status: CompanionHealthStatus,
    val summary: String,
    val detail: String,
    val checkedAtLabel: String,
)

interface DevicePairingRepository {
    val pairedDevices: StateFlow<List<PairedDeviceState>>
    val pairingSessions: StateFlow<List<PairingSessionState>>
    val transferDrafts: StateFlow<List<TransferDraftState>>

    suspend fun startPairing()

    suspend fun approvePairing(sessionId: String)

    suspend fun denyPairing(sessionId: String)

    suspend fun queueTransfer(
        deviceId: String,
        files: List<IndexedFileItem>,
    )

    suspend fun armDirectHttpBridge(deviceId: String)

    suspend fun useLoopbackBridge(deviceId: String)

    suspend fun setTransportValidationMode(
        deviceId: String,
        mode: TransportValidationMode,
    )

    suspend fun setDirectHttpEndpoint(
        deviceId: String,
        endpointUrl: String,
    )

    suspend fun probeCompanion(deviceId: String): CompanionHealthCheckResult

    suspend fun retryFailedTransfers(deviceId: String? = null)

    suspend fun refresh()
}

class PersistentDevicePairingRepository(
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
    private val transferOutboxScheduler: TransferOutboxScheduler? = null,
) : DevicePairingRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pairedDevices = MutableStateFlow<List<PairedDeviceState>>(emptyList())
    private val _pairingSessions = MutableStateFlow<List<PairingSessionState>>(emptyList())
    private val _transferDrafts = MutableStateFlow<List<TransferDraftState>>(emptyList())

    override val pairedDevices: StateFlow<List<PairedDeviceState>> = _pairedDevices.asStateFlow()
    override val pairingSessions: StateFlow<List<PairingSessionState>> = _pairingSessions.asStateFlow()
    override val transferDrafts: StateFlow<List<TransferDraftState>> = _transferDrafts.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun startPairing() {
        val now = System.currentTimeMillis()
        val qrSecret = UUID.randomUUID().toString().take(8).uppercase()
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.insert(
                "pairing_sessions",
                null,
                ContentValues().apply {
                    put("id", "pair-${UUID.randomUUID()}")
                    put("requested_role", "Desktop companion")
                    put("qr_secret", qrSecret)
                    put(
                        "requested_capabilities",
                        JSONArray(
                            listOf(
                                "files.transfer",
                                "app.open",
                                "session.notify",
                            ),
                        ).toString(),
                    )
                    put("status", PairingSessionStatus.Pending.name)
                    put("created_at", now)
                    put("expires_at", now + 10 * DateUtils.MINUTE_IN_MILLIS)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "devices.pair",
            result = "started",
            details = "Started a new pairing session with QR secret $qrSecret.",
        )
        refresh()
    }

    override suspend fun approvePairing(sessionId: String) {
        val session = _pairingSessions.value.firstOrNull { it.id == sessionId } ?: return
        val deviceName = "Desktop companion ${session.qrSecret.takeLast(4)}"
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.update(
                "pairing_sessions",
                ContentValues().apply {
                    put("status", PairingSessionStatus.Approved.name)
                    put("decided_at", now)
                },
                "id = ?",
                arrayOf(sessionId),
            )
            databaseHelper.writableDatabase.insert(
                "paired_devices",
                null,
                ContentValues().apply {
                    put("id", "device-${UUID.randomUUID()}")
                    put("name", deviceName)
                    put("role", session.requestedRole)
                    put("status", "Bridge ready")
                    put(
                        "capabilities_json",
                        JSONArray(session.requestedCapabilities).toString(),
                    )
                    put("transport_mode", DeviceTransportMode.Loopback.name)
                    put("endpoint_url", defaultEndpointFor(session.qrSecret))
                    put("trusted_secret", session.qrSecret.lowercase())
                    put("validation_mode", TransportValidationMode.Normal.name)
                    put("paired_at", now)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "devices.pair",
            result = "approved",
            details = "Approved pairing for $deviceName.",
        )
        refresh()
    }

    override suspend fun denyPairing(sessionId: String) {
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                "pairing_sessions",
                ContentValues().apply {
                    put("status", PairingSessionStatus.Denied.name)
                    put("decided_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(sessionId),
            )
        }
        auditTrailRepository.logAction(
            action = "devices.pair",
            result = "denied",
            details = "Denied pairing request $sessionId.",
        )
        refresh()
    }

    override suspend fun queueTransfer(
        deviceId: String,
        files: List<IndexedFileItem>,
    ) {
        if (files.isEmpty()) {
            return
        }
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId } ?: return
        val fileNames = files.map { it.name }
        val fileReferencesJson = JSONArray(
            files.map { file ->
                JSONObject()
                    .put("source_id", file.id)
                    .put("name", file.name)
                    .put("mime_type", file.mimeType)
            },
        ).toString()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.insert(
                "transfer_outbox",
                null,
                ContentValues().apply {
                    put("id", "transfer-${UUID.randomUUID()}")
                    put("device_id", deviceId)
                    put("device_name", device.name)
                    put("file_names_json", JSONArray(fileNames).toString())
                    put("file_refs_json", fileReferencesJson)
                    put("status", TransferDraftStatus.Queued.name)
                    put("created_at", now)
                    put("updated_at", now)
                    put("next_attempt_at", now)
                    put("attempt_count", 0)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "files.send_to_device",
            result = "queued",
            details = "Queued ${fileNames.size} files for ${device.name}.",
        )
        refresh()
        transferOutboxScheduler?.scheduleDrain()
    }

    override suspend fun armDirectHttpBridge(deviceId: String) {
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId } ?: return
        val trustedSecret = withContext(Dispatchers.IO) {
            databaseHelper.readableDatabase.query(
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
        } ?: device.id.takeLast(8).lowercase()
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("transport_mode", DeviceTransportMode.DirectHttp.name)
                    put("endpoint_url", device.endpointLabel ?: defaultEndpointFor(trustedSecret))
                    put("trusted_secret", trustedSecret)
                    put("status", "LAN bridge armed")
                },
                "id = ?",
                arrayOf(deviceId),
            )
        }
        retryFailedTransfers(deviceId)
        auditTrailRepository.logAction(
            action = "devices.transport",
            result = "direct_http",
            details = "Armed LAN bridge transport for ${device.name}.",
        )
        refresh()
        transferOutboxScheduler?.scheduleDrain()
    }

    override suspend fun useLoopbackBridge(deviceId: String) {
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId } ?: return
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("transport_mode", DeviceTransportMode.Loopback.name)
                    put("status", "Bridge ready")
                },
                "id = ?",
                arrayOf(deviceId),
            )
        }
        retryFailedTransfers(deviceId)
        auditTrailRepository.logAction(
            action = "devices.transport",
            result = "loopback",
            details = "Switched ${device.name} back to loopback bridge mode.",
        )
        refresh()
        transferOutboxScheduler?.scheduleDrain()
    }

    override suspend fun setTransportValidationMode(
        deviceId: String,
        mode: TransportValidationMode,
    ) {
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId } ?: return
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("validation_mode", mode.name)
                },
                "id = ?",
                arrayOf(deviceId),
            )
        }
        auditTrailRepository.logAction(
            action = "devices.transport_validation",
            result = mode.wireValue,
            details = "Set ${device.name} validation mode to ${mode.wireValue}.",
        )
        refresh()
    }

    override suspend fun setDirectHttpEndpoint(
        deviceId: String,
        endpointUrl: String,
    ) {
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId } ?: return
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                "paired_devices",
                ContentValues().apply {
                    put("endpoint_url", endpointUrl)
                    put("status", "LAN bridge endpoint updated")
                },
                "id = ?",
                arrayOf(deviceId),
            )
        }
        auditTrailRepository.logAction(
            action = "devices.transport_endpoint",
            result = "updated",
            details = "Updated ${device.name} direct HTTP endpoint to $endpointUrl.",
        )
        refresh()
    }

    override suspend fun probeCompanion(deviceId: String): CompanionHealthCheckResult {
        val checkedAt = System.currentTimeMillis()
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId }
        if (device == null) {
            return CompanionHealthCheckResult(
                deviceId = deviceId,
                status = CompanionHealthStatus.Misconfigured,
                summary = "Selected device is no longer available.",
                detail = "Refresh paired devices before probing the companion again.",
                checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                    checkedAt,
                    checkedAt,
                    DateUtils.SECOND_IN_MILLIS,
                ).toString(),
            )
        }
        if (device.transportMode != DeviceTransportMode.DirectHttp) {
            val result = CompanionHealthCheckResult(
                deviceId = device.id,
                status = CompanionHealthStatus.Skipped,
                summary = "Loopback mode does not expose a remote health endpoint.",
                detail = "Arm LAN bridge first if you want to probe the desktop companion over HTTP.",
                checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                    checkedAt,
                    checkedAt,
                    DateUtils.SECOND_IN_MILLIS,
                ).toString(),
            )
            auditTrailRepository.logAction(
                action = "devices.health_probe",
                result = "skipped",
                details = "Skipped companion probe for ${device.name} because loopback transport is active.",
            )
            return result
        }
        val endpoint = device.endpointLabel
        if (endpoint.isNullOrBlank()) {
            val result = CompanionHealthCheckResult(
                deviceId = device.id,
                status = CompanionHealthStatus.Misconfigured,
                summary = "Companion endpoint is missing.",
                detail = "Direct HTTP mode needs an endpoint URL before the shell can probe companion health.",
                checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                    checkedAt,
                    checkedAt,
                    DateUtils.SECOND_IN_MILLIS,
                ).toString(),
            )
            auditTrailRepository.logAction(
                action = "devices.health_probe",
                result = "misconfigured",
                details = "Companion probe could not run for ${device.name} because endpoint_url was missing.",
            )
            return result
        }

        val result = withContext(Dispatchers.IO) {
            val healthUrl = runCatching { healthUrlFor(endpoint) }.getOrNull()
            if (healthUrl == null) {
                return@withContext CompanionHealthCheckResult(
                    deviceId = device.id,
                    status = CompanionHealthStatus.Misconfigured,
                    summary = "Endpoint URL could not be parsed.",
                    detail = endpoint,
                    checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                        checkedAt,
                        checkedAt,
                        DateUtils.SECOND_IN_MILLIS,
                    ).toString(),
                )
            }

            runCatching {
                val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("Accept", "application/json")
                }
                val responseCode = connection.responseCode
                val responseBody = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                }
                connection.disconnect()

                if (responseCode in 200..299) {
                    val healthJson = runCatching { JSONObject(responseBody) }.getOrNull()
                    val transferCount = healthJson?.optLong("materialized_transfer_count", -1L) ?: -1L
                    CompanionHealthCheckResult(
                        deviceId = device.id,
                        status = CompanionHealthStatus.Healthy,
                        summary = buildString {
                            append("Companion responded with HTTP $responseCode")
                            if (transferCount >= 0L) {
                                append(" • $transferCount materialized")
                            }
                        },
                        detail = healthJson?.optString("inbox_dir")?.takeIf { it.isNotBlank() }
                            ?: healthUrl,
                        checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                            checkedAt,
                            checkedAt,
                            DateUtils.SECOND_IN_MILLIS,
                        ).toString(),
                    )
                } else {
                    CompanionHealthCheckResult(
                        deviceId = device.id,
                        status = CompanionHealthStatus.Unreachable,
                        summary = "Companion returned HTTP $responseCode",
                        detail = responseBody.take(140).ifBlank { healthUrl },
                        checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                            checkedAt,
                            checkedAt,
                            DateUtils.SECOND_IN_MILLIS,
                        ).toString(),
                    )
                }
            }.getOrElse { error ->
                CompanionHealthCheckResult(
                    deviceId = device.id,
                    status = CompanionHealthStatus.Unreachable,
                    summary = "Companion probe could not reach the endpoint.",
                    detail = error.message ?: endpoint,
                    checkedAtLabel = DateUtils.getRelativeTimeSpanString(
                        checkedAt,
                        checkedAt,
                        DateUtils.SECOND_IN_MILLIS,
                    ).toString(),
                )
            }
        }

        auditTrailRepository.logAction(
            action = "devices.health_probe",
            result = when (result.status) {
                CompanionHealthStatus.Healthy -> "ok"
                CompanionHealthStatus.Unreachable -> "failed"
                CompanionHealthStatus.Misconfigured -> "misconfigured"
                CompanionHealthStatus.Skipped -> "skipped"
            },
            details = "Companion probe for ${device.name}: ${result.summary} ${result.detail}".trim(),
        )
        return result
    }

    override suspend fun retryFailedTransfers(deviceId: String?) {
        val targetLabel = if (deviceId == null) {
            "all paired devices"
        } else {
            _pairedDevices.value.firstOrNull { it.id == deviceId }?.name ?: "selected device"
        }
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                val now = System.currentTimeMillis()
                put("status", TransferDraftStatus.Queued.name)
                put("updated_at", now)
                put("next_attempt_at", now)
                putNull("last_error")
            }
            if (deviceId == null) {
                databaseHelper.writableDatabase.update(
                    "transfer_outbox",
                    values,
                    "status = ?",
                    arrayOf(TransferDraftStatus.Failed.name),
                )
            } else {
                databaseHelper.writableDatabase.update(
                    "transfer_outbox",
                    values,
                    "device_id = ? AND status = ?",
                    arrayOf(deviceId, TransferDraftStatus.Failed.name),
                )
            }
        }
        auditTrailRepository.logAction(
            action = "files.send_to_device",
            result = "requeued",
            details = "Requeued failed transfer drafts for $targetLabel.",
        )
        refresh()
        transferOutboxScheduler?.scheduleDrain()
    }

    override suspend fun refresh() {
        _pairedDevices.value = queryDevices()
        _pairingSessions.value = queryPairingSessions()
        _transferDrafts.value = queryTransferDrafts()
    }

    private suspend fun queryDevices(): List<PairedDeviceState> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<PairedDeviceState>()
        databaseHelper.readableDatabase.query(
            "paired_devices",
            arrayOf(
                "id",
                "name",
                "role",
                "status",
                "capabilities_json",
                "transport_mode",
                "endpoint_url",
                "validation_mode",
            ),
            null,
            null,
            null,
            null,
            "paired_at DESC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val roleIndex = cursor.getColumnIndexOrThrow("role")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val capabilitiesIndex = cursor.getColumnIndexOrThrow("capabilities_json")
            val transportModeIndex = cursor.getColumnIndexOrThrow("transport_mode")
            val endpointIndex = cursor.getColumnIndexOrThrow("endpoint_url")
            val validationModeIndex = cursor.getColumnIndexOrThrow("validation_mode")

            while (cursor.moveToNext()) {
                devices += PairedDeviceState(
                    id = cursor.getString(idIndex),
                    name = cursor.getString(nameIndex),
                    role = cursor.getString(roleIndex),
                    health = cursor.getString(statusIndex),
                    capabilities = jsonArrayToList(cursor.getString(capabilitiesIndex)),
                    transportMode = DeviceTransportMode.valueOf(cursor.getString(transportModeIndex)),
                    endpointLabel = cursor.getString(endpointIndex),
                    validationMode = TransportValidationMode.valueOf(cursor.getString(validationModeIndex)),
                )
            }
        }
        devices
    }

    private suspend fun queryPairingSessions(): List<PairingSessionState> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sessions = mutableListOf<PairingSessionState>()
        databaseHelper.readableDatabase.query(
            "pairing_sessions",
            arrayOf(
                "id",
                "requested_role",
                "qr_secret",
                "requested_capabilities",
                "status",
                "created_at",
                "expires_at",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val roleIndex = cursor.getColumnIndexOrThrow("requested_role")
            val qrSecretIndex = cursor.getColumnIndexOrThrow("qr_secret")
            val capabilitiesIndex = cursor.getColumnIndexOrThrow("requested_capabilities")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
            val expiresAtIndex = cursor.getColumnIndexOrThrow("expires_at")

            while (cursor.moveToNext()) {
                val createdAt = cursor.getLong(createdAtIndex)
                val expiresAt = cursor.getLong(expiresAtIndex)
                sessions += PairingSessionState(
                    id = cursor.getString(idIndex),
                    requestedRole = cursor.getString(roleIndex),
                    qrSecret = cursor.getString(qrSecretIndex),
                    requestedCapabilities = jsonArrayToList(cursor.getString(capabilitiesIndex)),
                    status = PairingSessionStatus.valueOf(cursor.getString(statusIndex)),
                    createdAtLabel = DateUtils.getRelativeTimeSpanString(
                        createdAt,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    expiresAtLabel = DateUtils.getRelativeTimeSpanString(
                        expiresAt,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                )
            }
        }
        sessions
    }

    private suspend fun queryTransferDrafts(): List<TransferDraftState> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val drafts = mutableListOf<TransferDraftState>()
        databaseHelper.readableDatabase.query(
            "transfer_outbox",
            arrayOf(
                "id",
                "device_id",
                "device_name",
                "file_names_json",
                "status",
                "created_at",
                "updated_at",
                "transport_endpoint",
                "delivery_mode",
                "receipt_json",
                "next_attempt_at",
                "last_error",
                "attempt_count",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC",
            "10",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val deviceIdIndex = cursor.getColumnIndexOrThrow("device_id")
            val deviceNameIndex = cursor.getColumnIndexOrThrow("device_name")
            val fileNamesIndex = cursor.getColumnIndexOrThrow("file_names_json")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
            val updatedAtIndex = cursor.getColumnIndexOrThrow("updated_at")
            val transportEndpointIndex = cursor.getColumnIndexOrThrow("transport_endpoint")
            val deliveryModeIndex = cursor.getColumnIndexOrThrow("delivery_mode")
            val receiptJsonIndex = cursor.getColumnIndexOrThrow("receipt_json")
            val nextAttemptAtIndex = cursor.getColumnIndexOrThrow("next_attempt_at")
            val lastErrorIndex = cursor.getColumnIndexOrThrow("last_error")
            val attemptCountIndex = cursor.getColumnIndexOrThrow("attempt_count")

            while (cursor.moveToNext()) {
                val createdAt = cursor.getLong(createdAtIndex)
                val updatedAt = cursor.getLong(updatedAtIndex)
                val status = TransferDraftStatus.valueOf(cursor.getString(statusIndex))
                val transportEndpoint = cursor.getString(transportEndpointIndex)
                val deliveryMode = cursor.getString(deliveryModeIndex)
                val receiptJson = cursor.getString(receiptJsonIndex)
                val nextAttemptAt = cursor.getLong(nextAttemptAtIndex)
                val lastError = cursor.getString(lastErrorIndex)
                val nextAttemptAtEpochMillis = nextAttemptAt.takeIf {
                    status == TransferDraftStatus.Queued && it > now
                }
                val nextAttemptAtLabel = nextAttemptAtEpochMillis?.let {
                    DateUtils.getRelativeTimeSpanString(
                        it,
                        now,
                        DateUtils.SECOND_IN_MILLIS,
                    ).toString()
                }
                val receiptMetadata = receiptJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::parseReceiptMetadata)
                drafts += TransferDraftState(
                    id = cursor.getString(idIndex),
                    deviceId = cursor.getString(deviceIdIndex),
                    deviceName = cursor.getString(deviceNameIndex),
                    status = status,
                    createdAtLabel = DateUtils.getRelativeTimeSpanString(
                        createdAt,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                        updatedAt,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    fileNames = jsonArrayToList(cursor.getString(fileNamesIndex)),
                    detail = when {
                        nextAttemptAtLabel != null && !lastError.isNullOrBlank() ->
                            "Retry scheduled $nextAttemptAtLabel. Last error: $lastError"
                        nextAttemptAtLabel != null ->
                            "Retry scheduled $nextAttemptAtLabel."
                        !lastError.isNullOrBlank() -> lastError
                        receiptMetadata != null -> receiptMetadata.summary
                        !transportEndpoint.isNullOrBlank() -> "Delivered via $transportEndpoint"
                        status == TransferDraftStatus.Sending -> "Bridge transport is pushing this draft now."
                        else -> "Awaiting bridge delivery."
                    },
                    attemptCount = cursor.getInt(attemptCountIndex),
                    deliveryModeLabel = deliveryMode,
                    nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
                    nextAttemptAtLabel = nextAttemptAtLabel,
                    receiptReviewRequired = receiptMetadata?.reviewRequired ?: false,
                    receiptIssue = receiptMetadata?.issue,
                )
            }
        }
        drafts
    }

    private fun jsonArrayToList(raw: String): List<String> {
        val jsonArray = JSONArray(raw)
        return buildList {
            for (index in 0 until jsonArray.length()) {
                add(jsonArray.getString(index))
            }
        }
    }

    private fun defaultEndpointFor(secret: String): String {
        val normalized = secret.lowercase().replace(" ", "")
        return if (BuildConfig.DEBUG) {
            "http://10.0.2.2:8787/api/v1/transfers"
        } else {
            "http://mobileclaw-$normalized.local:8787/api/v1/transfers"
        }
    }

    private fun healthUrlFor(endpoint: String): String {
        val url = URL(endpoint)
        return URL(url.protocol, url.host, url.port, "/health").toString()
    }

    private fun parseReceiptMetadata(raw: String): ReceiptMetadata? {
        return runCatching {
            val json = JSONObject(raw)
            val reviewRequired = !json.optBoolean("receipt_valid", true)
            val issue = json.optString("receipt_issue").takeIf { it.isNotBlank() }
            ReceiptMetadata(
                summary = buildString {
                    append(json.optString("status", "accepted").replaceFirstChar(Char::uppercase))
                    json.optString("delivery_mode").takeIf { it.isNotBlank() }?.let { mode ->
                        append(" via ")
                        append(mode)
                    }
                    json.optString("materialized_dir").takeIf { it.isNotBlank() }?.let { dir ->
                        append(" • ")
                        append(dir)
                    }
                    val extractedEntries = json.optInt("extracted_entries", -1)
                    if (extractedEntries >= 0) {
                        append(" • ")
                        append("$extractedEntries files")
                    }
                    val requestedCount = json.optInt("requested_count", -1)
                    if (requestedCount >= 0 && extractedEntries < 0) {
                        append(" • ")
                        append("$requestedCount requested")
                    }
                    json.optString("status_detail").takeIf { it.isNotBlank() }?.let { detail ->
                        append(" • ")
                        append(detail)
                    }
                    if (reviewRequired) {
                        append(" • review receipt")
                        issue?.let {
                            append(" • ")
                            append(it)
                        }
                    }
                },
                reviewRequired = reviewRequired,
                issue = issue,
            )
        }.getOrNull()
    }
}

private data class ReceiptMetadata(
    val summary: String,
    val reviewRequired: Boolean,
    val issue: String?,
)
