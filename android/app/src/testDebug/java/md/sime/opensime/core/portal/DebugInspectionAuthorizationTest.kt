package md.sime.opensime.core.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import md.sime.opensime.OpenSimeRetainedSession
import md.sime.opensime.core.data.LiveAgendaRepository
import md.sime.opensime.core.model.AccountRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugInspectionAuthorizationTest {
    @get:Rule val temp = TemporaryFolder()

    private class Fixture {
        var requests = 0
        val http = SimeHttpTransport(executor = SimeHttpExecutor { requests++; error("Unexpected network") })
        val session = SimePortalSession(http = http)
        val auth = SimeAuthCoordinator(session)
        val retained = OpenSimeRetainedSession()
        init {
            session.seedStudents(listOf(ParsedStudentProfile("id_101", "Synthetic Student"), ParsedStudentProfile("id_201", "Synthetic Other")), emptyMap())
            listOf("_sessionId" to "999999", "_portalUrl" to "https://agenda.sime.md/", "_currentStudentKey" to "id_101", "_role" to AccountRole.PARENT).forEach { (name, value) ->
                SimePortalSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            retained.repository = LiveAgendaRepository(SimePortalClient(session), auth)
            setLive(true)
            setSelected("id_101")
            DebugInspectionBridge.attach(retained, auth)
        }
        fun setLive(value: Boolean) {
            OpenSimeRetainedSession::class.java.getDeclaredMethod("setLiveAuthenticated", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(retained, value)
        }
        fun setSelected(value: String) {
            OpenSimeRetainedSession::class.java.getDeclaredMethod("setSelectedStudentIdValue", String::class.java)
                .apply { isAccessible = true }.invoke(retained, value)
        }
    }

    @Test fun failureCategoriesNeverIncludeExceptionText() {
        assertEquals("network", debugInspectionFailureCategory(SimeNetworkError("private synthetic data")))
        assertEquals("session", debugInspectionFailureCategory(SimeSessionExpiredError()))
        assertEquals("cancelled_or_guard", debugInspectionFailureCategory(CancellationException("private synthetic data")))
        assertEquals("other", debugInspectionFailureCategory(IllegalStateException("private synthetic data")))
    }

    @Test fun openingRequiresLiveSessionAndExplicitSelectionWithoutNetwork() {
        val f = Fixture()
        f.setLive(false)
        assertNull(DebugInspectionBridge.capture())
        f.setLive(true)
        f.setSelected("")
        assertNull(DebugInspectionBridge.capture())
        f.setSelected("id_201")
        assertNull(DebugInspectionBridge.capture())
        f.setSelected("id_101")
        val selection = checkNotNull(DebugInspectionBridge.capture())
        assertEquals("id_101", selection.student)
        assertEquals(0, f.requests)
    }

    @Test fun noInspectionOccursUntilExplicitConfirmation() = runBlocking {
        val f = Fixture()
        val dir = temp.newFolder()
        DebugGradeSchemaInspector.initialize(dir)
        val selection = checkNotNull(DebugInspectionBridge.capture())
        var refreshes = 0
        assertNull(DebugGradeSchemaInspector.peekSummary())
        assertEquals(0, f.requests)
        try { DebugInspectionBridge.confirm(selection) { refreshes++ }; fail("Expected explicit student confirmation") }
        catch (_: CancellationException) { }
        assertEquals(0, refreshes)
        DebugInspectionBridge.confirmStudent(selection)
        val summary = DebugInspectionBridge.confirm(selection) {
            refreshes++
            val token = DebugGradeSchemaInspector.begin(f.session, "id_101", "id_101", "<html></html>")
            DebugGradeSchemaInspector.finish(token, true)
        }
        assertEquals(1, refreshes)
        assertTrue(summary.contains("dashboard_observed=true"))
        assertFalse(summary.contains("Synthetic Student"))
        assertFalse(summary.contains("id_101"))
        assertNull(DebugGradeSchemaInspector.peekSummary())
        assertTrue(dir.list().orEmpty().isEmpty())
        try { DebugInspectionBridge.confirm(selection) { refreshes++ }; fail("Expected single-use selection") }
        catch (_: CancellationException) { }
        assertEquals(1, refreshes)
    }

    @Test fun selectionChangeBeforeConfirmationRefusesRefresh() = runBlocking {
        val f = Fixture()
        DebugGradeSchemaInspector.initialize(temp.newFolder())
        val selection = checkNotNull(DebugInspectionBridge.capture())
        DebugInspectionBridge.confirmStudent(selection)
        DebugInspectionBridge.selectionChanged()
        var refreshes = 0
        try { DebugInspectionBridge.confirm(selection) { refreshes++ }; fail("Expected invalidated selection") }
        catch (_: CancellationException) { }
        assertEquals(0, refreshes)
        assertEquals(0, f.requests)
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun logoutDuringConfirmedInspectionDiscardsResult() = runBlocking {
        val f = Fixture()
        DebugGradeSchemaInspector.initialize(temp.newFolder())
        val selection = checkNotNull(DebugInspectionBridge.capture())
        DebugInspectionBridge.confirmStudent(selection)
        try {
            DebugInspectionBridge.confirm(selection) {
                val token = DebugGradeSchemaInspector.begin(f.session, "id_101", "id_101", "<html></html>")
                DebugGradeSchemaInspector.finish(token, true)
                f.session.clear(explicitLogout = true)
            }
            fail("Expected logout to invalidate inspection")
        } catch (_: CancellationException) { }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        assertEquals(0, f.requests)
    }
}
