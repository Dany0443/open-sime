package md.sime.opensime.core.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SimeAuthCoordinatorTest {

    private val publicEntryHtml = SimePortalFixtures.readFixture(SimePortalFixtures.PUBLIC_ENTRY_HTML)
    private val validDialogHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_VALID_HTML)
    private val incompleteDialogHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_INCOMPLETE_HTML)
    private val loginSuccessJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_SUCCESS_JSON)
    private val loginRejectedJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_REJECTED_JSON)
    private val loginThrottledJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_THROTTLED_JSON)
    private val loginUntrustedRedirectJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_UNTRUSTED_REDIRECT_JSON)
    private val dashboardStudentAHtml = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
    private val dashboardStudentBHtml = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_B_HTML)

    // -------------------------------------------------------------------------
    // 1. Successful 4-Step Login Sequence & Dashboard Verification
    // -------------------------------------------------------------------------

    @Test
    fun `login executes exact 4-step APEX sequence, verifies dashboard, and loads initial student`() = runBlocking {
        val requests = mutableListOf<SimeHttpExecutor.RawHopRequest>()
        val coordinator = createCoordinator { req ->
            requests.add(req)
            when {
                req.method == "GET" && req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> {
                    htmlResponse(
                        html = publicEntryHtml,
                        setCookie = "ORA_WWV_APP_104=PUBLIC_COOKIE; Path=/ords; Secure; HttpOnly",
                    )
                }
                req.method == "GET" && req.url.contains("login_desktop") && req.url.contains("p101_user_type=1") -> {
                    htmlResponse(
                        html = validDialogHtml,
                        setCookie = "ORA_WWV_DIALOG=DIALOG_COOKIE; Path=/ords; Secure; HttpOnly",
                    )
                }
                req.method == "POST" && req.url == "https://agenda.sime.md/ords/wwv_flow.accept" -> {
                    jsonResponse(
                        json = loginSuccessJson,
                        setCookie = "ORA_WWV_APP_104=AUTH_SESSION_COOKIE; Path=/ords; Secure; HttpOnly",
                    )
                }
                req.method == "GET" && req.url.contains("session=999999") -> {
                    htmlResponse(html = dashboardStudentAHtml)
                }
                else -> fail("Unexpected request: ${req.method} ${req.url}") as Nothing
            }
        }

        val authResult = coordinator.login(
            username = "parinte.test",
            password = "synthetic-password-123",
            role = AccountRole.PARENT,
        )

        // Verify exact 4-step sequence
        assertEquals(4, requests.size)
        assertEquals("GET", requests[0].method)
        assertEquals("https://agenda.sime.md/ords/r/agenda/main/public", requests[0].url)

        assertEquals("GET", requests[1].method)
        assertTrue(requests[1].url.contains("login_desktop"))
        assertTrue(requests[1].url.contains("p101_user_type=1"))

        assertEquals("POST", requests[2].method)
        assertEquals("https://agenda.sime.md/ords/wwv_flow.accept", requests[2].url)
        assertEquals("XMLHttpRequest", requests[2].headers["X-Requested-With"])
        assertEquals("https://agenda.sime.md", requests[2].headers["Origin"])
        assertEquals(requests[1].url, requests[2].headers["Referer"])
        assertEquals("application/json, text/javascript, */*; q=0.01", requests[2].headers["Accept"])

        // Inspect form-urlencoded POST body and p_json structure
        val postBody = String(requests[2].bodyBytes!!, StandardCharsets.UTF_8)
        val formParams = parseFormBody(postBody)
        assertEquals("104", formParams["p_flow_id"])
        assertEquals("101", formParams["p_flow_step_id"])
        assertEquals("999999", formParams["p_instance"])
        assertEquals("111111", formParams["p_page_submission_id"])
        assertEquals("LOGIN", formParams["p_request"])
        assertEquals("S", formParams["p_reload_on_submit"])

        val pJson = SimpleJson.parse(formParams.getValue("p_json")) as Map<*, *>
        assertEquals("222222", pJson["salt"])
        val pageItems = pJson["pageItems"] as Map<*, *>
        assertEquals("PROT_CHECKSUM_SANITIZED", pageItems["protected"])
        val items = pageItems["itemsToSubmit"] as List<*>
        assertEquals(4, items.size)
        val item0 = items[0] as Map<*, *>
        assertEquals("P101_USER_TYPE", item0["n"])
        assertEquals("1", item0["v"])
        assertEquals("ITEM_CHECKSUM_SANITIZED", item0["ck"])
        val item1 = items[1] as Map<*, *>
        assertEquals("P101_USER_TYPE_NAME", item1["n"])
        assertEquals("Autentificare părinte", item1["v"])
        assertEquals("ITEM_CHECKSUM_SANITIZED", item1["ck"])
        val item2 = items[2] as Map<*, *>
        assertEquals("P101_USERNAME", item2["n"])
        assertEquals("parinte.test", item2["v"])
        val item3 = items[3] as Map<*, *>
        assertEquals("P101_PASSWORD", item3["n"])
        assertEquals("synthetic-password-123", item3["v"])

        // Verify Step 4 landing request and authenticated coordinator state
        assertEquals("GET", requests[3].method)
        assertEquals("https://agenda.sime.md/ords/r/agenda/main/main-page?session=999999", requests[3].url)

        assertTrue(coordinator.isAuthenticated)
        assertEquals(AccountRole.PARENT, coordinator.role)
        assertEquals("999999", coordinator.session.sessionId)
        assertEquals(StudentId("id_101"), coordinator.activeStudentId)
        assertEquals(StudentId("id_101"), coordinator.activeSnapshot?.studentId)
        assertEquals("Elev 1", authResult.initialStudent.fullName)
        assertEquals(2, authResult.students.size)
        assertEquals(listOf(StudentId("id_101"), StudentId("id_601")), authResult.students.map { it.id })
    }

    // -------------------------------------------------------------------------
    // 2. Rejected Login, Incomplete Dialog, Untrusted Redirect, Unexpected Page
    // -------------------------------------------------------------------------

    @Test
    fun `rejected login throws SimeLoginRejectedError, clears cookies, and locks after 4 consecutive rejections`() = runBlocking {
        val fakeClock = AtomicLong(1_000_000L)
        val networkCalls = AtomicInteger(0)

        val coordinator = createCoordinator(nowMillis = fakeClock::get) { req ->
            networkCalls.incrementAndGet()
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(
                    html = publicEntryHtml,
                    setCookie = "ORA_WWV_APP_104=TEMP_SESSION; Path=/ords; Secure",
                )
                req.url.contains("login_desktop") -> htmlResponse(validDialogHtml)
                req.method == "POST" -> jsonResponse(loginRejectedJson)
                else -> fail("Unexpected request: ${req.url}") as Nothing
            }
        }

        // 4 rejected login attempts
        for (attempt in 1..4) {
            try {
                coordinator.login("parinte.test", "wrong-pass-$attempt")
                fail("Expected SimeLoginRejectedError on attempt $attempt")
            } catch (err: SimeLoginRejectedError) {
                assertEquals(SimeErrorCode.PORTAL_LOGIN_REJECTED, err.code)
                assertFalse(coordinator.isAuthenticated)
                assertNull(coordinator.session.sessionId)
                assertTrue(coordinator.session.http.cookies.isEmpty())
            }
        }

        val callsBefore5th = networkCalls.get()
        // 5th attempt within 60s window must fail immediately with SimeRateLimitError without network traffic
        try {
            coordinator.login("parinte.test", "wrong-pass-5")
            fail("Expected SimeRateLimitError on 5th attempt after 4 rejections")
        } catch (err: SimeRateLimitError) {
            assertEquals(SimeErrorCode.PORTAL_THROTTLED, err.code)
            assertEquals(60, err.waitSeconds)
        }
        assertEquals("Lockout guard must block before making network calls", callsBefore5th, networkCalls.get())

        // Advance clock past 60s lockout -> login is allowed to contact portal again
        fakeClock.addAndGet(61_000L)
        try {
            coordinator.login("parinte.test", "wrong-pass-after-lockout")
            fail("Expected SimeLoginRejectedError")
        } catch (_: SimeLoginRejectedError) {
            assertTrue(networkCalls.get() > callsBefore5th)
        }
    }

    @Test
    fun `malformed or incomplete login dialog fails closed with SimeParseError and clears cookies`() = runBlocking {
        val coordinator = createCoordinator { req ->
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(
                    html = publicEntryHtml,
                    setCookie = "ORA_WWV_APP_104=PRE_DIALOG_COOKIE; Path=/ords; Secure",
                )
                req.url.contains("login_desktop") -> htmlResponse(incompleteDialogHtml)
                else -> fail("Should not reach POST step when dialog is incomplete") as Nothing
            }
        }

        try {
            coordinator.login("parinte.test", "pass123")
            fail("Expected SimeParseError for missing pSalt")
        } catch (err: SimeParseError) {
            assertTrue(err.message!!.contains("missing required APEX fields"))
        }
        assertFalse(coordinator.isAuthenticated)
        assertTrue(coordinator.session.http.cookies.isEmpty())
    }

    @Test
    fun `untrusted redirect in login response fails closed with SimeNetworkError and clears cookies`() = runBlocking {
        val coordinator = createCoordinator { req ->
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(
                    html = publicEntryHtml,
                    setCookie = "ORA_WWV_APP_104=COOKIE_1; Path=/ords; Secure",
                )
                req.url.contains("login_desktop") -> htmlResponse(validDialogHtml)
                req.method == "POST" -> jsonResponse(loginUntrustedRedirectJson)
                else -> fail("Must never follow untrusted redirect to evil.example") as Nothing
            }
        }

        try {
            coordinator.login("parinte.test", "pass123")
            fail("Expected SimeNetworkError for untrusted redirect")
        } catch (err: SimeNetworkError) {
            assertEquals("Refused to contact a host other than agenda.sime.md", err.message)
        }
        assertFalse(coordinator.isAuthenticated)
        assertNull(coordinator.session.sessionId)
        assertTrue(coordinator.session.http.cookies.isEmpty())
    }

    @Test
    fun `unexpected non-dashboard landing page or maintenance page fails closed and cleans up session`() = runBlocking {
        var landingHtml = "<html><body><h1>Unexpected blank page without dashboard items</h1></body></html>"
        val coordinator = createCoordinator { req ->
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(publicEntryHtml)
                req.url.contains("login_desktop") -> htmlResponse(validDialogHtml)
                req.method == "POST" -> jsonResponse(loginSuccessJson)
                req.url.contains("session=999999") -> htmlResponse(landingHtml)
                else -> fail("Unexpected URL: ${req.url}") as Nothing
            }
        }

        try {
            coordinator.login("parinte.test", "pass123")
            fail("Expected SimeSessionExpiredError on non-dashboard landing page")
        } catch (err: SimeSessionExpiredError) {
            assertEquals(SimeErrorCode.PORTAL_UNEXPECTED_PAGE, err.code)
        }
        assertFalse(coordinator.isAuthenticated)
        assertNull(coordinator.activeSnapshot)

        // Now test maintenance page detection on landing
        landingHtml = "<html><body>Portalul este în mentenanță. Lucrări tehnice în curs.</body></html>"
        try {
            coordinator.login("parinte.test", "pass123")
            fail("Expected SimeRateLimitError on maintenance landing page")
        } catch (err: SimeRateLimitError) {
            assertEquals(SimeErrorCode.PORTAL_THROTTLED, err.code)
        }
        assertTrue(coordinator.session.http.breaker.isOpen)
        assertFalse(coordinator.isAuthenticated)
    }

    // -------------------------------------------------------------------------
    // 3. Logout and Cleanup After Terminal Failure
    // -------------------------------------------------------------------------

    @Test
    fun `logout and terminal auth failure wipe all in-memory state, snapshots, and cookies`() = runBlocking {
        var failNextSwitchWith401 = false
        val coordinator = createTwoStudentCoordinator(
            onStudentBRequest = {
                if (failNextSwitchWith401) {
                    SimeHttpExecutor.RawHopResponse(
                        status = 401,
                        headers = emptyMap(),
                        bodyBytes = ByteArray(0),
                    )
                } else {
                    htmlResponse(dashboardStudentBHtml)
                }
            },
        )

        coordinator.login("parinte.test", "pass123")
        assertTrue(coordinator.isAuthenticated)
        assertFalse(coordinator.session.http.cookies.isEmpty())
        assertNotNull(coordinator.cachedSnapshotFor(StudentId("id_101")))

        // Switch to Student B cleanly (using either nav link ID_601 or profile ID_201 once learned)
        val snapB = coordinator.switchStudent(StudentId("ID_601"))
        assertEquals(StudentId("id_601"), snapB.studentId)
        assertNotNull(coordinator.cachedSnapshotFor(StudentId("id_601")))
        assertNotNull(coordinator.cachedSnapshotFor(StudentId("ID_201")))

        // Explicit logout clears everything
        coordinator.logout()
        assertFalse(coordinator.isAuthenticated)
        assertNull(coordinator.session.sessionId)
        assertNull(coordinator.session.portalUrl)
        assertNull(coordinator.activeStudentId)
        assertNull(coordinator.activeSnapshot)
        assertTrue(coordinator.availableStudents().isEmpty())
        assertNull(coordinator.cachedSnapshotFor(StudentId("id_101")))
        assertNull(coordinator.cachedSnapshotFor(StudentId("id_601")))
        assertTrue(coordinator.session.http.cookies.isEmpty())

        // Log in again and trigger a terminal 401 session expiry during forceRefresh switch to Student B
        coordinator.login("parinte.test", "pass123")
        assertTrue(coordinator.isAuthenticated)
        failNextSwitchWith401 = true

        try {
            coordinator.switchStudent(StudentId("ID_601"), forceRefresh = true)
            fail("Expected SimeSessionExpiredError on HTTP 401")
        } catch (_: SimeSessionExpiredError) {
            // expected
        }

        // Terminal auth failure must have wiped session, snapshots, and cookies
        assertFalse(coordinator.isAuthenticated)
        assertNull(coordinator.session.sessionId)
        assertNull(coordinator.activeSnapshot)
        assertNull(coordinator.cachedSnapshotFor(StudentId("id_101")))
        assertTrue(coordinator.session.http.cookies.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 4. Concurrent Login Attempts & Serialized Checksummed Student Switching
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent login attempts and A to B to A student switches are serialized and preserve checksum URLs`() = runBlocking {
        val switchUrlsRequested = Collections.synchronizedList(mutableListOf<String>())
        val activeConcurrency = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val coordinator = createCoordinator { req ->
            val current = activeConcurrency.incrementAndGet()
            maxObservedConcurrency.updateAndGet { maxOf(it, current) }
            try {
                when {
                    req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(
                        html = publicEntryHtml,
                        setCookie = "ORA_WWV_APP_104=SESSION_COOKIE; Path=/ords; Secure",
                    )
                    req.url.contains("login_desktop") -> htmlResponse(validDialogHtml)
                    req.method == "POST" -> jsonResponse(loginSuccessJson)
                    req.url.contains("session=999999") -> htmlResponse(dashboardStudentAHtml)
                    req.url.contains("P1_ELEVE_ID:ID_601") -> {
                        switchUrlsRequested.add(req.url)
                        htmlResponse(dashboardStudentBHtml)
                    }
                    req.url.contains("P1_ELEVE_ID:ID_501") -> {
                        switchUrlsRequested.add(req.url)
                        htmlResponse(dashboardStudentAHtml)
                    }
                    else -> fail("Unexpected URL: ${req.url}") as Nothing
                }
            } finally {
                activeConcurrency.decrementAndGet()
            }
        }

        // Concurrent login calls must serialize cleanly via operationMutex
        val loginJobs = listOf(
            async { coordinator.login("parinte.test", "pass123") },
            async { coordinator.login("parinte.test", "pass123") },
        )
        loginJobs.awaitAll()
        assertEquals("Operation mutex must serialize concurrent logins", 1, maxObservedConcurrency.get())

        // Switch A -> B -> A -> B with forceRefresh (verifying both nav ID_601/ID_501 and profile ID_201/ID_101 aliases)
        val snapB1 = coordinator.switchStudent("ID_601", forceRefresh = true)
        assertEquals(StudentId("id_601"), snapB1.studentId)
        assertEquals("Elev 2", snapB1.student.fullName)
        assertEquals("7B", snapB1.student.className)

        val snapA1 = coordinator.switchStudent("ID_101", forceRefresh = true)
        assertEquals(StudentId("id_101"), snapA1.studentId)
        assertEquals("Elev 1", snapA1.student.fullName)
        assertEquals("10A", snapA1.student.className)

        val snapB2 = coordinator.switchStudent("ID_201", forceRefresh = true)
        assertEquals(StudentId("id_601"), snapB2.studentId)
        assertEquals("Elev 2", snapB2.student.fullName)

        // Every switch URL must include the rewritten active session ID (999999) and its cs= checksum
        assertEquals(3, switchUrlsRequested.size)
        for (url in switchUrlsRequested) {
            assertTrue("Switch URL must rewrite active session ID: $url", url.contains(":999999:"))
            assertTrue("Switch URL must preserve APEX checksum: $url", url.contains("&cs=CHECKSUM_SANITIZED"))
        }
    }

    // -------------------------------------------------------------------------
    // 5. Missing, Invalid, Unauthorized, or Mismatched Student IDs
    // -------------------------------------------------------------------------

    @Test
    fun `missing, blank, or unknown student IDs fail with STUDENT_NOT_FOUND and never fall back to first student`() = runBlocking {
        val coordinator = createTwoStudentCoordinator()
        coordinator.login("parinte.test", "pass123")
        assertEquals(StudentId("id_101"), coordinator.activeStudentId)

        for (invalidId in listOf(null, "", "   ", "ID_999999")) {
            try {
                coordinator.switchStudent(invalidId)
                fail("Expected SimeStudentNotFoundError for studentId='$invalidId'")
            } catch (err: SimeStudentNotFoundError) {
                assertEquals(SimeErrorCode.STUDENT_NOT_FOUND, err.code)
                assertFalse(err is SimeStudentMismatchError)
            }
            // Active snapshot must be cleared and must NEVER fall back to Student 1's snapshot
            assertNull("Must not retain or fall back to first student's snapshot", coordinator.activeSnapshot)
        }
    }

    @Test
    fun `student role cannot switch students or access another student ID`() = runBlocking {
        val coordinator = createCoordinator { req ->
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(publicEntryHtml)
                req.url.contains("login_desktop") && req.url.contains("p101_user_type=2") -> htmlResponse(validDialogHtml)
                req.method == "POST" -> jsonResponse(loginSuccessJson)
                req.url.contains("session=999999") -> htmlResponse(dashboardStudentAHtml)
                else -> fail("Unexpected URL in student role test: ${req.url}") as Nothing
            }
        }

        coordinator.login("elev.test", "pass123", role = AccountRole.STUDENT)
        assertEquals(AccountRole.STUDENT, coordinator.role)

        try {
            coordinator.switchStudent("ID_601")
            fail("Expected student role switchStudent to fail with SimeStudentNotFoundError")
        } catch (err: SimeStudentNotFoundError) {
            assertEquals(SimeErrorCode.STUDENT_NOT_FOUND, err.code)
        }

        try {
            coordinator.getStudentSnapshot("ID_601")
            fail("Expected student role access to another student to fail")
        } catch (err: SimeStudentNotFoundError) {
            assertEquals(SimeErrorCode.STUDENT_NOT_FOUND, err.code)
        }
    }

    @Test
    fun `portal returning wrong student page on switch retries once and fails closed with STUDENT_MISMATCH`() = runBlocking {
        val studentBSwitchAttempts = AtomicInteger(0)
        val coordinator = createTwoStudentCoordinator(
            onStudentBRequest = {
                studentBSwitchAttempts.incrementAndGet()
                // Portal stubbornly returns Student A's page (id_101) when Student B (id_601) was requested
                htmlResponse(dashboardStudentAHtml)
            },
        )

        coordinator.login("parinte.test", "pass123")
        assertEquals(StudentId("id_101"), coordinator.activeSnapshot?.studentId)

        try {
            coordinator.switchStudent("ID_601")
            fail("Expected SimeStudentMismatchError when portal returns Student A for Student B request")
        } catch (err: SimeStudentMismatchError) {
            assertEquals(SimeErrorCode.STUDENT_MISMATCH, err.code)
            assertEquals(StudentId("id_601"), err.requestedStudentId)
            assertEquals(StudentId("id_101"), err.actualStudentId)
        }

        // Verify exactly 2 attempts (initial + 1 retry) were made and activeSnapshot was cleared
        assertEquals(2, studentBSwitchAttempts.get())
        assertNull("Mismatched switch must clear activeSnapshot", coordinator.activeSnapshot)
        assertNull("Mismatched page must never be cached under Student B", coordinator.cachedSnapshotFor(StudentId("id_601")))
    }

    // -------------------------------------------------------------------------
    // 6. Late Response for Student A While Switching to Student B
    // -------------------------------------------------------------------------

    @Test
    fun `late response for student A never overwrites active state after switching to student B`() = runBlocking {
        val studentAGate = CompletableDeferred<Unit>()
        val studentAEnteredNetwork = CompletableDeferred<Unit>()
        var delayStudentA = false

        val coordinator = createCoordinator { req ->
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(publicEntryHtml)
                req.url.contains("login_desktop") -> htmlResponse(validDialogHtml)
                req.method == "POST" -> jsonResponse(loginSuccessJson)
                req.url.contains("session=999999") -> htmlResponse(dashboardStudentAHtml)
                req.url.contains("P1_ELEVE_ID:ID_501") -> {
                    if (delayStudentA) {
                        studentAEnteredNetwork.complete(Unit)
                        studentAGate.await()
                    }
                    htmlResponse(dashboardStudentAHtml)
                }
                req.url.contains("P1_ELEVE_ID:ID_601") -> htmlResponse(dashboardStudentBHtml)
                else -> fail("Unexpected URL: ${req.url}") as Nothing
            }
        }

        coordinator.login("parinte.test", "pass123")

        // First switch to Student B so the portal is on B, then start a slow switch back to Student A
        coordinator.switchStudent("ID_601")
        assertEquals(StudentId("id_601"), coordinator.activeStudentId)

        delayStudentA = true
        val deferredSwitchA = async {
            coordinator.switchStudent("ID_101", forceRefresh = true)
        }

        // Wait until Student A's HTTP request is in flight
        studentAEnteredNetwork.await()
        assertEquals(StudentId("id_101"), coordinator.activeStudentId)
        assertNull("While switch is in flight, activeSnapshot must be null", coordinator.activeSnapshot)

        // Now user switches to Student B before Student A's response returns
        val deferredSwitchB = async {
            coordinator.switchStudent("ID_601", forceRefresh = true)
        }
        yield()

        // Desired active student is immediately updated to Student B (id_601)
        assertEquals(StudentId("id_601"), coordinator.activeStudentId)

        // Release Student A's delayed network response
        studentAGate.complete(Unit)
        val snapA = deferredSwitchA.await()
        val snapB = deferredSwitchB.await()

        // Verify Student A's late completion did NOT overwrite Student B's active state
        assertEquals(StudentId("id_101"), snapA.studentId)
        assertEquals(StudentId("id_601"), snapB.studentId)
        assertEquals(StudentId("id_601"), coordinator.activeStudentId)
        assertEquals(StudentId("id_601"), coordinator.activeSnapshot?.studentId)
        assertEquals("Elev 2", coordinator.activeSnapshot?.student?.fullName)
    }

    // -------------------------------------------------------------------------
    // 7. Cancellation, Timeout, and Rate-Limit Without Leaked Session State
    // -------------------------------------------------------------------------

    @Test
    fun `cancellation, timeout, and throttling during login leave zero leaked cookies or session state`() = runBlocking {
        val dialogGate = CompletableDeferred<Unit>()
        val dialogEntered = CompletableDeferred<Unit>()
        var mode = "CANCEL"

        val coordinator = createCoordinator { req ->
            when {
                req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(
                    html = publicEntryHtml,
                    setCookie = "ORA_WWV_APP_104=INTERMEDIATE_COOKIE; Path=/ords; Secure",
                )
                req.url.contains("login_desktop") -> {
                    when (mode) {
                        "CANCEL" -> {
                            dialogEntered.complete(Unit)
                            dialogGate.await()
                            htmlResponse(validDialogHtml)
                        }
                        "TIMEOUT" -> throw SimeNetworkError("Request timed out")
                        else -> htmlResponse(validDialogHtml)
                    }
                }
                req.method == "POST" -> jsonResponse(loginThrottledJson)
                else -> fail("Unexpected URL: ${req.url}") as Nothing
            }
        }

        // 1. Cancellation mid-login
        val loginJob = async {
            coordinator.login("parinte.test", "pass123")
        }
        dialogEntered.await()
        loginJob.cancel(CancellationException("User cancelled login"))
        try {
            loginJob.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertFalse(coordinator.isAuthenticated)
        assertNull(coordinator.session.sessionId)
        assertTrue("Cancelled login must wipe intermediate cookies", coordinator.session.http.cookies.isEmpty())

        // 2. Timeout mid-login
        mode = "TIMEOUT"
        try {
            coordinator.login("parinte.test", "pass123")
            fail("Expected SimeNetworkError('Request timed out')")
        } catch (err: SimeNetworkError) {
            assertEquals("Request timed out", err.message)
        }
        assertFalse(coordinator.isAuthenticated)
        assertTrue("Timed-out login must wipe intermediate cookies", coordinator.session.http.cookies.isEmpty())

        // 3. Throttled login response (notification_msg with wait 120 seconds)
        mode = "THROTTLE"
        try {
            coordinator.login("parinte.test", "pass123")
            fail("Expected SimeRateLimitError")
        } catch (err: SimeRateLimitError) {
            assertEquals(120, err.waitSeconds)
        }
        assertFalse(coordinator.isAuthenticated)
        assertTrue(coordinator.session.http.breaker.isOpen)
        assertTrue("Throttled login must wipe intermediate cookies", coordinator.session.http.cookies.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 8. Secret Redaction in Error Messages and toString() Output
    // -------------------------------------------------------------------------

    @Test
    fun `error messages and toString outputs redact passwords, session IDs, salts, checksums, and student IDs`() = runBlocking {
        val coordinator = createTwoStudentCoordinator()
        val authResult = coordinator.login("parinte.test", "SUPER_SECRET_PASSWORD_999")

        val tokens = SimePortalParser.parseLoginDialogTokens(validDialogHtml)
        val redirect = SimePortalParser.parseLoginResponseRedirect(loginSuccessJson)
        val errorWithSecrets = SimeNetworkError(
            "Failed URL https://agenda.sime.md/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_101&cs=CHECKSUM_SANITIZED&pSalt=222222&password=SUPER_SECRET_PASSWORD_999",
        )

        val outputs = listOf(
            coordinator.toString(),
            coordinator.session.toString(),
            authResult.toString(),
            tokens.toString(),
            redirect.toString(),
            errorWithSecrets.message.orEmpty(),
            errorWithSecrets.toString(),
        )

        for (rendered in outputs) {
            assertFalse("Leaked password in: $rendered", rendered.contains("SUPER_SECRET_PASSWORD_999"))
            assertFalse("Leaked APEX sessionId in: $rendered", rendered.contains("999999"))
            assertFalse("Leaked pSalt in: $rendered", rendered.contains("222222"))
            assertFalse("Leaked pPageSubmissionId in: $rendered", rendered.contains("111111"))
            assertFalse("Leaked pPageItemsProtected in: $rendered", rendered.contains("PROT_CHECKSUM_SANITIZED"))
            assertFalse("Leaked URL checksum in: $rendered", rendered.contains("CHECKSUM_SANITIZED"))
            assertFalse("Leaked student ID ID_101 in: $rendered", rendered.contains("ID_101"))
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createCoordinator(
        nowMillis: () -> Long = System::currentTimeMillis,
        handler: suspend (SimeHttpExecutor.RawHopRequest) -> SimeHttpExecutor.RawHopResponse,
    ): SimeAuthCoordinator {
        val transport = SimeHttpTransport(
            cookies = SimeCookieJar(nowMillis = nowMillis),
            breaker = CircuitBreaker(nowMillis = nowMillis),
            globalBreaker = CircuitBreaker(nowMillis = nowMillis),
            executor = SimeHttpExecutor(handler),
        )
        val session = SimePortalSession(
            http = transport,
            nowMillis = nowMillis,
            todayProvider = { "2026-10-20" },
        )
        return SimeAuthCoordinator(
            session = session,
            nowMillis = nowMillis,
        )
    }

    private fun createTwoStudentCoordinator(
        onStudentBRequest: suspend (SimeHttpExecutor.RawHopRequest) -> SimeHttpExecutor.RawHopResponse = {
            htmlResponse(dashboardStudentBHtml)
        },
    ): SimeAuthCoordinator = createCoordinator { req ->
        when {
            req.url == SimeUrlValidator.PUBLIC_PORTAL_URL -> htmlResponse(
                html = publicEntryHtml,
                setCookie = "ORA_WWV_APP_104=SESSION_COOKIE; Path=/ords; Secure; HttpOnly",
            )
            req.url.contains("login_desktop") -> htmlResponse(validDialogHtml)
            req.method == "POST" -> jsonResponse(loginSuccessJson)
            req.url.contains("session=999999") -> htmlResponse(dashboardStudentAHtml)
            req.url.contains("P1_ELEVE_ID:ID_501") -> htmlResponse(dashboardStudentAHtml)
            req.url.contains("P1_ELEVE_ID:ID_601") -> onStudentBRequest(req)
            else -> fail("Unexpected request: ${req.method} ${req.url}") as Nothing
        }
    }

    private fun htmlResponse(
        html: String,
        setCookie: String? = null,
    ): SimeHttpExecutor.RawHopResponse {
        val headers = linkedMapOf<String, List<String>>(
            "Content-Type" to listOf("text/html; charset=UTF-8"),
        )
        if (setCookie != null) {
            headers["Set-Cookie"] = listOf(setCookie)
        }
        return SimeHttpExecutor.RawHopResponse(
            status = 200,
            headers = headers,
            bodyBytes = html.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun jsonResponse(
        json: String,
        setCookie: String? = null,
    ): SimeHttpExecutor.RawHopResponse {
        val headers = linkedMapOf<String, List<String>>(
            "Content-Type" to listOf("application/json; charset=UTF-8"),
        )
        if (setCookie != null) {
            headers["Set-Cookie"] = listOf(setCookie)
        }
        return SimeHttpExecutor.RawHopResponse(
            status = 200,
            headers = headers,
            bodyBytes = json.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun parseFormBody(encoded: String): Map<String, String> =
        encoded.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                val k = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                val v = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                k to v
            }
}
