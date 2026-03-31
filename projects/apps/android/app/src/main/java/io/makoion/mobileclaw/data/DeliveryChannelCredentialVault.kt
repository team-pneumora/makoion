package io.makoion.mobileclaw.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface DeliveryChannelCredentialVault {
    fun store(channelId: String, credential: String)

    fun read(channelId: String): String?

    fun hasCredential(channelId: String): Boolean

    fun clear(channelId: String)
}

class AndroidKeystoreDeliveryChannelCredentialVault(
    context: Context,
) : DeliveryChannelCredentialVault {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }

    override fun store(channelId: String, credential: String) {
        val trimmed = credential.trim()
        if (trimmed.isBlank()) {
            clear(channelId)
            return
        }
        val alias = aliasFor(channelId)
        val cipher = Cipher.getInstance(aesMode).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        }
        val encryptedBytes = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            payloadSeparator +
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        preferences.edit().putString(storageKey(channelId), payload).apply()
    }

    override fun read(channelId: String): String? {
        val payload = preferences.getString(storageKey(channelId), null) ?: return null
        val parts = payload.split(payloadSeparator, limit = 2)
        if (parts.size != 2) {
            return null
        }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(aesMode).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(aliasFor(channelId)),
                GCMParameterSpec(gcmTagLengthBits, iv),
            )
        }
        return runCatching {
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override fun hasCredential(channelId: String): Boolean {
        return preferences.contains(storageKey(channelId))
    }

    override fun clear(channelId: String) {
        preferences.edit().remove(storageKey(channelId)).apply()
        runCatching {
            keyStore.deleteEntry(aliasFor(channelId))
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, androidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun storageKey(channelId: String): String = "delivery.$channelId"

    private fun aliasFor(channelId: String): String = "${aliasPrefix}_$channelId"

    companion object {
        private const val preferencesName = "makoion_delivery_channel_credentials"
        private const val androidKeyStore = "AndroidKeyStore"
        private const val aesMode = "AES/GCM/NoPadding"
        private const val aliasPrefix = "makoion_delivery_channel"
        private const val payloadSeparator = "::"
        private const val gcmTagLengthBits = 128
    }
}
