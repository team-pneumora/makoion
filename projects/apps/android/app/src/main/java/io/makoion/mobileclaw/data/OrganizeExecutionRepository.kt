package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.text.format.DateUtils
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

data class PersistedOrganizeExecution(
    val approvalId: String,
    val result: OrganizeExecutionResult,
    val updatedAtLabel: String,
)

interface OrganizeExecutionRepository {
    val latest: StateFlow<PersistedOrganizeExecution?>

    suspend fun save(
        approvalId: String,
        result: OrganizeExecutionResult,
    ): PersistedOrganizeExecution

    suspend fun findByApprovalId(approvalId: String): PersistedOrganizeExecution?

    suspend fun refresh()
}

class PersistentOrganizeExecutionRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : OrganizeExecutionRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _latest = MutableStateFlow<PersistedOrganizeExecution?>(null)

    override val latest: StateFlow<PersistedOrganizeExecution?> = _latest.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun save(
        approvalId: String,
        result: OrganizeExecutionResult,
    ): PersistedOrganizeExecution {
        val saved = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("result_json", result.toJson())
                put("updated_at", now)
            }
            val updatedRows = databaseHelper.writableDatabase.update(
                "organize_executions",
                values,
                "approval_id = ?",
                arrayOf(approvalId),
            )
            if (updatedRows == 0) {
                values.put("approval_id", approvalId)
                values.put("created_at", now)
                databaseHelper.writableDatabase.insert(
                    "organize_executions",
                    null,
                    values,
                )
            }
            queryLatestByApprovalId(approvalId)
                ?: PersistedOrganizeExecution(
                    approvalId = approvalId,
                    result = result,
                    updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                        now,
                        now,
                        DateUtils.SECOND_IN_MILLIS,
                    ).toString(),
                )
        }
        _latest.value = saved
        return saved
    }

    override suspend fun refresh() {
        _latest.value = withContext(Dispatchers.IO) {
            queryLatest()
        }
    }

    override suspend fun findByApprovalId(approvalId: String): PersistedOrganizeExecution? {
        return withContext(Dispatchers.IO) {
            queryLatestByApprovalId(approvalId)
        }
    }

    private fun queryLatest(): PersistedOrganizeExecution? {
        databaseHelper.readableDatabase.query(
            "organize_executions",
            arrayOf("approval_id", "result_json", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursor.toPersistedExecution()
        }
    }

    private fun queryLatestByApprovalId(approvalId: String): PersistedOrganizeExecution? {
        databaseHelper.readableDatabase.query(
            "organize_executions",
            arrayOf("approval_id", "result_json", "updated_at"),
            "approval_id = ?",
            arrayOf(approvalId),
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return cursor.toPersistedExecution()
        }
    }

    private fun android.database.Cursor.toPersistedExecution(): PersistedOrganizeExecution? {
        val now = System.currentTimeMillis()
        val approvalId = getString(getColumnIndexOrThrow("approval_id"))
        val rawResult = getString(getColumnIndexOrThrow("result_json"))
        val updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
        val result = runCatching { rawResult.toOrganizeExecutionResult() }.getOrNull() ?: return null
        return PersistedOrganizeExecution(
            approvalId = approvalId,
            result = result,
            updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                updatedAt,
                now,
                DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
        )
    }
}

private fun OrganizeExecutionResult.toJson(): String {
    val entriesJson = JSONArray()
    entries.forEach { entry ->
        entriesJson.put(
            JSONObject()
                .put("file_id", entry.fileId)
                .put("file_name", entry.fileName)
                .put("mime_type", entry.mimeType)
                .put("source_label", entry.sourceLabel)
                .put("destination_folder", entry.destinationFolder)
                .put("source_uri", entry.sourceUri)
                .put("status", entry.status.name)
                .put("detail", entry.detail),
        )
    }
    return JSONObject()
        .put("processed_count", processedCount)
        .put("moved_count", movedCount)
        .put("copied_only_count", copiedOnlyCount)
        .put("delete_consent_required_count", deleteConsentRequiredCount)
        .put("failed_count", failedCount)
        .put("verified_count", verifiedCount)
        .put("destination_label", destinationLabel)
        .put("status_note", statusNote)
        .put("entries", entriesJson)
        .toString()
}

private fun String.toOrganizeExecutionResult(): OrganizeExecutionResult {
    val json = JSONObject(this)
    val entriesJson = json.optJSONArray("entries") ?: JSONArray()
    val entries = buildList {
        for (index in 0 until entriesJson.length()) {
            val entryJson = entriesJson.getJSONObject(index)
            add(
                OrganizeExecutionEntry(
                    fileId = entryJson.getString("file_id"),
                    fileName = entryJson.getString("file_name"),
                    mimeType = entryJson.optString("mime_type").takeIf { it.isNotBlank() },
                    sourceLabel = entryJson.getString("source_label"),
                    destinationFolder = entryJson.getString("destination_folder"),
                    sourceUri = entryJson.optString("source_uri").takeIf { it.isNotBlank() },
                    status = OrganizeExecutionStatus.valueOf(entryJson.getString("status")),
                    detail = entryJson.getString("detail"),
                ),
            )
        }
    }
    return OrganizeExecutionResult(
        processedCount = json.optInt("processed_count", entries.size),
        movedCount = json.optInt(
            "moved_count",
            entries.count { it.status == OrganizeExecutionStatus.Moved },
        ),
        copiedOnlyCount = json.optInt(
            "copied_only_count",
            entries.count { it.status == OrganizeExecutionStatus.CopiedOnly },
        ),
        deleteConsentRequiredCount = json.optInt(
            "delete_consent_required_count",
            entries.count { it.status == OrganizeExecutionStatus.DeleteConsentRequired },
        ),
        failedCount = json.optInt(
            "failed_count",
            entries.count { it.status == OrganizeExecutionStatus.Failed },
        ),
        verifiedCount = json.optInt(
            "verified_count",
            entries.count { it.status != OrganizeExecutionStatus.Failed },
        ),
        destinationLabel = json.optString("destination_label", "Makoion"),
        entries = entries,
        statusNote = json.optString("status_note").takeIf { it.isNotBlank() },
    )
}
