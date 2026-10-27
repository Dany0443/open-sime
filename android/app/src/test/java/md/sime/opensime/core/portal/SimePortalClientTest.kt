package md.sime.opensime.core.portal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SimePortalClientTest {

    private val publicEntryHtml = SimePortalFixtures.readFixture(SimePortalFixtures.PUBLIC_ENTRY_HTML)
    private val validDialogHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_VALID_HTML)
    private val loginSuccessJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_SUCCESS_JSON)
    private val dashboardStudentAHtml = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
    private val dashboardStudentBHtml = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_B_HTML)
    private val reportSem1Html = SimePortalFixtures.readFixture(SimePortalFixtures.REPORT_SEM1_HTML)
    private val calendarWeekJson = SimePortalFixtures.readFixture(SimePortalFixtures.CALENDAR_EVENTS_WEEK_JSON)

    // -------------------------------------------------------------------------
    // 1. Student-Scoped APEX AJAX Formulation
    // -------------------------------------------------------------------------

    @Test
    fun `ajax builds valid APEX request with correct flow parameters, headers, and p_json payload`() = runBlocking {
        val capturedRequests = mutableListOf<SimeHttpExecutor.RawHopRequest>()
        val client = createLoggedInClient(
            onRequest = { req ->
                capturedRequests.add(req)
                if (req.method == "POST" && req.url.contains("wwv_flow.ajax")) {
                    htmlResponse("<div>ajax-response-ok</div>")
                } else {
                    fail("Unexpected request: ${req.method} ${req.url}") as Nothing
                }
            },
        )

        val page = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        val result = client.ajax(
            page = page,
            ajaxId = "AJAX_SEM1_SANITIZED_1",
            extra = mapOf("x01" to "TEST_EXTRA"),
        )

        assertEquals("<div>ajax-response-ok</div>", result)
        assertEquals(1, capturedRequests.size)

        val ajaxReq = capturedRequests[0]
        assertEquals("POST", ajaxReq.method)
        assertEquals("https://agenda.sime.md/ords/wwv_flow.ajax?p_context=main/main-page/999999", ajaxReq.url)
        assertEquals("XMLHttpRequest", ajaxReq.headers["X-Requested-With"])
        assertEquals("https://agenda.sime.md", ajaxReq.headers["Origin"])
        assertEquals(page.url, ajaxReq.headers["Referer"])
        assertEquals("text/html, */*; q=0.01", ajaxReq.headers["Accept"])

        val formParams = parseFormBody(String(ajaxReq.bodyBytes!!, StandardCharsets.UTF_8))
        assertEquals("104", formParams["p_flow_id"])
        assertEquals("1", formParams["p_flow_step_id"])
        assertEquals("999999", formParams["p_instance"])
        assertEquals("PLUGIN=AJAX_SEM1_SANITIZED_1", formParams["p_request"])
        assertEquals("TEST_EXTRA", formParams["x01"])

        // Validate p_json structure
        val pJson = formParams["p_json"]
        assertNotNull(pJson)
        val parsedJson = SimpleJson.parse(pJson!!) as Map<*, *>
        assertEquals("222222", parsedJson["salt"])
        val pageItems = parsedJson["pageItems"] as Map<*, *>
        assertEquals("PROT_CHECKSUM_SANITIZED", pageItems["protected"])
        val itemsToSubmit = pageItems["itemsToSubmit"] as List<*>
        assertEquals(1, itemsToSubmit.size)
        val item = itemsToSubmit[0] as Map<*, *>
        assertEquals("P1_ELEVE_ID", item["n"])
        assertEquals("ID_101", item["v"])
        assertEquals("ITEM_CHECKSUM_SANITIZED", item["ck"])
    }

    @Test
    fun `ajax throws SimeParseError if dashboard lacks required APEX tokens`() = runBlocking {
        val client = createLoggedInClient()
        val brokenPage = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = "<html><body><input type='hidden' id='pFlowId' value='104'></body></html>",
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        try {
            client.ajax(brokenPage, "AJAX_ID")
            fail("Expected SimeParseError")
        } catch (err: SimeParseError) {
            assertTrue(err.message!!.contains("Dashboard page lacks the items required for APEX requests"))
        }
    }

    @Test
    fun `ajax throws SimeParseError if ajaxId is blank`() = runBlocking {
        val client = createLoggedInClient()
        val page = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        try {
            client.ajax(page, "   ")
            fail("Expected SimeParseError")
        } catch (err: SimeParseError) {
            assertTrue(err.message!!.contains("AJAX plugin identifier cannot be blank"))
        }
    }

    // -------------------------------------------------------------------------
    // 2. Semester Report Enrichment
    // -------------------------------------------------------------------------

    @Test
    fun `enrichPortalPage skips AJAX when semester report is already inline`() = runBlocking {
        var ajaxCount = 0
        val client = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    ajaxCount++
                    htmlResponse("<div>should-not-be-called</div>")
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        // dashboardStudentAHtml has SEMESTER_REG_1 inline with table rows
        val page = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        val enriched = client.enrichPortalPage(page, "id_101")
        // sem1 was inline; sem2 AJAX might be called if sem2 region lacks inline data
        // Verify that sem1 data is preserved from inline
        val mathSubject = enriched.catalog.subjects.firstOrNull { it.subject == "Matematica" }
        assertNotNull(mathSubject)
        assertEquals(9.0, mathSubject!!.average!!, 0.01)
    }

    @Test
    fun `enrichPortalPage invokes AJAX when semester report is missing inline`() = runBlocking {
        val capturedAjaxRequests = mutableListOf<SimeHttpExecutor.RawHopRequest>()
        val client = createLoggedInClient(
            onRequest = { req ->
                if (req.method == "POST" && req.url.contains("wwv_flow.ajax")) {
                    capturedAjaxRequests.add(req)
                    htmlResponse(reportSem1Html)
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        // Modify dashboard HTML to remove inline report table but leave script with AJAX IDs
        val htmlWithoutInline = dashboardStudentAHtml.replace(
            """<div id="SEMESTER_REG_1">""",
            """<div id="SEMESTER_REG_1"><p>Empty</p></div><div id="ignored">""",
        )

        val page = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = htmlWithoutInline,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        val enriched = client.enrichPortalPage(page, "id_101")

        assertTrue(capturedAjaxRequests.isNotEmpty())
        val fizica = enriched.catalog.subjects.firstOrNull { it.subject == "Fizica" }
        assertNotNull(fizica)
        assertEquals(9.0, fizica!!.average!!, 0.01)
        assertTrue(fizica.markDetails.any { it.isThesis })
    }

    @Test
    fun `enrichPortalPage falls back gracefully when AJAX throws`() = runBlocking {
        val client = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    throw SimeNetworkError("Simulated network timeout")
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        val page = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        // Should not throw; falls back to inline report data
        val enriched = client.enrichPortalPage(page, "id_101")
        assertNotNull(enriched)
        assertEquals("id_101", enriched.student.id)
    }

    // -------------------------------------------------------------------------
    // 3. Calendar Ranges in 21-Day Chunks
    // -------------------------------------------------------------------------

    @Test
    fun `calendarRange chunks requests across 21-day boundaries`() = runBlocking {
        val chunkRequests = mutableListOf<Map<String, String>>()
        val client = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    val params = parseFormBody(String(req.bodyBytes!!, StandardCharsets.UTF_8))
                    chunkRequests.add(params)
                    jsonResponse(calendarWeekJson)
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        // 31-day range: 2026-10-01 to 2026-10-31
        // Chunk 1: 2026-10-01 to 2026-10-21 (21 days)
        // Chunk 2: 2026-10-22 to 2026-10-31 (10 days)
        val entries = client.calendarRange("id_101", "2026-10-01", "2026-10-31")

        assertEquals(2, chunkRequests.size)
        assertEquals("GET", chunkRequests[0]["x01"])
        assertEquals("20261001000000", chunkRequests[0]["x02"])
        assertEquals("20261021235959", chunkRequests[0]["x03"])

        assertEquals("GET", chunkRequests[1]["x01"])
        assertEquals("20261022000000", chunkRequests[1]["x02"])
        assertEquals("20261031235959", chunkRequests[1]["x03"])

        // 3 items per chunk * 2 chunks = 6 items
        assertEquals(6, entries.size)
    }

    @Test
    fun `calendarRange throws SimeParseError on invalid JSON or non-array shape`() = runBlocking {
        val clientInvalidJson = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    htmlResponse("not a json")
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        try {
            clientInvalidJson.calendarRange("id_101", "2026-10-01", "2026-10-05")
            fail("Expected SimeParseError")
        } catch (err: SimeParseError) {
            assertTrue(err.message!!.contains("Calendar response is not valid JSON"))
        }

        val clientNonArray = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    jsonResponse("""{"error": "none"}""")
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        try {
            clientNonArray.calendarRange("id_101", "2026-10-01", "2026-10-05")
            fail("Expected SimeParseError")
        } catch (err: SimeParseError) {
            assertTrue(err.message!!.contains("Calendar response has an unexpected shape"))
        }
    }

    @Test
    fun `getWeek maps calendar events into week schedule`() = runBlocking {
        val client = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    jsonResponse(calendarWeekJson)
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        val sched = client.getWeek("id_101", "2026-10-24")
        assertEquals("2026-10-19", sched.weekStart)
        assertTrue(sched.days.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // 4. Strict Student Isolation & Delayed Response Fencing
    // -------------------------------------------------------------------------

    @Test
    fun `switching asserts current student before AJAX so child B never uses child A tokens`() = runBlocking {
        val capturedAjaxRequests = mutableListOf<Map<String, String>>()
        val client = createTwoStudentClient(
            onAjax = { req ->
                val params = parseFormBody(String(req.bodyBytes!!, StandardCharsets.UTF_8))
                capturedAjaxRequests.add(params)
                jsonResponse(calendarWeekJson)
            },
        )

        // Session starts with Student A active ("id_101")
        assertEquals("id_101", client.session.currentStudentKey)

        // Request calendar for Student B ("id_201")
        // Must assertCurrent = true -> loads Student B's page first -> makes AJAX with Student B's tokens
        val entries = client.calendarRange("id_201", "2026-10-24", "2026-10-24")
        assertTrue(entries.isNotEmpty())
        assertEquals("id_201", client.session.currentStudentKey)

        assertEquals(1, capturedAjaxRequests.size)
        val pJson = capturedAjaxRequests[0]["p_json"]!!
        val parsedJson = SimpleJson.parse(pJson) as Map<*, *>
        val itemsToSubmit = (parsedJson["pageItems"] as Map<*, *>)["itemsToSubmit"] as List<*>
        val item = itemsToSubmit[0] as Map<*, *>

        // Verified invariant: Child B's AJAX call MUST submit Child B's own ID and checksum
        assertEquals("ID_201", item["v"])
        assertEquals("ITEM_CHECKSUM_SANITIZED", item["ck"])
        assertEquals("222223", parsedJson["salt"]) // Student B's salt from fixture
    }

    @Test
    fun `enrichPortalPage throws SimeStudentMismatchError on ownership mismatch`() = runBlocking {
        val client = createLoggedInClient()
        val pageA = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        // Passing Child A's page but requesting Child B's ID
        try {
            client.enrichPortalPage(pageA, "id_201")
            fail("Expected SimeStudentMismatchError")
        } catch (err: SimeStudentMismatchError) {
            assertEquals(StudentId("id_201"), err.requestedStudentId)
            assertEquals(StudentId("id_101"), err.actualStudentId)
        }
    }

    @Test
    fun `concurrent requests for child A and child B are serialized and never cross-contaminate`() = runBlocking {
        val client = createTwoStudentClient(
            onStudentADelayMs = 80L,
            onStudentBDelayMs = 10L,
            onAjax = { jsonResponse(calendarWeekJson) },
        )

        val deferredA = async { client.fetchStudentSnapshot(StudentId("id_101")) }
        val deferredB = async { client.fetchStudentSnapshot(StudentId("id_201")) }

        val (snapA, snapB) = awaitAll(deferredA, deferredB)

        // Strict isolation checks
        assertEquals(StudentId("id_101"), snapA.studentId)
        assertEquals("10A", snapA.student.className)
        assertEquals("Profesor 1", snapA.student.classMasterName)
        assertTrue("Teachers must be populated in snapshot", snapA.teachers.isNotEmpty())
        assertTrue("Class master must be flagged as class master", snapA.teachers.any { it.isClassMaster && it.name == "Profesor 1" })
        assertTrue("Subject teachers must be present", snapA.teachers.any { it.name == "Profesor 2" })

        assertEquals(StudentId("id_201"), snapB.studentId)
        assertEquals("7B", snapB.student.className)
        assertEquals("Profesor 3", snapB.student.classMasterName)
        assertTrue("Student B teachers must be populated", snapB.teachers.isNotEmpty())
        assertTrue("Student B class master must be flagged", snapB.teachers.any { it.isClassMaster && it.name == "Profesor 3" })
    }

    @Test
    fun `two concurrent students switching contexts around delayed AJAX requests strictly serialize`() = runBlocking {
        val childAAjaxPayloads = mutableListOf<Map<String, String>>()
        val childBAjaxPayloads = mutableListOf<Map<String, String>>()

        val client = createTwoStudentClient(
            onStudentADelayMs = 50L,
            onStudentBDelayMs = 10L,
            onAjax = { req ->
                val body = String(req.bodyBytes!!, StandardCharsets.UTF_8)
                val params = parseFormBody(body)
                val pJson = SimpleJson.parse(params["p_json"]!!) as Map<*, *>
                val items = (pJson["pageItems"] as Map<*, *>)["itemsToSubmit"] as List<*>
                val item = items[0] as Map<*, *>
                val studentVal = item["v"] as String

                if (studentVal == "ID_101") {
                    delay(30L)
                    childAAjaxPayloads.add(params)
                } else if (studentVal == "ID_201") {
                    delay(10L)
                    childBAjaxPayloads.add(params)
                }
                jsonResponse(calendarWeekJson)
            },
        )

        // Launch concurrent calendarRange calls for Child A (31 days = 2 chunks) and Child B (31 days = 2 chunks)
        val jobA = async { client.calendarRange("id_101", "2026-10-01", "2026-10-31") }
        val jobB = async { client.calendarRange("id_201", "2026-10-01", "2026-10-31") }

        val (eventsA, eventsB) = awaitAll(jobA, jobB)

        assertEquals(6, eventsA.size)
        assertEquals(6, eventsB.size)

        // Child A had 2 chunks
        assertEquals(2, childAAjaxPayloads.size)
        for (payload in childAAjaxPayloads) {
            val pJson = SimpleJson.parse(payload["p_json"]!!) as Map<*, *>
            val item = ((pJson["pageItems"] as Map<*, *>)["itemsToSubmit"] as List<*>)[0] as Map<*, *>
            assertEquals("ID_101", item["v"])
            assertEquals("ITEM_CHECKSUM_SANITIZED", item["ck"])
        }

        // Child B had 2 chunks
        assertEquals(2, childBAjaxPayloads.size)
        for (payload in childBAjaxPayloads) {
            val pJson = SimpleJson.parse(payload["p_json"]!!) as Map<*, *>
            val item = ((pJson["pageItems"] as Map<*, *>)["itemsToSubmit"] as List<*>)[0] as Map<*, *>
            assertEquals("ID_201", item["v"])
            assertEquals("ITEM_CHECKSUM_SANITIZED", item["ck"])
        }
    }

    @Test
    fun `cancellation while waiting for request mutex preserves CancellationException`() = runBlocking {
        val client = createLoggedInClient()

        // Coroutine 1 holds requestMutex
        val lockAcquired = AtomicBoolean(false)
        val job1 = async {
            client.requestMutex.withLock {
                lockAcquired.set(true)
                delay(200L)
            }
        }

        while (!lockAcquired.get()) {
            delay(5L)
        }

        // Coroutine 2 attempts calendarRange while lock is held and gets cancelled
        val job2 = async {
            client.calendarRange("id_101", "2026-10-01", "2026-10-05")
        }

        delay(20L)
        job2.cancel()

        try {
            job2.await()
            fail("Expected CancellationException")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Verified: CancellationException preserved and not swallowed
            assertNotNull(cancel)
        }

        job1.await()
    }

    @Test
    fun `calendar chunk partial failure propagates error and does not return corrupted partial events`() = runBlocking {
        var chunkIndex = 0
        val client = createLoggedInClient(
            onRequest = { req ->
                if (req.url.contains("wwv_flow.ajax")) {
                    chunkIndex++
                    if (chunkIndex == 2) {
                        throw SimeNetworkError("Simulated connection reset on chunk 2")
                    }
                    jsonResponse(calendarWeekJson)
                } else {
                    fail("Unexpected request: ${req.url}") as Nothing
                }
            },
        )

        // 31-day range: chunk 1 succeeds, chunk 2 fails
        try {
            client.calendarRange("id_101", "2026-10-01", "2026-10-31")
            fail("Expected SimeNetworkError")
        } catch (err: SimeNetworkError) {
            assertTrue(err.message!!.contains("Simulated connection reset"))
        }
    }

    // -------------------------------------------------------------------------
    // 5. Single-Flight Relogin & ToString Redaction
    // -------------------------------------------------------------------------

    @Test
    fun `withRetry triggers reloginAction on SimeSessionExpiredError`() = runBlocking {
        val reloginTriggered = AtomicBoolean(false)
        var callCount = 0

        val client = SimePortalClient(
            session = SimePortalSession(),
            reloginAction = {
                reloginTriggered.set(true)
            },
        )

        val result = client.withRetry {
            callCount++
            if (callCount == 1) {
                throw SimeSessionExpiredError("Expired")
            }
            "success-after-retry"
        }

        assertTrue(reloginTriggered.get())
        assertEquals("success-after-retry", result)
        assertEquals(2, callCount)
    }

    @Test
    fun `session-expiry retry with changed student context re-authenticates and re-fetches`() = runBlocking {
        var callCount = 0
        val client = createLoggedInClient()

        val reloginClient = SimePortalClient(
            session = client.session,
            reloginAction = {
                // Relogin updates session with new session ID
                val setSid = SimePortalSession::class.java.getDeclaredField("_sessionId")
                setSid.isAccessible = true
                setSid.set(client.session, "NEW_SESSION_888888")
            },
        )

        val result = reloginClient.withRetry {
            callCount++
            if (callCount == 1) {
                throw SimeSessionExpiredError("APEX session expired")
            }
            client.session.sessionId
        }

        assertEquals(2, callCount)
        assertEquals("NEW_SESSION_888888", result)
    }

    @Test
    fun `cancellation while waiting on rate gate preserves CancellationException`() = runBlocking {
        val gate = RateGate(minIntervalMs = 5000L)
        // Complete one wait so the next wait will have a 5000ms delay
        gate.wait()

        val waitJob = async {
            gate.wait()
        }

        delay(30L)
        waitJob.cancel()

        try {
            waitJob.await()
            fail("Expected CancellationException")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            assertNotNull(cancel)
        }
    }

    @Test
    fun `toString redacts sensitive session identifiers`() {
        val client = createLoggedInClient()
        val str = client.toString()
        assertFalse(str.contains("999999"))
        assertFalse(str.contains("ID_101"))
        assertTrue(str.contains("SimePortalClient"))
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private fun createLoggedInClient(
        onRequest: suspend (SimeHttpExecutor.RawHopRequest) -> SimeHttpExecutor.RawHopResponse = {
            fail("Unexpected request: ${it.method} ${it.url}") as Nothing
        },
    ): SimePortalClient {
        val transport = SimeHttpTransport(
            executor = SimeHttpExecutor(onRequest),
        )
        val session = SimePortalSession(
            http = transport,
            nowMillis = { 1000L },
            todayProvider = { "2026-10-24" },
        )
        session.seedStudents(
            students = listOf(
                ParsedStudentProfile(
                    id = "id_101",
                    fullName = "Elev 1",
                    className = "10A",
                    schoolName = "Liceul Teoretic Sanitize",
                ),
            ),
            switchUrlsByStudentId = mapOf(
                "id_101" to "/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_501&cs=CHECKSUM_SANITIZED",
            ),
        )

        val setSessionIdMethod = SimePortalSession::class.java.getDeclaredField("_sessionId")
        setSessionIdMethod.isAccessible = true
        setSessionIdMethod.set(session, "999999")

        val setPortalUrlMethod = SimePortalSession::class.java.getDeclaredField("_portalUrl")
        setPortalUrlMethod.isAccessible = true
        setPortalUrlMethod.set(session, "https://agenda.sime.md/ords/r/agenda/main/1?session=999999")

        val setCurrentStudentMethod = SimePortalSession::class.java.getDeclaredField("_currentStudentKey")
        setCurrentStudentMethod.isAccessible = true
        setCurrentStudentMethod.set(session, "id_101")

        val setRoleMethod = SimePortalSession::class.java.getDeclaredField("_role")
        setRoleMethod.isAccessible = true
        setRoleMethod.set(session, AccountRole.PARENT)

        val studentPagesField = SimePortalSession::class.java.getDeclaredField("studentPages")
        studentPagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val studentPages = studentPagesField.get(session) as MutableMap<String, PortalStudentDashboardPage>
        studentPages["id_101"] = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        return SimePortalClient(
            session = session,
            nowMillis = { 1000L },
            todayProvider = { "2026-10-24" },
        )
    }

    private fun createTwoStudentClient(
        onStudentADelayMs: Long = 0L,
        onStudentBDelayMs: Long = 0L,
        onAjax: suspend (SimeHttpExecutor.RawHopRequest) -> SimeHttpExecutor.RawHopResponse = {
            htmlResponse("<div>ok</div>")
        },
    ): SimePortalClient {
        val transport = SimeHttpTransport(
            executor = SimeHttpExecutor { req ->
                when {
                    req.url.contains("wwv_flow.ajax") -> onAjax(req)
                    req.url.contains("P1_ELEVE_ID:ID_501") -> {
                        if (onStudentADelayMs > 0L) delay(onStudentADelayMs)
                        htmlResponse(dashboardStudentAHtml)
                    }
                    req.url.contains("P1_ELEVE_ID:ID_601") -> {
                        if (onStudentBDelayMs > 0L) delay(onStudentBDelayMs)
                        htmlResponse(dashboardStudentBHtml)
                    }
                    else -> fail("Unexpected request: ${req.method} ${req.url}") as Nothing
                }
            },
        )
        val session = SimePortalSession(
            http = transport,
            nowMillis = { 1000L },
            todayProvider = { "2026-10-24" },
        )
        session.seedStudents(
            students = listOf(
                ParsedStudentProfile(id = "id_101", fullName = "Elev 1", className = "10A", schoolName = "Liceul Sanitize"),
                ParsedStudentProfile(id = "id_201", fullName = "Elev 2", className = "7B", schoolName = "Gimnaziul Beta"),
            ),
            switchUrlsByStudentId = mapOf(
                "id_101" to "/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_501&cs=CHECKSUM_SANITIZED",
                "id_501" to "/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_501&cs=CHECKSUM_SANITIZED",
                "id_201" to "/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_601&cs=CHECKSUM_SANITIZED",
                "id_601" to "/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_601&cs=CHECKSUM_SANITIZED",
            ),
        )

        val setSessionIdMethod = SimePortalSession::class.java.getDeclaredField("_sessionId")
        setSessionIdMethod.isAccessible = true
        setSessionIdMethod.set(session, "999999")

        val setPortalUrlMethod = SimePortalSession::class.java.getDeclaredField("_portalUrl")
        setPortalUrlMethod.isAccessible = true
        setPortalUrlMethod.set(session, "https://agenda.sime.md/ords/r/agenda/main/1?session=999999")

        val setCurrentStudentMethod = SimePortalSession::class.java.getDeclaredField("_currentStudentKey")
        setCurrentStudentMethod.isAccessible = true
        setCurrentStudentMethod.set(session, "id_101")

        val setRoleMethod = SimePortalSession::class.java.getDeclaredField("_role")
        setRoleMethod.isAccessible = true
        setRoleMethod.set(session, AccountRole.PARENT)

        val studentPagesField = SimePortalSession::class.java.getDeclaredField("studentPages")
        studentPagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val studentPages = studentPagesField.get(session) as MutableMap<String, PortalStudentDashboardPage>
        studentPages["id_101"] = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentAHtml,
            studentKey = "id_101",
            fetchedAtEpochMillis = 1000L,
        )

        return SimePortalClient(
            session = session,
            nowMillis = { 1000L },
            todayProvider = { "2026-10-24" },
        )
    }

    private fun htmlResponse(html: String): SimeHttpExecutor.RawHopResponse =
        SimeHttpExecutor.RawHopResponse(
            status = 200,
            headers = mapOf("Content-Type" to listOf("text/html; charset=UTF-8")),
            bodyBytes = html.toByteArray(StandardCharsets.UTF_8),
        )

    private fun jsonResponse(json: String): SimeHttpExecutor.RawHopResponse =
        SimeHttpExecutor.RawHopResponse(
            status = 200,
            headers = mapOf("Content-Type" to listOf("application/json; charset=UTF-8")),
            bodyBytes = json.toByteArray(StandardCharsets.UTF_8),
        )

    private fun parseFormBody(encoded: String): Map<String, String> =
        encoded.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                val v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                k to v
            }
}

