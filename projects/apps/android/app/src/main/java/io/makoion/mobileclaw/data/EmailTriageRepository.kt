package io.makoion.mobileclaw.data

import android.content.ContentValues
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

enum class EmailTriageClassification {
    Promotional,
    Important,
    Review,
    Kept,
}

data class EmailTriageWriteRecord(
    val mailboxId: String,
    val automationId: String,
    val messageKey: String,
    val subject: String,
    val sender: String,
    val classification: EmailTriageClassification,
    val actionLabel: String,
    val reason: String,
    val snippet: String,
    val receivedAtEpochMillis: Long?,
)

data class EmailTriageRecord(
    val id: String,
    val mailboxId: String,
    val automationId: String,
    val messageKey: String,
    val subject: String,
    val sender: String,
    val classification: EmailTriageClassification,
    val actionLabel: String,
    val reason: String,
    val snippet: String,
    val receivedAtEpochMillis: Long?,
    val receivedAtLabel: String?,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

interface EmailTriageRepository {
    val records: StateFlow<List<EmailTriageRecord>>

    suspend fun replaceForAutomation(
        automationId: String,
        items: List<EmailTriageWriteRecord>,
    )

    suspend fun refresh()
}

class PersistentEmailTriageRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : EmailTriageRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _records = MutableStateFlow<List<EmailTriageRecord>>(emptyList())

    override val records: StateFlow<List<EmailTriageRecord>> = _records.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun replaceForAutomation(
        automationId: String,
        items: List<EmailTriageWriteRecord>,
    ) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            databaseHelper.ensureMailboxSchema()
            val db = databaseHelper.writableDatabase
            db.delete(
                triageTable,
                "automation_id = ?",
                arrayOf(automationId),
            )
            items.forEach { item ->
                db.insert(
                    triageTable,
                    null,
                    ContentValues().apply {
                        put("id", triageRecordId(item))
                        put("mailbox_id", item.mailboxId)
                        put("automation_id", item.automationId)
                        put("message_key", item.messageKey)
                        put("subject", item.subject)
                        put("sender", item.sender)
                        put("classification", item.classification.name)
                        put("action_label", item.actionLabel)
                        put("reason", item.reason)
                        put("snippet", item.snippet)
                        if (item.receivedAtEpochMillis == null) {
                            putNull("received_at")
                        } else {
                            put("received_at", item.receivedAtEpochMillis)
                        }
                        put("updated_at", now)
                    },
                )
            }
        }
        refresh()
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            databaseHelper.ensureMailboxSchema()
            val now = System.currentTimeMillis()
            _records.value = databaseHelper.readableDatabase.query(
                triageTable,
                arrayOf(
                    "id",
                    "mailbox_id",
                    "automation_id",
                    "message_key",
                    "subject",
                    "sender",
                    "classification",
                    "action_label",
                    "reason",
                    "snippet",
                    "received_at",
                    "updated_at",
                ),
                null,
                null,
                null,
                null,
                "updated_at DESC, received_at DESC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val receivedAt = cursor.optLong("received_at")
                        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                        add(
                            EmailTriageRecord(
                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                mailboxId = cursor.getString(cursor.getColumnIndexOrThrow("mailbox_id")),
                                automationId = cursor.getString(cursor.getColumnIndexOrThrow("automation_id")),
                                messageKey = cursor.getString(cursor.getColumnIndexOrThrow("message_key")),
                                subject = cursor.getString(cursor.getColumnIndexOrThrow("subject")),
                                sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                                classification = runCatching {
                                    EmailTriageClassification.valueOf(
                                        cursor.getString(cursor.getColumnIndexOrThrow("classification")),
                                    )
                                }.getOrDefault(EmailTriageClassification.Review),
                                actionLabel = cursor.getString(cursor.getColumnIndexOrThrow("action_label")),
                                reason = cursor.getString(cursor.getColumnIndexOrThrow("reason")),
                                snippet = cursor.getString(cursor.getColumnIndexOrThrow("snippet")),
                                receivedAtEpochMillis = receivedAt,
                                receivedAtLabel = receivedAt?.let { timestamp ->
                                    DateUtils.getRelativeTimeSpanString(
                                        timestamp,
                                        now,
                                        DateUtils.MINUTE_IN_MILLIS,
                                    ).toString()
                                },
                                updatedAtEpochMillis = updatedAt,
                                updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                                    updatedAt,
                                    now,
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val triageTable = "email_triage_records"
    }
}

private fun triageRecordId(item: EmailTriageWriteRecord): String {
    val stableFragment = item.messageKey.hashCode().toUInt().toString(16)
    return "triage-${item.automationId.takeLast(8)}-$stableFragment-${UUID.nameUUIDFromBytes(item.subject.toByteArray()).toString().take(8)}"
}

private fun android.database.Cursor.optLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return null
    }
    return getLong(index)
}
