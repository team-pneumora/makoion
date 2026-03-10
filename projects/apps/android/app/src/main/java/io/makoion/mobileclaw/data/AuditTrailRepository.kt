package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.content.Context
import android.text.format.DateUtils
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AuditTrailEvent(
    val id: String,
    val headline: String,
    val result: String,
    val details: String,
    val createdAtLabel: String,
)

interface AuditTrailRepository {
    val events: StateFlow<List<AuditTrailEvent>>

    suspend fun logAction(
        action: String,
        result: String,
        details: String,
    )

    suspend fun logApprovalDecision(
        item: ApprovalInboxItem,
        approved: Boolean,
    )

    suspend fun refresh()
}

class PersistentAuditTrailRepository(
    private val context: Context,
    private val databaseHelper: ShellDatabaseHelper,
) : AuditTrailRepository {
    private val _events = MutableStateFlow<List<AuditTrailEvent>>(emptyList())

    override val events: StateFlow<List<AuditTrailEvent>> = _events.asStateFlow()

    override suspend fun logAction(
        action: String,
        result: String,
        details: String,
    ) {
        insertEvent(
            action = action,
            result = result,
            details = details,
            requestId = null,
        )
        refresh()
    }

    override suspend fun logApprovalDecision(
        item: ApprovalInboxItem,
        approved: Boolean,
    ) {
        insertEvent(
            action = item.action,
            result = if (approved) "approved" else "denied",
            details = if (approved) {
                "User approved ${item.title}"
            } else {
                "User denied ${item.title}"
            },
            requestId = item.id,
        )
        refresh()
    }

    override suspend fun refresh() {
        _events.value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val events = mutableListOf<AuditTrailEvent>()
            databaseHelper.readableDatabase.query(
                "audit_events",
                arrayOf("id", "action", "result", "details", "created_at"),
                null,
                null,
                null,
                null,
                "created_at DESC",
                "12",
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val actionIndex = cursor.getColumnIndexOrThrow("action")
                val resultIndex = cursor.getColumnIndexOrThrow("result")
                val detailsIndex = cursor.getColumnIndexOrThrow("details")
                val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")

                while (cursor.moveToNext()) {
                    val createdAt = cursor.getLong(createdAtIndex)
                    events += AuditTrailEvent(
                        id = cursor.getString(idIndex),
                        headline = cursor.getString(actionIndex),
                        result = cursor.getString(resultIndex),
                        details = cursor.getString(detailsIndex),
                        createdAtLabel = DateUtils.getRelativeTimeSpanString(
                            createdAt,
                            now,
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                    )
                }
            }
            events
        }
    }

    private suspend fun insertEvent(
        action: String,
        result: String,
        details: String,
        requestId: String?,
    ) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.insert(
                "audit_events",
                null,
                ContentValues().apply {
                    put("id", "audit-${UUID.randomUUID()}")
                    put("request_id", requestId)
                    put("action", action)
                    put("result", result)
                    put("details", details)
                    put("created_at", now)
                },
            )
        }
    }
}
