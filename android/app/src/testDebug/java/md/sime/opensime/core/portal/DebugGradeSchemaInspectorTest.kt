package md.sime.opensime.core.portal

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import md.sime.opensime.core.model.AccountRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugGradeSchemaInspectorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/portal/$name"))
        .bufferedReader().use { it.readText() }
    private fun summary(dir: File) = checkNotNull(DebugGradeSchemaInspector.peekSummary())

    @Test fun summaryNeverTouchesDisk() = runBlocking {
        val dir = temp.newFolder()
        val session = session()
        DebugGradeSchemaInspector.initialize(dir)
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
            DebugGradeSchemaInspector.finish(token, true)
        }
        assertNotNull(DebugGradeSchemaInspector.takeSummary())
        assertTrue(dir.list().orEmpty().isEmpty())
    }
    private fun session(http: SimeHttpTransport = SimeHttpTransport()): SimePortalSession = SimePortalSession(http = http).also { session ->
        session.seedStudents(listOf(ParsedStudentProfile(id = "id_101", fullName = "Synthetic"), ParsedStudentProfile(id = "id_201", fullName = "Synthetic B")), emptyMap())
        listOf("_sessionId" to "999999", "_portalUrl" to "https://agenda.sime.md/", "_currentStudentKey" to "id_101", "_role" to AccountRole.PARENT).forEach { (name, value) ->
            SimePortalSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
    }

    private suspend fun expectAbort(block: suspend () -> Unit) {
        try { block(); fail("Expected inspection to abort") } catch (_: CancellationException) { }
    }

    private suspend fun hop(http: SimeHttpTransport, kind: SimeRequestKind) =
        http.request("GET", "https://agenda.sime.md/", kind = kind, debugInspectionReport = kind == SimeRequestKind.XHR)

    private fun ok() = SimeHttpExecutor.RawHopResponse(200, emptyMap(), "<html></html>".toByteArray())

    @Test fun unrelatedXhrIsRejectedBeforeDispatch() = runBlocking {
        val dir = temp.newFolder()
        var calls = 0
        val http = SimeHttpTransport(executor = SimeHttpExecutor { calls++; ok() })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            http.request("POST", "https://agenda.sime.md/", kind = SimeRequestKind.XHR)
        } }
        assertEquals(0, calls)
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun budgetCountsActualHopsAndRejectsFourthBeforeDispatch() = runBlocking {
        val dir = temp.newFolder()
        var calls = 0
        val http = SimeHttpTransport(executor = SimeHttpExecutor { calls++; ok() })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            hop(http, SimeRequestKind.NAVIGATE)
            hop(http, SimeRequestKind.XHR)
            hop(http, SimeRequestKind.XHR)
            DebugGradeSchemaInspector.initialize(temp.newFolder())
            hop(http, SimeRequestKind.XHR)
        } }
        assertEquals(3, calls)
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun threeHopsCanCompleteAndPublishForSelectedStudent() = runBlocking {
        val dir = temp.newFolder()
        var calls = 0
        val http = SimeHttpTransport(executor = SimeHttpExecutor { calls++; ok() })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            hop(http, SimeRequestKind.NAVIGATE)
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
            hop(http, SimeRequestKind.XHR)
            hop(http, SimeRequestKind.XHR)
            DebugGradeSchemaInspector.finish(token, true)
        }
        assertEquals(3, calls)
        assertNotNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun networkFailureCannotPublishEvenIfCaught() = runBlocking {
        val dir = temp.newFolder()
        val http = SimeHttpTransport(executor = SimeHttpExecutor { throw java.net.SocketTimeoutException() })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
            try { hop(http, SimeRequestKind.XHR) } catch (_: SimeNetworkError) { }
            DebugGradeSchemaInspector.finish(token, true)
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun incompleteInspectionNeverPublishesSummary() = runBlocking {
        val dir = temp.newFolder()
        val session = session()
        DebugGradeSchemaInspector.initialize(dir)
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun unfinishedRequestKeepsGateClosedAfterInspectionAborts() = runBlocking {
        val dir = temp.newFolder()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val http = SimeHttpTransport(executor = SimeHttpExecutor {
            calls++
            entered.complete(Unit)
            release.await()
            SimeHttpExecutor.RawHopResponse(302, mapOf("Location" to listOf("/redirect")), ByteArray(0))
        })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        lateinit var request: kotlinx.coroutines.Deferred<*>
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
            request = this@runBlocking.async { hop(http, SimeRequestKind.XHR) }
            entered.await()
            DebugGradeSchemaInspector.finish(token, true)
        } }
        release.complete(Unit)
        expectAbort { request.await() }
        assertEquals(1, calls)
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun redirectsAndRetriesCountEachHop() = runBlocking {
        val dir = temp.newFolder()
        var calls = 0
        val http = SimeHttpTransport(executor = SimeHttpExecutor {
            calls++
            SimeHttpExecutor.RawHopResponse(302, mapOf("Location" to listOf("/redirect")), ByteArray(0))
        })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") { hop(http, SimeRequestKind.XHR) } }
        assertEquals(2, calls)
        assertNull(DebugGradeSchemaInspector.peekSummary())

        calls = 0
        val retryHttp = SimeHttpTransport(executor = SimeHttpExecutor { calls++; ok() })
        val retrySession = session(retryHttp)
        val client = SimePortalClient(retrySession, reloginAction = {
            hop(retryHttp, SimeRequestKind.NAVIGATE)
            hop(retryHttp, SimeRequestKind.XHR)
            hop(retryHttp, SimeRequestKind.XHR)
        })
        expectAbort { DebugGradeSchemaInspector.inspect(retrySession, "id_101") {
            hop(retryHttp, SimeRequestKind.XHR)
            client.withRetry<Unit> { throw SimeSessionExpiredError() }
        } }
        assertEquals(3, calls)
    }

    @Test fun wrongStudentLogoutAndCancellationDiscardResults() = runBlocking {
        val dir = temp.newFolder()
        DebugGradeSchemaInspector.initialize(dir)
        val session = session()
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            DebugGradeSchemaInspector.begin(session, "id_101", "id_201", "<html></html>")
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
            DebugGradeSchemaInspector.finish(token, true)
            session.loadStudentPage("id_201")
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>")
            DebugGradeSchemaInspector.finish(token, true)
            session.clear(explicitLogout = true)
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        val next = session()
        expectAbort { DebugGradeSchemaInspector.inspect(next, "id_101") {
            val token = DebugGradeSchemaInspector.begin(next, "id_101", "id_101", "<html></html>")
            DebugGradeSchemaInspector.finish(token, true)
            SimePortalSession::class.java.getDeclaredField("_sessionGeneration").apply { isAccessible = true }.setLong(next, next.sessionGeneration + 1)
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        expectAbort { DebugGradeSchemaInspector.inspect(next, "id_101") {
            val token = DebugGradeSchemaInspector.begin(next, "id_101", "id_101", "<html></html>")
            DebugGradeSchemaInspector.finish(token, true)
            throw CancellationException("synthetic cancellation")
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun expiresAndRejectsOtherAccountsOrPreexistingRequests() = runBlocking {
        val dir = temp.newFolder()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = SimeHttpTransport(executor = SimeHttpExecutor { entered.complete(Unit); release.await(); ok() })
        val session = session(http)
        DebugGradeSchemaInspector.initialize(dir)
        val running = async { hop(http, SimeRequestKind.XHR) }
        entered.await()
        try { DebugGradeSchemaInspector.inspect(session, "id_101") { fail("must not arm") }; fail("must reject") }
        catch (_: IllegalStateException) { }
        release.complete(Unit)
        running.await()
        var now = 1L
        DebugGradeSchemaInspector.monotonicNanos = { now }
        try {
            expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
                now += 60_000_000_000L
                hop(http, SimeRequestKind.XHR)
            } }
        } finally { DebugGradeSchemaInspector.monotonicNanos = System::nanoTime }
        val other = session()
        expectAbort { DebugGradeSchemaInspector.inspect(session, "id_101") {
            DebugGradeSchemaInspector.checkStudent(other, "id_101")
        } }
        assertNull(DebugGradeSchemaInspector.peekSummary())
    }

    @Test fun inspectionRejectsUnknownStudentBeforeAnyOperation() = runBlocking {
        val session = SimePortalSession()
        DebugGradeSchemaInspector.initialize(temp.newFolder())
        var entered = false
        try {
            DebugGradeSchemaInspector.inspect(session, "not-a-selected-student") { entered = true }
            fail("Expected inspection to fail closed")
        } catch (_: IllegalStateException) {
            assertFalse(entered)
        }
    }

    @Test fun disabledByDefaultAndOneShot() = runBlocking {
        val dir = temp.newFolder()
        val session = session()
        DebugGradeSchemaInspector.initialize(dir)
        assertEquals(0L, DebugGradeSchemaInspector.begin(session, "id_101", "id_101", fixture("dashboard_student_a_sanitized.html")))
        assertNull(DebugGradeSchemaInspector.peekSummary())
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", fixture("dashboard_student_a_sanitized.html"))
            assertTrue(token > 0)
            DebugGradeSchemaInspector.finish(token, true)
        }
        assertNotNull(DebugGradeSchemaInspector.peekSummary())
        assertEquals(0L, DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<html></html>"))
    }

    @Test fun outputIsFixedVocabularyWithNoSensitiveCanaries() = runBlocking {
        val dir = temp.newFolder()
        val session = session()
        DebugGradeSchemaInspector.initialize(dir)
        val sensitive = "SENSITIVE_CANARY_PASSWORD_TOKEN_NAME_ID_SUBJECT_MARK_DATE_URL"
        val html = fixture("report_sem1_marks_theses_qualifiers.html")
            .replace("Fizica", sensitive)
            .replace("Profesor 1", sensitive)
            .replace("8</span>", "$sensitive</span>")
            .replace("12.10.2026", sensitive) +
            "<input value='$sensitive'><script>$sensitive</script><div $sensitive='$sensitive'></div>"
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", html)
            DebugGradeSchemaInspector.report(token, 1, html)
            DebugGradeSchemaInspector.finish(token, true)
        }
        val output = summary(dir)
        assertFalse(output.contains(sensitive))
        assertFalse(output.contains("Fizica"))
        assertFalse(output.contains("12.10.2026"))
        assertFalse(output.contains("Profesor"))
        assertTrue(output.lines().filter(String::isNotEmpty).all {
            it.matches(Regex("[a-z0-9_]+=(true|false|unknown|empty|numeric|non_numeric|mixed)"))
        })
        assertEquals(setOf(
            "dashboard_observed", "report_semester_1_observed", "report_semester_2_observed",
            "semester_1_region", "semester_2_region", "subject_column", "evaluation_column",
            "thesis_column", "thesis_marker", "semester_average_column", "annual_average_column",
            "semester_average_type", "annual_average_type", "completeness", "finalization", "deduplication",
        ), output.lines().filter(String::isNotEmpty).map { it.substringBefore('=') }.toSet())
        assertTrue(output.contains("evaluation_column=true"))
        assertTrue(output.contains("thesis_column=true"))
        assertTrue(output.contains("semester_average_column=true"))
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun abortedOrWrongTokenNeverWritesAndDoesNotLeakAcrossOperations() = runBlocking {
        val dir = temp.newFolder()
        val session = session()
        DebugGradeSchemaInspector.initialize(dir)
        try { DebugGradeSchemaInspector.inspect(session, "id_101") {
            val first = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<td headers='TEZA'>canary</td>")
            DebugGradeSchemaInspector.report(first + 1, 1, "<td headers='MED_AN'>canary</td>")
            DebugGradeSchemaInspector.finish(first, false)
        } } catch (_: kotlinx.coroutines.CancellationException) { }
        assertNull(DebugGradeSchemaInspector.peekSummary())
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            val second = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<td headers='DISCIPLINA'>canary</td>")
            DebugGradeSchemaInspector.finish(second, true)
        }
        assertTrue(summary(dir).contains("thesis_column=false"))
        assertTrue(summary(dir).contains("annual_average_column=false"))
    }

    @Test fun rejectsUnexpectedMarkersAndUnverifiedStatus() = runBlocking {
        val dir = temp.newFolder()
        val session = session()
        DebugGradeSchemaInspector.initialize(dir)
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            val token = DebugGradeSchemaInspector.begin(session, "id_101", "id_101", "<script><td headers='TEZA'>canary</td></script><td headers='TEZA_EXTRA_CANARY'>canary</td>")
            DebugGradeSchemaInspector.finish(token, true)
        }
        assertTrue(summary(dir).contains("thesis_column=false"))
        assertTrue(summary(dir).contains("completeness=unknown"))
        assertTrue(summary(dir).contains("finalization=unknown"))
    }

    @Test fun clientObservesExistingSyntheticResponsesWithoutExtraRequests() = runBlocking {
        val dir = temp.newFolder()
        val dashboard = fixture("dashboard_student_a_sanitized.html").replace(
            "<div id=\"SEMESTER_REG_1\">",
            "<div id=\"SEMESTER_REG_1\"><p>Empty</p></div><div id=\"ignored\">",
        )
        val report = fixture("report_sem1_marks_theses_qualifiers.html")
        var requests = 0
        val session = SimePortalSession(nowMillis = { 1000L }, http = SimeHttpTransport(executor = SimeHttpExecutor {
            requests++
            SimeHttpExecutor.RawHopResponse(200, mapOf("Content-Type" to listOf("text/html")), report.toByteArray())
        }))
        session.seedStudents(
            listOf(ParsedStudentProfile(id = "id_101", fullName = "Synthetic")),
            mapOf("id_101" to "/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_501&cs=CHECKSUM_SANITIZED"),
        )
        listOf("_sessionId" to "999999", "_portalUrl" to "https://agenda.sime.md/", "_currentStudentKey" to "id_101", "_role" to AccountRole.PARENT).forEach { (name, value) ->
            SimePortalSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        val page = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboard, studentKey = "id_101", fetchedAtEpochMillis = 1000L,
        )
        @Suppress("UNCHECKED_CAST")
        val pages = SimePortalSession::class.java.getDeclaredField("studentPages").apply { isAccessible = true }
            .get(session) as MutableMap<String, PortalStudentDashboardPage>
        pages["id_101"] = page
        val client = SimePortalClient(session)
        DebugGradeSchemaInspector.initialize(dir)
        client.enrichPortalPage(page, "id_101")
        val baseline = requests
        assertTrue(baseline > 0)
        assertNull(DebugGradeSchemaInspector.peekSummary())
        requests = 0
        DebugGradeSchemaInspector.inspect(session, "id_101") { client.enrichPortalPage(page, "id_101") }
        assertEquals(baseline, requests)
        assertTrue(summary(dir).contains("dashboard_observed=true"))
        assertTrue(summary(dir).contains("report_semester_1_observed=true"))
        requests = 0
        DebugGradeSchemaInspector.inspect(session, "id_101") {
            val selectedPage = session.sessionLock.withLock { session.loadStudentPage("id_101", assertCurrent = true) }
            client.enrichPortalPage(selectedPage, "id_101")
        }
        assertTrue(requests in 1..2)
        assertTrue(summary(dir).contains("report_semester_1_observed=true"))
    }
}
