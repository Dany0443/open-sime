package md.sime.opensime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import md.sime.opensime.core.data.AgendaSnapshotStore
import md.sime.opensime.core.data.CachedAgendaRepository
import md.sime.opensime.core.data.LiveAgendaRepository
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.portal.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionRestorationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = StandardAesGcmVaultCipher()
    private val preferences = mutableMapOf<String, SimeCredentialVaultTest.TestSharedPreferences>()
    private val context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            preferences.getOrPut(requireNotNull(name)) { SimeCredentialVaultTest.TestSharedPreferences() }
        override fun getFilesDir(): File = temporary.root
    }
    private val vault get() = SimeCredentialVault(context, cipher)
    private fun store(account: String = "synthetic-user") =
        AgendaSnapshotStore(File(context.filesDir, "agenda-snapshots"), cipher, account)
    private fun saveLogin() = vault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)

    @Test fun `startup restoration resumes pending credential cleanup without authenticating`() = runBlocking {
        val prefs = context.getSharedPreferences(SimeCredentialVault.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("credential_record", "1|pending|00000000-0000-0000-0000-000000000001").commit()
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth(calls), vault)?.join()
        assertEquals(0, calls.get())
        assertTrue(retained.loginRequired)
        assertFalse(prefs.contains("credential_record"))
    }
    private fun cacheSiblings() {
        val samples = SampleAgendaRepository.buildDefaultSampleSnapshots().values.toList()
        listOf("id_101", "id_601").forEachIndexed { index, id ->
            val sample = samples[index]
            store().write(sample.copy(student = sample.student.copy(id = StudentId(id)),
                dataSourceMode = DataSourceMode.LIVE_PORTAL, successfulSyncMillis = mapOf("grades" to 1000L)))
        }
    }

    @Test fun `logout after account switch authentication cleans verified account before publication`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        val original = store().list()
        assertTrue(original.isNotEmpty())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        val candidateVault = SimeCredentialVault(context, blockedCipher)
        val switching = retained.signIn(context, coordinator, candidateVault, "other-account", "synthetic-password", AccountRole.PARENT, true)
        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        assertTrue(coordinator.isAuthenticated)
        val logout = async(start = CoroutineStart.UNDISPATCHED) { retained.logout(context, coordinator, candidateVault) }
        release.countDown()
        logout.await()
        switching?.join()
        assertNull(retained.logoutError)
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        assertFalse(coordinator.isAuthenticated)
        val calls = AtomicInteger()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.restore(context, auth(calls), vault)?.join()
        assertEquals(0, calls.get())
    }

    @Test fun `failed login guard write preserves verified switched account for logout`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        val original = store().list()
        val settings = preferences.getValue("open_sime_settings")
        val failingCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                settings.commitSucceeds = false
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        retained.signIn(context, coordinator, SimeCredentialVault(context, failingCipher), "other-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertTrue(retained.authError is SimeCredentialStorageError)
        assertFalse(coordinator.isAuthenticated)
        assertFalse(retained.isLiveAuthenticated)
        settings.commitSucceeds = true
        retained.logout(context, coordinator, vault)
        assertNull(retained.logoutError)
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        retained.logout(context, coordinator, vault)
        assertNull(retained.logoutError)
        val calls = AtomicInteger()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.recoverPendingLogout(context, auth(calls), vault)?.join()
        restarted.restore(context, auth(calls), vault)?.join()
        assertEquals(0, calls.get())
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
    }

    @Test fun `failed switched login cleanup remains account scoped on restart`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        val original = store().list()
        val settings = preferences.getValue("open_sime_settings")
        val failingCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                settings.commitSucceeds = false
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        retained.signIn(context, coordinator, SimeCredentialVault(context, failingCipher), "other-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        settings.commitSucceeds = true
        val credentials = preferences.getValue(SimeCredentialVault.PREFS_NAME)
        credentials.commitSucceeds = false
        retained.logout(context, coordinator, vault)
        assertNotNull(retained.logoutError)
        assertEquals(other.accountStorageId, OpenSimeSettingsState.pendingLogoutCache(context))
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
        credentials.commitSucceeds = true
        val calls = AtomicInteger()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.recoverPendingLogout(context, auth(calls), vault)?.join()
        assertNull(restarted.logoutError)
        assertNull(OpenSimeSettingsState.pendingLogoutCache(context))
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        assertEquals(0, calls.get())
    }

    @Test fun `nonpersisting transition marker prevents account switch before portal authentication`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val calls = AtomicInteger()
        val coordinator = auth(calls)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        val before = calls.get()
        val original = store().list()
        val otherBefore = other.list()
        val settings = preferences.getValue("open_sime_settings")
        settings.applyFailedCommit = false
        settings.commitSucceeds = false
        retained.signIn(context, coordinator, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertEquals(before, calls.get())
        assertTrue(retained.authError is SimeCredentialStorageError)
        assertEquals(original, store().list())
        assertEquals(otherBefore, other.list())
        assertFalse(OpenSimeSettingsState.hasAuthTransition(context))
    }

    @Test fun `verified owner write failure leaves unknown recovery after restart`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val calls = AtomicInteger()
        val settings = context.getSharedPreferences("open_sime_settings", Context.MODE_PRIVATE) as SimeCredentialVaultTest.TestSharedPreferences
        settings.applyFailedCommit = false
        val coordinator = auth(calls) { if (calls.get() == 2) settings.commitSucceeds = false }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        val original = store().list()
        retained.signIn(context, coordinator, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertTrue(retained.authError is SimeCredentialStorageError)
        assertFalse(coordinator.isAuthenticated)
        assertTrue(OpenSimeSettingsState.hasAuthTransition(context))
        settings.commitSucceeds = true
        val beforeRetry = calls.get()
        retained.signIn(context, coordinator, vault, "third-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertEquals(beforeRetry, calls.get())
        assertTrue(retained.unknownLogoutCache || !OpenSimeSettingsState.hasAuthTransition(context))
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val restartCalls = AtomicInteger()
        restarted.restore(context, auth(restartCalls), vault)?.join()
        assertTrue(restarted.unknownLogoutCache)
        assertNotNull(restarted.logoutError)
        assertEquals(original, store().list())
        assertFalse(other.list().isEmpty())
        assertEquals(0, restartCalls.get())
    }

    @Test fun `nonpersisting guard failure after verified account switch leaves recovery warning after restart`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        val settings = preferences.getValue("open_sime_settings")
        settings.applyFailedCommit = false
        val failingCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                settings.commitSucceeds = false
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        retained.signIn(context, coordinator, SimeCredentialVault(context, failingCipher), "other-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertFalse(coordinator.isAuthenticated)
        settings.commitSucceeds = true
        val calls = AtomicInteger()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.recoverPendingLogout(context, auth(calls), vault)?.join()
        assertTrue(other.list().isEmpty() || restarted.unknownLogoutCache || restarted.logoutError != null)
        assertEquals(0, calls.get())
    }

    @Test fun `pending verified account cleanup precedes a different account login`() = runBlocking {
        cacheSiblings()
        val b = store("other-account")
        b.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val calls = AtomicInteger()
        val coordinator = auth(calls) { if (calls.get() == 3) assertTrue(b.list().isEmpty()) }
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        val a = store().list()
        val settings = preferences.getValue("open_sime_settings")
        settings.applyFailedCommit = false
        val failing = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                settings.commitSucceeds = false
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        retained.signIn(context, coordinator, SimeCredentialVault(context, failing), "other-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertEquals(b.accountStorageId, OpenSimeSettingsState.pendingLogoutCache(context))
        settings.commitSucceeds = true
        val before = calls.get()
        retained.signIn(context, coordinator, vault, "third-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertEquals(before + 1, calls.get())
        assertTrue(retained.isLiveAuthenticated)
        assertTrue(b.list().isEmpty())
        assertEquals(a, store().list())
        assertNull(OpenSimeSettingsState.pendingLogoutCache(context))
    }

    @Test fun `failed pending cleanup blocks another account login`() = runBlocking {
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        OpenSimeSettingsState.recordExplicitLogout(context, other.accountStorageId)
        val settings = preferences.getValue("open_sime_settings")
        settings.applyFailedCommit = false
        settings.commitSucceeds = false
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, auth(calls), vault, "third-account", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertEquals(0, calls.get())
        assertNotNull(retained.logoutError)
        assertEquals(other.accountStorageId, OpenSimeSettingsState.pendingLogoutCache(context))
    }

    @Test fun `different account cannot replace a pending cleanup target`() {
        val first = store("other-account")
        val second = store("third-account")
        OpenSimeSettingsState.recordExplicitLogout(context, first.accountStorageId)
        assertThrows(IllegalStateException::class.java) {
            OpenSimeSettingsState.recordExplicitLogout(context, second.accountStorageId)
        }
        assertEquals(first.accountStorageId, OpenSimeSettingsState.pendingLogoutCache(context))
    }

    @Test fun `unknown cache recovery cannot replace another pending account`() = runBlocking {
        val first = store("other-account")
        val second = store("third-account")
        val sample = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
        first.write(sample)
        second.write(sample)
        OpenSimeSettingsState.setUnknownLogoutCache(context, true)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.restore(context, coordinator, vault)?.join()
        assertTrue(retained.unknownLogoutCache)
        OpenSimeSettingsState.recordExplicitLogout(context, first.accountStorageId)
        retained.recoverUnknownCache(context, coordinator, "third-account", "synthetic-password", AccountRole.PARENT)!!.join()
        assertEquals(first.accountStorageId, OpenSimeSettingsState.pendingLogoutCache(context))
        assertFalse(first.list().isEmpty())
        assertFalse(second.list().isEmpty())
        assertNotNull(retained.authError)
    }

    @Test fun `verified account store deletion failure remains pending for restart`() = runBlocking {
        cacheSiblings()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        retained.snapshotStore = AgendaSnapshotStore(File(context.filesDir, "agenda-snapshots"), cipher,
            "synthetic-user", deleteAccountDirectory = { false })
        retained.logout(context, coordinator, vault)
        assertNotNull(retained.logoutError)
        assertEquals(store().accountStorageId, OpenSimeSettingsState.pendingLogoutCache(context))
        assertFalse(store().list().isEmpty())
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.recoverPendingLogout(context, auth(AtomicInteger()), vault)?.join()
        assertTrue(store().list().isEmpty())
        assertNull(restarted.logoutError)
    }

    @Test fun `logout after account switch publication clears only the new account`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        val original = store().list()
        retained.signIn(context, coordinator, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        assertTrue(retained.isLiveAuthenticated)
        retained.logout(context, coordinator, vault)
        assertNull(retained.logoutError)
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        retained.logout(context, coordinator, vault)
        assertNull(retained.logoutError)
        assertEquals(original, store().list())
    }

    @Test fun `cancelled verified account switch retains cleanup ownership`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        val original = store().list()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        val switching = retained.signIn(context, coordinator, SimeCredentialVault(context, blocked),
            "other-account", "synthetic-password", AccountRole.PARENT, true)!!
        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        switching.cancel()
        release.countDown()
        switching.join()
        retained.logout(context, coordinator, vault)
        assertEquals(original, store().list())
        assertTrue(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
    }

    @Test fun `logout after prior logout still clears a newly verified cancelled account`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(AtomicInteger())
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        retained.logout(context, coordinator, vault)
        assertNull(retained.logoutError)
        assertFalse(other.list().isEmpty())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return cipher.encrypt(plainText)
            }
            override fun decrypt(iv: ByteArray, cipherText: ByteArray) = cipher.decrypt(iv, cipherText)
        }
        val switching = retained.signIn(context, coordinator, SimeCredentialVault(context, blocked),
            "other-account", "synthetic-password", AccountRole.PARENT, true)!!
        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        switching.cancel()
        release.countDown()
        switching.join()
        retained.logout(context, coordinator, vault)
        assertTrue(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
    }

    @Test fun `rejected credentials can be corrected without unknown cleanup`() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = auth(calls) { if (calls.get() == 1) throw SimeLoginRejectedError("Synthetic rejection") }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, coordinator, vault, "synthetic-user", "wrong-password", AccountRole.PARENT, true)!!.join()
        assertTrue(retained.authError is SimeLoginRejectedError)
        assertFalse(OpenSimeSettingsState.hasAuthTransition(context))
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertTrue(retained.isLiveAuthenticated)
        assertEquals(2, calls.get())
    }

    @Test fun `transient login failure can be retried without unknown cleanup`() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = auth(calls) { if (calls.get() == 1) throw SimeNetworkError("Synthetic network failure") }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertTrue(retained.authError is SimeNetworkError)
        assertFalse(OpenSimeSettingsState.hasAuthTransition(context))
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)!!.join()
        assertTrue(retained.isLiveAuthenticated)
        assertEquals(2, calls.get())
    }

    @Test fun `failed account switch does not grant candidate cleanup ownership`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val otherBefore = other.list()
        val calls = AtomicInteger()
        val coordinator = auth(calls) { if (calls.get() == 2) throw SimeLoginRejectedError("Synthetic rejection") }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, coordinator, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        retained.signIn(context, coordinator, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)?.join()
        assertFalse(retained.isLiveAuthenticated)
        retained.logout(context, coordinator, vault)
        assertTrue(store().list().isEmpty())
        assertEquals(otherBefore, other.list())
        assertFalse(vault.hasSavedCredentials())
    }

    @Test fun `logout during account switch clears established account and preserves candidate`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val beforeOther = other.list()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = auth(calls) {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val startup = retained.restore(context, coordinator, vault)
        entered.await()
        val candidate = retained.signIn(context, coordinator, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)
        val logout = async(start = CoroutineStart.UNDISPATCHED) { retained.logout(context, coordinator, vault) }
        release.complete(Unit)
        logout.await()
        startup?.join()
        candidate?.join()
        assertNull(retained.logoutError)
        assertTrue(store().list().isEmpty())
        assertEquals(beforeOther, other.list())
        assertFalse(vault.hasSavedCredentials())
        assertFalse(coordinator.isAuthenticated)
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val restartedCalls = AtomicInteger()
        restarted.restore(context, auth(restartedCalls), vault)?.join()
        assertEquals(0, restartedCalls.get())
    }

    @Test fun `logout during first unverified login does not delete candidate cache`() = runBlocking {
        val candidateStore = store("other-account")
        candidateStore.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val before = candidateStore.list()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = auth(AtomicInteger()) {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val login = retained.signIn(context, coordinator, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)
        entered.await()
        val logout = async(start = CoroutineStart.UNDISPATCHED) { retained.logout(context, coordinator, vault) }
        release.complete(Unit)
        logout.await()
        login?.join()
        assertEquals(before, candidateStore.list())
        assertTrue(retained.unknownLogoutCache)
        assertNotNull(retained.logoutError)
        assertFalse(vault.hasSavedCredentials())
        assertFalse(coordinator.isAuthenticated)
    }

    @Test fun `restart after initial guard and before ownership check preserves incomplete cleanup`() = runBlocking {
        cacheSiblings()
        val prefs = context.getSharedPreferences(SimeCredentialVault.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("credential_record", "1|eligible|incomplete").commit()
        OpenSimeSettingsState.recordExplicitLogout(context, ownershipCheckPending = true)
        val calls = AtomicInteger()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.restore(context, auth(calls), vault)?.join()
        assertTrue(restarted.unknownLogoutCache)
        assertNotNull(restarted.logoutError)
        assertFalse(store().list().isEmpty())
        assertEquals(0, calls.get())
    }

    @Test fun `pending ownership without cache completes without unknown warning`() = runBlocking {
        OpenSimeSettingsState.recordExplicitLogout(context, ownershipCheckPending = true)
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val calls = AtomicInteger()
        restarted.restore(context, auth(calls), vault)?.join()
        assertFalse(OpenSimeSettingsState.hasPendingOwnershipCheck(context))
        assertFalse(restarted.unknownLogoutCache)
        assertNull(restarted.logoutError)
        assertEquals(0, calls.get())
    }

    @Test fun `pending ownership recovers known account and preserves another account`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        OpenSimeSettingsState.recordExplicitLogout(context, ownershipCheckPending = true)
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val calls = AtomicInteger()
        restarted.restore(context, auth(calls), vault)?.join()
        assertTrue(store().list().isEmpty())
        assertFalse(other.list().isEmpty())
        assertFalse(restarted.unknownLogoutCache)
        assertFalse(OpenSimeSettingsState.hasPendingOwnershipCheck(context))
        assertNull(restarted.logoutError)
        assertEquals(0, calls.get())
    }

    @Test fun `unreadable saved login leaves encrypted account cache orphaned after logout`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val before = store().list()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val prefs = context.getSharedPreferences(SimeCredentialVault.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("credential_record", "1|eligible|incomplete").commit()
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(calls)
        retained.restore(context, coordinator, vault)?.join()
        assertNull(retained.currentSnapshot)
        retained.logout(context, coordinator, vault)
        assertNotNull(retained.logoutError)
        assertTrue(retained.unknownLogoutCache)
        assertEquals(before, store().list())
        assertFalse(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.restore(context, auth(calls), vault)?.join()
        assertNull(restarted.currentSnapshot)
        assertTrue(restarted.unknownLogoutCache)
        assertEquals(0, calls.get())
        val recoveryAuth = auth(calls)
        val settings = preferences.getValue("open_sime_settings")
        settings.commitSucceeds = false
        restarted.recoverUnknownCache(context, recoveryAuth, "synthetic-user", "synthetic-password", AccountRole.PARENT)?.join()
        assertFalse(store().list().isEmpty())
        assertFalse(other.list().isEmpty())
        assertTrue(restarted.unknownLogoutCache)
        assertNotNull(restarted.authError)
        assertFalse(recoveryAuth.isAuthenticated)
        settings.commitSucceeds = true
        restarted.recoverUnknownCache(context, recoveryAuth, "synthetic-user", "synthetic-password", AccountRole.PARENT)?.join()
        assertTrue(store().list().isEmpty())
        assertFalse(other.list().isEmpty())
        assertTrue(restarted.unknownLogoutCache)
        assertNotNull(restarted.logoutError)
        assertFalse(recoveryAuth.isAuthenticated)
        assertFalse(vault.hasSavedCredentials())
        assertEquals(2, calls.get())
        val credentials = preferences.getValue(SimeCredentialVault.PREFS_NAME)
        credentials.commitSucceeds = false
        restarted.acknowledgeUnknownCache(context, vault)?.join()
        assertNotNull(restarted.logoutError)
        assertFalse(OpenSimeSettingsState.hasAcknowledgedUnknownCache(context))
        credentials.commitSucceeds = true
        restarted.acknowledgeUnknownCache(context, vault)?.join()
        assertNull(restarted.logoutError)
        assertTrue(restarted.unknownLogoutCache)
        restarted.signIn(context, recoveryAuth, vault, "other-account", "synthetic-password", AccountRole.PARENT, true)?.join()
        assertTrue(restarted.isLiveAuthenticated)
        assertFalse(vault.hasSavedCredentials())
        assertFalse(other.list().isEmpty())
        val nextCalls = AtomicInteger()
        val next = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        next.restore(context, auth(nextCalls), vault)?.join()
        assertTrue(next.unknownLogoutCache)
        assertNull(next.logoutError)
        assertEquals(0, nextCalls.get())
    }

    @Test fun `logout during credential decryption clears only restored account after restart`() = runBlocking {
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        val decryptCount = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray) = cipher.encrypt(plainText)
            override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
                if (decryptCount.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return cipher.decrypt(iv, cipherText)
            }
        }
        val guardedVault = SimeCredentialVault(context, blockingCipher)
        guardedVault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
        val calls = AtomicInteger()
        val coordinator = auth(calls)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val startup = retained.restore(context, coordinator, guardedVault)
        assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        val logout = async(start = CoroutineStart.UNDISPATCHED) { retained.logout(context, coordinator, guardedVault) }
        release.countDown()
        logout.await()
        startup?.join()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.recoverPendingLogout(context, auth(AtomicInteger()), SimeCredentialVault(context, cipher))?.join()
        assertTrue(store().list().isEmpty())
        assertFalse(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        assertFalse(coordinator.isAuthenticated)
        assertEquals(0, calls.get())
    }

    @Test fun `restart after logout guard before account target recovers only that account`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val other = store("other-account")
        other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
        // Model process death after the first durable guard and before target discovery.
        OpenSimeSettingsState.recordExplicitLogout(context)
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val calls = AtomicInteger()
        restarted.recoverPendingLogout(context, auth(calls), SimeCredentialVault(context, cipher))?.join()
        assertNull(restarted.logoutError)
        assertTrue(store().list().isEmpty())
        assertFalse(other.list().isEmpty())
        assertFalse(vault.hasSavedCredentials())
        assertEquals(0, calls.get())
    }

    @Test fun `startup recovery never authenticates with saved credentials`() = runBlocking {
        saveLogin()
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val coordinator = auth(calls)
        retained.recoverPendingLogout(context, coordinator, vault)?.join()
        assertEquals(0, calls.get())
        assertFalse(coordinator.isAuthenticated)
        assertNull(retained.currentSnapshot)
        assertTrue(vault.hasSavedCredentials())
    }

    @Test fun `startup recovery completes recorded logout without authenticating`() = runBlocking {
        saveLogin()
        OpenSimeSettingsState.recordExplicitLogout(context)
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.recoverPendingLogout(context, auth(calls), vault)?.join()
        assertEquals(0, calls.get())
        assertFalse(vault.hasSavedCredentials())
        assertNull(retained.logoutError)
        assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(context))
    }

    @Test
    fun `cold start restores saved account once and reconciles the selected student`() = runBlocking {
        saveLogin()
        val calls = AtomicInteger()
        val auth = auth(calls)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val first = retained.restore(context, auth, vault)
        val duplicate = retained.restore(context, auth, vault)
        assertSame(first, duplicate)
        first?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        assertTrue(retained.isLiveAuthenticated)
        assertTrue(retained.repository is LiveAgendaRepository)
        assertEquals(1, calls.get())
        assertNotNull(retained.currentSnapshot)
        assertFalse(retained.isRestoringSession)
        assertFalse(retained.isLoading)
        retained.restore(context, auth, vault)?.join()
        assertEquals(1, calls.get())
    }

    @Test
    fun `network failure keeps only authorized stale cache and permits explicit retry`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val calls = AtomicInteger()
        var offline = true
        val auth = auth(calls) { if (offline) throw SimeNetworkError("Synthetic offline") }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth, vault)?.join()
        assertTrue(retained.repository is CachedAgendaRepository)
        assertFalse(retained.isLiveAuthenticated)
        assertFalse(auth.isAuthenticated)
        assertTrue(retained.currentSnapshot!!.stale)
        assertEquals(mapOf("grades" to 1000L), retained.currentSnapshot!!.successfulSyncMillis)
        assertNotNull(retained.restorationError)
        assertFalse(retained.isRestoringSession)
        retained.selectStudent(StudentId("id_601"))?.join()
        assertEquals(StudentId("id_601"), retained.currentSnapshot?.studentId)
        offline = false
        retained.restore(context, auth, vault, retry = true)?.join()
        retained.selectStudent(StudentId("id_601"))?.join()
        assertTrue(retained.isLiveAuthenticated)
        assertEquals(StudentId("id_601"), retained.currentSnapshot?.studentId)
        assertEquals(2, calls.get())
    }

    @Test
    fun `process restart without saved credentials cannot expose cached private records`() = runBlocking {
        cacheSiblings()
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth(calls), vault)?.join()
        assertNull(retained.repository)
        assertNull(retained.currentSnapshot)
        assertEquals(0, calls.get())
        assertFalse(retained.isRestoringSession)
    }

    @Test
    fun `invalid saved credentials revoke offline display and never loop`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val calls = AtomicInteger()
        val auth = auth(calls) { throw SimeLoginRejectedError("Synthetic rejection") }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth, vault)?.join()
        assertTrue(retained.loginRequired)
        assertTrue(retained.authError is SimeLoginRejectedError)
        assertNull(retained.repository)
        assertNull(retained.currentSnapshot)
        assertFalse(vault.hasSavedCredentials())
        assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(context))
        retained.restore(context, auth, vault)?.join()
        retained.restore(context, auth, vault, retry = true)?.join()
        assertEquals(1, calls.get())
    }

    @Test
    fun `undecryptable saved login never authorizes private cache or sends a login`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val stored = preferences.getValue(SimeCredentialVault.PREFS_NAME)
        val fields = (stored.data["credential_record"] as String).split('|').toMutableList()
        fields[4] = "corrupt"
        stored.data["credential_record"] = fields.joinToString("|")
        val calls = AtomicInteger()
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth(calls), vault)?.join()
        assertFalse(retained.isLiveAuthenticated)
        assertNull(retained.currentSnapshot)
        assertEquals(0, calls.get())
    }

    @Test
    fun `offline start without cache exits loading and retries only on request`() = runBlocking {
        saveLogin()
        val calls = AtomicInteger()
        val auth = auth(calls) { throw SimeNetworkError("Synthetic offline") }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth, vault)?.join()
        assertFalse(retained.isRestoringSession)
        assertNotNull(retained.restorationError)
        assertNull(retained.repository)
        retained.restore(context, auth, vault)?.join()
        assertEquals(1, calls.get())
        retained.restore(context, auth, vault, retry = true)?.join()
        assertEquals(2, calls.get())
    }

    @Test
    fun `session-only authentication survives recreation but not a new process`() = runBlocking {
        val calls = AtomicInteger()
        val auth = auth(calls)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, auth, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, false)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        assertTrue(auth.isAuthenticated)
        assertFalse(vault.hasSavedCredentials())
        val original = retained.repository
        retained.restore(context, auth, vault)?.join()
        assertSame(original, retained.repository)
        assertEquals(1, calls.get())
        val restartedCalls = AtomicInteger()
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.restore(context, auth(restartedCalls), vault)?.join()
        assertNull(restarted.repository)
        assertFalse(restarted.isLiveAuthenticated)
        assertEquals(0, restartedCalls.get())
    }

    @Test
    fun `logout interrupts restoration clears all siblings and blocks a fresh startup`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = auth(calls) {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val startup = retained.restore(context, auth, vault)
        entered.await()
        retained.selectStudent(StudentId("id_601"))?.join()
        val logout = async(start = CoroutineStart.UNDISPATCHED) { retained.logout(context, auth, vault) }
        release.complete(Unit)
        logout.await()
        startup?.join()
        assertNull(retained.currentSnapshot)
        assertTrue(store().list().isEmpty())
        assertFalse(auth.isAuthenticated)
        assertFalse(vault.hasSavedCredentials())
        val restarted = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        restarted.restore(context, auth, vault)?.join()
        assertTrue(restarted.loginRequired)
        assertEquals(1, calls.get())
    }

    @Test
    fun `manual account change owns state when an old restoration responds late`() = runBlocking {
        saveLogin()
        cacheSiblings()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = auth(calls) {
            if (calls.get() == 1) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val startup = retained.restore(context, auth, vault)
        entered.await()
        val manual = retained.signIn(context, auth, vault, "synthetic-other", "synthetic-password", AccountRole.PARENT, true)
        release.complete(Unit)
        manual?.join()
        startup?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        assertTrue(retained.isLiveAuthenticated)
        assertEquals("synthetic-other", vault.loadCredentials()?.username)
        assertEquals(store("synthetic-other").accountStorageId, retained.snapshotStore?.accountStorageId)
        assertTrue(store().list().isNotEmpty())
        assertEquals(2, calls.get())
    }

    @Test
    fun `expired in-process session can reauthenticate once on explicit retry`() = runBlocking {
        val calls = AtomicInteger()
        val auth = auth(calls)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, auth, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        auth.session.clear()
        retained.restore(context, auth, vault, retry = true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        assertTrue(auth.isAuthenticated)
        assertEquals(2, calls.get())
    }

    @Test
    fun `recreation resume and timezone changes do not duplicate a successful reconciliation`() = runBlocking {
        saveLogin()
        val calls = AtomicInteger()
        val requests = AtomicInteger()
        val auth = auth(calls, requests = requests)
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.restore(context, auth, vault)?.join()
        val selected = StudentId(retained.selectedStudentIdValue)
        retained.selectStudent(selected)?.join()
        val before = requests.get()
        val timezone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
            retained.restore(context, auth, vault)?.join()
            retained.selectStudent(selected)?.join()
            assertEquals(before, requests.get())
            assertEquals(1, calls.get())
        } finally { java.util.TimeZone.setDefault(timezone) }
    }

    @Test
    fun `failed durable eligibility cannot report a successful persistent login`() = runBlocking {
        OpenSimeSettingsState.recordExplicitLogout(context)
        preferences.getValue("open_sime_settings").commitSucceeds = false
        val auth = auth(AtomicInteger())
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        retained.signIn(context, auth, vault, "synthetic-user", "synthetic-password", AccountRole.PARENT, true)?.join()
        assertFalse(auth.isAuthenticated)
        assertFalse(retained.isLiveAuthenticated)
        assertFalse(vault.hasSavedCredentials())
        assertTrue(retained.authError is SimeCredentialStorageError)
        assertNull(retained.repository)
        assertFalse(retained.isAuthenticating)
    }

    @Test
    fun `cancelling startup is recoverable and cannot publish a successful session`() = runBlocking {
        saveLogin()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val auth = auth(calls) { entered.complete(Unit); release.await() }
        val retained = OpenSimeRetainedSession(workScope = this, snapshotCipher = cipher)
        val startup = retained.restore(context, auth, vault)!!
        entered.await()
        startup.cancel()
        startup.join()
        assertFalse(retained.isRestoringSession)
        assertFalse(auth.isAuthenticated)
        assertNotNull(retained.restorationError)
        release.complete(Unit)
        retained.restore(context, auth, vault, retry = true)?.join()
        retained.selectStudent(StudentId(retained.selectedStudentIdValue))?.join()
        assertTrue(retained.isLiveAuthenticated)
        assertEquals(2, calls.get())
    }

    private fun auth(calls: AtomicInteger, requests: AtomicInteger? = null, beforeLogin: suspend () -> Unit = {}): SimeAuthCoordinator {
        val transport = SimeHttpTransport(breaker = CircuitBreaker(), globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor { request ->
                requests?.incrementAndGet()
                val fixture = when {
                    request.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> {
                        calls.incrementAndGet(); beforeLogin(); SimePortalFixtures.PUBLIC_ENTRY_HTML
                    }
                    request.url.contains("login_desktop") -> SimePortalFixtures.LOGIN_DIALOG_VALID_HTML
                    request.url.contains("wwv_flow.accept") -> SimePortalFixtures.LOGIN_RESPONSE_SUCCESS_JSON
                    request.url.contains("wwv_flow.ajax") -> SimePortalFixtures.REPORT_SEM1_HTML
                    request.url.contains("P1_ELEVE_ID:ID_601") -> SimePortalFixtures.DASHBOARD_STUDENT_B_HTML
                    else -> SimePortalFixtures.DASHBOARD_STUDENT_A_HTML
                }
                val body = if (request.bodyBytes?.toString(Charsets.UTF_8)?.contains("x01=GET") == true) "[]"
                    else SimePortalFixtures.readFixture(fixture)
                SimeHttpExecutor.RawHopResponse(200, emptyMap(), body.toByteArray())
            })
        return SimeAuthCoordinator(SimePortalSession(http = transport, todayProvider = { "2026-10-24" }))
    }
}
