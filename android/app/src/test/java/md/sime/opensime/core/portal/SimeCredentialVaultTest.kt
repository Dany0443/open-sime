package md.sime.opensime.core.portal

import android.content.SharedPreferences
import java.util.Base64
import md.sime.opensime.core.model.AccountRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimeCredentialVaultTest {

    private class TestSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class Editor(private val prefs: TestSharedPreferences) : SharedPreferences.Editor {
            private val modifications = mutableMapOf<String, Any?>()
            private var shouldClear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                modifications[key ?: ""] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                modifications[key ?: ""] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                shouldClear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (shouldClear) prefs.data.clear()
                modifications.forEach { (k, v) ->
                    if (v == null) prefs.data.remove(k) else prefs.data[k] = v
                }
            }
        }
    }

    private val cipher = StandardAesGcmVaultCipher()
    private val testPrefs = TestSharedPreferences()
    private val vault = SimeCredentialVault(preferences = testPrefs, cipher = cipher)

    @Test
    fun `initial state has no saved credentials`() {
        assertFalse(vault.hasSavedCredentials())
        assertNull(vault.loadCredentials())
    }

    @Test
    fun `saveCredentials stores ciphertext and random IV without plaintext leakage`() {
        val username = "student.test"
        val password = "SuperSecretPassword123!"
        val role = AccountRole.STUDENT

        vault.saveCredentials(username, password, role)

        assertTrue(vault.hasSavedCredentials())
        val rawPayload = testPrefs.getString("enc_payload", null)
        val rawIv = testPrefs.getString("enc_iv", null)

        assertNotNull(rawPayload)
        assertNotNull(rawIv)

        // Ensure zero plaintext leakage in storage
        assertFalse("Storage must not contain plaintext password", rawPayload!!.contains(password))
        assertFalse("Storage must not contain plaintext username", rawPayload.contains(username))

        // Validate IV is a 12-byte standard GCM IV
        val ivBytes = Base64.getDecoder().decode(rawIv)
        assertEquals(12, ivBytes.size)
    }

    @Test
    fun `loadCredentials restores exact student and parent credentials`() {
        // Test student credentials
        vault.saveCredentials("elev_123", "secretPass!", AccountRole.STUDENT)
        val studentCreds = vault.loadCredentials()
        assertNotNull(studentCreds)
        assertEquals("elev_123", studentCreds?.username)
        assertEquals("secretPass!", studentCreds?.password)
        assertEquals(AccountRole.STUDENT, studentCreds?.role)

        // Test parent credentials
        vault.saveCredentials("parinte_456", "parentPass99", AccountRole.PARENT)
        val parentCreds = vault.loadCredentials()
        assertNotNull(parentCreds)
        assertEquals("parinte_456", parentCreds?.username)
        assertEquals("parentPass99", parentCreds?.password)
        assertEquals(AccountRole.PARENT, parentCreds?.role)
    }

    @Test
    fun `saveCredentials trims leading and trailing whitespace from username`() {
        vault.saveCredentials("   elev_trim   ", "pass", AccountRole.STUDENT)
        val creds = vault.loadCredentials()
        assertNotNull(creds)
        assertEquals("elev_trim", creds?.username)
    }

    @Test
    fun `clearCredentials deletes all vault storage`() {
        vault.saveCredentials("student", "pass", AccountRole.STUDENT)
        assertTrue(vault.hasSavedCredentials())

        vault.clearCredentials()

        assertFalse(vault.hasSavedCredentials())
        assertNull(vault.loadCredentials())
        assertNull(testPrefs.getString("enc_payload", null))
        assertNull(testPrefs.getString("enc_iv", null))
    }

    @Test
    fun `tampered ciphertext causes GCM authentication failure and returns null`() {
        vault.saveCredentials("student", "pass", AccountRole.STUDENT)
        val validPayloadB64 = testPrefs.getString("enc_payload", null)!!
        val bytes = Base64.getDecoder().decode(validPayloadB64)

        // Tamper with one byte of the ciphertext
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        val tamperedPayloadB64 = Base64.getEncoder().encodeToString(bytes)
        testPrefs.edit().putString("enc_payload", tamperedPayloadB64).apply()

        // GCM authentication tag verification will fail; vault must catch and return null safely
        assertNull(vault.loadCredentials())
    }

    @Test
    fun `corrupt base64 in preferences returns null safely`() {
        testPrefs.edit()
            .putString("enc_payload", "not_valid_base64!!!")
            .putString("enc_iv", "also_not_valid!")
            .apply()

        assertNull(vault.loadCredentials())
    }

    @Test
    fun `each encryption produces a fresh distinct IV and distinct ciphertext`() {
        val (iv1, cipher1) = cipher.encrypt("secret".toByteArray(Charsets.UTF_8))
        val (iv2, cipher2) = cipher.encrypt("secret".toByteArray(Charsets.UTF_8))

        // IVs must never be reused
        assertFalse(iv1.contentEquals(iv2))
        assertFalse(cipher1.contentEquals(cipher2))

        // Both decrypt back to identical plaintext
        assertEquals("secret", String(cipher.decrypt(iv1, cipher1), Charsets.UTF_8))
        assertEquals("secret", String(cipher.decrypt(iv2, cipher2), Charsets.UTF_8))
    }
}
