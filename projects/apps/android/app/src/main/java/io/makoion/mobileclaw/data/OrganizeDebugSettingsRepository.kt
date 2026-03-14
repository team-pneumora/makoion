package io.makoion.mobileclaw.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class OrganizeDebugSettingsState(
    val forceDeleteConsentForTesting: Boolean = false,
)

interface OrganizeDebugSettingsRepository {
    val state: StateFlow<OrganizeDebugSettingsState>

    suspend fun setForceDeleteConsentForTesting(enabled: Boolean)
}

class PersistentOrganizeDebugSettingsRepository(
    context: Context,
) : OrganizeDebugSettingsRepository {
    private val preferences = context.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val _state = MutableStateFlow(readState())

    override val state: StateFlow<OrganizeDebugSettingsState> = _state.asStateFlow()

    override suspend fun setForceDeleteConsentForTesting(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .putBoolean(forceDeleteConsentKey, enabled)
                .apply()
            _state.value = readState()
        }
    }

    private fun readState(): OrganizeDebugSettingsState {
        return OrganizeDebugSettingsState(
            forceDeleteConsentForTesting = preferences.getBoolean(forceDeleteConsentKey, false),
        )
    }

    companion object {
        private const val preferencesName = "organize_debug_settings"
        private const val forceDeleteConsentKey = "force_delete_consent_for_testing"
    }
}
