package md.sime.opensime

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.portal.CircuitBreaker
import md.sime.opensime.core.portal.SimeAuthCoordinator
import md.sime.opensime.core.portal.SimeCredentialStorageError
import md.sime.opensime.core.portal.SimeCredentialVault
import md.sime.opensime.core.portal.SimeHttpExecutor
import md.sime.opensime.core.portal.SimeHttpTransport
import md.sime.opensime.core.portal.SimePortalSession
import md.sime.opensime.core.portal.SimeUrlValidator
import md.sime.opensime.core.portal.VaultCipher
import md.sime.opensime.feature.auth.LoginScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Isolated form tests: no portal access or production preferences. */
class LoginSecretOwnershipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun credentialGenerationRevocationDoesNotAffectSnapshotKey() {
        val keys = md.sime.opensime.core.portal.AndroidCredentialGenerationKeys()
        val generation = UUID.randomUUID().toString()
        val snapshotCipher = md.sime.opensime.core.portal.AndroidKeyStoreVaultCipher()
        val snapshot = "synthetic snapshot".toByteArray()
        val (snapshotIv, encryptedSnapshot) = snapshotCipher.encrypt(snapshot)
        try {
            keys.create(generation)
            val (iv, encrypted) = keys.encrypt(generation, "synthetic credential".toByteArray())
            assertEquals("synthetic credential", String(keys.decrypt(generation, iv, encrypted)))
            keys.revoke(generation)
            assertFalse(keys.exists(generation))
            org.junit.Assert.assertThrows(Exception::class.java) { keys.decrypt(generation, iv, encrypted) }
            assertFalse(keys.exists(generation))
            assertEquals("synthetic snapshot", String(snapshotCipher.decrypt(snapshotIv, encryptedSnapshot)))
        } finally { keys.revoke(generation) }
    }

    @Test
    fun rootLoginRecoversFromVaultFailureAndAllowsUncheckedRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val prefix = "phase01a-test-${UUID.randomUUID()}"
        val directory = File(target.cacheDir, prefix)
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("$prefix-$name", mode)
            override fun getFilesDir(): File = directory
        }
        context.getSharedPreferences("open_sime_settings", Context.MODE_PRIVATE)
            .edit().putString("language", "RO").commit()
        val auth = SimeAuthCoordinator(SimePortalSession(http = SimeHttpTransport(
            breaker = CircuitBreaker(), globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor { request ->
                val resource = when {
                    request.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> "public_entry_parent_and_student.html"
                    request.url.contains("login_desktop") -> "login_dialog_valid.html"
                    request.url.contains("wwv_flow.accept") -> "login_response_success.json"
                    request.url.contains("wwv_flow.ajax") -> "report_sem1_marks_theses_qualifiers.html"
                    else -> "dashboard_student_a_sanitized.html"
                }
                val body = if (request.bodyBytes?.toString(Charsets.UTF_8)?.contains("x01=GET") == true) "[]"
                    else instrumentation.context.assets.open("fixtures/portal/$resource").bufferedReader().use { it.readText() }
                SimeHttpExecutor.RawHopResponse(200, emptyMap(), body.toByteArray())
            },
        )))
        val vault = SimeCredentialVault(context, object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> =
                throw IllegalStateException("synthetic-provider-failure")
            override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray = error("Not used")
        })
        val showApp = mutableStateOf(true)
        try {
            compose.setContent {
                if (showApp.value) CompositionLocalProvider(LocalContext provides context) {
                    OpenSimeApp(authCoordinator = auth, credentialVault = vault)
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("onboarding_action_continue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("onboarding_action_continue").performScrollTo().performClick()
            compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
            compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
            compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("auth_error_banner").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(AppStringCatalog.ro.credentialStorageFailed).assertExists()
            compose.onNodeWithTag("auth_submit_btn").assertIsEnabled()
            compose.runOnIdle {
                assertFalse(auth.isAuthenticated)
                assertFalse(vault.hasSavedCredentials())
                check(OpenSimeSettingsState.isExplicitlyLoggedOut(context))
            }
            compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().performClick().assertIsOff()
            compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("compact_bottom_nav").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                check(auth.isAuthenticated)
                assertFalse(vault.hasSavedCredentials())
                assertFalse(OpenSimeSettingsState.isExplicitlyLoggedOut(context))
            }
        } finally {
            compose.runOnIdle { showApp.value = false }
            listOf("open_sime_settings", SimeCredentialVault.PREFS_NAME).forEach {
                target.deleteSharedPreferences("$prefix-$it")
            }
            directory.deleteRecursively()
        }
    }

    @Test
    fun existingKeystoreVaultHonorsBothPersistenceChoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "phase01a-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val vault = SimeCredentialVault(preferences)
        try {
            vault.updateLoginPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, true)
            assertEquals("synthetic-password", vault.loadCredentials()?.password)
            assertFalse(preferences.all.toString().contains("synthetic-password"))
            vault.updateLoginPersistence("synthetic-user", "synthetic-password", AccountRole.PARENT, false)
            assertFalse(vault.hasSavedCredentials())
            assertNull(vault.loadCredentials())
        } finally {
            context.deleteSharedPreferences(preferencesName)
        }
    }

    @Test
    fun persistenceDefaultsToEnabled() {
        compose.setContent {
            OpenSimeTheme {
                LoginScreen(false, null, { _, _, _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().assertIsOn()
    }

    @Test
    fun storageFailureHasRecoverableLocalizedPresentation() {
        compose.setContent {
            OpenSimeTheme {
                LoginScreen(false, SimeCredentialStorageError(), { _, _, _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("auth_error_banner").performScrollTo()
        compose.onNodeWithTag("auth_submit_btn").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            AppLanguage.entries.forEach { language ->
                check(AppStringCatalog.forLanguage(language).credentialStorageFailed.isNotBlank())
            }
        }
    }

    @Test
    fun uncheckedChoiceIsSubmittedAsSessionOnly() {
        var submittedChoice: Boolean? = null
        compose.setContent {
            OpenSimeTheme {
                LoginScreen(false, null, { _, _, _, remember -> submittedChoice = remember }, {}, {})
            }
        }
        compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
        compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, submittedChoice) }
    }

    @Test
    fun savedInstanceStateRestoresChoiceButNeverPassword() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            OpenSimeTheme {
                LoginScreen(false, null, { _, _, _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
        compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("auth_username_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("synthetic-user")))
        compose.onNodeWithTag("auth_password_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().assertIsOff()
    }
}
