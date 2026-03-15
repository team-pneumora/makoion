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

enum class ResourceRegistryPriority(
    val rank: Int,
    val label: String,
) {
    Priority1(1, "Priority 1"),
    Priority2(2, "Priority 2"),
    Priority3(3, "Priority 3"),
    Priority4(4, "Priority 4"),
}

enum class ResourceRegistryHealthState {
    Active,
    NeedsSetup,
    Planned,
    Degraded,
}

data class ResourceRegistryEntryState(
    val id: String,
    val resourceType: String,
    val title: String,
    val priority: ResourceRegistryPriority,
    val health: ResourceRegistryHealthState,
    val summary: String,
    val capabilities: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

internal data class ResourceRegistryEntrySeed(
    val id: String,
    val resourceType: String,
    val title: String,
    val priority: ResourceRegistryPriority,
    val health: ResourceRegistryHealthState,
    val summary: String,
    val capabilities: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

interface ResourceRegistryRepository {
    val entries: StateFlow<List<ResourceRegistryEntryState>>

    suspend fun syncSnapshot(
        fileIndexState: FileIndexState,
        pairedDevices: List<PairedDeviceState>,
        providerProfiles: List<ModelProviderProfileState>,
    )

    suspend fun refresh()
}

internal fun buildResourceRegistryEntries(
    fileIndexState: FileIndexState,
    pairedDevices: List<PairedDeviceState>,
    providerProfiles: List<ModelProviderProfileState>,
): List<ResourceRegistryEntrySeed> {
    val phoneLocalStorage = ResourceRegistryEntrySeed(
        id = resourceIdPhoneLocalStorage,
        resourceType = resourceTypePhoneLocalStorage,
        title = "Phone local storage",
        priority = ResourceRegistryPriority.Priority1,
        health = if (fileIndexState.permissionGranted || fileIndexState.indexedCount > 0) {
            ResourceRegistryHealthState.Active
        } else {
            ResourceRegistryHealthState.NeedsSetup
        },
        summary = if (fileIndexState.permissionGranted || fileIndexState.indexedCount > 0) {
            "${fileIndexState.indexedCount} indexed file(s) are currently available through ${fileIndexState.scanSource}."
        } else {
            "Grant media access so Makoion can search, summarize, and organize local files directly on the phone."
        },
        capabilities = if (fileIndexState.permissionGranted || fileIndexState.indexedCount > 0) {
            listOf("files.search", "files.summarize", "files.organize", "files.transfer")
        } else {
            emptyList()
        },
        metadata = mapOf(
            "permissionGranted" to fileIndexState.permissionGranted.toString(),
            "indexedCount" to fileIndexState.indexedCount.toString(),
            "scanSource" to fileIndexState.scanSource,
        ),
    )
    val documentRoots = ResourceRegistryEntrySeed(
        id = resourceIdPhoneDocumentRoots,
        resourceType = resourceTypePhoneDocumentRoots,
        title = "Attached document roots",
        priority = ResourceRegistryPriority.Priority1,
        health = if (fileIndexState.documentTreeCount > 0) {
            ResourceRegistryHealthState.Active
        } else {
            ResourceRegistryHealthState.NeedsSetup
        },
        summary = if (fileIndexState.documentTreeCount > 0) {
            "${fileIndexState.documentTreeCount} SAF document root(s) are attached: ${fileIndexState.documentRoots.joinToString().ifBlank { "attached" }}."
        } else {
            "Attach SAF document roots for deeper folders that MediaStore does not cover."
        },
        capabilities = if (fileIndexState.documentTreeCount > 0) {
            listOf("files.search", "files.summarize", "files.organize")
        } else {
            emptyList()
        },
        metadata = buildMap {
            put("documentTreeCount", fileIndexState.documentTreeCount.toString())
            if (fileIndexState.documentRoots.isNotEmpty()) {
                put("documentRoots", fileIndexState.documentRoots.joinToString("|"))
            }
        },
    )
    val cloudDrives = ResourceRegistryEntrySeed(
        id = resourceIdCloudDrives,
        resourceType = resourceTypeCloudDrives,
        title = "Cloud drives",
        priority = ResourceRegistryPriority.Priority2,
        health = ResourceRegistryHealthState.Planned,
        summary = "Google Drive, OneDrive, and Dropbox connectors are planned as the next resource tier after phone storage.",
        metadata = mapOf(
            "plannedConnectors" to "gdrive|onedrive|dropbox",
        ),
    )
    val companionCapabilities = pairedDevices.flatMap { it.capabilities }
        .distinct()
        .sorted()
    val companions = ResourceRegistryEntrySeed(
        id = resourceIdExternalCompanions,
        resourceType = resourceTypeExternalCompanions,
        title = "External companions",
        priority = ResourceRegistryPriority.Priority3,
        health = if (pairedDevices.isNotEmpty()) {
            ResourceRegistryHealthState.Active
        } else {
            ResourceRegistryHealthState.NeedsSetup
        },
        summary = if (pairedDevices.isNotEmpty()) {
            val directHttpCount = pairedDevices.count { it.transportMode == DeviceTransportMode.DirectHttp }
            buildString {
                append("${pairedDevices.size} companion device(s) are paired")
                append("; $directHttpCount direct HTTP target(s) are available.")
            }
        } else {
            "Pair a desktop or external computer so the phone agent can hand off remote actions and file transfers."
        },
        capabilities = companionCapabilities,
        metadata = mapOf(
            "pairedCount" to pairedDevices.size.toString(),
            "directHttpCount" to pairedDevices.count { it.transportMode == DeviceTransportMode.DirectHttp }.toString(),
        ),
    )
    val configuredProviderCount = providerProfiles.count {
        it.credentialStatus == ModelProviderCredentialStatus.Stored
    }
    val enabledProviderCount = providerProfiles.count(ModelProviderProfileState::enabled)
    val defaultProvider = providerProfiles.firstOrNull { it.enabled && it.isDefault }
        ?: providerProfiles.firstOrNull(ModelProviderProfileState::enabled)
    val aiModelProviders = ResourceRegistryEntrySeed(
        id = resourceIdAiModelProviders,
        resourceType = resourceTypeAiModelProviders,
        title = "AI model providers",
        priority = ResourceRegistryPriority.Priority4,
        health = when {
            providerProfiles.any { it.enabled && it.credentialStatus == ModelProviderCredentialStatus.Stored } ->
                ResourceRegistryHealthState.Active
            providerProfiles.isNotEmpty() -> ResourceRegistryHealthState.NeedsSetup
            else -> ResourceRegistryHealthState.Planned
        },
        summary = if (providerProfiles.isEmpty()) {
            "Provider profiles are not seeded yet."
        } else {
            buildString {
                append("${providerProfiles.size} provider profile(s) are available")
                defaultProvider?.let { profile ->
                    append("; default is ")
                    append(profile.displayName)
                    append(" / ")
                    append(profile.selectedModel)
                }
                append(". Configured credential count: ")
                append(configuredProviderCount)
                append(".")
            }
        },
        capabilities = buildList {
            if (configuredProviderCount > 0) {
                add("model.route")
                add("model.provider.select")
            }
            if (enabledProviderCount > 1 && configuredProviderCount > 0) {
                add("model.route.failover")
            }
        },
        metadata = buildMap {
            put("profileCount", providerProfiles.size.toString())
            put("enabledProviderCount", enabledProviderCount.toString())
            put("configuredProviderCount", configuredProviderCount.toString())
            defaultProvider?.let {
                put("defaultProviderId", it.providerId)
                put("defaultModel", it.selectedModel)
            }
        },
    )
    val mcpApiEndpoints = ResourceRegistryEntrySeed(
        id = resourceIdMcpApiEndpoints,
        resourceType = resourceTypeMcpApiEndpoints,
        title = "MCP and API endpoints",
        priority = ResourceRegistryPriority.Priority4,
        health = ResourceRegistryHealthState.Planned,
        summary = "MCP servers, browser automation, and third-party API profiles will join the resource registry after provider credentials are secured.",
        metadata = mapOf(
            "plannedCapabilities" to "mcp.connect|api.profile.attach|browser.automation",
        ),
    )
    return listOf(
        phoneLocalStorage,
        documentRoots,
        cloudDrives,
        companions,
        aiModelProviders,
        mcpApiEndpoints,
    )
}

class PersistentResourceRegistryRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : ResourceRegistryRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow<List<ResourceRegistryEntryState>>(emptyList())

    override val entries: StateFlow<List<ResourceRegistryEntryState>> = _entries.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun syncSnapshot(
        fileIndexState: FileIndexState,
        pairedDevices: List<PairedDeviceState>,
        providerProfiles: List<ModelProviderProfileState>,
    ) {
        val seeds = buildResourceRegistryEntries(
            fileIndexState = fileIndexState,
            pairedDevices = pairedDevices,
            providerProfiles = providerProfiles,
        )
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                seeds.forEach { seed ->
                    db.insertWithOnConflict(
                        resourceRegistryTable,
                        null,
                        ContentValues().apply {
                            put("id", seed.id)
                            put("resource_type", seed.resourceType)
                            put("title", seed.title)
                            put("priority_rank", seed.priority.rank)
                            put("priority_label", seed.priority.label)
                            put("health_state", seed.health.name)
                            put("summary", seed.summary)
                            put("capabilities_json", JSONArray(seed.capabilities).toString())
                            put("metadata_json", JSONObject(seed.metadata).toString())
                            put("updated_at", now)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                val ids = seeds.map(ResourceRegistryEntrySeed::id)
                if (ids.isEmpty()) {
                    db.delete(resourceRegistryTable, null, null)
                } else {
                    val placeholders = ids.joinToString(",") { "?" }
                    db.delete(
                        resourceRegistryTable,
                        "id NOT IN ($placeholders)",
                        ids.toTypedArray(),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        refresh()
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            _entries.value = databaseHelper.readableDatabase.query(
                resourceRegistryTable,
                arrayOf(
                    "id",
                    "resource_type",
                    "title",
                    "priority_rank",
                    "priority_label",
                    "health_state",
                    "summary",
                    "capabilities_json",
                    "metadata_json",
                    "updated_at",
                ),
                null,
                null,
                null,
                null,
                "priority_rank ASC, title ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                        add(
                            ResourceRegistryEntryState(
                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                resourceType = cursor.getString(cursor.getColumnIndexOrThrow("resource_type")),
                                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                                priority = cursor.getInt(cursor.getColumnIndexOrThrow("priority_rank"))
                                    .let(::priorityFromRank),
                                health = cursor.getString(cursor.getColumnIndexOrThrow("health_state"))
                                    .let(::healthFromName),
                                summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                                capabilities = jsonArrayToList(
                                    cursor.getString(cursor.getColumnIndexOrThrow("capabilities_json")),
                                ),
                                metadata = jsonObjectToMap(
                                    cursor.getString(cursor.getColumnIndexOrThrow("metadata_json")),
                                ),
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

    private fun jsonObjectToMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return emptyMap()
        }
        return buildMap {
            json.keys().forEach { key ->
                put(key, json.optString(key))
            }
        }
    }

    private fun priorityFromRank(rank: Int): ResourceRegistryPriority {
        return ResourceRegistryPriority.entries.firstOrNull { it.rank == rank }
            ?: ResourceRegistryPriority.Priority4
    }

    private fun healthFromName(name: String): ResourceRegistryHealthState {
        return runCatching { ResourceRegistryHealthState.valueOf(name) }
            .getOrDefault(ResourceRegistryHealthState.Planned)
    }

    companion object {
        private const val resourceRegistryTable = "resource_registry_entries"
    }
}

const val resourceIdPhoneLocalStorage = "phone-local-storage"
const val resourceIdPhoneDocumentRoots = "attached-document-roots"
const val resourceIdCloudDrives = "cloud-drives"
const val resourceIdExternalCompanions = "external-companions"
const val resourceIdAiModelProviders = "ai-model-providers"
const val resourceIdMcpApiEndpoints = "mcp-api-endpoints"

const val resourceTypePhoneLocalStorage = "phone.local_storage"
const val resourceTypePhoneDocumentRoots = "phone.document_roots"
const val resourceTypeCloudDrives = "cloud.drives"
const val resourceTypeExternalCompanions = "external.companions"
const val resourceTypeAiModelProviders = "model.providers"
const val resourceTypeMcpApiEndpoints = "mcp.api_endpoints"
