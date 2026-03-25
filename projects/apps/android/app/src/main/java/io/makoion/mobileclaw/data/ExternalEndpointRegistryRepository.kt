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

enum class ExternalEndpointCategory(
    val categoryId: String,
    val displayName: String,
) {
    McpServer("mcp_server", "MCP server"),
    BrowserAutomation("browser_automation", "Browser automation"),
    ThirdPartyApi("third_party_api", "Third-party API"),
}

enum class ExternalEndpointStatus {
    NeedsSetup,
    Staged,
    Connected,
}

data class ExternalEndpointProfileState(
    val endpointId: String,
    val displayName: String,
    val category: ExternalEndpointCategory,
    val status: ExternalEndpointStatus,
    val summary: String,
    val supportedCapabilities: List<String>,
    val endpointLabel: String? = null,
    val transportLabel: String? = null,
    val authLabel: String? = null,
    val toolNames: List<String> = emptyList(),
    val syncedSkillCount: Int = 0,
    val lastSyncAtEpochMillis: Long? = null,
    val lastSyncAtLabel: String? = null,
    val healthDetails: String? = null,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

internal data class SeededExternalEndpointProfile(
    val endpointId: String,
    val displayName: String,
    val category: ExternalEndpointCategory,
    val status: ExternalEndpointStatus,
    val summary: String,
    val supportedCapabilities: List<String>,
    val defaultConnectedLabel: String? = null,
    val defaultTransportLabel: String? = null,
    val defaultAuthLabel: String? = null,
    val defaultToolNames: List<String> = emptyList(),
    val defaultHealthDetails: String? = null,
)

data class ExternalEndpointConnectionSnapshot(
    val endpointLabel: String? = null,
    val summary: String? = null,
    val transportLabel: String? = null,
    val authLabel: String? = null,
    val toolNames: List<String> = emptyList(),
    val syncedSkillCount: Int? = null,
    val lastSyncAtEpochMillis: Long? = null,
    val healthDetails: String? = null,
)

interface ExternalEndpointRegistryRepository {
    val profiles: StateFlow<List<ExternalEndpointProfileState>>

    suspend fun stageEndpoint(endpointId: String)

    suspend fun markConnected(
        endpointId: String,
        connectionSnapshot: ExternalEndpointConnectionSnapshot = ExternalEndpointConnectionSnapshot(),
    )

    suspend fun resetEndpoint(endpointId: String)

    suspend fun refresh()
}

internal fun defaultExternalEndpointSeeds(): List<SeededExternalEndpointProfile> {
    return listOf(
        SeededExternalEndpointProfile(
            endpointId = "companion-mcp-bridge",
            displayName = "Companion MCP bridge",
            category = ExternalEndpointCategory.McpServer,
            status = ExternalEndpointStatus.NeedsSetup,
            summary = "This seed reserves the MCP bridge profile that will expose companion-side tools and file operations to the phone agent.",
            supportedCapabilities = listOf("mcp.connect", "mcp.tools.list", "mcp.tools.call", "mcp.skills.sync"),
            defaultConnectedLabel = "Companion desktop MCP relay",
            defaultTransportLabel = "Direct HTTP bridge",
            defaultAuthLabel = "Device trust + local policy gate",
            defaultToolNames = listOf(
                "desktop.session.notify",
                "desktop.app.open",
                "desktop.workflow.run",
                "files.transfer.receive",
                "browser.research.plan",
                "api.request.ingest",
            ),
            defaultHealthDetails = "Ready to advertise tool schemas to the phone agent and route guarded tool calls.",
        ),
        SeededExternalEndpointProfile(
            endpointId = "browser-automation-profile",
            displayName = "Browser automation profile",
            category = ExternalEndpointCategory.BrowserAutomation,
            status = ExternalEndpointStatus.NeedsSetup,
            summary = "This seed keeps browser navigation, extraction, and submission flows visible until a real browser automation executor is wired.",
            supportedCapabilities = listOf("browser.navigate", "browser.extract", "browser.submit"),
            defaultConnectedLabel = "Browser automation placeholder",
            defaultTransportLabel = "Embedded automation runner",
            defaultAuthLabel = "Local policy gate",
            defaultToolNames = listOf("browser.navigate", "browser.extract", "browser.submit"),
            defaultHealthDetails = "Ready to expose browser automation plans once a guarded executor is attached.",
        ),
        SeededExternalEndpointProfile(
            endpointId = "third-party-api-profile",
            displayName = "Third-party API profile",
            category = ExternalEndpointCategory.ThirdPartyApi,
            status = ExternalEndpointStatus.NeedsSetup,
            summary = "This seed tracks generic REST or webhook API profiles that the phone agent can attach after credential and policy wiring lands.",
            supportedCapabilities = listOf("api.profile.attach", "api.request.plan", "api.response.ingest"),
            defaultConnectedLabel = "Third-party API placeholder",
            defaultTransportLabel = "HTTPS profile",
            defaultAuthLabel = "Credential vault binding",
            defaultToolNames = listOf("api.request.plan", "api.response.ingest"),
            defaultHealthDetails = "Ready to bind request templates and response validators after credential setup.",
        ),
    )
}

class PersistentExternalEndpointRegistryRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : ExternalEndpointRegistryRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _profiles = MutableStateFlow<List<ExternalEndpointProfileState>>(emptyList())

    override val profiles: StateFlow<List<ExternalEndpointProfileState>> = _profiles.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun stageEndpoint(endpointId: String) {
        val seed = defaultExternalEndpointSeeds().firstOrNull { it.endpointId == endpointId } ?: return
        updateEndpoint(
            seed = seed,
            status = ExternalEndpointStatus.Staged,
            summary = when (seed.category) {
                ExternalEndpointCategory.McpServer ->
                    "MCP bridge staging is recorded. Transport handshake, tool schema sync, and policy gating still need to be wired."
                ExternalEndpointCategory.BrowserAutomation ->
                    "Browser automation staging is recorded. Session bootstrap, DOM extraction, and guarded action execution are still pending."
                ExternalEndpointCategory.ThirdPartyApi ->
                    "External API staging is recorded. Auth profile binding, request templates, and response validators still need to be added."
            },
            endpointLabel = null,
            transportLabel = null,
            authLabel = null,
            toolNames = emptyList(),
            syncedSkillCount = 0,
            lastSyncAtEpochMillis = null,
            healthDetails = null,
        )
    }

    override suspend fun markConnected(
        endpointId: String,
        connectionSnapshot: ExternalEndpointConnectionSnapshot,
    ) {
        val seed = defaultExternalEndpointSeeds().firstOrNull { it.endpointId == endpointId } ?: return
        val current = profiles.value.firstOrNull { it.endpointId == endpointId }
        val recordedLabel = connectionSnapshot.endpointLabel?.trim().takeUnless { it.isNullOrBlank() }
            ?: current?.endpointLabel
            ?: seed.defaultConnectedLabel
            ?: seed.displayName
        val transportLabel = connectionSnapshot.transportLabel?.trim().takeUnless { it.isNullOrBlank() }
            ?: current?.transportLabel
            ?: seed.defaultTransportLabel
        val authLabel = connectionSnapshot.authLabel?.trim().takeUnless { it.isNullOrBlank() }
            ?: current?.authLabel
            ?: seed.defaultAuthLabel
        val toolNames = connectionSnapshot.toolNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty {
                current?.toolNames?.takeIf { it.isNotEmpty() }
                    ?: seed.defaultToolNames
            }
        val syncedSkillCount = connectionSnapshot.syncedSkillCount
            ?: current?.syncedSkillCount
            ?: 0
        val lastSyncAtEpochMillis = connectionSnapshot.lastSyncAtEpochMillis
            ?: current?.lastSyncAtEpochMillis
        val healthDetails = connectionSnapshot.healthDetails?.trim().takeUnless { it.isNullOrBlank() }
            ?: current?.healthDetails
            ?: seed.defaultHealthDetails
        val summary = connectionSnapshot.summary?.trim().takeUnless { it.isNullOrBlank() }
            ?: buildConnectedSummary(
                seed = seed,
                endpointLabel = recordedLabel,
                transportLabel = transportLabel,
                toolNames = toolNames,
                syncedSkillCount = syncedSkillCount,
                healthDetails = healthDetails,
            )
        updateEndpoint(
            seed = seed,
            status = ExternalEndpointStatus.Connected,
            summary = summary,
            endpointLabel = recordedLabel,
            transportLabel = transportLabel,
            authLabel = authLabel,
            toolNames = toolNames,
            syncedSkillCount = syncedSkillCount,
            lastSyncAtEpochMillis = lastSyncAtEpochMillis,
            healthDetails = healthDetails,
        )
    }

    override suspend fun resetEndpoint(endpointId: String) {
        val seed = defaultExternalEndpointSeeds().firstOrNull { it.endpointId == endpointId } ?: return
        updateEndpoint(
            seed = seed,
            status = seed.status,
            summary = seed.summary,
            endpointLabel = null,
            transportLabel = null,
            authLabel = null,
            toolNames = emptyList(),
            syncedSkillCount = 0,
            lastSyncAtEpochMillis = null,
            healthDetails = null,
        )
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            _profiles.value = queryProfiles(db)
        }
    }

    private suspend fun updateEndpoint(
        seed: SeededExternalEndpointProfile,
        status: ExternalEndpointStatus,
        summary: String,
        endpointLabel: String?,
        transportLabel: String?,
        authLabel: String?,
        toolNames: List<String>,
        syncedSkillCount: Int,
        lastSyncAtEpochMillis: Long?,
        healthDetails: String?,
    ) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            db.insertWithOnConflict(
                endpointTable,
                null,
                ContentValues().apply {
                    put("endpoint_id", seed.endpointId)
                    put("display_name", seed.displayName)
                    put("category_id", seed.category.categoryId)
                    put("status", status.name)
                    put("summary", summary)
                    put(
                        "supported_capabilities_json",
                        JSONArray(seed.supportedCapabilities).toString(),
                    )
                    put("endpoint_label", endpointLabel)
                    put("transport_label", transportLabel)
                    put("auth_label", authLabel)
                    put("tool_names_json", JSONArray(toolNames).toString())
                    put("synced_skill_count", syncedSkillCount)
                    put("last_sync_at", lastSyncAtEpochMillis)
                    put("health_details", healthDetails)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        refresh()
    }

    private fun ensureSeedData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        defaultExternalEndpointSeeds().forEach { seed ->
            db.insertWithOnConflict(
                endpointTable,
                null,
                ContentValues().apply {
                    put("endpoint_id", seed.endpointId)
                    put("display_name", seed.displayName)
                    put("category_id", seed.category.categoryId)
                    put("status", seed.status.name)
                    put("summary", seed.summary)
                    put(
                        "supported_capabilities_json",
                        JSONArray(seed.supportedCapabilities).toString(),
                    )
                    putNull("endpoint_label")
                    putNull("transport_label")
                    putNull("auth_label")
                    put("tool_names_json", JSONArray().toString())
                    put("synced_skill_count", 0)
                    putNull("last_sync_at")
                    putNull("health_details")
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun queryProfiles(db: SQLiteDatabase): List<ExternalEndpointProfileState> {
        val now = System.currentTimeMillis()
        return db.query(
            endpointTable,
            arrayOf(
                "endpoint_id",
                "display_name",
                "category_id",
                "status",
                "summary",
                "supported_capabilities_json",
                "endpoint_label",
                "transport_label",
                "auth_label",
                "tool_names_json",
                "synced_skill_count",
                "last_sync_at",
                "health_details",
                "updated_at",
            ),
            null,
            null,
            null,
            null,
            "category_id ASC, display_name ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    add(
                        ExternalEndpointProfileState(
                            endpointId = cursor.getString(cursor.getColumnIndexOrThrow("endpoint_id")),
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                            category = categoryFromId(
                                cursor.getString(cursor.getColumnIndexOrThrow("category_id")),
                            ),
                            status = runCatching {
                                ExternalEndpointStatus.valueOf(
                                    cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                )
                            }.getOrDefault(ExternalEndpointStatus.NeedsSetup),
                            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                            supportedCapabilities = jsonArrayToList(
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow("supported_capabilities_json"),
                                ),
                            ),
                            endpointLabel = cursor.getString(cursor.getColumnIndexOrThrow("endpoint_label")),
                            transportLabel = cursor.getString(cursor.getColumnIndexOrThrow("transport_label")),
                            authLabel = cursor.getString(cursor.getColumnIndexOrThrow("auth_label")),
                            toolNames = jsonArrayToList(
                                cursor.getString(cursor.getColumnIndexOrThrow("tool_names_json")),
                            ),
                            syncedSkillCount = cursor.getInt(cursor.getColumnIndexOrThrow("synced_skill_count")),
                            lastSyncAtEpochMillis = cursor.takeIf {
                                !cursor.isNull(cursor.getColumnIndexOrThrow("last_sync_at"))
                            }?.getLong(cursor.getColumnIndexOrThrow("last_sync_at")),
                            lastSyncAtLabel = cursor.takeIf {
                                !cursor.isNull(cursor.getColumnIndexOrThrow("last_sync_at"))
                            }?.let {
                                val lastSyncAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_sync_at"))
                                DateUtils.getRelativeTimeSpanString(
                                    lastSyncAt,
                                    now,
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString()
                            },
                            healthDetails = cursor.getString(cursor.getColumnIndexOrThrow("health_details")),
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

    private fun categoryFromId(categoryId: String): ExternalEndpointCategory {
        return ExternalEndpointCategory.entries.firstOrNull { it.categoryId == categoryId }
            ?: ExternalEndpointCategory.ThirdPartyApi
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
        private const val endpointTable = "external_endpoint_profiles"
    }
}

private fun buildConnectedSummary(
    seed: SeededExternalEndpointProfile,
    endpointLabel: String,
    transportLabel: String?,
    toolNames: List<String>,
    syncedSkillCount: Int,
    healthDetails: String?,
): String {
    return buildString {
        append(endpointLabel)
        when (seed.category) {
            ExternalEndpointCategory.McpServer -> {
                append(" is connected")
                transportLabel?.let {
                    append(" over ")
                    append(it)
                }
                if (toolNames.isNotEmpty()) {
                    append(". ")
                    append(toolNames.size)
                    append(" MCP tool(s) are advertised")
                }
                if (syncedSkillCount > 0) {
                    append(" and ")
                    append(syncedSkillCount)
                    append(" skill package(s) are synced")
                }
                append(".")
            }
            ExternalEndpointCategory.BrowserAutomation -> {
                append(" is connected for guarded browser sessions")
                if (toolNames.isNotEmpty()) {
                    append(" with ")
                    append(toolNames.size)
                    append(" advertised action(s)")
                }
                append(".")
            }
            ExternalEndpointCategory.ThirdPartyApi -> {
                append(" is connected as an external API profile")
                if (toolNames.isNotEmpty()) {
                    append(" with ")
                    append(toolNames.size)
                    append(" advertised operation(s)")
                }
                append(".")
            }
        }
        healthDetails?.let {
            append(" ")
            append(it)
        }
    }
}
