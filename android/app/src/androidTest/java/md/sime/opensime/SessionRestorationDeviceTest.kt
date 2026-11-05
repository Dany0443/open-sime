package md.sime.opensime

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.portal.SimeNetworkError
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses an empty test activity, synthetic accounts, and namespaced debug-only storage. */
class SessionRestorationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sessionOnlyAccountSurvivesActivityRecreationAndBackground() {
        val fixture = UnifiedLogoutTest.Fixture()
        val auth = fixture.auth()
        lateinit var retained: OpenSimeRetainedSession
        fun install(activity: ComponentActivity) = activity.setContent {
            CompositionLocalProvider(LocalContext provides fixture.context) {
                retained = viewModel()
                OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault)
            }
        }
        try {
            compose.activityRule.scenario.onActivity(::install)
            signIn(persistent = false)
            waitForReady { retained }
            val original = retained
            val repository = retained.repository
            val requests = fixture.requests.get()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity(::install)
            waitFor("compact_bottom_nav")
            compose.runOnIdle {
                assertSame(original, retained)
                assertSame(repository, retained.repository)
                assertTrue(auth.isAuthenticated)
                assertFalse(fixture.vault.hasSavedCredentials())
                assertEquals(requests, fixture.requests.get())
                assertEquals(1, fixture.loginRequests.get())
            }
        } finally { fixture.close() }
    }

    @Test
    fun savedCredentialsRestoreAndSurviveActivityRecreation() {
        val fixture = UnifiedLogoutTest.Fixture()
        fixture.vault.saveCredentials("synthetic-user", "synthetic-password", md.sime.opensime.core.model.AccountRole.PARENT)
        md.sime.opensime.core.designsystem.OpenSimeSettingsState.setExplicitlyLoggedOut(fixture.context, false)
        SampleAgendaRepository.buildDefaultSampleSnapshots().values.toList().take(2).forEachIndexed { index, sample ->
            fixture.account.write(sample.copy(student = sample.student.copy(id = StudentId(if (index == 0) "id_101" else "id_601")),
                dataSourceMode = DataSourceMode.LIVE_PORTAL, successfulSyncMillis = mapOf("grades" to 1000L)))
        }
        val auth = fixture.auth()
        lateinit var retained: OpenSimeRetainedSession
        fun install(activity: ComponentActivity) = activity.setContent {
            CompositionLocalProvider(LocalContext provides fixture.context) {
                retained = viewModel()
                OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault)
            }
        }
        try {
            compose.activityRule.scenario.onActivity(::install)
            waitForReady { retained }
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity(::install)
            waitForReady { retained }
            compose.runOnIdle {
                assertTrue(auth.isAuthenticated)
                assertTrue(fixture.vault.hasSavedCredentials())
                assertEquals(1, fixture.loginRequests.get())
            }
        } finally { fixture.close() }
    }

    @Test
    fun coldProcessRestorationRespectsThePersistenceChoice() {
        val stage = InstrumentationRegistry.getArguments().getString("phase02Stage")
        val verify = stage?.startsWith("verify") == true
        val interrupted = stage == "prepare-interrupted"
        val sessionOnly = stage?.contains("session") == true
        val offline = stage == "verify-offline"
        val fixture = UnifiedLogoutTest.Fixture(if (verify || interrupted) "verify" else null,
            if (stage != null) "phase02-process-${if (sessionOnly) "session" else "persistent"}" else null)
        val pending = CompletableDeferred<Unit>()
        val auth = fixture.auth {
            if (interrupted) {
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                    putString("phase02", "AUTH_PENDING")
                })
                pending.await()
            }
            if (offline) throw SimeNetworkError("Synthetic offline")
        }
        lateinit var retained: OpenSimeRetainedSession
        try {
            compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalContext provides fixture.context) {
                    retained = viewModel()
                    OpenSimeApp(authCoordinator = auth, credentialVault = fixture.vault)
                }
            } }
            if (interrupted) {
                compose.waitUntil(5_000) { fixture.loginRequests.get() == 1 }
            } else if (!verify) {
                signIn(persistent = !sessionOnly)
                waitForReady { retained }
                assertEquals(!sessionOnly, fixture.vault.hasSavedCredentials())
            } else {
                if (!sessionOnly && !offline) {
                    waitForReady { retained }
                    compose.runOnIdle { assertTrue(auth.isAuthenticated); assertEquals(1, fixture.loginRequests.get()) }
                } else {
                    compose.waitUntil(5_000) { !retained.isRestoringSession }
                    compose.runOnIdle { assertFalse(auth.isAuthenticated); assertEquals(if (offline) 1 else 0, fixture.loginRequests.get()) }
                }
            }
        } finally {
            if (stage?.startsWith("prepare") != true) fixture.close()
        }
    }

    private fun signIn(persistent: Boolean) {
        waitFor("auth_username_input")
        compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
        compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
        if (!persistent) compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().performClick()
        compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
    }
    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitForReady(session: () -> OpenSimeRetainedSession) {
        waitFor("compact_bottom_nav")
        compose.waitUntil(5_000) { session().isLiveAuthenticated && !session().isLoading && !session().isAuthenticating }
    }
}
