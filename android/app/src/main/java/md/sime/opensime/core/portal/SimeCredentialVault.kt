package md.sime.opensime.core.portal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import md.sime.opensime.core.model.AccountRole
import kotlinx.coroutines.CancellationException

/**
 * Credentials securely saved on the local device.
 */
data class SavedCredentials(
    val username: String,
    val password: String,
    val role: AccountRole,
) {
    override fun toString(): String = "SavedCredentials(role=$role, credentials=[REDACTED])"
}

/** Deliberately excludes provider messages and causes, which may contain sensitive values. */
class SimeCredentialStorageError : Exception("Secure credential storage failed")

class SimeCredentialRestoreError : Exception("Saved login unavailable")

/**
 * Pluggable encryption engine interface for AES-256-GCM.
 * Enables both hardware-backed Android KeyStore on devices and JVM unit testing.
 */
interface VaultCipher {
    fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray
}

internal interface CredentialGenerationKeys {
    fun create(generation: String)
    fun exists(generation: String): Boolean
    fun revoke(generation: String)
    fun encrypt(generation: String, plainText: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray): ByteArray
}

/** Credential-only keys; generations never share the snapshot encryption alias. */
internal class AndroidCredentialGenerationKeys : CredentialGenerationKeys {
    companion object {
        private const val PREFIX = "open_sime_credential_v1_"
        private val lock = Any()
        private fun alias(generation: String): String {
            require(generation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            return PREFIX + generation
        }
        private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    override fun create(generation: String) = synchronized(lock) {
        val keyAlias = alias(generation)
        check(!store().containsAlias(keyAlias)) { "Credential generation already exists" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        generator.generateKey()
        check(store().containsAlias(keyAlias)) { "Credential key creation was not confirmed" }
    }

    override fun exists(generation: String): Boolean = synchronized(lock) { store().containsAlias(alias(generation)) }

    override fun revoke(generation: String) = synchronized(lock) {
        val keyAlias = alias(generation)
        val keys = store()
        if (keys.containsAlias(keyAlias)) keys.deleteEntry(keyAlias)
        check(!store().containsAlias(keyAlias)) { "Credential key deletion was not confirmed" }
    }

    override fun encrypt(generation: String, plainText: ByteArray): Pair<ByteArray, ByteArray> = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireKey(generation))
        cipher.iv to cipher.doFinal(plainText)
    }

    override fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray): ByteArray = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireKey(generation), GCMParameterSpec(128, iv))
        cipher.doFinal(cipherText)
    }

    private fun requireKey(generation: String): SecretKey =
        (store().getEntry(alias(generation), null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Credential key unavailable")
}

/**
 * Hardware-backed [VaultCipher] implementation using Android's `"AndroidKeyStore"`.
 *
 * Invariants:
 * - Uses AES-256 in GCM mode with no padding (128-bit authentication tag).
 * - Key material never leaves the secure hardware boundary.
 * - Each encryption generates a fresh, randomized 12-byte initialization vector (IV).
 */
class AndroidKeyStoreVaultCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val keyStoreProvider: String = ANDROID_KEYSTORE_PROVIDER,
) : VaultCipher {

    companion object {
        const val DEFAULT_KEY_ALIAS = "open_sime_vault_key"
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val KEY_SIZE = 256
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                keyStoreProvider,
            )
            val keyGenSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("KeyStore entry '$keyAlias' is not a SecretKeyEntry")
        return entry.secretKey
    }

    override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher IV was null")
        val cipherText = cipher.doFinal(plainText)
        return Pair(iv, cipherText)
    }

    override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(cipherText)
    }
}

/**
 * Standard Java Cryptography Extension (JCE) implementation of [VaultCipher] for JVM testing.
 */
class StandardAesGcmVaultCipher(
    private val secretKey: SecretKey = generateKey(),
) : VaultCipher {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        fun generateKey(): SecretKey {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            return keyGen.generateKey()
        }
    }

    override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher IV was null")
        val cipherText = cipher.doFinal(plainText)
        return Pair(iv, cipherText)
    }

    override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(cipherText)
    }
}

/**
 * Secure credential vault for Open SIME Android.
 *
 * Rules:
 * 1. Passwords and credentials are never stored in plaintext or unencrypted SharedPreferences.
 * 2. Entire payload is encrypted using AES-256-GCM before writing to the private vault preferences.
 * 3. Clearing or forgetting saved credentials completely removes encrypted keys from storage.
 * 4. Backups are blocked by `allowBackup="false"` and `data_extraction_rules.xml`.
 */
class SimeCredentialVault internal constructor(
    private val preferences: SharedPreferences,
    private val generationKeys: CredentialGenerationKeys,
) {
    constructor(preferences: SharedPreferences) : this(preferences, AndroidCredentialGenerationKeys())
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    // Cipher injection is retained for synthetic JVM tests; the application constructor always uses Android Keystore.
    internal constructor(preferences: SharedPreferences, cipher: VaultCipher) : this(preferences, object : CredentialGenerationKeys {
        override fun create(generation: String) = Unit
        override fun exists(generation: String) = true
        override fun revoke(generation: String) = Unit
        override fun encrypt(generation: String, plainText: ByteArray) = cipher.encrypt(plainText)
        override fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
    })
    internal constructor(context: Context, cipher: VaultCipher) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), cipher)

    companion object {
        const val PREFS_NAME = "open_sime_vault"
        private const val KEY_ENCRYPTED_PAYLOAD = "enc_payload"
        private const val KEY_IV = "enc_iv"
        private const val KEY_RECORD = "credential_record"
    }

    /**
     * Returns true if encrypted credentials and an initialization vector are present.
     */
    fun hasSavedCredentials(): Boolean = loadCredentials() != null

    internal fun hasPendingCredentialCleanup(): Boolean =
        preferences.getString(KEY_RECORD, null)?.startsWith("1|pending|") == true

    /**
     * Decrypts and returns the saved credentials, or null if absent or corrupt.
     */
    fun loadCredentials(): SavedCredentials? {
        return try {
            val fields = preferences.getString(KEY_RECORD, null)?.split('|') ?: return null
            if (fields.size != 5 || fields[0] != "1" || fields[1] != "eligible") return null
            val generation = fields[2]
            if (UUID.fromString(generation).toString() != generation || !generationKeys.exists(generation)) return null
            val iv = Base64.getDecoder().decode(fields[3])
            val cipherText = Base64.getDecoder().decode(fields[4])
            if (iv.size != 12 || cipherText.size < 16) return null
            val plainBytes = generationKeys.decrypt(generation, iv, cipherText)
            val plainText = String(plainBytes, Charsets.UTF_8)
            val parts = plainText.split('\n', limit = 6)
            if (parts.size != 6 || parts[0] != "1" || parts[1] != "eligible" || parts[2] != generation) return null
            val role = runCatching { AccountRole.valueOf(parts[3]) }.getOrNull() ?: return null
            val username = parts[4]
            val password = parts[5]
            if (username.isBlank() || password.isBlank()) return null
            SavedCredentials(username = username, password = password, role = role)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Encrypts and writes credentials to the private vault preferences.
     */
    fun saveCredentials(username: String, password: String, role: AccountRole) {
        saveCredentials(username, password, role) { }
    }

    private fun saveCredentials(username: String, password: String, role: AccountRole, beforeCommit: () -> Unit) {
        require(username.isNotBlank() && password.isNotBlank())
        val oldGeneration = storedGeneration()
        val generation = UUID.randomUUID().toString()
        generationKeys.create(generation)
        try {
            val plainText = "1\neligible\n$generation\n${role.name}\n${username.trim()}\n$password"
            val (iv, encrypted) = generationKeys.encrypt(generation, plainText.toByteArray(Charsets.UTF_8))
            val record = listOf("1", "eligible", generation, Base64.getEncoder().encodeToString(iv), Base64.getEncoder().encodeToString(encrypted)).joinToString("|")
            beforeCommit()
            check(preferences.edit().putString(KEY_RECORD, record).remove(KEY_ENCRYPTED_PAYLOAD).remove(KEY_IV).commit()) {
                "Secure credential storage failed"
            }
            if (oldGeneration != null) generationKeys.revoke(oldGeneration)
        } catch (error: Exception) {
            runCatching { generationKeys.revoke(generation) }
            throw error
        }
    }

    /** Called off the main thread before a manual login is published to the UI. */
    internal fun updateLoginPersistence(username: String, password: String, role: AccountRole, rememberMe: Boolean,
        beforeCommit: () -> Unit = {},
    ) {
        try {
            if (rememberMe) {
                saveCredentials(username, password, role, beforeCommit)
            } else {
                beforeCommit()
                clearCredentials()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A failed commit can still have updated the in-memory preferences.
            runCatching { clearCredentials() }
            throw SimeCredentialStorageError()
        }
    }

    /**
     * Erases all saved credentials and initialization vectors from storage.
     */
    fun clearCredentials() {
        val generation = storedGeneration()
        if (generation != null) {
            // Keep the generation in the record until key deletion is confirmed, so
            // a later process can retry even after a partial cleanup.
            val invalidated = runCatching {
                preferences.edit().putString(KEY_RECORD, "1|pending|$generation").commit()
            }.getOrDefault(false)
            val revoked = runCatching { generationKeys.revoke(generation) }.isSuccess
            if (!invalidated || !revoked) throw SimeCredentialStorageError()
        }
        val cleared = preferences.edit()
            .remove(KEY_RECORD)
            .remove(KEY_ENCRYPTED_PAYLOAD)
            .remove(KEY_IV)
            .commit()
        if (!cleared) throw SimeCredentialStorageError()
    }

    private fun storedGeneration(): String? {
        val value = preferences.getString(KEY_RECORD, null)?.split('|')?.getOrNull(2) ?: return null
        return runCatching { UUID.fromString(value).toString().takeIf { it == value } }.getOrNull()
    }
}

