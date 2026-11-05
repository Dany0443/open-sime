package md.sime.opensime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import md.sime.opensime.core.data.AgendaSnapshotStore
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.portal.AndroidKeyStoreVaultCipher
import md.sime.opensime.core.portal.CircuitBreaker
import md.sime.opensime.core.portal.SimeAuthCoordinator
import md.sime.opensime.core.portal.SimeCredentialVault
import md.sime.opensime.core.portal.SimeHttpExecutor
import md.sime.opensime.core.portal.SimeHttpTransport
import md.sime.opensime.core.portal.SimePortalSession
import md.sime.opensime.core.portal.SimeUrlValidator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic portal responses and namespaced storage, confined to the debug installation. */
class UnifiedLogoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownRecoveryPreservesDifferentPendingAccount() = runBlocking {
        val fixture = Fixture()
        try {
            val sample = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
            fixture.account.write(sample)
            fixture.other.write(sample)
            OpenSimeSettingsState.setUnknownLogoutCache(fixture.context, true)
            val retained = OpenSimeRetainedSession(workScope = this)
            val auth = fixture.auth()
            retained.restore(fixture.context, auth, fixture.vault)?.join()
            assertTrue(retained.unknownLogoutCache)
            OpenSimeSettingsState.recordExplicitLogout(fixture.context, fixture.other.accountStorageId)
            retained.recoverUnknownCache(fixture.context, auth, "synthetic-user", "synthetic-password", AccountRole.PARENT)!!.join()
            assertEquals(fixture.other.accountStorageId, OpenSimeSettingsState.pendingLogoutCache(fixture.context))
            assertEquals(1, fixture.account.list().size)
            assertEquals(1, fixture.other.list().size)
            assertNotNull(retained.authError)
            assertFalse(auth.isAuthenticated)
        } finally { fixture.close() }
    }

    @Test fun doubleFailureAcrossProcessRestart() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("doubleFailureStage")
        val failure = InstrumentationRegistry.getArguments().getString("cleanupFailure") ?: "both"
        org.junit.Assume.assumeTrue(stage == "prepare" || stage == "verify")
        require(failure in setOf("both", "guard", "vault"))
        val fixture = Fixture(if (stage == "verify") "verify" else "prepare", "cleanup-failure-$failure")
        if (stage == "prepare") {
            OpenSimeSettingsState.recordSuccessfulLogin(fixture.context) { }
            fixture.vault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
            fixture.failBothWrites = failure == "both"
            fixture.failGuard = failure == "guard"
            fixture.failDeletion = failure == "vault"
            val retained = OpenSimeRetainedSession()
            retained.logout(fixture.context, fixture.auth(), fixture.vault)
            assertNotNull(retained.logoutError)
            retained.logout(fixture.context, fixture.auth(), fixture.vault)
            assertNotNull(retained.logoutError)
        } else {
            try {
                assertEquals(failure == "vault", OpenSimeSettingsState.isExplicitlyLoggedOut(fixture.context))
                // The credential-generation key is revoked independently of preference writes.
                assertFalse(fixture.vault.hasSavedCredentials())
                val auth = fixture.auth()
                val owner = owner()
                lateinit var retained: OpenSimeRetainedSession
                try {
                    compose.setContent {
                        CompositionLocalProvider(LocalContext provides fixture.context, LocalViewModelStoreOwner provides owner) {
                            retained = viewModel()
                            OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault)
                        }
                    }
                    compose.waitUntil(5_000) {
                        if (failure == "vault") !fixture.vault.hasSavedCredentials() else true
                    }
                    compose.waitForIdle()
                    compose.runOnIdle {
                        assertEquals(0, fixture.loginRequests.get())
                        assertFalse(auth.isAuthenticated)
                        assertNull(retained.currentSnapshot)
                        assertFalse(fixture.vault.hasSavedCredentials())
                    }
                } finally { owner.viewModelStore.clear() }
            } finally { fixture.close() }
        }
    }

    @Test
    fun queuedSubmitCallbacksDoNotDuplicateLogin() {
        val fixture = Fixture()
        val mounted = mutableStateOf(true)
        val owner = owner()
        val release = CompletableDeferred<Unit>()
        val auth = fixture.auth { release.await() }
        try {
            compose.setContent {
                if (mounted.value) CompositionLocalProvider(
                    LocalContext provides fixture.context, LocalViewModelStoreOwner provides owner,
                ) { OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault) }
            }
            waitFor("auth_username_input")
            compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
            compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
            compose.onNodeWithTag("auth_submit_btn").performScrollTo()
            val submit = compose.onNodeWithTag("auth_submit_btn").fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { submit(); submit() }
            release.complete(Unit)
            waitFor("compact_bottom_nav")
            compose.runOnIdle { assertEquals(1, fixture.loginRequests.get()) }
        } finally {
            release.complete(Unit)
            compose.runOnIdle { mounted.value = false; owner.viewModelStore.clear() }
            fixture.close()
        }
    }

    @Test
    fun recreationDuringAuthenticationRetainsWorkAndClearsPasswordInput() {
        val fixture = Fixture()
        val mounted = mutableStateOf(true)
        val owner = owner()
        val release = CompletableDeferred<Unit>()
        val auth = fixture.auth { release.await() }
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent {
                if (mounted.value) CompositionLocalProvider(
                    LocalContext provides fixture.context, LocalViewModelStoreOwner provides owner,
                ) { OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault) }
            }
            waitFor("auth_username_input")
            compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
            compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
            compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1, fixture.loginRequests.get()) }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("auth_password_input").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("auth_submit_btn").performScrollTo().assertIsNotEnabled()
            release.complete(Unit)
            waitFor("compact_bottom_nav")
            compose.runOnIdle { assertEquals(1, fixture.loginRequests.get()); assertTrue(auth.isAuthenticated) }
        } finally {
            release.complete(Unit)
            compose.runOnIdle { mounted.value = false; owner.viewModelStore.clear() }
            fixture.close()
        }
    }

    @Test
    fun upstreamCancellationLeavesLoadingAndOffersRetry() {
        val fixture = Fixture()
        val mounted = mutableStateOf(true)
        val owner = owner()
        val samples = SampleAgendaRepository()
        val repository = object : md.sime.opensime.core.data.AgendaRepository {
            override fun listStudents() = samples.listStudents()
            override suspend fun getStudentSnapshot(studentId: md.sime.opensime.core.model.StudentId): md.sime.opensime.core.model.StudentAgendaSnapshot =
                throw kotlinx.coroutines.CancellationException("Obsolete upstream request")
        }
        OpenSimeSettingsState.setExplicitlyLoggedOut(fixture.context, false)
        val auth = fixture.auth()
        try {
            compose.setContent {
                if (mounted.value) CompositionLocalProvider(
                    LocalContext provides fixture.context, LocalViewModelStoreOwner provides owner,
                ) { OpenSimeApp(repository, auth, fixture.vault) }
            }
            compose.onNodeWithText(AppStringCatalog.ro.actionRetry).assertExists()
            compose.runOnIdle { assertEquals(0, fixture.requests.get()) }
        } finally {
            compose.runOnIdle { mounted.value = false; owner.viewModelStore.clear() }
            fixture.close()
        }
    }

    @Test fun persistentLoginLogoutAndFreshAppDoNotRestoreAccount() = exerciseLogout(true)
    @Test fun sessionOnlyLogoutAndFreshAppDoNotRestoreAccount() = exerciseLogout(false)
    @Test fun failedDeletionShowsRetryAndBlocksLogin() = exerciseLogout(true, failDeletion = true)
    @Test fun explicitLogoutSurvivesProcessRestart() = exerciseLogout(true,
        restartStage = InstrumentationRegistry.getArguments().getString("phase01bRestartStage"))

    private fun exerciseLogout(persistent: Boolean, failDeletion: Boolean = false, restartStage: String? = null) {
        val fixture = Fixture(restartStage)
        val mounted = mutableStateOf(true)
        var owner = owner()
        var auth = fixture.auth()
        lateinit var retained: OpenSimeRetainedSession
        try {
            compose.setContent {
                if (mounted.value) CompositionLocalProvider(
                    LocalContext provides fixture.context, LocalViewModelStoreOwner provides owner,
                ) {
                    retained = viewModel()
                    OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault)
                }
            }
            if (restartStage == "verify") {
                compose.waitUntil(5_000) {
                    !retained.logoutInProgress && OpenSimeSettingsState.pendingLogoutCache(fixture.context) == null
                }
                waitFor("auth_username_input")
                compose.runOnIdle {
                    assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(fixture.context))
                    assertFalse(fixture.vault.hasSavedCredentials())
                    assertFalse(auth.isAuthenticated)
                    assertTrue(fixture.account.list().isEmpty())
                    assertEquals(1, fixture.other.list().size)
                    assertEquals(0, fixture.requests.get())
                    assertEquals(fixture.preferences, OpenSimeSettingsState.loadFromPreferences(fixture.context))
                }
                return
            }
            waitFor("auth_username_input")
            compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
            compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
            if (!persistent) compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().performClick()
            compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
            waitFor("compact_bottom_nav")
            compose.runOnIdle {
                assertTrue(auth.isAuthenticated)
                assertEquals(persistent, fixture.vault.hasSavedCredentials())
                // Seed two siblings plus an unrelated account with the actual Keystore cipher.
                SampleAgendaRepository.buildDefaultSampleSnapshots().values.forEach(fixture.account::write)
                fixture.other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
                fixture.failDeletion = failDeletion
                if (restartStage == "prepare-failure") {
                    retained.snapshotStore = AgendaSnapshotStore(File(fixture.context.filesDir, "agenda-snapshots"),
                        AndroidKeyStoreVaultCipher(), "synthetic-user", deleteAccountDirectory = { false })
                }
            }
            compose.onNodeWithTag("topbar_action_profile").performClick()
            compose.onNodeWithTag("profile_action_forget_credentials").assertDoesNotExist()
            compose.onNodeWithTag("profile_action_logout").performScrollTo().assertExists()
            compose.onNodeWithTag("topbar_action_settings").performClick()
            compose.onNodeWithTag("settings_action_forget_credentials").assertDoesNotExist()
            val callsBeforeLogout = fixture.requests.get()
            if (persistent && !failDeletion) {
                compose.onNodeWithTag("settings_close_btn").performClick()
                compose.onNodeWithTag("profile_action_logout").performScrollTo().performClick()
            } else {
                compose.onNodeWithTag("settings_action_logout").performScrollTo().performClick()
            }
            if (restartStage == "prepare-failure") {
                compose.waitUntil(5_000) { retained.logoutError != null }
                compose.onNodeWithText(AppStringCatalog.ro.logoutCleanupFailed).assertExists()
                compose.runOnIdle {
                    assertFalse(fixture.vault.hasSavedCredentials())
                    assertTrue(fixture.account.list().isNotEmpty())
                    assertNotNull(OpenSimeSettingsState.pendingLogoutCache(fixture.context))
                    assertEquals(callsBeforeLogout, fixture.requests.get())
                }
                return
            }
            if (failDeletion) {
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("auth_username_input").fetchSemanticsNodes().isEmpty() &&
                        retained.logoutError != null
                }
                compose.onNodeWithText(AppStringCatalog.ro.logoutCleanupFailed).assertExists()
                compose.onNodeWithTag("auth_submit_btn").assertDoesNotExist()
                compose.runOnIdle {
                    assertFalse(auth.isAuthenticated)
                    assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(fixture.context))
                    fixture.failDeletion = false
                }
                compose.onNodeWithText(AppStringCatalog.ro.actionRetry).performClick()
            }
            waitFor("auth_username_input")
            compose.runOnIdle {
                assertFalse(auth.isAuthenticated)
                assertNull(auth.session.sessionId)
                assertEquals(0, auth.session.http.cookies.size)
                assertTrue(auth.availableStudents().isEmpty())
                assertNull(retained.repository)
                assertNull(retained.snapshotStore)
                assertTrue(fixture.account.list().isEmpty())
                assertEquals(1, fixture.other.list().size)
                assertFalse(fixture.vault.hasSavedCredentials())
                assertEquals(fixture.preferences, OpenSimeSettingsState.loadFromPreferences(fixture.context))
                assertEquals(callsBeforeLogout, fixture.requests.get())
                mounted.value = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                owner.viewModelStore.clear()
                owner = owner()
                auth = fixture.auth()
                mounted.value = true
            }
            waitFor("auth_username_input")
            compose.runOnIdle {
                assertFalse(auth.isAuthenticated)
                assertEquals(callsBeforeLogout, fixture.requests.get())
                assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(fixture.context))
            }
        } finally {
            compose.runOnIdle { mounted.value = false; owner.viewModelStore.clear() }
            if (restartStage?.startsWith("prepare") != true) fixture.close()
        }
    }

    @Test
    fun logoutDuringAuthenticationCannotPublishLateSessionOrSnapshots() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mounted = mutableStateOf(true)
        val owner = owner()
        val auth = fixture.auth {
            entered.countDown()
            withContext(NonCancellable + Dispatchers.IO) { check(release.await(10, TimeUnit.SECONDS)) }
        }
        lateinit var retained: OpenSimeRetainedSession
        try {
            fixture.vault.saveCredentials("synthetic-user", "synthetic-password", AccountRole.PARENT)
            OpenSimeSettingsState.setExplicitlyLoggedOut(fixture.context, false)
            SampleAgendaRepository.buildDefaultSampleSnapshots().values.forEach(fixture.account::write)
            fixture.other.write(SampleAgendaRepository.buildDefaultSampleSnapshots().values.first())
            compose.setContent {
                if (mounted.value) CompositionLocalProvider(
                    LocalContext provides fixture.context, LocalViewModelStoreOwner provides owner,
                ) {
                    retained = viewModel()
                    OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.runOnIdle {
                retained.viewModelScope.launch { retained.logout(fixture.context, auth, fixture.vault) }
            }
            compose.runOnIdle { assertTrue(retained.logoutInProgress) }
            release.countDown()
            waitFor("auth_username_input")
            compose.runOnIdle {
                assertFalse(auth.isAuthenticated)
                assertNull(auth.session.sessionId)
                assertEquals(0, auth.session.http.cookies.size)
                assertNull(retained.repository)
                assertTrue(fixture.account.list().isEmpty())
                assertEquals(1, fixture.other.list().size)
                assertFalse(fixture.vault.hasSavedCredentials())
                assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(fixture.context))
            }
        } finally {
            release.countDown()
            compose.runOnIdle { mounted.value = false; owner.viewModelStore.clear() }
            fixture.close()
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun owner() = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    internal class Fixture(restartStage: String? = null, prefixOverride: String? = null) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val target = instrumentation.targetContext
        private val prefix = prefixOverride ?: if (restartStage == null) "phase01b-test-${UUID.randomUUID()}"
            else "phase01b-test-process-restart"
        private val directory = File(target.cacheDir, prefix)
        @Volatile var failDeletion = false
        @Volatile var failBothWrites = false
        @Volatile var failGuard = false
        val requests = AtomicInteger()
        val loginRequests = AtomicInteger()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = target.getSharedPreferences("$prefix-$name", mode)
                if (name != SimeCredentialVault.PREFS_NAME && name != "open_sime_settings") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = original.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { delegate.putBoolean(key, value); return this }
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                            override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                            override fun commit(): Boolean = if (failBothWrites || (failDeletion && name == SimeCredentialVault.PREFS_NAME) || (failGuard && name == "open_sime_settings")) false else delegate.commit()
                        }
                    }
                }
            }
            override fun getFilesDir(): File = directory
        }
        val vault = SimeCredentialVault(context)
        private val cipher = AndroidKeyStoreVaultCipher()
        val account = AgendaSnapshotStore(File(directory, "agenda-snapshots"), cipher, "synthetic-user")
        val other = AgendaSnapshotStore(File(directory, "agenda-snapshots"), cipher, "synthetic-unrelated")
        val preferences = OpenSimeSettingsState(language = md.sime.opensime.core.i18n.AppLanguage.RO,
            themeMode = md.sime.opensime.core.designsystem.ThemeMode.DARK,
            highContrast = true, reduceMotion = true, onboardingCompleted = true)
        init {
            if (restartStage != "verify") {
                OpenSimeSettingsState.saveToPreferences(context, preferences)
                OpenSimeSettingsState.recordExplicitLogout(context)
            }
        }
        fun auth(beforeFirstRequest: suspend () -> Unit = {}): SimeAuthCoordinator {
            var first = true
            return SimeAuthCoordinator(SimePortalSession(http = SimeHttpTransport(
                breaker = CircuitBreaker(), globalBreaker = CircuitBreaker(),
                executor = SimeHttpExecutor { request ->
                    requests.incrementAndGet()
                    if (request.url == SimeUrlValidator.PUBLIC_PORTAL_URL) loginRequests.incrementAndGet()
                    if (first) { first = false; beforeFirstRequest() }
                    val resource = when {
                        request.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> "public_entry_parent_and_student.html"
                        request.url.contains("login_desktop") -> "login_dialog_valid.html"
                        request.url.contains("wwv_flow.accept") -> "login_response_success.json"
                        request.url.contains("wwv_flow.ajax") -> "report_sem1_marks_theses_qualifiers.html"
                        request.url.contains("P1_ELEVE_ID:ID_601") -> "dashboard_student_b_sanitized.html"
                        else -> "dashboard_student_a_sanitized.html"
                    }
                    val body = if (request.bodyBytes?.toString(Charsets.UTF_8)?.contains("x01=GET") == true) "[]"
                        else instrumentation.context.assets.open("fixtures/portal/$resource").bufferedReader().use { it.readText() }
                    SimeHttpExecutor.RawHopResponse(200,
                        mapOf("Set-Cookie" to listOf("synthetic_cookie=value; Path=/; Secure; HttpOnly")), body.toByteArray())
                },
            )))
        }
        fun close() {
            listOf("open_sime_settings", SimeCredentialVault.PREFS_NAME).forEach {
                target.deleteSharedPreferences("$prefix-$it")
            }
            directory.deleteRecursively()
        }
    }
}
