package md.sime.opensime.core.portal

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import md.sime.opensime.OpenSimeRetainedSession
import md.sime.opensime.core.data.LiveAgendaRepository
import md.sime.opensime.core.model.AccountRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.concurrent.atomic.AtomicInteger

/** Exercises only the isolated debug process with a synthetic, non-network transport. */
class DebugInspectionUiTest {
    private lateinit var fixture: Fixture
    private val compose = createAndroidComposeRule<DebugSchemaActivity>()
    private val setup = object : ExternalResource() {
        override fun before() {
            fixture = Fixture()
            DebugGradeSchemaInspector.initialize(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            DebugInspectionBridge.attach(fixture.retained, fixture.auth)
        }
    }
    @get:Rule val rule: RuleChain = RuleChain.outerRule(setup).around(compose)

    @Test fun showsSelectedStudentAndWaitsForConfirmation() {
        compose.onNodeWithTag("debug_inspection_selected").assertExists()
        compose.onNodeWithText("Synthetic Student", substring = true).assertExists()
        compose.onNodeWithTag("debug_inspection_confirm").assertDoesNotExist()
        compose.onNodeWithTag("debug_inspection_select").performClick()
        compose.onNodeWithTag("debug_inspection_confirm").assertExists()
        compose.onNodeWithTag("debug_inspection_summary").assertDoesNotExist()
        assertEquals(0, fixture.requests.get())
        compose.onNodeWithTag("debug_inspection_confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("debug_inspection_status")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("debug_inspection_status").assertExists()
    }

    @Test fun studentSwitchBeforeConfirmationRefusesToStart() {
        compose.onNodeWithTag("debug_inspection_select").performClick()
        compose.onNodeWithTag("debug_inspection_confirm").assertExists()
        compose.runOnIdle { DebugInspectionBridge.selectionChanged() }
        compose.onNodeWithTag("debug_inspection_confirm").performClick()
        compose.onNodeWithTag("debug_inspection_status").assertExists()
        compose.onNodeWithTag("debug_inspection_confirm").assertDoesNotExist()
        assertEquals(0, fixture.requests.get())
    }

    private class Fixture {
        val requests = AtomicInteger()
        private val http = SimeHttpTransport(executor = SimeHttpExecutor { requests.incrementAndGet(); error("Synthetic transport only") })
        private val session = SimePortalSession(http = http)
        val auth = SimeAuthCoordinator(session)
        val retained = OpenSimeRetainedSession()
        init {
            session.seedStudents(listOf(ParsedStudentProfile("id_101", "Synthetic Student")), emptyMap())
            listOf("_sessionId" to "999999", "_portalUrl" to "https://agenda.sime.md/", "_currentStudentKey" to "id_101", "_role" to AccountRole.PARENT).forEach { (name, value) ->
                SimePortalSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            retained.repository = LiveAgendaRepository(SimePortalClient(session), auth)
            OpenSimeRetainedSession::class.java.getDeclaredMethod("setLiveAuthenticated", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(retained, true)
            OpenSimeRetainedSession::class.java.getDeclaredMethod("setSelectedStudentIdValue", String::class.java).apply { isAccessible = true }.invoke(retained, "id_101")
        }
    }
}
