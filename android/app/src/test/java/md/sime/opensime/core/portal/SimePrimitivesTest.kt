package md.sime.opensime.core.portal

import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 1 unit tests covering:
 * 1. Strict `SimeUrlValidator` (malformed URLs, untrusted redirect destinations, checksum enforcement, session rewriting).
 * 2. `ChisinauTime` (`Europe/Chisinau` date/time formatting, DST transitions, year inference, full-date guards, semester inference).
 * 3. `SimeLogScrubber` (passwords, `P101_PASSWORD`, bearer tokens, cookies, checksums, salts, nested maps/lists).
 * 4. `SimePortalError` hierarchy and domain exception mapping (`STUDENT_MISMATCH`, `STUDENT_NOT_FOUND`, `PORTAL_THROTTLED`).
 * 5. Sanitized fixture corpus integrity, including incomplete login forms, rejected/throttled responses, and untrusted redirects.
 */
class SimePrimitivesTest {

    // --- 1. SimeUrlValidator Tests ---

    @Test
    fun `requireSimeUrl accepts valid https agenda sime md URLs and normalizes default port 443`() {
        assertEquals(
            "https://agenda.sime.md/ords/r/agenda/main/public",
            SimeUrlValidator.requireSimeUrl("https://agenda.sime.md/ords/r/agenda/main/public"),
        )
        assertEquals(
            "https://agenda.sime.md/ords/r/agenda/main/dashboard?session=999999",
            SimeUrlValidator.requireSimeUrl("https://AGENDA.SIME.MD:443/ords/r/agenda/main/dashboard?session=999999"),
        )
        assertEquals(
            "https://agenda.sime.md/",
            SimeUrlValidator.requireSimeUrl("https://agenda.sime.md"),
        )
    }

    @Test
    fun `requireSimeUrl and resolveSimeUrl reject malformed URLs and untrusted redirect destinations`() {
        val untrustedOrMalformed = listOf(
            "",
            "   ",
            "not a url",
            "http://agenda.sime.md/ords/r/agenda/main/public",
            "ftp://agenda.sime.md/ords",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "https://evil.example/ords",
            "https://agenda.sime.md.evil.example/ords",
            "https://sub.agenda.sime.md/ords",
            "https://agenda.sime.md@evil.example/ords",
            "https://user:pass@agenda.sime.md/ords",
            "https://user@agenda.sime.md/ords",
            "https://agenda.sime.md:8443/ords",
            "https://agenda.sime.md:80/ords",
            "https://agenda.sime.md\\@evil.example/ords",
            "https://agenda.sime.md/ords\nHost: evil.example",
            "//evil.example/ords",
        )

        for (candidate in untrustedOrMalformed) {
            try {
                SimeUrlValidator.requireSimeUrl(candidate)
                fail("Expected requireSimeUrl to reject '$candidate'")
            } catch (err: SimeNetworkError) {
                assertEquals(SimeErrorCode.PORTAL_UNEXPECTED_PAGE, err.code)
                assertTrue(err.message!!.contains("Refused to contact a host other than agenda.sime.md"))
            }
        }

        // Relative redirects resolve onto https://agenda.sime.md, while off-host redirect targets are blocked
        assertEquals(
            "https://agenda.sime.md/ords/r/agenda/main/main-page?session=999999",
            SimeUrlValidator.resolveSimeUrl("/ords/r/agenda/main/main-page?session=999999"),
        )
        assertEquals(
            "https://agenda.sime.md/ords/wwv_flow.accept",
            SimeUrlValidator.resolveSimeUrl("wwv_flow.accept", "https://agenda.sime.md/ords/"),
        )

        val untrustedRedirects = listOf(
            "https://evil.example/phish",
            "//evil.example/phish",
            "http://agenda.sime.md/downgrade",
            "https://agenda.sime.md:8080/custom-port",
            "https://admin:secret@agenda.sime.md/ords",
        )
        for (redirectTarget in untrustedRedirects) {
            try {
                SimeUrlValidator.resolveSimeUrl(redirectTarget, "https://agenda.sime.md/ords/r/agenda/main/public")
                fail("Expected resolveSimeUrl to block redirect target '$redirectTarget'")
            } catch (err: SimeNetworkError) {
                assertTrue(err.message!!.contains("Refused"))
            }
        }
    }

    @Test
    fun `studentSwitchUrl enforces APEX checksum and formatSwitchUrl rewrites session ID safely`() {
        try {
            SimeUrlValidator.studentSwitchUrl(sessionId = "999999", studentId = "ID_101", checksum = null)
            fail("Expected studentSwitchUrl without checksum to fail")
        } catch (err: SimeStudentNotFoundError) {
            assertEquals(SimeErrorCode.STUDENT_NOT_FOUND, err.code)
        }

        try {
            SimeUrlValidator.studentSwitchUrl(sessionId = "999999", studentId = "ID_101", checksum = "   ")
            fail("Expected studentSwitchUrl with blank checksum to fail")
        } catch (err: SimeStudentNotFoundError) {
            assertEquals(SimeErrorCode.STUDENT_NOT_FOUND, err.code)
        }

        val switchUrl = SimeUrlValidator.studentSwitchUrl(
            sessionId = "999999",
            studentId = "ID_501",
            checksum = "CHECKSUM_SANITIZED",
        )
        assertEquals(
            "https://agenda.sime.md/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_501&cs=CHECKSUM_SANITIZED",
            switchUrl,
        )

        val rewritten = SimeUrlValidator.formatSwitchUrl(
            rawHref = "/ords/f?p=AGENDA:1:111222::NO::P1_ELEVE_ID:ID_601&cs=CHECKSUM_SANITIZED",
            sessionId = "999999",
        )
        assertEquals(
            "https://agenda.sime.md/ords/f?p=AGENDA:1:999999::NO::P1_ELEVE_ID:ID_601&cs=CHECKSUM_SANITIZED",
            rewritten,
        )

        assertNull(SimeUrlValidator.formatSwitchUrl(null, "999999"))
        assertNull(SimeUrlValidator.formatSwitchUrl("   ", "999999"))

        // Untrusted href in formatSwitchUrl must fail closed
        try {
            SimeUrlValidator.formatSwitchUrl("https://evil.example/ords/f?p=AGENDA:1:111::NO::P1_ELEVE_ID:1", "999999")
            fail("Expected formatSwitchUrl to reject off-host href")
        } catch (err: SimeNetworkError) {
            assertTrue(err.message!!.contains("Refused"))
        }
    }

    @Test
    fun `unescapeJs decodes unicode and hex escapes preserving Romanian diacritics`() {
        assertEquals(
            "f?p=/A părinte&cs=1",
            SimeUrlValidator.unescapeJs("""f?p=\u002F\x41 p\u0103rinte\u0026cs=1"""),
        )
    }

    // --- 2. ChisinauTime Tests ---

    @Test
    fun `ChisinauTime parses dates, infers plausible years, and rejects invalid calendar dates`() {
        val today = "2026-10-24"
        assertEquals("2026-10-19", ChisinauTime.parseDate("19.10", today))
        assertEquals("2025-12-30", ChisinauTime.parseDate("30.12", today))
        assertNull(ChisinauTime.parseDate("31.02.2026", today))
        assertNull(ChisinauTime.parseDate("29.02.2025", today))
        assertEquals("2024-02-29", ChisinauTime.parseDate("29.02.2024", today))
        assertEquals("2026-10-01", ChisinauTime.parseDate("2026-10-01", today))
        assertEquals("2026-09-05", ChisinauTime.parseDate("05.09.26", today))

        // parseFullDate rejects decimal marks like "9.5" or "8,75" so they are never read as dates
        assertNull(ChisinauTime.parseFullDate("9.5"))
        assertNull(ChisinauTime.parseFullDate("19.10"))
        assertEquals("2026-10-19", ChisinauTime.parseFullDate("Nota din 19.10.2026"))
        assertEquals("2026-11-15", ChisinauTime.parseFullDate("2026-11-15"))
    }

    @Test
    fun `ChisinauTime handles Europe Chisinau timestamps, weekStart, and semester inference`() {
        // 2026-10-24 is in EEST (UTC+3) before the last Sunday of October
        val startEpoch = ChisinauTime.parseEventTime("2026-10-24T08:30:00")
        assertNotNull(startEpoch)
        assertEquals("2026-10-24", ChisinauTime.chisinauDate(startEpoch!!))
        assertEquals("08:30", ChisinauTime.chisinauHm(startEpoch))

        // Explicit UTC offset conversion: 05:30Z == 08:30 EEST (UTC+3)
        val utcEpoch = ChisinauTime.parseEventTime("2026-10-24T05:30:00Z")
        assertEquals(startEpoch, utcEpoch)

        // Winter EET (UTC+2) timestamp: 2026-01-15T08:00:00
        val winterEpoch = ChisinauTime.parseEventTime("2026-01-15T08:00:00")
        assertNotNull(winterEpoch)
        assertEquals("2026-01-15", ChisinauTime.chisinauDate(winterEpoch!!))
        assertEquals("08:00", ChisinauTime.chisinauHm(winterEpoch))

        // Invalid event times return null
        assertNull(ChisinauTime.parseEventTime("bad-timestamp"))
        assertNull(ChisinauTime.parseEventTime("2026-02-30T08:00:00"))
        assertNull(ChisinauTime.parseEventTime("2026-10-24T25:00:00"))

        // Week start is always Monday
        assertEquals("2026-10-19", ChisinauTime.weekStart("2026-10-24")) // Saturday -> Monday
        assertEquals("2026-10-19", ChisinauTime.weekStart("2026-10-19")) // Monday -> Monday
        assertEquals("2026-10-19", ChisinauTime.weekStart("2026-10-25")) // Sunday -> Monday

        // Semester inference: Sep-Dec = 1, Jan-Aug = 2
        assertEquals(1, ChisinauTime.inferSemester("2026-09-01", fallback = 2))
        assertEquals(1, ChisinauTime.inferSemester("2026-12-20", fallback = 2))
        assertEquals(2, ChisinauTime.inferSemester("2027-01-10", fallback = 1))
        assertEquals(2, ChisinauTime.inferSemester("2027-05-31", fallback = 1))
        assertEquals(1, ChisinauTime.inferAbsenceSemester("2026-10-14", explicitSemester = null))
        assertEquals(2, ChisinauTime.inferAbsenceSemester("2026-10-14", explicitSemester = 2))
    }

    // --- 3. SimeLogScrubber Tests ---

    @Test
    fun `SimeLogScrubber redacts passwords, tokens, cookies, salts, submission IDs, and checksums`() {
        val rawJson =
            """{"password":"mySuperSecret123","P101_PASSWORD":"apexPassword99","token":"tok-abc","pSalt":"222222","pPageSubmissionId":"111111","user":"elev1"}"""
        val scrubbedJson = SimeLogScrubber.scrubString(rawJson)
        assertFalse(scrubbedJson.contains("mySuperSecret123"))
        assertFalse(scrubbedJson.contains("apexPassword99"))
        assertFalse(scrubbedJson.contains("tok-abc"))
        assertFalse(scrubbedJson.contains("222222"))
        assertFalse(scrubbedJson.contains("111111"))
        assertTrue(scrubbedJson.contains("\"user\":\"elev1\""))
        assertTrue(scrubbedJson.contains(SimeLogScrubber.REDACTED))

        val apexPayloadJson =
            """{"itemsToSubmit":[{"n":"P101_USERNAME","v":"parent1"},{"n":"P101_PASSWORD","v":"topSecretPass!"}]}"""
        val scrubbedApex = SimeLogScrubber.scrubString(apexPayloadJson)
        assertFalse(scrubbedApex.contains("topSecretPass!"))
        assertTrue(scrubbedApex.contains("parent1"))

        val sampleUrl =
            "https://agenda.sime.md/ords/f?p=104:1:123::NO::&cs=3A9F5E12B7C80&password=secret&pSalt=987654"
        val scrubbedUrl = SimeLogScrubber.scrubString(sampleUrl)
        assertFalse(scrubbedUrl.contains("3A9F5E12B7C80"))
        assertFalse(scrubbedUrl.contains("password=secret"))
        assertFalse(scrubbedUrl.contains("987654"))
        assertTrue(scrubbedUrl.contains("cs=[REDACTED]"))
        assertTrue(scrubbedUrl.contains("password=[REDACTED]"))

        val headersLine = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret\nCookie: ORA_WWV_APP_104=sessionCookieValue; Path=/"
        val scrubbedHeaders = SimeLogScrubber.scrubString(headersLine)
        assertFalse(scrubbedHeaders.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(scrubbedHeaders.contains("sessionCookieValue"))
        assertTrue(scrubbedHeaders.contains("Bearer [REDACTED]"))
        assertTrue(scrubbedHeaders.contains("Cookie: [REDACTED]"))

        val mapInput = mapOf(
            "username" to "parent1",
            "password" to "secretPassword",
            "P101_PASSWORD" to "secretPassword2",
            "token" to "session-xyz",
            "cookie" to "ora_wwv_apex=abc123",
            "cs" to "CHECKSUM_123",
            "safeData" to "valid data",
            "nested" to listOf(mapOf("pSalt" to "999888", "subject" to "Matematica")),
        )
        @Suppress("UNCHECKED_CAST")
        val scrubbedMap = SimeLogScrubber.scrubValue(mapInput) as Map<String, Any?>
        assertEquals("[REDACTED]", scrubbedMap["password"])
        assertEquals("[REDACTED]", scrubbedMap["P101_PASSWORD"])
        assertEquals("[REDACTED]", scrubbedMap["token"])
        assertEquals("[REDACTED]", scrubbedMap["cookie"])
        assertEquals("[REDACTED]", scrubbedMap["cs"])
        assertEquals("valid data", scrubbedMap["safeData"])
        assertEquals("parent1", scrubbedMap["username"])
    }

    // --- 4. SimePortalError & Student Isolation Error Mapping Tests ---

    @Test
    fun `SimePortalError maps domain student isolation exceptions and clamps retryAfterSeconds`() {
        val domainMismatch = StudentMismatchException(
            requestedStudentId = StudentId("ID_101"),
            actualStudentId = StudentId("ID_201"),
        )
        val mappedMismatch = SimePortalError.fromThrowable(domainMismatch)
        assertTrue(mappedMismatch is SimeStudentMismatchError)
        assertEquals(SimeErrorCode.STUDENT_MISMATCH, mappedMismatch.code)
        assertEquals(StudentId("ID_101"), (mappedMismatch as SimeStudentMismatchError).requestedStudentId)
        assertEquals(StudentId("ID_201"), mappedMismatch.actualStudentId)

        val domainNotFound = StudentNotFoundException(StudentId("ID_999"))
        val mappedNotFound = SimePortalError.fromThrowable(domainNotFound)
        assertTrue(mappedNotFound is SimeStudentNotFoundError)
        assertEquals(SimeErrorCode.STUDENT_NOT_FOUND, mappedNotFound.code)

        val throttled = SimeRateLimitError("Rate limited", waitSeconds = 99_999)
        assertEquals(SimeErrorCode.PORTAL_THROTTLED, throttled.code)
        assertEquals(SimePortalError.MAX_WAIT_SECONDS, throttled.waitSeconds)
    }

    // --- 5. Sanitized Fixture Corpus & Incomplete/Invalid Fixture Validation Tests ---

    @Test
    fun `sanitized fixture corpus loads cleanly and contains no unredacted personal data`() {
        for (path in SimePortalFixtures.ALL_FIXTURE_PATHS) {
            val content = SimePortalFixtures.readFixture(path)
            assertTrue("Fixture $path must not be blank", content.isNotBlank())
            // Verify real personal data from sanitization tests is never present
            assertFalse("Fixture $path must not contain real names", content.contains("Ion Popescu"))
            assertFalse("Fixture $path must not contain real addresses", content.contains("Stefan cel Mare"))
        }

        val dashboardA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val dashboardB = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_B_HTML)
        assertTrue(dashboardA.contains("id=\"P1_ELEVE_ID\" name=\"P1_ELEVE_ID\" value=\"ID_101\""))
        assertTrue(dashboardB.contains("id=\"P1_ELEVE_ID\" name=\"P1_ELEVE_ID\" value=\"ID_201\""))
        assertTrue(dashboardA.contains("P1_JSON_ABSENCES"))
        assertTrue(dashboardA.contains("t_TreeNav"))
    }

    @Test
    fun `fixture validation accepts valid login dialog and rejects incomplete dialog missing pSalt`() {
        val validHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_VALID_HTML)
        val tokens = SimePortalFixtures.validateLoginDialogHtml(validHtml)
        assertEquals("104", tokens["pFlowId"])
        assertEquals("101", tokens["pFlowStepId"])
        assertEquals("999999", tokens["pInstance"])
        assertEquals("111111", tokens["pPageSubmissionId"])
        assertEquals("222222", tokens["pSalt"])
        assertEquals("PROT_CHECKSUM_SANITIZED", tokens["pPageItemsProtected"])
        assertEquals("https://agenda.sime.md/ords/wwv_flow.accept", tokens["actionUrl"])

        val incompleteHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_INCOMPLETE_HTML)
        try {
            SimePortalFixtures.validateLoginDialogHtml(incompleteHtml)
            fail("Expected incomplete login dialog missing pSalt to throw SimeParseError")
        } catch (err: SimeParseError) {
            assertEquals(SimeErrorCode.PORTAL_UNEXPECTED_PAGE, err.code)
            assertTrue(err.message!!.contains("missing required APEX fields"))
        }
    }

    @Test
    fun `fixture validation handles success redirect, rejected login, throttled lockout, and untrusted redirect`() {
        // 1. Valid login response
        val successJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_SUCCESS_JSON)
        val (portalUrl, sessionId) = SimePortalFixtures.validateLoginResponseJson(successJson)
        assertEquals("https://agenda.sime.md/ords/r/agenda/main/main-page?session=999999", portalUrl)
        assertEquals("999999", sessionId)

        // 2. Rejected credentials response
        val rejectedJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_REJECTED_JSON)
        try {
            SimePortalFixtures.validateLoginResponseJson(rejectedJson)
            fail("Expected rejected login fixture to throw SimeLoginRejectedError")
        } catch (err: SimeLoginRejectedError) {
            assertEquals(SimeErrorCode.PORTAL_LOGIN_REJECTED, err.code)
            assertEquals("Invalid login credentials", err.message)
        }

        // 3. Throttled / blocked account response
        val throttledJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_THROTTLED_JSON)
        try {
            SimePortalFixtures.validateLoginResponseJson(throttledJson)
            fail("Expected throttled login fixture to throw SimeRateLimitError")
        } catch (err: SimeRateLimitError) {
            assertEquals(SimeErrorCode.PORTAL_THROTTLED, err.code)
            assertEquals(120, err.waitSeconds)
        }

        // 4. Untrusted redirect target inside base64 atob(...)
        val untrustedJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_UNTRUSTED_REDIRECT_JSON)
        try {
            SimePortalFixtures.validateLoginResponseJson(untrustedJson)
            fail("Expected untrusted redirect fixture to throw SimeNetworkError")
        } catch (err: SimeNetworkError) {
            assertTrue(err.message!!.contains("Refused to contact a host other than agenda.sime.md"))
        }

        // 5. Malformed / incomplete JSON responses
        for (invalidBody in listOf("", "not json", "[]", "{}", """{"redirectURL": 123}""")) {
            try {
                SimePortalFixtures.validateLoginResponseJson(invalidBody)
                fail("Expected invalid body '$invalidBody' to throw SimeParseError")
            } catch (err: SimeParseError) {
                assertEquals(SimeErrorCode.PORTAL_UNEXPECTED_PAGE, err.code)
            }
        }
    }
}

