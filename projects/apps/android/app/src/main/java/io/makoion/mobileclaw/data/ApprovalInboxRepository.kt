package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.content.Context
import android.text.format.DateUtils
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ApprovalInboxRisk {
    Low,
    Medium,
    High,
}

enum class ApprovalInboxStatus {
    Pending,
    Approved,
    Denied,
}

data class ApprovalInboxItem(
    val id: String,
    val title: String,
    val action: String,
    val risk: ApprovalInboxRisk,
    val summary: String,
    val requestedAtLabel: String,
    val status: ApprovalInboxStatus = ApprovalInboxStatus.Pending,
    val payloadJson: String? = null,
)

interface ApprovalInboxRepository {
    val items: StateFlow<List<ApprovalInboxItem>>

    suspend fun approve(id: String): ApprovalInboxItem?

    suspend fun deny(id: String): ApprovalInboxItem?

    suspend fun submitOrganizeApproval(
        plan: FileOrganizePlan,
        items: List<IndexedFileItem>,
        forceDeleteConsentForTesting: Boolean = false,
    ): ApprovalInboxItem?

    suspend fun submitTransferApproval(
        device: PairedDeviceState,
        files: List<IndexedFileItem>,
    ): ApprovalInboxItem?

    suspend fun recordExecutionOutcome(
        id: String,
        note: String,
    )

    suspend fun refresh()
}

class PersistentApprovalInboxRepository(
    private val context: Context,
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
) : ApprovalInboxRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<ApprovalInboxItem>>(emptyList())

    override val items: StateFlow<List<ApprovalInboxItem>> = _items.asStateFlow()

    init {
        repositoryScope.launch {
            ensureSeedData()
            refresh()
            auditTrailRepository.refresh()
        }
    }

    override suspend fun approve(id: String): ApprovalInboxItem? {
        return resolve(id, ApprovalInboxStatus.Approved)
    }

    override suspend fun deny(id: String): ApprovalInboxItem? {
        return resolve(id, ApprovalInboxStatus.Denied)
    }

    override suspend fun submitOrganizeApproval(
        plan: FileOrganizePlan,
        items: List<IndexedFileItem>,
        forceDeleteConsentForTesting: Boolean,
    ): ApprovalInboxItem? {
        if (plan.steps.isEmpty()) {
            return null
        }

        val now = System.currentTimeMillis()
        val payload = buildOrganizeApprovalPayload(
            plan = plan,
            items = items,
            forceDeleteConsentForTesting = forceDeleteConsentForTesting,
            requestedAtEpochMs = now,
        )
        val approvalId = "approval-${UUID.randomUUID()}"
        val risk = when (plan.riskLabel) {
            "High" -> ApprovalInboxRisk.High
            "Medium" -> ApprovalInboxRisk.Medium
            else -> ApprovalInboxRisk.Low
        }
        val title = "Organize ${plan.steps.size} files into Makoion folders"
        val summary = buildString {
            append("Execution will copy files into managed MediaStore folders, verify the destination bytes, ")
            append("and delete the source only when Android grants that path. ")
            append("Some originals may remain pending explicit delete consent.")
            if (forceDeleteConsentForTesting) {
                append(" Debug build regression mode will intentionally keep supported MediaStore originals pending delete consent.")
            }
        }
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.insert(
                "approval_requests",
                null,
                ContentValues().apply {
                    put("id", approvalId)
                    put("title", title)
                    put("intent_action", filesOrganizeExecuteActionKey)
                    put("assessed_risk", risk.name)
                    put("summary", summary)
                    put("intent_payload_json", payload.toJson())
                    put("requested_at", now)
                    put("status", ApprovalInboxStatus.Pending.name)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "files.organize",
            result = "approval_requested",
            details = buildString {
                append("Requested approval to organize ")
                append(plan.steps.size)
                append(" files via ")
                append(plan.strategy.name)
                append(".")
                if (forceDeleteConsentForTesting) {
                    append(" Debug build will force the delete consent path for supported MediaStore files.")
                }
            },
        )
        refresh()
        return _items.value.firstOrNull { it.id == approvalId }
    }

    override suspend fun submitTransferApproval(
        device: PairedDeviceState,
        files: List<IndexedFileItem>,
    ): ApprovalInboxItem? {
        if (files.isEmpty()) {
            return null
        }

        val now = System.currentTimeMillis()
        val approvalId = "approval-${UUID.randomUUID()}"
        val payload = TransferApprovalPayload(
            deviceId = device.id,
            deviceName = device.name,
            fileReferences = files.map { file ->
                TransferFileReference(
                    sourceId = file.id,
                    name = file.name,
                    mimeType = file.mimeType,
                )
            },
            requestedAtEpochMs = now,
        )
        val summary = buildString {
            append("Execution will queue ")
            append(files.size)
            append(" file(s) for ")
            append(device.name)
            append(" through the companion bridge. Background delivery may continue after approval.")
        }
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.insert(
                "approval_requests",
                null,
                ContentValues().apply {
                    put("id", approvalId)
                    put("title", "Send ${files.size} files to ${device.name}")
                    put("intent_action", filesTransferExecuteActionKey)
                    put("assessed_risk", ApprovalInboxRisk.High.name)
                    put("summary", summary)
                    put("intent_payload_json", payload.toJson())
                    put("requested_at", now)
                    put("status", ApprovalInboxStatus.Pending.name)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "files.transfer",
            result = "approval_requested",
            details = "Requested approval to send ${files.size} files to ${device.name}.",
        )
        refresh()
        return _items.value.firstOrNull { it.id == approvalId }
    }

    override suspend fun recordExecutionOutcome(
        id: String,
        note: String,
    ) {
        withContext(Dispatchers.IO) {
            val existingSummary = databaseHelper.readableDatabase.query(
                "approval_requests",
                arrayOf("summary"),
                "id = ?",
                arrayOf(id),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow("summary"))
                } else {
                    null
                }
            } ?: return@withContext

            val nextSummary = buildString {
                append(existingSummary.substringBefore("\nResult:"))
                append("\nResult: ")
                append(note)
            }
            databaseHelper.writableDatabase.update(
                "approval_requests",
                ContentValues().apply {
                    put("summary", nextSummary)
                },
                "id = ?",
                arrayOf(id),
            )
        }
        refresh()
    }

    override suspend fun refresh() {
        _items.value = queryApprovals()
    }

    private suspend fun resolve(
        id: String,
        status: ApprovalInboxStatus,
    ): ApprovalInboxItem? {
        val item = _items.value.firstOrNull { it.id == id } ?: return null
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                "approval_requests",
                ContentValues().apply {
                    put("status", status.name)
                    put("decided_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(id),
            )
        }
        auditTrailRepository.logApprovalDecision(
            item = item,
            approved = status == ApprovalInboxStatus.Approved,
        )
        refresh()
        return item.copy(status = status)
    }

    private suspend fun ensureSeedData() {
        withContext(Dispatchers.IO) {
            val cursor = databaseHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM approval_requests",
                null,
            )
            val count = cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            if (count > 0L) {
                return@withContext
            }

            val now = System.currentTimeMillis()
            insertApproval(
                title = "Share 12 files to Drive",
                action = "files.share",
                risk = ApprovalInboxRisk.High,
                summary = "Needs explicit approval before external transfer.",
                requestedAt = now,
            )
            insertApproval(
                title = "Rename invoice batch",
                action = "files.organize",
                risk = ApprovalInboxRisk.Medium,
                summary = "Model requested confirmation before bulk rename.",
                requestedAt = now - 5 * DateUtils.MINUTE_IN_MILLIS,
            )
        }
    }

    private fun insertApproval(
        title: String,
        action: String,
        risk: ApprovalInboxRisk,
        summary: String,
        requestedAt: Long,
        payloadJson: String? = null,
    ) {
        databaseHelper.writableDatabase.insert(
            "approval_requests",
            null,
            ContentValues().apply {
                put("id", "approval-${UUID.randomUUID()}")
                put("title", title)
                put("intent_action", action)
                put("assessed_risk", risk.name)
                put("summary", summary)
                put("intent_payload_json", payloadJson)
                put("requested_at", requestedAt)
                put("status", ApprovalInboxStatus.Pending.name)
            },
        )
    }

    private suspend fun queryApprovals(): List<ApprovalInboxItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val approvals = mutableListOf<ApprovalInboxItem>()
        databaseHelper.readableDatabase.query(
            "approval_requests",
            arrayOf(
                "id",
                "title",
                "intent_action",
                "assessed_risk",
                "summary",
                "intent_payload_json",
                "requested_at",
                "status",
            ),
            null,
            null,
            null,
            null,
            "requested_at DESC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val titleIndex = cursor.getColumnIndexOrThrow("title")
            val actionIndex = cursor.getColumnIndexOrThrow("intent_action")
            val riskIndex = cursor.getColumnIndexOrThrow("assessed_risk")
            val summaryIndex = cursor.getColumnIndexOrThrow("summary")
            val payloadIndex = cursor.getColumnIndexOrThrow("intent_payload_json")
            val requestedAtIndex = cursor.getColumnIndexOrThrow("requested_at")
            val statusIndex = cursor.getColumnIndexOrThrow("status")

            while (cursor.moveToNext()) {
                val requestedAt = cursor.getLong(requestedAtIndex)
                approvals += ApprovalInboxItem(
                    id = cursor.getString(idIndex),
                    title = cursor.getString(titleIndex),
                    action = cursor.getString(actionIndex),
                    risk = ApprovalInboxRisk.valueOf(cursor.getString(riskIndex)),
                    summary = cursor.getString(summaryIndex),
                    requestedAtLabel = DateUtils.getRelativeTimeSpanString(
                        requestedAt,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    status = ApprovalInboxStatus.valueOf(cursor.getString(statusIndex)),
                    payloadJson = cursor.getString(payloadIndex),
                )
            }
        }
        approvals
    }
}
