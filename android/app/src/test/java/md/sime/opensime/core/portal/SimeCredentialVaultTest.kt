package md.sime.opensime.core.portal

import android.content.SharedPreferences
import android.content.Context
import android.content.ContextWrapper
import md.sime.opensime.OpenSimeRetainedSession
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import java.util.Base64
import md.sime.opensime.core.model.AccountRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimeCredentialVaultTest {

    internal class TestSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        var commitSucceeds = true
        var applyFailedCommit = true

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
                if (prefs.commitSucceeds || prefs.applyFailedCommit) apply()
                return prefs.commitSucceeds
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

    @Test fun `logout records ineligibility even when key revocation fails and retries after recreation`() {
        var failRevoke = true
        val keys = object : CredentialGenerationKeys {
            val entries = mutableMapOf<String, VaultCipher>()
            override fun create(generation: String) { entries[generation] = StandardAesGcmVaultCipher() }
            override fun exists(generation: String) = entries.containsKey(generation)
            override fun revoke(generation: String) { if (failRevoke) error("synthetic failure"); entries.remove(generation) }
            override fun encrypt(generation: String, plainText: ByteArray) = entries.getValue(generation).encrypt(plainText)
            override fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray) = entries.getValue(generation).decrypt(iv, cipherText)
        }
        val prefs = TestSharedPreferences()
        val first = SimeCredentialVault(prefs, keys)
        first.saveCredentials("test", "secret", AccountRole.PARENT)
        assertTrue(runCatching { first.clearCredentials() }.isFailure)
        val restarted = SimeCredentialVault(prefs, keys)
        assertNull(restarted.loadCredentials())
        assertTrue(restarted.hasPendingCredentialCleanup())
        failRevoke = false
        restarted.clearCredentials()
        assertFalse(restarted.hasPendingCredentialCleanup())
        assertTrue(keys.entries.isEmpty())
        restarted.clearCredentials()
    }

    @Test fun `failed record invalidation still attempts credential key revocation`() {
        var revoked = false
        val keys = object : CredentialGenerationKeys {
            val delegate = StandardAesGcmVaultCipher()
            override fun create(generation: String) = Unit
            override fun exists(generation: String) = !revoked
            override fun revoke(generation: String) { revoked = true }
            override fun encrypt(generation: String, plainText: ByteArray) = delegate.encrypt(plainText)
            override fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray) = delegate.decrypt(iv, cipherText)
        }
        val prefs = TestSharedPreferences()
        val recordVault = SimeCredentialVault(prefs, keys)
        recordVault.saveCredentials("test", "secret", AccountRole.PARENT)
        val diskBeforeLogout = prefs.getString("credential_record", null)
        prefs.commitSucceeds = false
        assertTrue(runCatching { recordVault.clearCredentials() }.isFailure)
        assertTrue(revoked)
        val restartedPrefs = TestSharedPreferences().apply { data["credential_record"] = diskBeforeLogout }
        assertNull(SimeCredentialVault(restartedPrefs, keys).loadCredentials())
    }

    @Test fun `both invalidation failures report failure and cannot guarantee restart safety`() {
        val keys = object : CredentialGenerationKeys {
            val delegate = StandardAesGcmVaultCipher()
            override fun create(generation: String) = Unit
            override fun exists(generation: String) = true
            override fun revoke(generation: String) { error("synthetic failure") }
            override fun encrypt(generation: String, plainText: ByteArray) = delegate.encrypt(plainText)
            override fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray) = delegate.decrypt(iv, cipherText)
        }
        val prefs = TestSharedPreferences()
        val recordVault = SimeCredentialVault(prefs, keys)
        recordVault.saveCredentials("test", "secret", AccountRole.PARENT)
        val previouslyCommitted = prefs.getString("credential_record", null)
        prefs.commitSucceeds = false
        assertTrue(runCatching { recordVault.clearCredentials() }.isFailure)
        // Model commit(false) leaving the old durable record on disk.
        val restartedPrefs = TestSharedPreferences().apply { data["credential_record"] = previouslyCommitted }
        assertNotNull(SimeCredentialVault(restartedPrefs, keys).loadCredentials())
    }

    @Test fun `versioned record requires its generation key and rejects legacy records`() {
        val keys = object : CredentialGenerationKeys {
            val entries = mutableMapOf<String, VaultCipher>()
            override fun create(generation: String) { check(entries.put(generation, StandardAesGcmVaultCipher()) == null) }
            override fun exists(generation: String) = entries.containsKey(generation)
            override fun revoke(generation: String) { entries.remove(generation) }
            override fun encrypt(generation: String, plainText: ByteArray) = entries.getValue(generation).encrypt(plainText)
            override fun decrypt(generation: String, iv: ByteArray, cipherText: ByteArray) = entries.getValue(generation).decrypt(iv, cipherText)
        }
        val preferences = TestSharedPreferences()
        val recordVault = SimeCredentialVault(preferences, keys)
        recordVault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
        assertEquals("synthetic-user", SimeCredentialVault(preferences, keys).loadCredentials()?.username)
        val record = preferences.getString("credential_record", null)!!
        assertFalse(record.contains("synthetic-user"))
        val generation = record.split('|')[2]
        keys.revoke(generation)
        assertNull(SimeCredentialVault(preferences, keys).loadCredentials())
        assertFalse(keys.exists(generation))
        val (legacyIv, legacyBytes) = cipher.encrypt("PARENT\nsynthetic-user\nsynthetic-password".toByteArray())
        val legacy = TestSharedPreferences().apply {
            edit().putString("enc_payload", Base64.getEncoder().encodeToString(legacyBytes))
                .putString("enc_iv", Base64.getEncoder().encodeToString(legacyIv)).commit()
        }
        assertFalse(SimeCredentialVault(legacy, keys).hasSavedCredentials())
    }

    @Test fun `partial credential record is never eligible or decrypted after recreation`() {
        vault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
        val fields = testPrefs.getString("credential_record", null)!!.split('|')
        for (partial in listOf(fields.take(3).joinToString("|"), fields.take(4).joinToString("|"))) {
            val persisted = TestSharedPreferences()
            persisted.edit().putString("credential_record", partial).commit()
            val recreated = SimeCredentialVault(preferences = persisted, cipher = cipher)
            assertFalse(recreated.hasSavedCredentials())
            assertNull(recreated.loadCredentials())
        }
    }

    @Test fun `unsupported version altered eligibility and generation fail closed`() {
        vault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
        val original = testPrefs.getString("credential_record", null)!!.split('|')
        for ((index, value) in listOf(0 to "2", 1 to "ineligible", 2 to "00000000-0000-0000-0000-000000000000")) {
            val altered = original.toMutableList().apply { this[index] = value }
            testPrefs.edit().putString("credential_record", altered.joinToString("|")).commit()
            assertFalse(vault.hasSavedCredentials())
            assertNull(vault.loadCredentials())
        }
    }

    @Test
    fun `simultaneous logout during credential saving drains the write and keeps automatic login blocked`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val settings = TestSharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = settings
        }
        val delayedVault = SimeCredentialVault(testPrefs, object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        })
        val auth = loginCoordinator()
        val session = OpenSimeRetainedSession()
        val login = async(start = CoroutineStart.UNDISPATCHED) {
            auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, true, delayedVault)
        }
        session.trackSessionJob(login)
        try {
            entered.await()
            val firstLogout = async(start = CoroutineStart.UNDISPATCHED) { session.logout(context, auth, delayedVault) }
            val generation = session.sessionGeneration
            session.logout(context, auth, delayedVault)
            assertEquals(generation, session.sessionGeneration)
            assertTrue(session.logoutInProgress)
            release.countDown()
            firstLogout.await()
            assertTrue(login.isCancelled)
            assertFalse(auth.isAuthenticated)
            assertFalse(delayedVault.hasSavedCredentials())
            assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(context))
            assertNull(session.logoutError)
            assertFalse(session.logoutInProgress)
        } finally { release.countDown() }
    }

    @Test
    fun `logout during encryption prevents obsolete credentials from committing or clearing a newer login`() = runBlocking {
        for (explicitLogout in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val delayedVault = SimeCredentialVault(testPrefs, object : VaultCipher {
                override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    return cipher.encrypt(plainText)
                }
                override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
            })
            val auth = loginCoordinator()
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { auth.loginWithPersistence("synthetic-old", "synthetic-password", AccountRole.PARENT, true, delayedVault) }
            }
            try {
                entered.await()
                if (explicitLogout) auth.logout() else old.cancel()
                val newer = async(start = CoroutineStart.UNDISPATCHED) { auth.login("synthetic-new", "synthetic-password") }
                val newerStartedBeforeStorageFinished = newer.isCompleted
                release.countDown()
                val oldFailure = runCatching { old.await().getOrThrow() }.exceptionOrNull()
                newer.await()
                assertFalse("Login and persistence must share APEX serialization", newerStartedBeforeStorageFinished)
                assertTrue(oldFailure is CancellationException)
                assertTrue(auth.isAuthenticated)
                assertFalse(vault.hasSavedCredentials())
                assertNull(auth.lastError)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `checked login saves credentials in the existing encrypted vault`() = runBlocking {
        val auth = loginCoordinator()
        auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, true, vault)
        assertTrue(auth.isAuthenticated)
        assertEquals("synthetic-user", vault.loadCredentials()?.username)
        assertFalse(testPrefs.data.toString().contains("synthetic-password"))
    }

    @Test
    fun `unchecked login clears previous credentials without encrypting new ones`() = runBlocking {
        vault.saveCredentials("old-synthetic-user", "old-synthetic-password", AccountRole.PARENT)
        val failingCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> =
                error("Encryption must not be used for session-only login")
            override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray = cipher.decrypt(iv, cipherText)
        }
        val auth = loginCoordinator()
        auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, false,
            SimeCredentialVault(testPrefs, failingCipher))
        assertTrue(auth.isAuthenticated)
        assertTrue(testPrefs.data.isEmpty())
        assertNull(vault.loadCredentials())
    }

    @Test
    fun `encryption failure rejects login safely and permits session-only retry`() = runBlocking {
        val failingVault = SimeCredentialVault(testPrefs, object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> =
                throw IllegalStateException("synthetic-secret-in-provider-error")
            override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray = error("Not used")
        })
        val auth = loginCoordinator()
        val failure = runCatching {
            auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, true, failingVault)
        }.exceptionOrNull()
        assertTrue(failure is SimeCredentialStorageError)
        assertFalse(failure.toString().contains("synthetic-secret-in-provider-error"))
        assertTrue(generateSequence(failure) { it.cause }.all {
            !it.toString().contains("synthetic-secret-in-provider-error")
        })
        assertFalse(auth.isAuthenticated)
        assertTrue(auth.session.http.cookies.isEmpty())
        assertNull(auth.activeSnapshot)
        assertTrue(testPrefs.data.isEmpty())
        auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, false, failingVault)
        assertTrue(auth.isAuthenticated)
        assertFalse(failingVault.hasSavedCredentials())
    }

    @Test
    fun `failed preference commit does not complete a persistent login`() = runBlocking {
        testPrefs.commitSucceeds = false
        val auth = loginCoordinator()
        val failure = runCatching {
            auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, true, vault)
        }.exceptionOrNull()
        assertTrue(failure is SimeCredentialStorageError)
        assertFalse(auth.isAuthenticated)
        assertFalse(vault.hasSavedCredentials())
    }

    @Test
    fun `failed removal cannot complete session-only login`() = runBlocking {
        vault.saveCredentials("old-synthetic-user", "old-synthetic-password", AccountRole.PARENT)
        testPrefs.commitSucceeds = false
        val auth = loginCoordinator()
        val failure = runCatching {
            auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, false, vault)
        }.exceptionOrNull()
        assertTrue(failure is SimeCredentialStorageError)
        assertFalse(auth.isAuthenticated)
        assertFalse(vault.hasSavedCredentials())
    }

    @Test
    fun `cancellation during persistence remains cancellation and clears new session`() = runBlocking {
        val cancellingVault = SimeCredentialVault(testPrefs, object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> = throw CancellationException()
            override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray = error("Not used")
        })
        val auth = loginCoordinator()
        val failure = runCatching {
            auth.loginWithPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, true, cancellingVault)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(auth.isAuthenticated)
        assertTrue(auth.session.http.cookies.isEmpty())
    }

    @Test
    fun `saved credential diagnostics redact username and password`() {
        val diagnostic = SavedCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT).toString()
        assertFalse(diagnostic.contains("synthetic-user"))
        assertFalse(diagnostic.contains("synthetic-password"))
    }

    private fun loginCoordinator(): SimeAuthCoordinator {
        val executor = SimeHttpExecutor { request ->
            val resource = when {
                request.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> SimePortalFixtures.PUBLIC_ENTRY_HTML
                request.url.contains("login_desktop") -> SimePortalFixtures.LOGIN_DIALOG_VALID_HTML
                request.method == "POST" -> SimePortalFixtures.LOGIN_RESPONSE_SUCCESS_JSON
                else -> SimePortalFixtures.DASHBOARD_STUDENT_A_HTML
            }
            SimeHttpExecutor.RawHopResponse(200,
                mapOf("Set-Cookie" to listOf("synthetic-session=value; Path=/; Secure")),
                SimePortalFixtures.readFixture(resource).toByteArray())
        }
        return SimeAuthCoordinator(SimePortalSession(http = SimeHttpTransport(
            executor = executor, breaker = CircuitBreaker(), globalBreaker = CircuitBreaker())))
    }

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
        val record = testPrefs.getString("credential_record", null)!!
        val parts = record.split('|')
        val rawPayload = parts[4]
        val rawIv = parts[3]

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
    fun `credential deletion reports a failed durable write`() {
        vault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
        testPrefs.commitSucceeds = false
        assertTrue(runCatching { vault.clearCredentials() }.isFailure)
    }

    @Test
    fun `tampered ciphertext causes GCM authentication failure and returns null`() {
        vault.saveCredentials("student", "pass", AccountRole.STUDENT)
        val fields = testPrefs.getString("credential_record", null)!!.split('|').toMutableList()
        val validPayloadB64 = fields[4]
        val bytes = Base64.getDecoder().decode(validPayloadB64)

        // Tamper with one byte of the ciphertext
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        val tamperedPayloadB64 = Base64.getEncoder().encodeToString(bytes)
        fields[4] = tamperedPayloadB64
        testPrefs.edit().putString("credential_record", fields.joinToString("|")).apply()

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
