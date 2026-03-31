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

enum class MailboxConnectionStatus {
    NeedsSetup,
    Staged,
    Connected,
}

data class MailboxConnectionConfig(
    val mailboxId: String = primaryMailboxConnectionId,
    val displayName: String = "Primary mailbox",
    val host: String,
    val port: Int = 993,
    val username: String,
    val inboxFolder: String = "INBOX",
    val promotionsFolder: String = "Promotions",
)

data class MailboxConnectionProfileState(
    val mailboxId: String,
    val displayName: String,
    val status: MailboxConnectionStatus,
    val summary: String,
    val host: String,
    val port: Int,
    val username: String,
    val inboxFolder: String,
    val promotionsFolder: String,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
    val lastSyncAtEpochMillis: Long? = null,
    val lastSyncAtLabel: String? = null,
    val lastError: String? = null,
) {
    val connectionLabel: String
        get() = "$username@$host:$port"
}

interface MailboxConnectionRepository {
    val profiles: StateFlow<List<MailboxConnectionProfileState>>

    suspend fun upsertMailbox(
        config: MailboxConnectionConfig,
        status: MailboxConnectionStatus,
        summary: String,
        lastError: String? = null,
        lastSyncAtEpochMillis: Long? = null,
    ): MailboxConnectionProfileState

    suspend fun primaryMailbox(): MailboxConnectionProfileState?

    suspend fun refresh()
}

class PersistentMailboxConnectionRepository(
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
) : MailboxConnectionRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _profiles = MutableStateFlow<List<MailboxConnectionProfileState>>(emptyList())

    override val profiles: StateFlow<List<MailboxConnectionProfileState>> = _profiles.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun upsertMailbox(
        config: MailboxConnectionConfig,
        status: MailboxConnectionStatus,
        summary: String,
        lastError: String?,
        lastSyncAtEpochMillis: Long?,
    ): MailboxConnectionProfileState {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            databaseHelper.ensureMailboxSchema()
            databaseHelper.writableDatabase.insertWithOnConflict(
                mailboxTable,
                null,
                ContentValues().apply {
                    put("mailbox_id", config.mailboxId)
                    put("display_name", config.displayName)
                    put("status", status.name)
                    put("summary", summary)
                    put("host", config.host)
                    put("port", config.port)
                    put("username", config.username)
                    put("inbox_folder", config.inboxFolder)
                    put("promotions_folder", config.promotionsFolder)
                    put("updated_at", now)
                    if (lastSyncAtEpochMillis == null) {
                        putNull("last_sync_at")
                    } else {
                        put("last_sync_at", lastSyncAtEpochMillis)
                    }
                    if (lastError.isNullOrBlank()) {
                        putNull("last_error")
                    } else {
                        put("last_error", lastError)
                    }
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        auditTrailRepository.logAction(
            action = "mailbox.connect",
            result = status.name.lowercase(),
            details = "Mailbox ${config.displayName} recorded as ${status.name.lowercase()} (${config.username}@${config.host}:${config.port}).",
            requestId = config.mailboxId,
        )
        refresh()
        return _profiles.value.first { it.mailboxId == config.mailboxId }
    }

    override suspend fun primaryMailbox(): MailboxConnectionProfileState? {
        refresh()
        return _profiles.value.firstOrNull { it.mailboxId == primaryMailboxConnectionId }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            databaseHelper.ensureMailboxSchema()
            val now = System.currentTimeMillis()
            _profiles.value = databaseHelper.readableDatabase.query(
                mailboxTable,
                arrayOf(
                    "mailbox_id",
                    "display_name",
                    "status",
                    "summary",
                    "host",
                    "port",
                    "username",
                    "inbox_folder",
                    "promotions_folder",
                    "updated_at",
                    "last_sync_at",
                    "last_error",
                ),
                null,
                null,
                null,
                null,
                "updated_at DESC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                        val lastSyncAt = cursor.optLong("last_sync_at")
                        add(
                            MailboxConnectionProfileState(
                                mailboxId = cursor.getString(cursor.getColumnIndexOrThrow("mailbox_id")),
                                displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                                status = runCatching {
                                    MailboxConnectionStatus.valueOf(
                                        cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                    )
                                }.getOrDefault(MailboxConnectionStatus.NeedsSetup),
                                summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                                host = cursor.getString(cursor.getColumnIndexOrThrow("host")),
                                port = cursor.getInt(cursor.getColumnIndexOrThrow("port")),
                                username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
                                inboxFolder = cursor.getString(cursor.getColumnIndexOrThrow("inbox_folder")),
                                promotionsFolder = cursor.getString(cursor.getColumnIndexOrThrow("promotions_folder")),
                                updatedAtEpochMillis = updatedAt,
                                updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                                    updatedAt,
                                    now,
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                                lastSyncAtEpochMillis = lastSyncAt,
                                lastSyncAtLabel = lastSyncAt?.let { timestamp ->
                                    DateUtils.getRelativeTimeSpanString(
                                        timestamp,
                                        now,
                                        DateUtils.MINUTE_IN_MILLIS,
                                    ).toString()
                                },
                                lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error")),
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val mailboxTable = "mailbox_connection_profiles"
    }
}

private fun android.database.Cursor.optLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return null
    }
    return getLong(index)
}

const val primaryMailboxConnectionId = "primary-mailbox"
