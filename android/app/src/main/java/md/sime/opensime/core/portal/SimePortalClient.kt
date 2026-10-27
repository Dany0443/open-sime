package md.sime.opensime.core.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId

/**
 * High-level portal client for reading student-scoped agenda data from `agenda.sime.md`.
 *
 * Implements the portal client capabilities defined in `web/server.js:2073-2535`:
 * 1. **Per-session operation serialization:** Uses [requestMutex] (delegated to [SimePortalSession.sessionLock])
 *    so concurrent UI requests never interleave on the shared Oracle APEX session.
 * 2. **Student-scoped APEX AJAX:** Formulates valid `wwv_flow.ajax` POST payloads using that student's
 *    own `P1_ELEVE_ID`, `data-for` checksum, `pSalt`, and `pPageItemsProtected`.
 * 3. **Semester report enrichment:** Discovers missing semester regions and fetches `AJAX_SEM*` report data.
 * 4. **Calendar chunking:** Fetches timetable calendar ranges in 21-day chunks (`CALENDAR_CHUNK_DAYS = 21`).
 * 5. **Strict student isolation:** Re-asserts the active student before AJAX calls (`assertCurrent = true`)
 *    and verifies page ownership before mapping snapshots.
 * 6. **Single-flight relogin:** Supports [withRetry] on [SimeSessionExpiredError] protected by [reloginMutex].
 */
open class SimePortalClient(
    val session: SimePortalSession = SimePortalSession(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val todayProvider: () -> String = ChisinauTime::todayChisinau,
    private val reloginAction: (suspend () -> Unit)? = null,
) {
    val requestMutex: Mutex
        get() = session.sessionLock

    private val reloginMutex = Mutex()

    val http: SimeHttpTransport
        get() = session.http

    val sessionId: String?
        get() = session.sessionId

    val isAuthenticated: Boolean
        get() = session.isAuthenticated

    val role: AccountRole?
        get() = session.role

    fun listStudents(): List<ParsedStudentProfile> = session.listStudents()

    fun isBreakerOpen(): Boolean =
        http.breaker.remainingSeconds() > 0 || http.globalBreaker.remainingSeconds() > 0

    /**
     * Executes [operation] with single-flight re-login on [SimeSessionExpiredError].
     * Mirrors `SimeClient.withRetry` in `web/server.js:2264-2272`.
     */
    suspend fun <T> withRetry(operation: suspend () -> T): T {
        try {
            return operation()
        } catch (err: SimeSessionExpiredError) {
            val relogin = reloginAction ?: throw err
            reloginMutex.withLock {
                relogin()
            }
            return operation()
        }
    }

    /**
     * Executes a student-scoped APEX AJAX plugin request (`web/server.js:2388-2427`).
     *
     * Validates that [page] contains:
     * - `P1_ELEVE_ID`
     * - `data-for="P1_ELEVE_ID"` checksum
     * - `pSalt`
     * - `pPageItemsProtected`
     * - active session ID
     * Throws [SimeParseError] if any required token is missing.
     */
    suspend fun ajax(
        page: PortalStudentDashboardPage,
        ajaxId: String,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val cleanAjaxId = ajaxId.trim()
        if (cleanAjaxId.isEmpty()) {
            throw SimeParseError("AJAX plugin identifier cannot be blank")
        }

        val doc = SimeDomHelpers.parseHtml(page.html)
        val student = SimeDomHelpers.getInputValue(doc, SimeUrlValidator.ITEM_STUDENT_ID)
        val checksum = SimeDomHelpers.getItemChecksum(doc, SimeUrlValidator.ITEM_STUDENT_ID)
        val salt = SimeDomHelpers.getInputValue(doc, "pSalt")
        val protectedItems = SimeDomHelpers.getInputValue(doc, "pPageItemsProtected")
        val sid = session.sessionId

        if (student.isEmpty() || checksum.isNullOrEmpty() || salt.isEmpty() || sid.isNullOrEmpty()) {
            throw SimeParseError("Dashboard page lacks the items required for APEX requests")
        }

        val flowInfo = SimeLoginParser.extractPageFlowInfo(doc)
        val payload = buildAjaxPayloadJson(
            salt = salt,
            protectedItems = protectedItems,
            studentId = student,
            studentChecksum = checksum,
        )

        val requestData = linkedMapOf(
            "p_flow_id" to flowInfo.flowId,
            "p_flow_step_id" to flowInfo.flowStepId,
            "p_instance" to sid,
            "p_request" to "PLUGIN=$cleanAjaxId",
            "p_json" to payload,
        )
        requestData.putAll(extra)

        val headers = linkedMapOf(
            "Referer" to page.url,
            "Origin" to SimeUrlValidator.BASE_URL,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "text/html, */*; q=0.01",
        )

        val resp = http.request(
            method = "POST",
            url = SimeUrlValidator.ajaxUrl(sid),
            data = requestData,
            headers = headers,
            kind = SimeRequestKind.XHR,
            gated = false,
        )
        return resp.text
    }

    /**
     * Enriches a student's dashboard page with missing semester reports fetched via APEX AJAX,
     * mirrors `SimeClient.enrichPortalPage` (`web/server.js:2128-2163`).
     */
    suspend fun enrichPortalPage(
        page: PortalStudentDashboardPage,
        requestedStudentId: String? = null,
    ): ParsedPortalPage = requestMutex.withLock {
        enrichPortalPageInternal(page, requestedStudentId)
    }

    private suspend fun enrichPortalPageInternal(
        page: PortalStudentDashboardPage,
        requestedStudentId: String?,
        extraCalendar: List<ParsedCalendarEntry> = emptyList(),
    ): ParsedPortalPage {
        if (!requestedStudentId.isNullOrBlank()) {
            val canonRequested = session.canonicalStudentKey(requestedStudentId)
                .ifEmpty { SimeStudentParser.studentKey(requestedStudentId) }
            if (canonRequested.isNotEmpty() && page.studentKey.isNotEmpty() && canonRequested != page.studentKey) {
                throw SimeStudentMismatchError(
                    message = "Portal page belongs to another student than $requestedStudentId",
                    requestedStudentId = StudentId(canonRequested),
                    actualStudentId = StudentId(page.studentKey),
                )
            }
        }

        val extraReports = mutableListOf<SemesterReportInput>()
        val doc = SimeDomHelpers.parseHtml(page.html)

        for (region in SimePortalParser.REGIONS) {
            val inlineScope = SimePortalParser.findSemesterScope(doc, region)
            if (inlineScope != null && SimePortalParser.parseSemesterReportFromRoot(inlineScope).isNotEmpty()) {
                continue
            }
            val ajaxId = SimePortalParser.findReportAjaxId(page.html, region.id) ?: continue
            try {
                val repHtml = ajax(page, ajaxId)
                val rep = SimePortalParser.parseSemesterReport(repHtml)
                if (rep.isNotEmpty()) {
                    extraReports.add(SemesterReportInput(semester = region.semester, report = rep))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: SimeSessionExpiredError) {
                throw err
            } catch (err: SimeStudentMismatchError) {
                throw err
            } catch (_: Exception) {
                // fallback to inline report data
            }
        }

        val data = SimePortalParser.parseFullPortal(
            html = page.html,
            todayIso = todayProvider(),
            extraReports = extraReports,
            extraCalendar = extraCalendar,
        )

        val pageKey = page.studentKey.ifEmpty { SimeStudentParser.studentKey(data.student.id) }
        if (!requestedStudentId.isNullOrBlank()) {
            val canonRequested = session.canonicalStudentKey(requestedStudentId)
                .ifEmpty { SimeStudentParser.studentKey(requestedStudentId) }
            if (canonRequested.isNotEmpty() && canonRequested != pageKey) {
                throw SimeStudentMismatchError(
                    message = "Portal page belongs to another student than $requestedStudentId",
                    requestedStudentId = StudentId(canonRequested),
                    actualStudentId = pageKey.takeIf { it.isNotEmpty() }?.let(::StudentId),
                )
            }
        }

        val own = if (pageKey.isNotEmpty()) session.listStudents().firstOrNull { it.id == pageKey } else null
        val canonicalProfile = if (pageKey.isNotEmpty()) {
            data.metadata.copy(
                id = pageKey,
                fullName = data.metadata.fullName.ifEmpty { own?.fullName ?: pageKey },
                className = data.metadata.className ?: own?.className,
                schoolName = data.metadata.schoolName ?: own?.schoolName,
            )
        } else {
            data.metadata
        }

        return data.copy(
            metadata = canonicalProfile,
            student = ParsedStudentProfile(
                id = canonicalProfile.id,
                fullName = canonicalProfile.fullName,
                className = canonicalProfile.className,
                schoolName = canonicalProfile.schoolName,
            ),
            students = session.listStudents(),
        )
    }

    /**
     * Fetches timetable calendar events between [startIso] and [endIso] in 21-day chunks.
     * Mirrors `SimeClient.calendarRange` (`web/server.js:2429-2451`).
     */
    suspend fun calendarRange(
        studentId: String,
        startIso: String,
        endIso: String,
    ): List<ParsedCalendarEntry> = requestMutex.withLock {
        calendarRangeInternal(studentId, startIso, endIso)
    }

    private suspend fun calendarRangeInternal(
        studentId: String,
        startIso: String,
        endIso: String,
    ): List<ParsedCalendarEntry> {
        val page = session.loadStudentPage(studentId, assertCurrent = true)
        val ajaxId = SimePortalParser.findCalendarAjaxId(page.html)
            ?: throw SimeParseError("Calendar widget not found on the dashboard")
        val entries = mutableListOf<ParsedCalendarEntry>()
        var from = startIso
        while (from <= endIso) {
            val chunkEnd = ChisinauTime.addDays(from, CALENDAR_CHUNK_DAYS - 1)
            val to = if (chunkEnd < endIso) chunkEnd else endIso
            val body = ajax(
                page = page,
                ajaxId = ajaxId,
                extra = mapOf(
                    "x01" to "GET",
                    "x02" to "${from.replace("-", "")}000000",
                    "x03" to "${to.replace("-", "")}235959",
                ),
            )
            val parsedJson = try {
                SimpleJson.parse(body)
            } catch (_: Exception) {
                throw SimeParseError("Calendar response is not valid JSON")
            }
            if (parsedJson !is List<*>) {
                throw SimeParseError("Calendar response has an unexpected shape")
            }
            entries.addAll(SimeCalendarAndTeachersParser.parseCalendarEvents(body))
            from = ChisinauTime.addDays(from, CALENDAR_CHUNK_DAYS)
        }
        return entries
    }

    /**
     * Lessons for the Monday-Sunday week containing [isoDate], grouped by day.
     * Mirrors `SimeClient.getWeek` (`web/server.js:2493-2499`).
     */
    suspend fun getWeek(
        studentId: String,
        isoDate: String = todayProvider(),
    ): ParsedSchedule = requestMutex.withLock {
        getWeekInternal(studentId, isoDate)
    }

    private suspend fun getWeekInternal(
        studentId: String,
        isoDate: String,
    ): ParsedSchedule {
        val start = ChisinauTime.weekStart(isoDate)
        val entries = calendarRangeInternal(studentId, start, ChisinauTime.addDays(start, 6))
        val days = SimeCalendarAndTeachersParser.groupByDay(entries)
        return ParsedSchedule(weekStart = start, days = days)
    }

    /**
     * Enriched catalog grades and averages for [studentId].
     * Mirrors `SimeClient.getCatalog` (`web/server.js:2476-2490`).
     */
    suspend fun getCatalog(studentId: String): ParsedCatalog = requestMutex.withLock {
        getCatalogInternal(studentId)
    }

    private suspend fun getCatalogInternal(studentId: String): ParsedCatalog {
        val page = session.loadStudentPage(studentId, assertCurrent = true)
        val data = enrichPortalPageInternal(page, studentId)
        if (data.catalog.subjects.isNotEmpty()) {
            return data.catalog
        }
        val reports = mutableListOf<SemesterReportInput>()
        for (region in SimePortalParser.REGIONS) {
            val ajaxId = SimePortalParser.findReportAjaxId(page.html, region.id) ?: continue
            val repHtml = ajax(page, ajaxId)
            val rep = SimePortalParser.parseSemesterReport(repHtml)
            if (rep.isNotEmpty()) {
                reports.add(SemesterReportInput(semester = region.semester, report = rep))
            }
        }
        if (reports.isEmpty()) {
            throw SimeParseError("Semester report regions not found on the dashboard")
        }
        return SimeGradesParser.buildCatalog(reports)
    }

    /**
     * Absence records for [studentId], integrating embedded and calendar absence markers.
     * Mirrors `SimeClient.getAbsenceRecords` (`web/server.js:2502-2516`).
     */
    suspend fun getAbsenceRecords(studentId: String): List<ParsedAbsenceRecord> = requestMutex.withLock {
        getAbsenceRecordsInternal(studentId)
    }

    private suspend fun getAbsenceRecordsInternal(studentId: String): List<ParsedAbsenceRecord> {
        val today = todayProvider()
        val page = session.loadStudentPage(studentId)
        val doc = SimeDomHelpers.parseHtml(page.html)
        val embeddedRecords = SimeAbsencesParser.extractEmbeddedAbsenceRecords(doc, today, studentId)
        if (embeddedRecords.isNotEmpty()) {
            return embeddedRecords
        }
        val year = today.take(4).toIntOrNull() ?: 2026
        val month = today.substring(5, 7).toIntOrNull() ?: 9
        val startYear = if (month >= 9) year else year - 1
        val calEntries = calendarRangeInternal(studentId, "$startYear-09-01", today)
        val htmlRecords = SimeAbsencesParser.extractHtmlAbsenceRecords(doc, today, studentId)
        return SimeAbsencesParser.absenceRecords(calEntries, studentId) + htmlRecords
    }

    /**
     * Teachers list for [studentId], derived from calendar lessons, metadata, and catalog.
     * Mirrors `SimeClient.getTeachers` (`web/server.js:2519-2534`).
     */
    suspend fun getTeachers(studentId: String): List<ParsedTeacher> = requestMutex.withLock {
        getTeachersInternal(studentId)
    }

    private suspend fun getTeachersInternal(studentId: String): List<ParsedTeacher> {
        val page = session.loadStudentPage(studentId)
        val doc = SimeDomHelpers.parseHtml(page.html)
        val meta = SimeStudentParser.parseStudentMetadata(doc)
        val today = todayProvider()
        val entries = calendarRangeInternal(studentId, ChisinauTime.addDays(today, -7), ChisinauTime.addDays(today, 14))
        var catalogSubjects: List<ParsedCatalogSubject> = emptyList()
        try {
            val catalog = getCatalogInternal(studentId)
            catalogSubjects = catalog.subjects
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: SimeSessionExpiredError) {
            throw err
        } catch (err: SimeStudentMismatchError) {
            throw err
        } catch (_: Exception) {
            // Degrade gracefully if catalog cannot be loaded
        }
        return SimeCalendarAndTeachersParser.buildTeachers(entries, meta, catalogSubjects)
    }

    /**
     * Fetches, enriches, and maps a verified [StudentAgendaSnapshot] for [studentId].
     * Serialized through [requestMutex] to guarantee single-flight APEX state coordination.
     */
    open suspend fun fetchStudentSnapshot(
        studentId: StudentId,
        forceRefresh: Boolean = false,
    ): StudentAgendaSnapshot = requestMutex.withLock {
        fetchStudentSnapshotInternal(studentId, forceRefresh)
    }

    private suspend fun fetchStudentSnapshotInternal(
        studentId: StudentId,
        forceRefresh: Boolean,
    ): StudentAgendaSnapshot {
        currentCoroutineContext().ensureActive()
        val rawInput = studentId.value.trim()
        if (rawInput.isEmpty()) {
            throw SimeStudentNotFoundError("Student ID is required", studentId = null)
        }
        val canonicalKey = session.canonicalStudentKey(rawInput)
        if (canonicalKey.isEmpty()) {
            throw SimeStudentNotFoundError("No such student", studentId = studentId)
        }
        val targetStudentId = StudentId(canonicalKey)

        val page = session.loadStudentPage(
            rawStudentId = canonicalKey,
            bypassCache = forceRefresh,
            assertCurrent = true,
        )
        currentCoroutineContext().ensureActive()

        val doc = SimeDomHelpers.parseHtml(page.html)
        var extraCalendar: List<ParsedCalendarEntry> = emptyList()
        val embeddedCal = SimeCalendarAndTeachersParser.extractEmbeddedCalendarEntries(doc)
        if (embeddedCal.isEmpty() && SimeCalendarAndTeachersParser.findCalendarAjaxId(page.html) != null) {
            try {
                val today = todayProvider()
                val weekStart = ChisinauTime.weekStart(today)
                extraCalendar = calendarRangeInternal(
                    studentId = canonicalKey,
                    startIso = ChisinauTime.addDays(weekStart, -7),
                    endIso = ChisinauTime.addDays(weekStart, 13),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: SimeSessionExpiredError) {
                throw err
            } catch (err: SimeStudentMismatchError) {
                throw err
            } catch (_: Exception) {
                // Graceful fallback
            }
        }

        val enrichedPage = enrichPortalPageInternal(page, canonicalKey, extraCalendar)
        val allCalendar = if (embeddedCal.isNotEmpty()) embeddedCal else extraCalendar
        val finalTeachers = if (enrichedPage.teachers.isNotEmpty()) {
            enrichedPage.teachers
        } else {
            SimeCalendarAndTeachersParser.buildTeachers(
                entries = allCalendar,
                metadata = enrichedPage.metadata,
                catalogSubjects = enrichedPage.catalog.subjects,
            )
        }

        val finalPage = if (extraCalendar.isNotEmpty() && enrichedPage.schedule.days.isEmpty()) {
            val days = SimeCalendarAndTeachersParser.groupByDay(extraCalendar)
            val sched = ParsedSchedule(weekStart = ChisinauTime.weekStart(todayProvider()), days = days)
            enrichedPage.copy(
                schedule = sched,
                orar = sched,
                timetable = days[todayProvider()].orEmpty(),
                teachers = finalTeachers,
            )
        } else {
            enrichedPage.copy(teachers = finalTeachers)
        }

        val snapshot = SimePortalParser.toStudentAgendaSnapshot(
            portalPage = finalPage,
            referenceIsoDate = todayProvider(),
            expectedStudentId = targetStudentId,
        )
        return snapshot.verifyStudentOwnership(targetStudentId)
    }

    override fun toString(): String =
        "SimePortalClient(isAuthenticated=$isAuthenticated, role=$role)"

    companion object {
        const val CALENDAR_CHUNK_DAYS: Int = 21

        internal fun buildAjaxPayloadJson(
            salt: String,
            protectedItems: String,
            studentId: String,
            studentChecksum: String,
        ): String = buildString {
            append("""{"salt":""")
            append(SimpleJson.quoteString(salt))
            append(""","pageItems":{"itemsToSubmit":[{"n":"P1_ELEVE_ID","v":""")
            append(SimpleJson.quoteString(studentId))
            append(""","ck":""")
            append(SimpleJson.quoteString(studentChecksum))
            append("""}],"protected":""")
            append(SimpleJson.quoteString(protectedItems))
            append(""","rowVersion":"","formRegionChecksums":[]}}""")
        }
    }
}
