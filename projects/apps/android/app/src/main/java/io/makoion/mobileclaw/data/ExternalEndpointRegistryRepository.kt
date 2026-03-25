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
import org.json.JSONObject

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

data class McpToolSchemaProfile(
    val name: String,
    val title: String,
    val summary: String,
    val inputSchemaSummary: String? = null,
    val requiresConfirmation: Boolean = false,
)

data class McpSkillBundleProfile(
    val bundleId: String,
    val title: String,
    val summary: String,
    val toolNames: List<String>,
    val versionLabel: String? = null,
)

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
    val toolSchemas: List<McpToolSchemaProfile> = emptyList(),
    val skillBundles: List<McpSkillBundleProfile> = emptyList(),
    val workflowIds: List<String> = emptyList(),
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
    val defaultToolSchemas: List<McpToolSchemaProfile> = emptyList(),
    val defaultSkillBundles: List<McpSkillBundleProfile> = emptyList(),
    val defaultWorkflowIds: List<String> = emptyList(),
    val defaultHealthDetails: String? = null,
)

data class ExternalEndpointConnectionSnapshot(
    val endpointLabel: String? = null,
    val summary: String? = null,
    val transportLabel: String? = null,
    val authLabel: String? = null,
    val toolNames: List<String> = emptyList(),
    val toolSchemas: List<McpToolSchemaProfile> = emptyList(),
    val skillBundles: List<McpSkillBundleProfile> = emptyList(),
    val workflowIds: List<String> = emptyList(),
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
            defaultToolSchemas = listOf(
                McpToolSchemaProfile(
                    name = "desktop.session.notify",
                    title = "Desktop Session Notify",
                    summary = "Show a guarded desktop notification on the paired companion.",
                    inputSchemaSummary = "title:string, body:string",
                ),
                McpToolSchemaProfile(
                    name = "desktop.app.open",
                    title = "Desktop App Open",
                    summary = "Open an approved desktop surface such as inbox or latest transfer.",
                    inputSchemaSummary = "target_kind:string, target_label?:string, open_mode:string",
                    requiresConfirmation = true,
                ),
                McpToolSchemaProfile(
                    name = "desktop.workflow.run",
                    title = "Desktop Workflow Run",
                    summary = "Run a named guarded desktop workflow through the companion.",
                    inputSchemaSummary = "workflow_id:string, workflow_label?:string, run_mode:string",
                    requiresConfirmation = true,
                ),
                McpToolSchemaProfile(
                    name = "files.transfer.receive",
                    title = "Receive Transfer Payload",
                    summary = "Materialize incoming transfer payloads into the companion inbox.",
                    inputSchemaSummary = "transfer archive payload",
                ),
                McpToolSchemaProfile(
                    name = "browser.research.plan",
                    title = "Browser Research Plan",
                    summary = "Stage a browser research brief for later guarded execution.",
                    inputSchemaSummary = "topic:string, objective:string",
                ),
                McpToolSchemaProfile(
                    name = "api.request.ingest",
                    title = "API Request Ingest",
                    summary = "Register API ingest work for later guarded execution and parsing.",
                    inputSchemaSummary = "request template + auth binding",
                ),
            ),
            defaultSkillBundles = listOf(
                McpSkillBundleProfile(
                    bundleId = "desktop_action_bridge",
                    title = "Desktop action bridge",
                    summary = "Routes notification, open, and workflow actions through the paired companion.",
                    toolNames = listOf("desktop.session.notify", "desktop.app.open", "desktop.workflow.run"),
                    versionLabel = "2026.03",
                ),
                McpSkillBundleProfile(
                    bundleId = "browser_research_handoff",
                    title = "Browser research handoff",
                    summary = "Stages browser research work through the companion MCP connector.",
                    toolNames = listOf("browser.research.plan"),
                    versionLabel = "2026.03",
                ),
                McpSkillBundleProfile(
                    bundleId = "external_api_ingest",
                    title = "External API ingest",
                    summary = "Registers API ingestion and response handoff through the companion.",
                    toolNames = listOf("api.request.ingest"),
                    versionLabel = "2026.03",
                ),
            ),
            defaultWorkflowIds = listOf(
                "open_latest_transfer",
                "open_actions_folder",
                "open_latest_action",
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
            defaultToolSchemas = listOf(
                McpToolSchemaProfile(
                    name = "browser.navigate",
                    title = "Browser Navigate",
                    summary = "Navigate a guarded browser session to a target URL.",
                    inputSchemaSummary = "url:string",
                ),
                McpToolSchemaProfile(
                    name = "browser.extract",
                    title = "Browser Extract",
                    summary = "Extract structured content from the current page.",
                    inputSchemaSummary = "selector:string or extraction brief",
                ),
                McpToolSchemaProfile(
                    name = "browser.submit",
                    title = "Browser Submit",
                    summary = "Submit a guarded browser form or interaction plan.",
                    inputSchemaSummary = "action plan",
                    requiresConfirmation = true,
                ),
            ),
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
            defaultToolSchemas = listOf(
                McpToolSchemaProfile(
                    name = "api.request.plan",
                    title = "API Request Plan",
                    summary = "Prepare a guarded API request profile before execution.",
                    inputSchemaSummary = "method, url, headers, body template",
                ),
                McpToolSchemaProfile(
                    name = "api.response.ingest",
                    title = "API Response Ingest",
                    summary = "Parse and store API responses into the agent state model.",
                    inputSchemaSummary = "response body + validation rules",
                ),
            ),
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
            toolSchemas = emptyList(),
            skillBundles = emptyList(),
            workflowIds = emptyList(),
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
        val toolSchemas = connectionSnapshot.toolSchemas
            .ifEmpty {
                current?.toolSchemas?.takeIf { it.isNotEmpty() }
                    ?: seed.defaultToolSchemas
            }
        val skillBundles = connectionSnapshot.skillBundles
            .ifEmpty {
                current?.skillBundles?.takeIf { it.isNotEmpty() }
                    ?: seed.defaultSkillBundles
            }
        val workflowIds = connectionSnapshot.workflowIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty {
                current?.workflowIds?.takeIf { it.isNotEmpty() }
                    ?: seed.defaultWorkflowIds
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
                toolSchemas = toolSchemas,
                skillBundles = skillBundles,
                workflowIds = workflowIds,
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
            toolSchemas = toolSchemas,
            skillBundles = skillBundles,
            workflowIds = workflowIds,
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
            toolSchemas = emptyList(),
            skillBundles = emptyList(),
            workflowIds = emptyList(),
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
        toolSchemas: List<McpToolSchemaProfile>,
        skillBundles: List<McpSkillBundleProfile>,
        workflowIds: List<String>,
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
                    put("tool_schemas_json", toolSchemasToJson(toolSchemas).toString())
                    put("skill_bundles_json", skillBundlesToJson(skillBundles).toString())
                    put("workflow_ids_json", JSONArray(workflowIds).toString())
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
                    put("tool_schemas_json", JSONArray().toString())
                    put("skill_bundles_json", JSONArray().toString())
                    put("workflow_ids_json", JSONArray().toString())
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
                "tool_schemas_json",
                "skill_bundles_json",
                "workflow_ids_json",
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
                            toolSchemas = jsonArrayToToolSchemas(
                                cursor.getString(cursor.getColumnIndexOrThrow("tool_schemas_json")),
                            ),
                            skillBundles = jsonArrayToSkillBundles(
                                cursor.getString(cursor.getColumnIndexOrThrow("skill_bundles_json")),
                            ),
                            workflowIds = jsonArrayToList(
                                cursor.getString(cursor.getColumnIndexOrThrow("workflow_ids_json")),
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

    private fun toolSchemasToJson(toolSchemas: List<McpToolSchemaProfile>): JSONArray {
        return JSONArray(
            toolSchemas.map { schema ->
                JSONObject().apply {
                    put("name", schema.name)
                    put("title", schema.title)
                    put("summary", schema.summary)
                    put("input_schema_summary", schema.inputSchemaSummary)
                    put("requires_confirmation", schema.requiresConfirmation)
                }
            },
        )
    }

    private fun skillBundlesToJson(skillBundles: List<McpSkillBundleProfile>): JSONArray {
        return JSONArray(
            skillBundles.map { bundle ->
                JSONObject().apply {
                    put("bundle_id", bundle.bundleId)
                    put("title", bundle.title)
                    put("summary", bundle.summary)
                    put("tool_names", JSONArray(bundle.toolNames))
                    put("version_label", bundle.versionLabel)
                }
            },
        )
    }

    private fun jsonArrayToToolSchemas(raw: String?): List<McpToolSchemaProfile> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
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

    private fun jsonArrayToSkillBundles(raw: String?): List<McpSkillBundleProfile> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val bundleId = json.optString("bundle_id").trim()
                val title = json.optString("title").trim()
                val summary = json.optString("summary").trim()
                if (bundleId.isBlank() || title.isBlank() || summary.isBlank()) {
                    continue
                }
                add(
                    McpSkillBundleProfile(
                        bundleId = bundleId,
                        title = title,
                        summary = summary,
                        toolNames = jsonArrayToList(json.optJSONArray("tool_names")?.toString()),
                        versionLabel = json.optString("version_label").trim().takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
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
    toolSchemas: List<McpToolSchemaProfile>,
    skillBundles: List<McpSkillBundleProfile>,
    workflowIds: List<String>,
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
                if (toolSchemas.isNotEmpty()) {
                    append(" with ")
                    append(toolSchemas.size)
                    append(" schema profile(s)")
                }
                if (skillBundles.isNotEmpty()) {
                    append(" and ")
                    append(skillBundles.size)
                    append(" skill bundle(s)")
                }
                if (syncedSkillCount > 0) {
                    append(". ")
                    append(syncedSkillCount)
                    append(" skill package(s) are synced")
                }
                if (workflowIds.isNotEmpty()) {
                    append(". ")
                    append(workflowIds.size)
                    append(" workflow target(s) are available")
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
