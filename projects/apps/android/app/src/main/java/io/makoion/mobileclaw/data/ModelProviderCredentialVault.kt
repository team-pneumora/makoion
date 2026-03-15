package io.makoion.mobileclaw.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ModelProviderCredentialVault {
    suspend fun store(providerId: String, secret: String)

    suspend fun hasCredential(providerId: String): Boolean

    suspend fun clear(providerId: String)
}

class AndroidKeystoreModelProviderCredentialVault(
    context: Context,
) : ModelProviderCredentialVault {
    private val preferences = context.getSharedPreferences(
        vaultPreferencesName,
        Context.MODE_PRIVATE,
    )

    override suspend fun store(
        providerId: String,
        secret: String,
    ) {
        withContext(Dispatchers.IO) {
            val cipher = Cipher.getInstance(cipherTransformation)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(providerId))
            val encryptedSecret = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            val payload = buildString {
                append(Base64.getEncoder().encodeToString(cipher.iv))
                append(payloadSeparator)
                append(Base64.getEncoder().encodeToString(encryptedSecret))
            }
            preferences.edit()
                .putString(providerId, payload)
                .commit()
        }
    }

    override suspend fun hasCredential(providerId: String): Boolean {
        return withContext(Dispatchers.IO) {
            preferences.contains(providerId)
        }
    }

    override suspend fun clear(providerId: String) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .remove(providerId)
                .commit()
            runCatching {
                keyStore().deleteEntry(aliasForProvider(providerId))
            }
        }
    }

    private fun getOrCreateSecretKey(providerId: String): SecretKey {
        val alias = aliasForProvider(providerId)
        val keyStore = keyStore()
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            androidKeystoreType,
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun keyStore(): KeyStore {
        return KeyStore.getInstance(androidKeystoreType).apply {
            load(null)
        }
    }

    private fun aliasForProvider(providerId: String): String {
        return "$keyAliasPrefix$providerId"
    }

    companion object {
        private const val vaultPreferencesName = "model_provider_credential_vault"
        private const val androidKeystoreType = "AndroidKeyStore"
        private const val cipherTransformation = "AES/GCM/NoPadding"
        private const val keyAliasPrefix = "io.makoion.mobileclaw.provider."
        private const val payloadSeparator = ":"
    }
}

internal fun maskProviderCredential(secret: String): String {
    val trimmed = secret.trim()
    if (trimmed.isBlank()) {
        return "empty"
    }
    return when {
        trimmed.length <= 6 -> "${trimmed.first()}...${trimmed.last()}"
        else -> "${trimmed.take(4)}...${trimmed.takeLast(4)}"
    }
}
