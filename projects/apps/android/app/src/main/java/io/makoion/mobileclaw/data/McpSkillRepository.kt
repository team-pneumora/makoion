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

enum class McpSkillStatus {
    Installed,
}

data class McpSkillRecord(
    val skillId: String,
    val title: String,
    val versionLabel: String,
    val summary: String,
    val capabilities: List<String>,
    val sourceEndpointId: String,
    val sourceLabel: String,
    val status: McpSkillStatus,
    val revision: Int,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

data class McpSkillSyncResult(
    val sourceLabel: String? = null,
    val updatedSkillCount: Int = 0,
    val totalInstalledCount: Int = 0,
    val summary: String,
)

internal data class SeededMcpSkill(
    val skillId: String,
    val title: String,
    val summary: String,
    val capabilities: List<String>,
)

interface McpSkillRepository {
    val skills: StateFlow<List<McpSkillRecord>>

    suspend fun syncFromMcpBridge(endpoint: ExternalEndpointProfileState?): McpSkillSyncResult

    suspend fun refresh()
}

internal fun defaultMcpSkillSeeds(): List<SeededMcpSkill> {
    return listOf(
        SeededMcpSkill(
            skillId = "mcp-skill-desktop-actions",
            title = "Desktop action bridge",
            summary = "Adds agent-side MCP wiring for companion action openings, workflow launches, and handoff callbacks.",
            capabilities = listOf("mcp.tools.list", "mcp.tools.call", "devices.app_open", "devices.workflow_run"),
        ),
        SeededMcpSkill(
            skillId = "mcp-skill-browser-research",
            title = "Browser research handoff",
            summary = "Adds MCP-routed browser research planning so the phone agent can stage browsing and extraction work behind chat.",
            capabilities = listOf("mcp.tools.list", "mcp.tools.call", "browser.navigate", "browser.extract"),
        ),
        SeededMcpSkill(
            skillId = "mcp-skill-api-ingest",
            title = "External API ingest",
            summary = "Adds MCP-routed API request and response ingestion hooks for future skill packages and third-party automations.",
            capabilities = listOf("mcp.tools.list", "mcp.tools.call", "api.request.plan", "api.response.ingest"),
        ),
    )
}

class PersistentMcpSkillRepository(
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
) : McpSkillRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _skills = MutableStateFlow<List<McpSkillRecord>>(emptyList())

    override val skills: StateFlow<List<McpSkillRecord>> = _skills.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun syncFromMcpBridge(endpoint: ExternalEndpointProfileState?): McpSkillSyncResult {
        if (endpoint == null || endpoint.category != ExternalEndpointCategory.McpServer) {
            return McpSkillSyncResult(
                summary = "No MCP bridge profile is currently available for skill sync.",
            )
        }
        if (endpoint.status != ExternalEndpointStatus.Connected) {
            return McpSkillSyncResult(
                sourceLabel = endpoint.endpointLabel ?: endpoint.displayName,
                summary = "The MCP bridge is not connected yet, so no skill bundle could be synced.",
            )
        }

        val sourceLabel = endpoint.endpointLabel ?: endpoint.displayName
        val now = System.currentTimeMillis()
        val seeds = defaultMcpSkillSeeds()
        withContext(Dispatchers.IO) {
            databaseHelper.ensureMcpSkillSchema()
            val db = databaseHelper.writableDatabase
            val existingById = querySkills(db).associateBy { it.skillId }
            seeds.forEach { seed ->
                val nextRevision = (existingById[seed.skillId]?.revision ?: 0) + 1
                db.insertWithOnConflict(
                    skillTable,
                    null,
                    ContentValues().apply {
                        put("skill_id", seed.skillId)
                        put("title", seed.title)
                        put("version_label", buildVersionLabel(now, nextRevision))
                        put("summary", seed.summary)
                        put("capabilities_json", JSONArray(seed.capabilities).toString())
                        put("source_endpoint_id", endpoint.endpointId)
                        put("source_label", sourceLabel)
                        put("status", McpSkillStatus.Installed.name)
                        put("revision", nextRevision)
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        refresh()
        auditTrailRepository.logAction(
            action = "mcp.skills.sync",
            result = "installed",
            details = "Synced ${seeds.size} MCP skill(s) from $sourceLabel.",
        )
        return McpSkillSyncResult(
            sourceLabel = sourceLabel,
            updatedSkillCount = seeds.size,
            totalInstalledCount = _skills.value.size,
            summary = "Synced ${seeds.size} MCP skill(s) from $sourceLabel.",
        )
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            databaseHelper.ensureMcpSkillSchema()
            _skills.value = querySkills(databaseHelper.readableDatabase)
        }
    }

    private fun querySkills(db: SQLiteDatabase): List<McpSkillRecord> {
        val now = System.currentTimeMillis()
        return db.query(
            skillTable,
            arrayOf(
                "skill_id",
                "title",
                "version_label",
                "summary",
                "capabilities_json",
                "source_endpoint_id",
                "source_label",
                "status",
                "revision",
                "updated_at",
            ),
            null,
            null,
            null,
            null,
            "updated_at DESC, title ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    add(
                        McpSkillRecord(
                            skillId = cursor.getString(cursor.getColumnIndexOrThrow("skill_id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            versionLabel = cursor.getString(cursor.getColumnIndexOrThrow("version_label")),
                            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                            capabilities = jsonArrayToList(
                                cursor.getString(cursor.getColumnIndexOrThrow("capabilities_json")),
                            ),
                            sourceEndpointId = cursor.getString(cursor.getColumnIndexOrThrow("source_endpoint_id")),
                            sourceLabel = cursor.getString(cursor.getColumnIndexOrThrow("source_label")),
                            status = runCatching {
                                McpSkillStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                            }.getOrDefault(McpSkillStatus.Installed),
                            revision = cursor.getInt(cursor.getColumnIndexOrThrow("revision")),
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

    private fun buildVersionLabel(
        timestamp: Long,
        revision: Int,
    ): String {
        val utcMonth = java.text.SimpleDateFormat("yyyy.MM", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(timestamp))
        return "$utcMonth-r$revision"
    }

    companion object {
        private const val skillTable = "mcp_skill_catalog"
    }
}
