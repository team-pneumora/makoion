package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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

enum class CloudDriveProviderKind(
    val providerId: String,
    val displayName: String,
) {
    GoogleDrive("gdrive", "Google Drive"),
    OneDrive("onedrive", "OneDrive"),
    Dropbox("dropbox", "Dropbox"),
}

enum class CloudDriveConnectionStatus {
    NeedsSetup,
    Staged,
    Connected,
}

data class CloudDriveConnectionState(
    val provider: CloudDriveProviderKind,
    val status: CloudDriveConnectionStatus,
    val summary: String,
    val supportedScopes: List<String>,
    val accountLabel: String? = null,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

internal data class SeededCloudDriveConnection(
    val provider: CloudDriveProviderKind,
    val status: CloudDriveConnectionStatus,
    val summary: String,
    val supportedScopes: List<String>,
    val accountLabel: String? = null,
)

interface CloudDriveConnectionRepository {
    val connections: StateFlow<List<CloudDriveConnectionState>>

    suspend fun stageConnection(provider: CloudDriveProviderKind)

    suspend fun markConnected(
        provider: CloudDriveProviderKind,
        accountLabel: String,
    )

    suspend fun resetConnection(provider: CloudDriveProviderKind)

    suspend fun refresh()
}

internal fun defaultCloudDriveSeeds(): List<SeededCloudDriveConnection> {
    return listOf(
        SeededCloudDriveConnection(
            provider = CloudDriveProviderKind.GoogleDrive,
            status = CloudDriveConnectionStatus.NeedsSetup,
            summary = "OAuth handoff and token vault wiring are not connected yet. This seed reserves Google Drive as the first Priority 2 cloud connector.",
            supportedScopes = listOf("drive.readonly", "drive.file"),
        ),
        SeededCloudDriveConnection(
            provider = CloudDriveProviderKind.OneDrive,
            status = CloudDriveConnectionStatus.NeedsSetup,
            summary = "Graph auth is not wired yet. This seed records the expected OneDrive connector shape for the phone agent.",
            supportedScopes = listOf("files.read", "files.readwrite"),
        ),
        SeededCloudDriveConnection(
            provider = CloudDriveProviderKind.Dropbox,
            status = CloudDriveConnectionStatus.NeedsSetup,
            summary = "Dropbox OAuth is still pending. This seed keeps the connector visible in Settings and the resource registry.",
            supportedScopes = listOf("files.metadata.read", "files.content.read"),
        ),
    )
}

class PersistentCloudDriveConnectionRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : CloudDriveConnectionRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connections = MutableStateFlow<List<CloudDriveConnectionState>>(emptyList())

    override val connections: StateFlow<List<CloudDriveConnectionState>> = _connections.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun stageConnection(provider: CloudDriveProviderKind) {
        updateConnection(
            provider = provider,
            status = CloudDriveConnectionStatus.Staged,
            summary = "Connector staging is recorded. OAuth handoff, refresh token storage, and background sync are still pending implementation.",
            accountLabel = null,
        )
    }

    override suspend fun markConnected(
        provider: CloudDriveProviderKind,
        accountLabel: String,
    ) {
        updateConnection(
            provider = provider,
            status = CloudDriveConnectionStatus.Connected,
            summary = "Mock-ready connector recorded for $accountLabel. Real OAuth and token vault integration still need to replace this placeholder state.",
            accountLabel = accountLabel,
        )
    }

    override suspend fun resetConnection(provider: CloudDriveProviderKind) {
        val seed = defaultCloudDriveSeeds().firstOrNull { it.provider == provider } ?: return
        updateConnection(
            provider = provider,
            status = seed.status,
            summary = seed.summary,
            accountLabel = seed.accountLabel,
        )
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            _connections.value = queryConnections(db)
        }
    }

    private suspend fun updateConnection(
        provider: CloudDriveProviderKind,
        status: CloudDriveConnectionStatus,
        summary: String,
        accountLabel: String?,
    ) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            val seed = defaultCloudDriveSeeds().firstOrNull { it.provider == provider } ?: return@withContext
            db.insertWithOnConflict(
                cloudDriveTable,
                null,
                ContentValues().apply {
                    put("provider_id", provider.providerId)
                    put("display_name", provider.displayName)
                    put("status", status.name)
                    put("summary", summary)
                    put("supported_scopes_json", JSONArray(seed.supportedScopes).toString())
                    put("account_label", accountLabel)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        refresh()
    }

    private fun ensureSeedData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        defaultCloudDriveSeeds().forEach { seed ->
            db.insertWithOnConflict(
                cloudDriveTable,
                null,
                ContentValues().apply {
                    put("provider_id", seed.provider.providerId)
                    put("display_name", seed.provider.displayName)
                    put("status", seed.status.name)
                    put("summary", seed.summary)
                    put("supported_scopes_json", JSONArray(seed.supportedScopes).toString())
                    put("account_label", seed.accountLabel)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun queryConnections(db: SQLiteDatabase): List<CloudDriveConnectionState> {
        val now = System.currentTimeMillis()
        return db.query(
            cloudDriveTable,
            arrayOf(
                "provider_id",
                "display_name",
                "status",
                "summary",
                "supported_scopes_json",
                "account_label",
                "updated_at",
            ),
            null,
            null,
            null,
            null,
            "display_name ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val provider = providerFromId(cursor.getString(cursor.getColumnIndexOrThrow("provider_id")))
                    val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    add(
                        CloudDriveConnectionState(
                            provider = provider,
                            status = runCatching {
                                CloudDriveConnectionStatus.valueOf(
                                    cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                )
                            }.getOrDefault(CloudDriveConnectionStatus.NeedsSetup),
                            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                            supportedScopes = jsonArrayToList(
                                cursor.getString(cursor.getColumnIndexOrThrow("supported_scopes_json")),
                            ),
                            accountLabel = cursor.getString(cursor.getColumnIndexOrThrow("account_label")),
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

    private fun providerFromId(providerId: String): CloudDriveProviderKind {
        return CloudDriveProviderKind.entries.firstOrNull { it.providerId == providerId }
            ?: CloudDriveProviderKind.GoogleDrive
    }

    private fun jsonArrayToList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }.filter { it.isNotBlank() }
    }

    companion object {
        private const val cloudDriveTable = "cloud_drive_connections"
    }
}
