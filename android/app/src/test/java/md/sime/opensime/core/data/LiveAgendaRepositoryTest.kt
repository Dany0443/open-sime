package md.sime.opensime.core.data

import kotlinx.coroutines.runBlocking
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentNotFoundException
import md.sime.opensime.core.portal.ParsedStudentProfile
import md.sime.opensime.core.portal.PortalStudentDashboardPage
import md.sime.opensime.core.portal.SimeHttpExecutor
import md.sime.opensime.core.portal.SimeHttpTransport
import md.sime.opensime.core.portal.SimeNetworkError
import md.sime.opensime.core.portal.SimePortalClient
import md.sime.opensime.core.portal.SimePortalFixtures
import md.sime.opensime.core.portal.SimePortalSession
import md.sime.opensime.core.portal.SimeRateLimitError
import md.sime.opensime.core.portal.SimeStudentMismatchError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LiveAgendaRepositoryTest {

    private val dashboardStudentAHtml = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
    private val dashboardStudentBHtml = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_B_HTML)
    private val calendarWeekJson = SimePortalFixtures.readFixture(SimePortalFixtures.CALENDAR_EVENTS_WEEK_JSON)

    // -------------------------------------------------------------------------
    // 1. Student Listing & Scoping
    // -------------------------------------------------------------------------

    @Test
    fun `listStudents returns domain profiles for all known students in session`() {
        val (repo, _) = createRepositoryFixture()

        val students = repo.listStudents()
        assertEquals(2, students.size)
        assertEquals(StudentId("id_101"), students[0].id)
        assertEquals("Elev 1", students[0].fullName)
        assertEquals("10A", students[0].className)

        assertEquals(StudentId("id_201"), students[1].id)
        assertEquals("Elev 2", students[1].fullName)
        assertEquals("7B", students[1].className)
    }

    @Test
    fun `getStudentSnapshot requires explicit valid student ID and never falls back to first student`() = runBlocking {
        val (repo, _) = createRepositoryFixture()

        try {
            repo.getStudentSnapshot(StudentId("unknown_student"))
            fail("Expected StudentNotFoundException")
        } catch (err: StudentNotFoundException) {
            assertEquals(StudentId("unknown_student"), err.requestedStudentId)
        }
    }

    // -------------------------------------------------------------------------
    // 2. Verified Snapshot Fetching & Student Isolation
    // -------------------------------------------------------------------------

    @Test
    fun `getStudentSnapshot returns verified snapshot for requested student with isolated metadata`() = runBlocking {
        val (repo, _) = createRepositoryFixture()

        val snapA = repo.getStudentSnapshot(StudentId("id_101"))
        assertEquals(StudentId("id_101"), snapA.studentId)
        assertEquals("10A", snapA.student.className)
        assertEquals("Profesor 1", snapA.student.classMasterName)
        assertEquals("Liceul Teoretic Sanitize", snapA.student.schoolName)

        val snapB = repo.getStudentSnapshot(StudentId("id_201"))
        assertEquals(StudentId("id_201"), snapB.studentId)
        assertEquals("7B", snapB.student.className)
        assertEquals("Profesor 3", snapB.student.classMasterName)
        assertEquals("Gimnaziul Sanitize Beta", snapB.student.schoolName)
    }

    @Test
    fun `getStudentSnapshot throws StudentMismatchException on ownership mismatch`() = runBlocking {
        val client = createMockClient(
            onFetchSnapshot = { reqId ->
                // Return Student A's snapshot regardless of requested ID to simulate portal bug
                val snap = createMockSnapshot("id_101", "10A")
                if (reqId.value == "id_201") {
                    throw SimeStudentMismatchError(
                        message = "Portal returned child A instead of child B",
                        requestedStudentId = StudentId("id_201"),
                        actualStudentId = StudentId("id_101"),
                    )
                }
                snap
            },
        )
        val repo = LiveAgendaRepository(client = client)

        try {
            repo.getStudentSnapshot(StudentId("id_201"))
            fail("Expected StudentMismatchException")
        } catch (err: StudentMismatchException) {
            assertEquals(StudentId("id_201"), err.requestedStudentId)
            assertEquals(StudentId("id_101"), err.actualStudentId)
        }
    }

    // -------------------------------------------------------------------------
    // 3. TTL Caching & Stale-While-Error Fallback
    // -------------------------------------------------------------------------

    @Test
    fun `in-memory caching respects TTL without unnecessary upstream requests`() = runBlocking {
        val currentTime = AtomicLong(10_000L)
        val upstreamFetchCount = AtomicInteger(0)

        val (repo, _) = createRepositoryFixture(
            nowMillis = currentTime::get,
            onFetch = { upstreamFetchCount.incrementAndGet() },
        )

        // 1. First fetch -> populates cache
        val snap1 = repo.getStudentSnapshot(StudentId("id_101"))
        val initialCalls = upstreamFetchCount.get()
        assertTrue(initialCalls > 0)

        // 2. Second fetch at t+10s (< 60s TTL) -> cache hit, 0 additional calls
        currentTime.addAndGet(10_000L)
        val snap2 = repo.getStudentSnapshot(StudentId("id_101"))
        assertEquals(initialCalls, upstreamFetchCount.get())
        assertEquals(snap1.studentId, snap2.studentId)

        // 3. Third fetch at t+65s (> 60s TTL) -> cache expired, triggers fresh upstream call
        currentTime.addAndGet(55_000L)
        val snap3 = repo.getStudentSnapshot(StudentId("id_101"))
        assertTrue(upstreamFetchCount.get() > initialCalls)
        assertEquals(snap1.studentId, snap3.studentId)
    }

    @Test
    fun `stale cached snapshot is served when circuit breaker is open`() = runBlocking {
        val currentTime = AtomicLong(10_000L)
        val (repo, client) = createRepositoryFixture(
            nowMillis = currentTime::get,
        )

        // Initial fetch into cache
        repo.getStudentSnapshot(StudentId("id_101"))

        // Advance time past 60s TTL so cache is stale
        currentTime.addAndGet(70_000L)

        // Trip the circuit breaker
        client.http.breaker.trip(30)
        assertTrue(client.isBreakerOpen())

        // Should return stale snapshot instead of failing
        val stale = repo.getStudentSnapshot(StudentId("id_101"))
        assertNotNull(stale)
        assertEquals(StudentId("id_101"), stale.studentId)
    }

    @Test
    fun `stale cached snapshot is served on transient network or rate limit failure`() = runBlocking {
        val currentTime = AtomicLong(10_000L)
        var shouldFailUpstream = false

        val (repo, _) = createRepositoryFixture(
            nowMillis = currentTime::get,
            onFetch = {
                if (shouldFailUpstream) {
                    throw SimeNetworkError("Simulated network drop")
                }
            },
        )

        // Initial fetch into cache
        repo.getStudentSnapshot(StudentId("id_101"))

        // Advance time past 60s TTL so cache is stale
        currentTime.addAndGet(70_000L)

        // Cause upstream failure
        shouldFailUpstream = true

        // Should fall back to stale cached snapshot
        val fallback = repo.getStudentSnapshot(StudentId("id_101"))
        assertEquals(StudentId("id_101"), fallback.studentId)
    }

    @Test
    fun `transient error without cached snapshot propagates to caller`() = runBlocking {
        val client = createMockClient(
            onFetchSnapshot = {
                throw SimeRateLimitError("Too Many Requests", 60)
            },
        )
        val repo = LiveAgendaRepository(client = client)

        try {
            repo.getStudentSnapshot(StudentId("id_101"))
            fail("Expected SimeRateLimitError")
        } catch (err: SimeRateLimitError) {
            assertEquals(60, err.waitSeconds)
        }
    }

    @Test
    fun `two children cached snapshots are partitioned and never cross-contaminate`() = runBlocking {
        val (repo, _) = createRepositoryFixture()

        repo.getStudentSnapshot(StudentId("id_101"))
        repo.getStudentSnapshot(StudentId("id_201"))

        val cachedA = repo.cachedSnapshotFor(StudentId("id_101"))
        val cachedB = repo.cachedSnapshotFor(StudentId("id_201"))

        assertNotNull(cachedA)
        assertNotNull(cachedB)

        assertEquals("10A", cachedA!!.student.className)
        assertEquals("7B", cachedB!!.student.className)
    }

    @Test
    fun `session-generation invalidation prevents returning cached snapshots across logout or reauth`() = runBlocking {
        val currentTime = AtomicLong(10_000L)
        val (repo, client) = createRepositoryFixture(nowMillis = currentTime::get)

        // 1. Fetch Child A in current session (session generation 0)
        val snap = repo.getStudentSnapshot(StudentId("id_101"))
        assertEquals("10A", snap.student.className)
        assertNotNull(repo.cachedSnapshotFor(StudentId("id_101")))

        // 2. Logout / clear session -> increments sessionGeneration
        client.session.clear()
        assertEquals(1L, client.session.sessionGeneration)

        // 3. cachedSnapshotFor must immediately return null
        assertNull(repo.cachedSnapshotFor(StudentId("id_101")))

        // 4. Even if stale and upstream fails, it must NOT serve the old session's snapshot
        currentTime.addAndGet(70_000L)
        try {
            repo.getStudentSnapshot(StudentId("id_101"))
            fail("Expected StudentNotFoundException or failure because session was cleared")
        } catch (err: StudentNotFoundException) {
            // Expected because cleared session has no valid student mapping
            assertEquals(StudentId("id_101"), err.requestedStudentId)
        }
    }

    @Test
    fun `no stale fallback after authentication failure, authorization failure, or student mismatch`() = runBlocking {
        val currentTime = AtomicLong(10_000L)

        // Test SimeSessionExpiredError is NOT masked
        var errorToThrow: Throwable = md.sime.opensime.core.portal.SimeSessionExpiredError("Session expired")
        val client = createMockClient(
            onFetchSnapshot = { throw errorToThrow },
        )
        val repo = LiveAgendaRepository(client = client, nowMillis = currentTime::get)

        // First pre-populate cache
        val snap = createMockSnapshot("id_101", "10A")
        client.session.seedStudents(
            listOf(ParsedStudentProfile(id = "id_101", fullName = "Elev 1", className = "10A", schoolName = "Liceul")),
        )
        val cacheField = LiveAgendaRepository::class.java.getDeclaredField("snapshotCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(repo) as MutableMap<StudentId, Any>
        val cachedEntryConstructor = Class.forName("md.sime.opensime.core.data.LiveAgendaRepository\$CachedSnapshot")
            .declaredConstructors[0]
        cachedEntryConstructor.isAccessible = true
        val entry = cachedEntryConstructor.newInstance(snap, 10_000L, client.session.sessionGeneration)
        cache[StudentId("id_101")] = entry

        // Advance time past 60s TTL so cache is stale
        currentTime.addAndGet(70_000L)

        // 1. SimeSessionExpiredError must NOT be masked by stale data
        try {
            repo.getStudentSnapshot(StudentId("id_101"))
            fail("Expected SimeSessionExpiredError")
        } catch (err: md.sime.opensime.core.portal.SimeSessionExpiredError) {
            assertEquals("Session expired", err.message)
        }

        // 2. SimeForbiddenError must NOT be masked by stale data
        errorToThrow = md.sime.opensime.core.portal.SimeForbiddenError("Forbidden 403")
        try {
            repo.getStudentSnapshot(StudentId("id_101"))
            fail("Expected SimeForbiddenError")
        } catch (err: md.sime.opensime.core.portal.SimeForbiddenError) {
            assertEquals("Forbidden 403", err.message)
        }

        // 3. SimeStudentMismatchError must NOT be masked by stale data
        errorToThrow = SimeStudentMismatchError("Mismatch", StudentId("id_101"), StudentId("id_201"))
        try {
            repo.getStudentSnapshot(StudentId("id_101"))
            fail("Expected StudentMismatchException")
        } catch (err: StudentMismatchException) {
            assertEquals(StudentId("id_101"), err.requestedStudentId)
            assertEquals(StudentId("id_201"), err.actualStudentId)
        }
    }

    // -------------------------------------------------------------------------
    // Test Setup Helpers
    // -------------------------------------------------------------------------

    private fun createRepositoryFixture(
        nowMillis: () -> Long = { 10_000L },
        onFetch: () -> Unit = {},
    ): Pair<LiveAgendaRepository, SimePortalClient> {
        val transport = SimeHttpTransport(
            executor = SimeHttpExecutor { req ->
                onFetch()
                when {
                    req.url.contains("wwv_flow.ajax") -> jsonResponse(calendarWeekJson)
                    req.url.contains("P1_ELEVE_ID:ID_501") -> htmlResponse(dashboardStudentAHtml)
                    req.url.contains("P1_ELEVE_ID:ID_601") -> htmlResponse(dashboardStudentBHtml)
                    else -> htmlResponse(dashboardStudentAHtml)
                }
            },
        )
        val session = SimePortalSession(
            http = transport,
            nowMillis = nowMillis,
            todayProvider = { "2026-10-24" },
        )
        session.seedStudents(
            students = listOf(
                ParsedStudentProfile(id = "id_101", fullName = "Elev 1", className = "10A", schoolName = "Liceul Teoretic Sanitize"),
                ParsedStudentProfile(id = "id_201", fullName = "Elev 2", className = "7B", schoolName = "Gimnaziul Sanitize Beta"),
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
            fetchedAtEpochMillis = nowMillis(),
        )
        studentPages["id_201"] = PortalStudentDashboardPage(
            url = "https://agenda.sime.md/ords/r/agenda/main/1?session=999999",
            html = dashboardStudentBHtml,
            studentKey = "id_201",
            fetchedAtEpochMillis = nowMillis(),
        )

        val client = SimePortalClient(
            session = session,
            nowMillis = nowMillis,
            todayProvider = { "2026-10-24" },
        )
        val repo = LiveAgendaRepository(
            client = client,
            nowMillis = nowMillis,
        )
        return repo to client
    }

    private fun createMockClient(
        onFetchSnapshot: suspend (StudentId) -> StudentAgendaSnapshot,
    ): SimePortalClient {
        val session = SimePortalSession()
        session.seedStudents(
            listOf(
                ParsedStudentProfile(id = "id_101", fullName = "Elev 1", className = "10A", schoolName = "Liceul Sanitize"),
                ParsedStudentProfile(id = "id_201", fullName = "Elev 2", className = "7B", schoolName = "Gimnaziul Beta"),
            ),
        )
        val setSessionIdMethod = SimePortalSession::class.java.getDeclaredField("_sessionId")
        setSessionIdMethod.isAccessible = true
        setSessionIdMethod.set(session, "999999")

        val setPortalUrlMethod = SimePortalSession::class.java.getDeclaredField("_portalUrl")
        setPortalUrlMethod.isAccessible = true
        setPortalUrlMethod.set(session, "https://agenda.sime.md/ords/r/agenda/main/1?session=999999")

        return object : SimePortalClient(session = session) {
            override suspend fun fetchStudentSnapshot(
                studentId: StudentId,
                forceRefresh: Boolean,
            ): StudentAgendaSnapshot = onFetchSnapshot(studentId)

            override fun toString(): String = "MockClient"
        }
    }

    private fun createMockSnapshot(studentId: String, className: String): StudentAgendaSnapshot {
        val sId = StudentId(studentId)
        val profile = md.sime.opensime.core.model.StudentProfile(
            id = sId,
            fullName = "Elev $studentId",
            className = className,
            schoolName = "Liceul Sanitize",
            classMasterName = "Profesor",
            currentSemester = 1,
            academicYear = "2026-2027",
        )
        return StudentAgendaSnapshot(
            student = profile,
            dataSourceMode = md.sime.opensime.core.model.DataSourceMode.LIVE_PORTAL,
            referenceIsoDate = "2026-10-24",
            referenceTimeHm = "09:15",
            recentMarks = emptyList(),
            subjectSummaries = emptyList(),
            todayLessons = emptyList(),
            weekLessonsByDay = emptyMap(),
            absenceSummary = md.sime.opensime.core.model.AbsenceSummary(0, 0, 0, 100.0),
            absences = emptyList(),
            homeworkItems = emptyList(),
            teachers = emptyList(),
            weekDayIsoDates = listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23"),
            sem1AbsenceSummary = md.sime.opensime.core.model.AbsenceSummary(0, 0, 0),
            sem2AbsenceSummary = md.sime.opensime.core.model.AbsenceSummary(0, 0, 0),
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
}
