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

enum class ModelProviderCredentialStatus {
    Missing,
    Stored,
}

data class ModelProviderProfileState(
    val providerId: String,
    val displayName: String,
    val supportedModels: List<String>,
    val defaultModel: String,
    val selectedModel: String,
    val enabled: Boolean,
    val isDefault: Boolean,
    val credentialStatus: ModelProviderCredentialStatus,
    val credentialLabel: String? = null,
    val baseUrl: String? = null,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

data class AgentModelPreference(
    val preferredProviderId: String? = null,
    val preferredProviderLabel: String? = null,
    val preferredModel: String? = null,
    val enabledProviderIds: List<String> = emptyList(),
    val configuredProviderIds: List<String> = emptyList(),
)

interface ModelProviderSettingsRepository {
    val profiles: StateFlow<List<ModelProviderProfileState>>

    suspend fun setProviderEnabled(
        providerId: String,
        enabled: Boolean,
    )

    suspend fun setDefaultProvider(providerId: String)

    suspend fun selectModel(
        providerId: String,
        model: String,
    )

    suspend fun storeCredential(
        providerId: String,
        secret: String,
    )

    suspend fun revealCredential(providerId: String): String?

    suspend fun clearCredential(providerId: String)

    suspend fun refresh()
}

internal data class SeededModelProviderProfile(
    val providerId: String,
    val displayName: String,
    val supportedModels: List<String>,
    val defaultModel: String,
    val selectedModel: String,
    val enabled: Boolean,
    val isDefault: Boolean,
    val credentialStatus: ModelProviderCredentialStatus,
    val credentialLabel: String? = null,
    val baseUrl: String? = null,
)

internal fun defaultModelProviderSeeds(): List<SeededModelProviderProfile> {
    return listOf(
        SeededModelProviderProfile(
            providerId = "openai",
            displayName = "OpenAI",
            supportedModels = listOf("gpt-5.4", "gpt-5.4-pro", "gpt-5-mini", "gpt-5-nano", "gpt-4.1"),
            defaultModel = "gpt-5.4",
            selectedModel = "gpt-5.4",
            enabled = true,
            isDefault = true,
            credentialStatus = ModelProviderCredentialStatus.Missing,
        ),
        SeededModelProviderProfile(
            providerId = "anthropic",
            displayName = "Anthropic",
            supportedModels = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5"),
            defaultModel = "claude-sonnet-4-6",
            selectedModel = "claude-sonnet-4-6",
            enabled = true,
            isDefault = false,
            credentialStatus = ModelProviderCredentialStatus.Missing,
        ),
        SeededModelProviderProfile(
            providerId = "google-gemini",
            displayName = "Google Gemini",
            supportedModels = listOf("gemini-2.0-flash", "gemini-2.0-pro", "gemini-1.5-pro"),
            defaultModel = "gemini-2.0-flash",
            selectedModel = "gemini-2.0-flash",
            enabled = true,
            isDefault = false,
            credentialStatus = ModelProviderCredentialStatus.Missing,
        ),
    )
}

internal fun normalizeSeedSelectedModel(
    selectedModel: String,
    seed: SeededModelProviderProfile,
): String {
    return selectedModel.takeIf { it in seed.supportedModels } ?: seed.defaultModel
}

internal fun resolveAgentModelPreference(
    profiles: List<ModelProviderProfileState>,
): AgentModelPreference {
    val enabledProfiles = profiles.filter(ModelProviderProfileState::enabled)
    val configuredProfiles = profiles.filter {
        it.credentialStatus == ModelProviderCredentialStatus.Stored
    }
    val preferredProfile = enabledProfiles.firstOrNull(ModelProviderProfileState::isDefault)
        ?: enabledProfiles.firstOrNull()
    return AgentModelPreference(
        preferredProviderId = preferredProfile?.providerId,
        preferredProviderLabel = preferredProfile?.displayName,
        preferredModel = preferredProfile?.selectedModel,
        enabledProviderIds = enabledProfiles.map(ModelProviderProfileState::providerId),
        configuredProviderIds = configuredProfiles.map(ModelProviderProfileState::providerId),
    )
}

class PersistentModelProviderSettingsRepository(
    private val databaseHelper: ShellDatabaseHelper,
    private val credentialVault: ModelProviderCredentialVault,
) : ModelProviderSettingsRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _profiles = MutableStateFlow<List<ModelProviderProfileState>>(emptyList())

    override val profiles: StateFlow<List<ModelProviderProfileState>> = _profiles.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun setProviderEnabled(
        providerId: String,
        enabled: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            val existing = queryProfileRow(db, providerId) ?: return@withContext
            if (existing.enabled == enabled) {
                return@withContext
            }
            val now = System.currentTimeMillis()
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("is_enabled", enabled.asSqlBoolean())
                    put("updated_at", now)
                },
                "provider_id = ?",
                arrayOf(providerId),
            )
            if (!enabled && existing.isDefault) {
                db.update(
                    modelProviderProfilesTable,
                    ContentValues().apply {
                        put("is_default", 0)
                    },
                    null,
                    null,
                )
            }
            ensureDefaultSelection(db, now)
        }
        refresh()
    }

    override suspend fun setDefaultProvider(providerId: String) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            val existing = queryProfileRow(db, providerId) ?: return@withContext
            val now = System.currentTimeMillis()
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("is_default", 0)
                },
                null,
                null,
            )
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("is_enabled", 1)
                    put("is_default", 1)
                    put("selected_model", existing.selectedModel)
                    put("updated_at", now)
                },
                "provider_id = ?",
                arrayOf(providerId),
            )
        }
        refresh()
    }

    override suspend fun selectModel(
        providerId: String,
        model: String,
    ) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            val existing = queryProfileRow(db, providerId) ?: return@withContext
            if (model !in existing.supportedModels || model == existing.selectedModel) {
                return@withContext
            }
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("selected_model", model)
                    put("updated_at", System.currentTimeMillis())
                },
                "provider_id = ?",
                arrayOf(providerId),
            )
        }
        refresh()
    }

    override suspend fun storeCredential(
        providerId: String,
        secret: String,
    ) {
        val normalizedSecret = secret.trim()
        if (normalizedSecret.isBlank()) {
            return
        }
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            queryProfileRow(db, providerId) ?: return@withContext
            credentialVault.store(providerId, normalizedSecret)
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("credential_status", ModelProviderCredentialStatus.Stored.name)
                    put("credential_label", maskProviderCredential(normalizedSecret))
                    put("updated_at", System.currentTimeMillis())
                },
                "provider_id = ?",
                arrayOf(providerId),
            )
        }
        refresh()
    }

    override suspend fun clearCredential(providerId: String) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            queryProfileRow(db, providerId) ?: return@withContext
            credentialVault.clear(providerId)
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("credential_status", ModelProviderCredentialStatus.Missing.name)
                    putNull("credential_label")
                    put("updated_at", System.currentTimeMillis())
                },
                "provider_id = ?",
                arrayOf(providerId),
            )
        }
        refresh()
    }

    override suspend fun revealCredential(providerId: String): String? {
        return withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            queryProfileRow(db, providerId) ?: return@withContext null
            credentialVault.read(providerId)?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            reconcileCredentialStatuses(db)
            _profiles.value = queryProfiles(db)
        }
    }

    private fun ensureSeedData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        defaultModelProviderSeeds().forEach { seed ->
            db.insertWithOnConflict(
                modelProviderProfilesTable,
                null,
                ContentValues().apply {
                    put("provider_id", seed.providerId)
                    put("display_name", seed.displayName)
                    put("supported_models_json", jsonArrayString(seed.supportedModels))
                    put("default_model", seed.defaultModel)
                    put("selected_model", seed.selectedModel)
                    put("is_enabled", seed.enabled.asSqlBoolean())
                    put("is_default", seed.isDefault.asSqlBoolean())
                    put("credential_status", seed.credentialStatus.name)
                    put("credential_label", seed.credentialLabel)
                    put("base_url", seed.baseUrl)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            reconcileSeedProfile(db, seed, now)
        }
        ensureDefaultSelection(db, now)
    }

    private fun reconcileSeedProfile(
        db: SQLiteDatabase,
        seed: SeededModelProviderProfile,
        now: Long,
    ) {
        val existing = queryProfileRow(db, seed.providerId) ?: return
        val normalizedSelectedModel = normalizeSeedSelectedModel(existing.selectedModel, seed)
        val needsUpdate =
            existing.displayName != seed.displayName ||
                existing.supportedModels != seed.supportedModels ||
                existing.defaultModel != seed.defaultModel ||
                existing.selectedModel != normalizedSelectedModel
        if (!needsUpdate) {
            return
        }
        db.update(
            modelProviderProfilesTable,
            ContentValues().apply {
                put("display_name", seed.displayName)
                put("supported_models_json", jsonArrayString(seed.supportedModels))
                put("default_model", seed.defaultModel)
                put("selected_model", normalizedSelectedModel)
                put("updated_at", now)
            },
            "provider_id = ?",
            arrayOf(seed.providerId),
        )
    }

    private fun ensureDefaultSelection(
        db: SQLiteDatabase,
        now: Long,
    ) {
        val currentProfiles = queryProfileRows(db)
        val enabledProfiles = currentProfiles.filter(StoredModelProviderProfile::enabled)
        val defaultEnabledProfile = enabledProfiles.firstOrNull(StoredModelProviderProfile::isDefault)
        if (enabledProfiles.isEmpty() || defaultEnabledProfile != null) {
            return
        }
        val preferred = enabledProfiles.firstOrNull { it.providerId == defaultSeedProviderId }
            ?: enabledProfiles.first()
        db.update(
            modelProviderProfilesTable,
            ContentValues().apply {
                put("is_default", 0)
            },
            null,
            null,
        )
        db.update(
            modelProviderProfilesTable,
            ContentValues().apply {
                put("is_default", 1)
                put("updated_at", now)
            },
            "provider_id = ?",
            arrayOf(preferred.providerId),
        )
    }

    private suspend fun reconcileCredentialStatuses(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        queryProfileRows(db).forEach { row ->
            val hasCredential = credentialVault.hasCredential(row.providerId)
            val targetStatus = if (hasCredential) {
                ModelProviderCredentialStatus.Stored
            } else {
                ModelProviderCredentialStatus.Missing
            }
            val needsUpdate =
                row.credentialStatus != targetStatus ||
                    (!hasCredential && row.credentialLabel != null)
            if (!needsUpdate) {
                return@forEach
            }
            db.update(
                modelProviderProfilesTable,
                ContentValues().apply {
                    put("credential_status", targetStatus.name)
                    if (hasCredential) {
                        put("credential_label", row.credentialLabel)
                    } else {
                        putNull("credential_label")
                    }
                    put("updated_at", now)
                },
                "provider_id = ?",
                arrayOf(row.providerId),
            )
        }
    }

    private fun queryProfiles(db: SQLiteDatabase): List<ModelProviderProfileState> {
        val now = System.currentTimeMillis()
        return queryProfileRows(db).map { row ->
            ModelProviderProfileState(
                providerId = row.providerId,
                displayName = row.displayName,
                supportedModels = row.supportedModels,
                defaultModel = row.defaultModel,
                selectedModel = row.selectedModel,
                enabled = row.enabled,
                isDefault = row.isDefault,
                credentialStatus = row.credentialStatus,
                credentialLabel = row.credentialLabel,
                baseUrl = row.baseUrl,
                updatedAtEpochMillis = row.updatedAtEpochMillis,
                updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                    row.updatedAtEpochMillis,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
        }
    }

    private fun queryProfileRow(
        db: SQLiteDatabase,
        providerId: String,
    ): StoredModelProviderProfile? {
        return queryProfileRows(
            db = db,
            selection = "provider_id = ?",
            selectionArgs = arrayOf(providerId),
        ).firstOrNull()
    }

    private fun queryProfileRows(
        db: SQLiteDatabase,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<StoredModelProviderProfile> {
        return db.query(
            modelProviderProfilesTable,
            arrayOf(
                "provider_id",
                "display_name",
                "supported_models_json",
                "default_model",
                "selected_model",
                "is_enabled",
                "is_default",
                "credential_status",
                "credential_label",
                "base_url",
                "updated_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "is_default DESC, is_enabled DESC, display_name ASC",
        ).use { cursor ->
            val profiles = mutableListOf<StoredModelProviderProfile>()
            while (cursor.moveToNext()) {
                profiles += StoredModelProviderProfile(
                    providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                    supportedModels = jsonArrayToList(
                        cursor.getString(cursor.getColumnIndexOrThrow("supported_models_json")),
                    ),
                    defaultModel = cursor.getString(cursor.getColumnIndexOrThrow("default_model")),
                    selectedModel = cursor.getString(cursor.getColumnIndexOrThrow("selected_model")),
                    enabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) != 0,
                    isDefault = cursor.getInt(cursor.getColumnIndexOrThrow("is_default")) != 0,
                    credentialStatus = runCatching {
                        ModelProviderCredentialStatus.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("credential_status")),
                        )
                    }.getOrDefault(ModelProviderCredentialStatus.Missing),
                    credentialLabel = cursor.getString(cursor.getColumnIndexOrThrow("credential_label")),
                    baseUrl = cursor.getString(cursor.getColumnIndexOrThrow("base_url")),
                    updatedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                )
            }
            profiles
        }
    }

    private fun jsonArrayString(values: List<String>): String {
        return JSONArray(values).toString()
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

    private fun Boolean.asSqlBoolean(): Int {
        return if (this) 1 else 0
    }

    private data class StoredModelProviderProfile(
        val providerId: String,
        val displayName: String,
        val supportedModels: List<String>,
        val defaultModel: String,
        val selectedModel: String,
        val enabled: Boolean,
        val isDefault: Boolean,
        val credentialStatus: ModelProviderCredentialStatus,
        val credentialLabel: String?,
        val baseUrl: String?,
        val updatedAtEpochMillis: Long,
    )

    companion object {
        private const val modelProviderProfilesTable = "model_provider_profiles"
        private const val defaultSeedProviderId = "openai"
    }
}
