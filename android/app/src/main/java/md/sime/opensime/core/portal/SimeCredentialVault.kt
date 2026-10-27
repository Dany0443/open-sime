package md.sime.opensime.core.portal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import md.sime.opensime.core.model.AccountRole

/**
 * Credentials securely saved on the local device.
 */
data class SavedCredentials(
    val username: String,
    val password: String,
    val role: AccountRole,
)

/**
 * Pluggable encryption engine interface for AES-256-GCM.
 * Enables both hardware-backed Android KeyStore on devices and JVM unit testing.
 */
interface VaultCipher {
    fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray
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
class SimeCredentialVault(
    private val preferences: SharedPreferences,
    private val cipher: VaultCipher = AndroidKeyStoreVaultCipher(),
) {

    constructor(
        context: Context,
        cipher: VaultCipher = AndroidKeyStoreVaultCipher(),
    ) : this(
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        cipher = cipher,
    )

    companion object {
        const val PREFS_NAME = "open_sime_vault"
        private const val KEY_ENCRYPTED_PAYLOAD = "enc_payload"
        private const val KEY_IV = "enc_iv"
    }

    /**
     * Returns true if encrypted credentials and an initialization vector are present.
     */
    fun hasSavedCredentials(): Boolean {
        val payload = preferences.getString(KEY_ENCRYPTED_PAYLOAD, null)
        val iv = preferences.getString(KEY_IV, null)
        return !payload.isNullOrBlank() && !iv.isNullOrBlank()
    }

    /**
     * Decrypts and returns the saved credentials, or null if absent or corrupt.
     */
    fun loadCredentials(): SavedCredentials? {
        val payloadB64 = preferences.getString(KEY_ENCRYPTED_PAYLOAD, null) ?: return null
        val ivB64 = preferences.getString(KEY_IV, null) ?: return null
        return try {
            val cipherText = Base64.getDecoder().decode(payloadB64)
            val iv = Base64.getDecoder().decode(ivB64)
            val plainBytes = cipher.decrypt(iv = iv, cipherText = cipherText)
            val plainText = String(plainBytes, Charsets.UTF_8)
            val parts = plainText.split('\n', limit = 3)
            if (parts.size != 3) return null
            val role = runCatching { AccountRole.valueOf(parts[0]) }.getOrNull() ?: return null
            val username = parts[1]
            val password = parts[2]
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
        val plainText = "${role.name}\n${username.trim()}\n$password"
        val (iv, cipherText) = cipher.encrypt(plainText.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val payloadB64 = Base64.getEncoder().encodeToString(cipherText)
        preferences.edit()
            .putString(KEY_ENCRYPTED_PAYLOAD, payloadB64)
            .putString(KEY_IV, ivB64)
            .apply()
    }

    /**
     * Erases all saved credentials and initialization vectors from storage.
     */
    fun clearCredentials() {
        preferences.edit()
            .remove(KEY_ENCRYPTED_PAYLOAD)
            .remove(KEY_IV)
            .apply()
    }
}

