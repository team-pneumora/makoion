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

enum class McpBridgeDiscoveryStatus {
    Ready,
    Unreachable,
    Misconfigured,
    Skipped,
}

data class McpBridgeDiscoveryResult(
    val deviceId: String,
    val status: McpBridgeDiscoveryStatus,
    val summary: String,
    val detail: String,
    val serverLabel: String? = null,
    val transportLabel: String? = null,
    val authLabel: String? = null,
    val toolNames: List<String> = emptyList(),
    val toolSchemas: List<McpToolSchemaProfile> = emptyList(),
    val skillBundles: List<McpSkillBundleProfile> = emptyList(),
    val workflowIds: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val discoveredAtLabel: String,
)

enum class CompanionSessionNotifyStatus {
    Delivered,
    Failed,
    Misconfigured,
    Skipped,
}

data class CompanionSessionNotifyResult(
    val deviceId: String,
    val status: CompanionSessionNotifyStatus,
    val summary: String,
    val detail: String,
    val sentAtLabel: String,
)

enum class CompanionAppOpenStatus {
    Opened,
    Recorded,
    Failed,
    Misconfigured,
    Skipped,
}

data class CompanionAppOpenResult(
    val deviceId: String,
    val targetKind: String,
    val targetLabel: String,
    val status: CompanionAppOpenStatus,
    val summary: String,
    val detail: String,
    val sentAtLabel: String,
)

enum class CompanionWorkflowRunStatus {
    Completed,
    Recorded,
    Failed,
    Misconfigured,
    Skipped,
}

data class CompanionWorkflowRunResult(
    val deviceId: String,
    val workflowId: String,
    val status: CompanionWorkflowRunStatus,
    val summary: String,
    val detail: String,
    val sentAtLabel: String,
)

private const val defaultRemoteActionMode = "best_effort"
private const val recordOnlyRemoteActionMode = "record_only"
const val companionCapabilityFilesTransfer = "files.transfer"
const val companionCapabilitySessionNotify = "session.notify"
const val companionCapabilityAppOpen = "app.open"
const val companionCapabilityWorkflowRun = "workflow.run"
const val companionAppOpenTargetInbox = "inbox"
const val companionAppOpenTargetLatestTransfer = "latest_transfer"
const val companionAppOpenTargetActionsFolder = "actions_folder"
const val companionAppOpenTargetLatestAction = "latest_action"

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
        approvalRequestId: String? = null,
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

    suspend fun discoverMcpBridge(deviceId: String): McpBridgeDiscoveryResult

    suspend fun probeCompanion(deviceId: String): CompanionHealthCheckResult

    suspend fun sendSessionNotification(
        deviceId: String,
        title: String,
        body: String,
    ): CompanionSessionNotifyResult

    suspend fun sendAppOpen(
        deviceId: String,
        targetKind: String,
        targetLabel: String,
        openMode: String = defaultRemoteActionMode,
    ): CompanionAppOpenResult

    suspend fun sendWorkflowRun(
        deviceId: String,
        workflowId: String,
        workflowLabel: String,
        runMode: String = defaultRemoteActionMode,
    ): CompanionWorkflowRunResult

    suspend fun retryFailedTransfers(deviceId: String? = null)

    suspend fun retryTransferApproval(approvalRequestId: String): Int

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
                                companionCapabilityFilesTransfer,
                                companionCapabilityAppOpen,
                                companionCapabilitySessionNotify,
                                companionCapabilityWorkflowRun,
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
                    put("capabilities_json", emptyJsonArrayString())
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
        approvalRequestId: String?,
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
                    put("approval_request_id", approvalRequestId)
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
                    put("capabilities_json", emptyJsonArrayString())
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
                    put("capabilities_json", emptyJsonArrayString())
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

    override suspend fun discoverMcpBridge(deviceId: String): McpBridgeDiscoveryResult {
        val discoveredAt = System.currentTimeMillis()
        val discoveredAtLabel = DateUtils.getRelativeTimeSpanString(
            discoveredAt,
            discoveredAt,
            DateUtils.SECOND_IN_MILLIS,
        ).toString()
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId }
        if (device == null) {
            return McpBridgeDiscoveryResult(
                deviceId = deviceId,
                status = McpBridgeDiscoveryStatus.Misconfigured,
                summary = "Selected device is no longer available.",
                detail = "Refresh paired devices before discovering the MCP bridge again.",
                discoveredAtLabel = discoveredAtLabel,
            )
        }
        if (device.transportMode != DeviceTransportMode.DirectHttp) {
            val result = McpBridgeDiscoveryResult(
                deviceId = device.id,
                status = McpBridgeDiscoveryStatus.Skipped,
                summary = "Loopback mode does not expose companion MCP discovery.",
                detail = "Arm LAN bridge first if you want to discover companion MCP tools over HTTP.",
                discoveredAtLabel = discoveredAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.mcp_discovery",
                result = "skipped",
                details = "Skipped MCP discovery for ${device.name} because loopback transport is active.",
            )
            return result
        }
        val endpoint = device.endpointLabel
        if (endpoint.isNullOrBlank()) {
            val result = McpBridgeDiscoveryResult(
                deviceId = device.id,
                status = McpBridgeDiscoveryStatus.Misconfigured,
                summary = "Companion endpoint is missing.",
                detail = "Direct HTTP mode needs an endpoint URL before the shell can discover MCP tools.",
                discoveredAtLabel = discoveredAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.mcp_discovery",
                result = "misconfigured",
                details = "MCP discovery could not run for ${device.name} because endpoint_url was missing.",
            )
            return result
        }

        val result = withContext(Dispatchers.IO) {
            val discoveryUrl = runCatching { mcpDiscoveryUrlFor(endpoint) }.getOrNull()
            if (discoveryUrl == null) {
                return@withContext McpBridgeDiscoveryResult(
                    deviceId = device.id,
                    status = McpBridgeDiscoveryStatus.Misconfigured,
                    summary = "Endpoint URL could not be parsed.",
                    detail = endpoint,
                    discoveredAtLabel = discoveredAtLabel,
                )
            }
            val trustedSecret = queryTrustedSecret(device.id)
            runCatching {
                val connection = (URL(discoveryUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("Accept", "application/json")
                    trustedSecret?.takeIf { it.isNotBlank() }?.let { secret ->
                        setRequestProperty("X-MobileClaw-Trusted-Secret", secret)
                    }
                }
                val responseCode = connection.responseCode
                val responseBody = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                }
                connection.disconnect()

                if (responseCode in 200..299) {
                    val discoveryJson = runCatching { JSONObject(responseBody) }.getOrNull()
                    val toolNames = jsonArrayToTrimmedList(discoveryJson?.optJSONArray("tool_names"))
                    val toolSchemas = jsonArrayToToolSchemas(discoveryJson?.optJSONArray("tool_schemas"))
                    val skillBundles = jsonArrayToSkillBundles(discoveryJson?.optJSONArray("skill_bundles"))
                    val workflowIds = jsonArrayToTrimmedList(discoveryJson?.optJSONArray("workflow_ids"))
                    val capabilities = jsonArrayToTrimmedList(discoveryJson?.optJSONArray("capabilities"))
                    if (capabilities.isNotEmpty()) {
                        updateAdvertisedCapabilities(device.id, capabilities)
                    }
                    McpBridgeDiscoveryResult(
                        deviceId = device.id,
                        status = McpBridgeDiscoveryStatus.Ready,
                        summary = buildString {
                            append("HTTP $responseCode")
                            append(" • ${toolNames.size} tool(s)")
                            if (toolSchemas.isNotEmpty()) {
                                append(" • ${toolSchemas.size} schema(s)")
                            }
                            if (skillBundles.isNotEmpty()) {
                                append(" • ${skillBundles.size} bundle(s)")
                            }
                            if (capabilities.isNotEmpty()) {
                                append(" • ${capabilities.size} capabilities")
                            }
                        },
                        detail = discoveryJson?.optString("status_detail")?.takeIf { it.isNotBlank() }
                            ?: discoveryUrl,
                        serverLabel = discoveryJson?.optString("server_label")?.takeIf { it.isNotBlank() },
                        transportLabel = discoveryJson?.optString("transport_label")?.takeIf { it.isNotBlank() },
                        authLabel = discoveryJson?.optString("auth_label")?.takeIf { it.isNotBlank() },
                        toolNames = toolNames,
                        toolSchemas = toolSchemas,
                        skillBundles = skillBundles,
                        workflowIds = workflowIds,
                        capabilities = capabilities,
                        discoveredAtLabel = discoveredAtLabel,
                    )
                } else {
                    McpBridgeDiscoveryResult(
                        deviceId = device.id,
                        status = McpBridgeDiscoveryStatus.Unreachable,
                        summary = "Companion returned HTTP $responseCode for MCP discovery.",
                        detail = responseBody.take(180).ifBlank { discoveryUrl },
                        discoveredAtLabel = discoveredAtLabel,
                    )
                }
            }.getOrElse { error ->
                McpBridgeDiscoveryResult(
                    deviceId = device.id,
                    status = McpBridgeDiscoveryStatus.Unreachable,
                    summary = "Companion MCP discovery could not reach the endpoint.",
                    detail = directHttpReachabilityDetail(
                        requestUrl = discoveryUrl,
                        fallbackUrl = endpoint,
                        error = error,
                    ),
                    discoveredAtLabel = discoveredAtLabel,
                )
            }
        }

        auditTrailRepository.logAction(
            action = "devices.mcp_discovery",
            result = when (result.status) {
                McpBridgeDiscoveryStatus.Ready -> "ok"
                McpBridgeDiscoveryStatus.Unreachable -> "failed"
                McpBridgeDiscoveryStatus.Misconfigured -> "misconfigured"
                McpBridgeDiscoveryStatus.Skipped -> "skipped"
            },
            details = "MCP discovery for ${device.name}: ${result.summary} ${result.detail}".trim(),
        )
        refresh()
        return result
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
                    val capabilities = advertisedCapabilitiesFromHealthJson(healthJson)
                    updateAdvertisedCapabilities(
                        deviceId = device.id,
                        capabilities = capabilities,
                    )
                    CompanionHealthCheckResult(
                        deviceId = device.id,
                        status = CompanionHealthStatus.Healthy,
                        summary = buildString {
                            append("HTTP $responseCode")
                            if (capabilities.isNotEmpty()) {
                                append(" • ${capabilities.size} capabilities")
                            }
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
                    detail = directHttpReachabilityDetail(
                        requestUrl = healthUrl,
                        fallbackUrl = endpoint,
                        error = error,
                    ),
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
        refresh()
        return result
    }

    override suspend fun sendSessionNotification(
        deviceId: String,
        title: String,
        body: String,
    ): CompanionSessionNotifyResult {
        val sentAt = System.currentTimeMillis()
        val sentAtLabel = DateUtils.getRelativeTimeSpanString(
            sentAt,
            sentAt,
            DateUtils.SECOND_IN_MILLIS,
        ).toString()
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId }
        if (device == null) {
            return CompanionSessionNotifyResult(
                deviceId = deviceId,
                status = CompanionSessionNotifyStatus.Misconfigured,
                summary = "Selected device is no longer available.",
                detail = "Refresh paired devices before trying session.notify again.",
                sentAtLabel = sentAtLabel,
            )
        }
        if (device.transportMode != DeviceTransportMode.DirectHttp) {
            val result = CompanionSessionNotifyResult(
                deviceId = device.id,
                status = CompanionSessionNotifyStatus.Skipped,
                summary = "Loopback mode does not expose companion session.notify.",
                detail = "Arm LAN bridge first if you want to send a remote session notification.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.session_notify",
                result = "skipped",
                details = "Skipped session.notify for ${device.name} because loopback transport is active.",
            )
            return result
        }
        if (!device.supportsCapability(companionCapabilitySessionNotify)) {
            val result = CompanionSessionNotifyResult(
                deviceId = device.id,
                status = CompanionSessionNotifyStatus.Skipped,
                summary = "Companion has not advertised session.notify yet.",
                detail = "Run Check companion health to refresh the remote capability snapshot before sending session.notify.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.session_notify",
                result = "skipped",
                details = "Skipped session.notify for ${device.name} because the latest /health capability snapshot does not advertise session.notify.",
            )
            return result
        }
        val endpoint = device.endpointLabel
        if (endpoint.isNullOrBlank()) {
            val result = CompanionSessionNotifyResult(
                deviceId = device.id,
                status = CompanionSessionNotifyStatus.Misconfigured,
                summary = "Companion endpoint is missing.",
                detail = "Direct HTTP mode needs an endpoint URL before the shell can send session notifications.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.session_notify",
                result = "misconfigured",
                details = "session.notify could not run for ${device.name} because endpoint_url was missing.",
            )
            return result
        }

        val result = withContext(Dispatchers.IO) {
            val notifyUrl = runCatching { sessionNotifyUrlFor(endpoint) }.getOrNull()
            if (notifyUrl == null) {
                return@withContext CompanionSessionNotifyResult(
                    deviceId = device.id,
                    status = CompanionSessionNotifyStatus.Misconfigured,
                    summary = "Endpoint URL could not be parsed.",
                    detail = endpoint,
                    sentAtLabel = sentAtLabel,
                )
            }
            val trustedSecret = queryTrustedSecret(device.id)
            runCatching {
                val payload = JSONObject()
                    .put("request_id", "notify-${UUID.randomUUID()}")
                    .put("source", BuildConfig.APPLICATION_ID)
                    .put("device_name", "Makoion Android shell")
                    .put("title", title)
                    .put("body", body)
                    .put("requested_at", sentAt)
                    .toString()
                val connection = (URL(notifyUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3_000
                    readTimeout = 4_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    trustedSecret?.takeIf { it.isNotBlank() }?.let { secret ->
                        setRequestProperty("X-MobileClaw-Trusted-Secret", secret)
                    }
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                val responseCode = connection.responseCode
                val responseBody = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                }
                connection.disconnect()

                if (responseCode in 200..299) {
                    val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
                    CompanionSessionNotifyResult(
                        deviceId = device.id,
                        status = CompanionSessionNotifyStatus.Delivered,
                        summary = "Companion accepted session.notify with HTTP $responseCode.",
                        detail = buildString {
                            responseJson?.optString("status_detail")?.takeIf { it.isNotBlank() }?.let { detail ->
                                append(detail)
                            }
                            responseJson?.optString("materialized_dir")?.takeIf { it.isNotBlank() }?.let { materializedDir ->
                                if (isNotBlank()) {
                                    append(" • ")
                                }
                                append(materializedDir)
                            }
                            if (isBlank()) {
                                append(notifyUrl)
                            }
                        },
                        sentAtLabel = sentAtLabel,
                    )
                } else {
                    CompanionSessionNotifyResult(
                        deviceId = device.id,
                        status = CompanionSessionNotifyStatus.Failed,
                        summary = "Companion returned HTTP $responseCode for session.notify.",
                        detail = responseBody.take(160).ifBlank { notifyUrl },
                        sentAtLabel = sentAtLabel,
                    )
                }
            }.getOrElse { error ->
                CompanionSessionNotifyResult(
                    deviceId = device.id,
                    status = CompanionSessionNotifyStatus.Failed,
                    summary = "Companion session.notify could not reach the endpoint.",
                    detail = directHttpReachabilityDetail(
                        requestUrl = notifyUrl,
                        fallbackUrl = endpoint,
                        error = error,
                    ),
                    sentAtLabel = sentAtLabel,
                )
            }
        }

        auditTrailRepository.logAction(
            action = "devices.session_notify",
            result = when (result.status) {
                CompanionSessionNotifyStatus.Delivered -> "delivered"
                CompanionSessionNotifyStatus.Failed -> "failed"
                CompanionSessionNotifyStatus.Misconfigured -> "misconfigured"
                CompanionSessionNotifyStatus.Skipped -> "skipped"
            },
            details = "session.notify for ${device.name}: ${result.summary} ${result.detail}".trim(),
        )
        return result
    }

    override suspend fun sendAppOpen(
        deviceId: String,
        targetKind: String,
        targetLabel: String,
        openMode: String,
    ): CompanionAppOpenResult {
        val sentAt = System.currentTimeMillis()
        val sentAtLabel = DateUtils.getRelativeTimeSpanString(
            sentAt,
            sentAt,
            DateUtils.SECOND_IN_MILLIS,
        ).toString()
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId }
        if (device == null) {
            return CompanionAppOpenResult(
                deviceId = deviceId,
                targetKind = targetKind,
                targetLabel = targetLabel,
                status = CompanionAppOpenStatus.Misconfigured,
                summary = "Selected device is no longer available.",
                detail = "Refresh paired devices before trying app.open again.",
                sentAtLabel = sentAtLabel,
            )
        }
        if (device.transportMode != DeviceTransportMode.DirectHttp) {
            val result = CompanionAppOpenResult(
                deviceId = device.id,
                targetKind = targetKind,
                targetLabel = targetLabel,
                status = CompanionAppOpenStatus.Skipped,
                summary = "Loopback mode does not expose companion app.open.",
                detail = "Arm LAN bridge first if you want to open a desktop surface over HTTP.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.app_open",
                result = "skipped",
                details = "Skipped app.open ($targetKind) for ${device.name} because loopback transport is active.",
            )
            return result
        }
        if (!device.supportsCapability(companionCapabilityAppOpen)) {
            val result = CompanionAppOpenResult(
                deviceId = device.id,
                targetKind = targetKind,
                targetLabel = targetLabel,
                status = CompanionAppOpenStatus.Skipped,
                summary = "Companion has not advertised app.open yet.",
                detail = "Run Check companion health to refresh the remote capability snapshot before sending app.open.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.app_open",
                result = "skipped",
                details = "Skipped app.open ($targetKind) for ${device.name} because the latest /health capability snapshot does not advertise app.open.",
            )
            return result
        }
        val endpoint = device.endpointLabel
        if (endpoint.isNullOrBlank()) {
            val result = CompanionAppOpenResult(
                deviceId = device.id,
                targetKind = targetKind,
                targetLabel = targetLabel,
                status = CompanionAppOpenStatus.Misconfigured,
                summary = "Companion endpoint is missing.",
                detail = "Direct HTTP mode needs an endpoint URL before the shell can request app.open.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.app_open",
                result = "misconfigured",
                details = "app.open ($targetKind) could not run for ${device.name} because endpoint_url was missing.",
            )
            return result
        }

        val result = withContext(Dispatchers.IO) {
            val appOpenUrl = runCatching { appOpenUrlFor(endpoint) }.getOrNull()
            if (appOpenUrl == null) {
                return@withContext CompanionAppOpenResult(
                    deviceId = device.id,
                    targetKind = targetKind,
                    targetLabel = targetLabel,
                    status = CompanionAppOpenStatus.Misconfigured,
                    summary = "Endpoint URL could not be parsed.",
                    detail = endpoint,
                    sentAtLabel = sentAtLabel,
                )
            }
            val trustedSecret = queryTrustedSecret(device.id)
            runCatching {
                val payload = JSONObject()
                    .put("request_id", "app-open-${UUID.randomUUID()}")
                    .put("source", BuildConfig.APPLICATION_ID)
                    .put("device_name", "Makoion Android shell")
                    .put("target_kind", targetKind)
                    .put("target_label", targetLabel)
                    .put("open_mode", openMode)
                    .put("requested_at", sentAt)
                    .toString()
                val connection = (URL(appOpenUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3_000
                    readTimeout = 4_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    trustedSecret?.takeIf { it.isNotBlank() }?.let { secret ->
                        setRequestProperty("X-MobileClaw-Trusted-Secret", secret)
                    }
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                val responseCode = connection.responseCode
                val responseBody = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                }
                connection.disconnect()

                if (responseCode in 200..299) {
                    val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
                    val status = when {
                        responseJson?.optBoolean("opened", false) == true -> CompanionAppOpenStatus.Opened
                        openMode.equals(recordOnlyRemoteActionMode, ignoreCase = true) -> CompanionAppOpenStatus.Recorded
                        else -> CompanionAppOpenStatus.Failed
                    }
                    CompanionAppOpenResult(
                        deviceId = device.id,
                        targetKind = targetKind,
                        targetLabel = targetLabel,
                        status = status,
                        summary = when (status) {
                            CompanionAppOpenStatus.Opened ->
                                "Companion accepted app.open for $targetLabel with HTTP $responseCode."
                            CompanionAppOpenStatus.Recorded ->
                                "Companion recorded app.open for $targetLabel with HTTP $responseCode."
                            else -> "Companion accepted app.open but did not open $targetLabel."
                        },
                        detail = buildString {
                            responseJson?.optString("status_detail")?.takeIf { it.isNotBlank() }?.let { detail ->
                                append(detail)
                            }
                            responseJson?.optString("opened_path")?.takeIf { it.isNotBlank() }?.let { openedPath ->
                                if (isNotBlank()) {
                                    append(" • ")
                                }
                                append(openedPath)
                            }
                            responseJson?.optString("materialized_dir")?.takeIf { it.isNotBlank() }?.let { materializedDir ->
                                if (isNotBlank()) {
                                    append(" • ")
                                }
                                append(materializedDir)
                            }
                            if (isBlank()) {
                                append(appOpenUrl)
                            }
                        },
                        sentAtLabel = sentAtLabel,
                    )
                } else {
                    CompanionAppOpenResult(
                        deviceId = device.id,
                        targetKind = targetKind,
                        targetLabel = targetLabel,
                        status = CompanionAppOpenStatus.Failed,
                        summary = "Companion returned HTTP $responseCode for app.open $targetLabel.",
                        detail = responseBody.take(160).ifBlank { appOpenUrl },
                        sentAtLabel = sentAtLabel,
                    )
                }
            }.getOrElse { error ->
                CompanionAppOpenResult(
                    deviceId = device.id,
                    targetKind = targetKind,
                    targetLabel = targetLabel,
                    status = CompanionAppOpenStatus.Failed,
                    summary = "Companion app.open could not reach the endpoint for $targetLabel.",
                    detail = directHttpReachabilityDetail(
                        requestUrl = appOpenUrl,
                        fallbackUrl = endpoint,
                        error = error,
                    ),
                    sentAtLabel = sentAtLabel,
                )
            }
        }

        auditTrailRepository.logAction(
            action = "devices.app_open",
            result = when (result.status) {
                CompanionAppOpenStatus.Opened -> "opened"
                CompanionAppOpenStatus.Recorded -> "recorded"
                CompanionAppOpenStatus.Failed -> "failed"
                CompanionAppOpenStatus.Misconfigured -> "misconfigured"
                CompanionAppOpenStatus.Skipped -> "skipped"
            },
            details = "app.open $targetKind for ${device.name}: ${result.summary} ${result.detail}".trim(),
        )
        return result
    }

    override suspend fun sendWorkflowRun(
        deviceId: String,
        workflowId: String,
        workflowLabel: String,
        runMode: String,
    ): CompanionWorkflowRunResult {
        val sentAt = System.currentTimeMillis()
        val sentAtLabel = DateUtils.getRelativeTimeSpanString(
            sentAt,
            sentAt,
            DateUtils.SECOND_IN_MILLIS,
        ).toString()
        val device = _pairedDevices.value.firstOrNull { it.id == deviceId }
        if (device == null) {
            return CompanionWorkflowRunResult(
                deviceId = deviceId,
                workflowId = workflowId,
                status = CompanionWorkflowRunStatus.Misconfigured,
                summary = "Selected device is no longer available.",
                detail = "Refresh paired devices before trying workflow.run again.",
                sentAtLabel = sentAtLabel,
            )
        }
        if (device.transportMode != DeviceTransportMode.DirectHttp) {
            val result = CompanionWorkflowRunResult(
                deviceId = device.id,
                workflowId = workflowId,
                status = CompanionWorkflowRunStatus.Skipped,
                summary = "Loopback mode does not expose companion workflow.run.",
                detail = "Arm LAN bridge first if you want to run a desktop workflow over HTTP.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.workflow_run",
                result = "skipped",
                details = "Skipped workflow.run for ${device.name} because loopback transport is active.",
            )
            return result
        }
        if (!device.supportsCapability(companionCapabilityWorkflowRun)) {
            val result = CompanionWorkflowRunResult(
                deviceId = device.id,
                workflowId = workflowId,
                status = CompanionWorkflowRunStatus.Skipped,
                summary = "Companion has not advertised workflow.run yet.",
                detail = "Run Check companion health to refresh the remote capability snapshot before sending workflow.run.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.workflow_run",
                result = "skipped",
                details = "Skipped workflow.run for ${device.name} because the latest /health capability snapshot does not advertise workflow.run.",
            )
            return result
        }
        val endpoint = device.endpointLabel
        if (endpoint.isNullOrBlank()) {
            val result = CompanionWorkflowRunResult(
                deviceId = device.id,
                workflowId = workflowId,
                status = CompanionWorkflowRunStatus.Misconfigured,
                summary = "Companion endpoint is missing.",
                detail = "Direct HTTP mode needs an endpoint URL before the shell can request workflow.run.",
                sentAtLabel = sentAtLabel,
            )
            auditTrailRepository.logAction(
                action = "devices.workflow_run",
                result = "misconfigured",
                details = "workflow.run could not run for ${device.name} because endpoint_url was missing.",
            )
            return result
        }

        val result = withContext(Dispatchers.IO) {
            val workflowUrl = runCatching { workflowRunUrlFor(endpoint) }.getOrNull()
            if (workflowUrl == null) {
                return@withContext CompanionWorkflowRunResult(
                    deviceId = device.id,
                    workflowId = workflowId,
                    status = CompanionWorkflowRunStatus.Misconfigured,
                    summary = "Endpoint URL could not be parsed.",
                    detail = endpoint,
                    sentAtLabel = sentAtLabel,
                )
            }
            val trustedSecret = queryTrustedSecret(device.id)
            runCatching {
                val payload = JSONObject()
                    .put("request_id", "workflow-run-${UUID.randomUUID()}")
                    .put("source", BuildConfig.APPLICATION_ID)
                    .put("device_name", "Makoion Android shell")
                    .put("workflow_id", workflowId)
                    .put("workflow_label", workflowLabel)
                    .put("run_mode", runMode)
                    .put("requested_at", sentAt)
                    .toString()
                val connection = (URL(workflowUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3_000
                    readTimeout = 4_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    trustedSecret?.takeIf { it.isNotBlank() }?.let { secret ->
                        setRequestProperty("X-MobileClaw-Trusted-Secret", secret)
                    }
                }
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                val responseCode = connection.responseCode
                val responseBody = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    connection.errorStream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                }
                connection.disconnect()

                if (responseCode in 200..299) {
                    val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
                    val status = when {
                        responseJson?.optBoolean("executed", false) == true -> CompanionWorkflowRunStatus.Completed
                        runMode.equals(recordOnlyRemoteActionMode, ignoreCase = true) -> CompanionWorkflowRunStatus.Recorded
                        else -> CompanionWorkflowRunStatus.Failed
                    }
                    CompanionWorkflowRunResult(
                        deviceId = device.id,
                        workflowId = workflowId,
                        status = status,
                        summary = when (status) {
                            CompanionWorkflowRunStatus.Completed ->
                                "Companion accepted workflow.run with HTTP $responseCode."
                            CompanionWorkflowRunStatus.Recorded ->
                                "Companion recorded workflow.run with HTTP $responseCode."
                            else -> "Companion accepted workflow.run but did not execute the workflow."
                        },
                        detail = buildString {
                            responseJson?.optString("status_detail")?.takeIf { it.isNotBlank() }?.let { detail ->
                                append(detail)
                            }
                            responseJson?.optString("materialized_dir")?.takeIf { it.isNotBlank() }?.let { materializedDir ->
                                if (isNotBlank()) {
                                    append(" • ")
                                }
                                append(materializedDir)
                            }
                            if (isBlank()) {
                                append(workflowUrl)
                            }
                        },
                        sentAtLabel = sentAtLabel,
                    )
                } else {
                    CompanionWorkflowRunResult(
                        deviceId = device.id,
                        workflowId = workflowId,
                        status = CompanionWorkflowRunStatus.Failed,
                        summary = "Companion returned HTTP $responseCode for workflow.run.",
                        detail = responseBody.take(160).ifBlank { workflowUrl },
                        sentAtLabel = sentAtLabel,
                    )
                }
            }.getOrElse { error ->
                CompanionWorkflowRunResult(
                    deviceId = device.id,
                    workflowId = workflowId,
                    status = CompanionWorkflowRunStatus.Failed,
                    summary = "Companion workflow.run could not reach the endpoint.",
                    detail = directHttpReachabilityDetail(
                        requestUrl = workflowUrl,
                        fallbackUrl = endpoint,
                        error = error,
                    ),
                    sentAtLabel = sentAtLabel,
                )
            }
        }

        auditTrailRepository.logAction(
            action = "devices.workflow_run",
            result = when (result.status) {
                CompanionWorkflowRunStatus.Completed -> "completed"
                CompanionWorkflowRunStatus.Recorded -> "recorded"
                CompanionWorkflowRunStatus.Failed -> "failed"
                CompanionWorkflowRunStatus.Misconfigured -> "misconfigured"
                CompanionWorkflowRunStatus.Skipped -> "skipped"
            },
            details = "workflow.run $workflowId for ${device.name}: ${result.summary} ${result.detail}".trim(),
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

    override suspend fun retryTransferApproval(approvalRequestId: String): Int {
        val requeuedCount = withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                val now = System.currentTimeMillis()
                put("status", TransferDraftStatus.Queued.name)
                put("updated_at", now)
                put("next_attempt_at", now)
                putNull("last_error")
            }
            databaseHelper.writableDatabase.update(
                "transfer_outbox",
                values,
                "approval_request_id = ? AND status = ?",
                arrayOf(approvalRequestId, TransferDraftStatus.Failed.name),
            )
        }
        auditTrailRepository.logAction(
            action = "files.send_to_device",
            result = if (requeuedCount > 0) "requeued" else "requeue_skipped",
            details = if (requeuedCount > 0) {
                "Requeued $requeuedCount failed transfer draft(s) for approval $approvalRequestId."
            } else {
                "No failed transfer drafts were available for approval $approvalRequestId."
            },
        )
        refresh()
        if (requeuedCount > 0) {
            transferOutboxScheduler?.scheduleDrain()
        }
        return requeuedCount
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

    private fun advertisedCapabilitiesFromHealthJson(healthJson: JSONObject?): List<String> {
        val capabilitiesJson = healthJson?.optJSONArray("capabilities") ?: return emptyList()
        return buildList {
            for (index in 0 until capabilitiesJson.length()) {
                add(capabilitiesJson.optString(index))
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun updateAdvertisedCapabilities(
        deviceId: String,
        capabilities: List<String>,
    ) = withContext(Dispatchers.IO) {
        databaseHelper.writableDatabase.update(
            "paired_devices",
            ContentValues().apply {
                put("capabilities_json", JSONArray(capabilities).toString())
            },
            "id = ?",
            arrayOf(deviceId),
        )
    }

    private fun emptyJsonArrayString(): String = JSONArray(emptyList<String>()).toString()

    private fun PairedDeviceState.supportsCapability(capability: String): Boolean {
        return capabilities.any { advertised ->
            advertised.equals(capability, ignoreCase = true)
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

    private fun mcpDiscoveryUrlFor(endpoint: String): String {
        val url = URL(endpoint)
        return URL(url.protocol, url.host, url.port, "/api/v1/mcp/discovery").toString()
    }

    private suspend fun queryTrustedSecret(deviceId: String): String? = withContext(Dispatchers.IO) {
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
    }

    private fun sessionNotifyUrlFor(endpoint: String): String {
        val url = URL(endpoint)
        return URL(url.protocol, url.host, url.port, "/api/v1/session/notify").toString()
    }

    private fun appOpenUrlFor(endpoint: String): String {
        val url = URL(endpoint)
        return URL(url.protocol, url.host, url.port, "/api/v1/app/open").toString()
    }

    private fun workflowRunUrlFor(endpoint: String): String {
        val url = URL(endpoint)
        return URL(url.protocol, url.host, url.port, "/api/v1/workflow/run").toString()
    }

    private fun jsonArrayToTrimmedList(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun jsonArrayToToolSchemas(array: JSONArray?): List<McpToolSchemaProfile> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                val json = item as? JSONObject ?: continue
                val name = json.optString("name").trim()
                val title = json.optString("title").trim()
                val summary = json.optString("summary").trim()
                if (name.isBlank() || title.isBlank() || summary.isBlank()) {
                    continue
                }
                add(
                    McpToolSchemaProfile(
                        name = name,
                        title = title,
                        summary = summary,
                        inputSchemaSummary = json.optString("input_schema_summary")
                            .trim()
                            .takeIf { it.isNotBlank() },
                        requiresConfirmation = json.optBoolean("requires_confirmation"),
                    ),
                )
            }
        }
    }

    private fun jsonArrayToSkillBundles(array: JSONArray?): List<McpSkillBundleProfile> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is JSONObject -> {
                        val bundleId = item.optString("bundle_id").trim()
                        val title = item.optString("title").trim()
                        val summary = item.optString("summary").trim()
                        if (bundleId.isBlank() || title.isBlank() || summary.isBlank()) {
                            continue
                        }
                        add(
                            McpSkillBundleProfile(
                                bundleId = bundleId,
                                title = title,
                                summary = summary,
                                toolNames = jsonArrayToTrimmedList(item.optJSONArray("tool_names")),
                                versionLabel = item.optString("version_label")
                                    .trim()
                                    .takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                    is String -> {
                        val bundleId = item.trim()
                        if (bundleId.isBlank()) {
                            continue
                        }
                        add(
                            McpSkillBundleProfile(
                                bundleId = bundleId,
                                title = bundleId.replace('_', ' ').replaceFirstChar(Char::uppercase),
                                summary = "Advertised by the paired companion MCP discovery endpoint.",
                                toolNames = emptyList(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun directHttpReachabilityDetail(
        requestUrl: String,
        fallbackUrl: String,
        error: Throwable,
    ): String {
        val baseDetail = error.message ?: fallbackUrl
        val parsedUrl = runCatching { URL(requestUrl) }.getOrNull()
            ?: runCatching { URL(fallbackUrl) }.getOrNull()
            ?: return baseDetail
        val port = parsedUrl.port.takeIf { it > 0 } ?: parsedUrl.defaultPort
        return when (parsedUrl.host.lowercase()) {
            "127.0.0.1",
            "localhost",
            ->
                "$baseDetail. Restore adb reverse for tcp:$port and confirm the desktop companion is listening on that port."
            "10.0.2.2" ->
                "$baseDetail. Confirm the emulator host route is available and the desktop companion is listening on port $port."
            else -> baseDetail
        }
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
