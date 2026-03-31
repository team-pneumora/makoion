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

interface MailboxCredentialVault {
    fun store(mailboxId: String, credential: String)

    fun read(mailboxId: String): String?

    fun hasCredential(mailboxId: String): Boolean

    fun clear(mailboxId: String)
}

class AndroidKeystoreMailboxCredentialVault(
    context: Context,
) : MailboxCredentialVault {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }

    override fun store(mailboxId: String, credential: String) {
        val trimmed = credential.trim()
        if (trimmed.isBlank()) {
            clear(mailboxId)
            return
        }
        val alias = aliasFor(mailboxId)
        val cipher = Cipher.getInstance(aesMode).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        }
        val encryptedBytes = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            payloadSeparator +
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        preferences.edit().putString(storageKey(mailboxId), payload).apply()
    }

    override fun read(mailboxId: String): String? {
        val payload = preferences.getString(storageKey(mailboxId), null) ?: return null
        val parts = payload.split(payloadSeparator, limit = 2)
        if (parts.size != 2) {
            return null
        }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(aesMode).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(aliasFor(mailboxId)),
                GCMParameterSpec(gcmTagLengthBits, iv),
            )
        }
        return runCatching {
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override fun hasCredential(mailboxId: String): Boolean {
        return preferences.contains(storageKey(mailboxId))
    }

    override fun clear(mailboxId: String) {
        preferences.edit().remove(storageKey(mailboxId)).apply()
        runCatching {
            keyStore.deleteEntry(aliasFor(mailboxId))
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

    private fun storageKey(mailboxId: String): String = "mailbox.$mailboxId"

    private fun aliasFor(mailboxId: String): String = "${aliasPrefix}_$mailboxId"

    companion object {
        private const val preferencesName = "makoion_mailbox_credentials"
        private const val androidKeyStore = "AndroidKeyStore"
        private const val aesMode = "AES/GCM/NoPadding"
        private const val aliasPrefix = "makoion_mailbox"
        private const val payloadSeparator = "::"
        private const val gcmTagLengthBits = 128
    }
}
