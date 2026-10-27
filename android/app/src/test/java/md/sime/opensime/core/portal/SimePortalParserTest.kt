package md.sime.opensime.core.portal

import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.SubjectScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 unit tests for [SimePortalParser] exercising the sanitized fixture corpus under
 * `src/test/resources/fixtures/portal/` as well as missing, malformed, empty, and edge-case inputs.
 */
class SimePortalParserTest {

    // =========================================================================
    // 1. Public Entry & Login Dialog / Handshake Parsing
    // =========================================================================

    @Test
    fun `findDialogUrl extracts parent and student modal dialog URLs and fails closed on missing link`() {
        val publicHtml = SimePortalFixtures.readFixture(SimePortalFixtures.PUBLIC_ENTRY_HTML)

        val parentUrl = SimePortalParser.findDialogUrl(publicHtml, "1")
        assertTrue(parentUrl.startsWith("https://agenda.sime.md/ords/r/agenda/main/login_desktop?"))
        assertTrue(parentUrl.contains("p101_user_type=1"))
        assertTrue(parentUrl.contains("cs=CHECKSUM_SANITIZED_P1"))

        val studentUrl = SimePortalParser.findDialogUrl(publicHtml, "2")
        assertTrue(studentUrl.startsWith("https://agenda.sime.md/ords/r/agenda/main/login_desktop?"))
        assertTrue(studentUrl.contains("p101_user_type=2"))
        assertTrue(studentUrl.contains("cs=CHECKSUM_SANITIZED_S2"))

        assertThrows(SimeParseError::class.java) {
            SimePortalParser.findDialogUrl("<html><body><a href='/other'>No login</a></body></html>", "1")
        }
    }

    @Test
    fun `parseLoginDialogTokens extracts all required tokens and checksums and fails closed when incomplete`() {
        val validHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_VALID_HTML)
        val tokens = SimePortalParser.parseLoginDialogTokens(validHtml)

        assertEquals("https://agenda.sime.md/ords/wwv_flow.accept", tokens.actionUrl)
        assertEquals("104", tokens.pFlowId)
        assertEquals("101", tokens.pFlowStepId)
        assertEquals("999999", tokens.pInstance)
        assertEquals("111111", tokens.pPageSubmissionId)
        assertEquals("222222", tokens.pSalt)
        assertEquals("PROT_CHECKSUM_SANITIZED", tokens.pPageItemsProtected)
        assertEquals("S", tokens.pReloadOnSubmit)
        assertEquals("ITEM_CHECKSUM_SANITIZED", tokens.checksums["P101_USER_TYPE"])
        assertEquals("ITEM_CHECKSUM_SANITIZED", tokens.checksums["P101_USER_TYPE_NAME"])

        val incompleteHtml = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_DIALOG_INCOMPLETE_HTML)
        assertThrows(SimeParseError::class.java) {
            SimePortalParser.parseLoginDialogTokens(incompleteHtml)
        }

        assertThrows(SimeParseError::class.java) {
            SimePortalParser.parseLoginDialogTokens("<html><body><form></form></body></html>")
        }
    }

    @Test
    fun `parseLoginResponseRedirect handles success, rejected credentials, throttling, untrusted hosts, and malformed JSON`() {
        val successJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_SUCCESS_JSON)
        val redirect = SimePortalParser.parseLoginResponseRedirect(successJson)
        assertEquals("https://agenda.sime.md/ords/r/agenda/main/main-page?session=999999", redirect.portalUrl)
        assertEquals("999999", redirect.sessionId)

        val rejectedJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_REJECTED_JSON)
        val rejectedErr = assertThrows(SimeLoginRejectedError::class.java) {
            SimePortalParser.parseLoginResponseRedirect(rejectedJson)
        }
        assertEquals(SimeErrorCode.PORTAL_LOGIN_REJECTED, rejectedErr.code)
        assertTrue(rejectedErr.message!!.contains("Invalid login credentials", ignoreCase = true))

        val throttledJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_THROTTLED_JSON)
        val throttledErr = assertThrows(SimeRateLimitError::class.java) {
            SimePortalParser.parseLoginResponseRedirect(throttledJson)
        }
        assertEquals(SimeErrorCode.PORTAL_THROTTLED, throttledErr.code)
        assertEquals(120, throttledErr.waitSeconds)

        val untrustedJson = SimePortalFixtures.readFixture(SimePortalFixtures.LOGIN_RESPONSE_UNTRUSTED_REDIRECT_JSON)
        assertThrows(SimeNetworkError::class.java) {
            SimePortalParser.parseLoginResponseRedirect(untrustedJson)
        }

        assertThrows(SimeParseError::class.java) {
            SimePortalParser.parseLoginResponseRedirect("not valid json")
        }
        assertThrows(SimeParseError::class.java) {
            SimePortalParser.parseLoginResponseRedirect("""{"otherField": 123}""")
        }
    }

    // =========================================================================
    // 2. Dashboard Identity, Student Switching Links & Isolation Invariant
    // =========================================================================

    @Test
    fun `dashboard fixtures preserve distinct student identity, metadata, AJAX IDs, and student switch links`() {
        val htmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val htmlB = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_B_HTML)

        val docA = SimePortalParser.parseHtml(htmlA)
        val docB = SimePortalParser.parseHtml(htmlB)

        assertTrue(SimePortalParser.isDashboard(docA))
        assertTrue(SimePortalParser.isDashboard(docB))
        assertFalse(SimePortalParser.isDashboard("<html><body>Public Login Page</body></html>"))

        val flowInfoA = SimePortalParser.extractPageFlowInfo(docA)
        assertEquals("104", flowInfoA.flowId)
        assertEquals("1", flowInfoA.flowStepId)
        assertEquals("ITEM_CHECKSUM_SANITIZED", SimePortalParser.getItemChecksum(docA, "P1_ELEVE_ID"))
        assertEquals("ITEM_CHECKSUM_SANITIZED", SimePortalParser.getItemChecksum(docB, "P1_ELEVE_ID"))

        assertEquals(
            "AJAX_SEM1_SANITIZED_1",
            SimePortalParser.findReportAjaxId(htmlA, "sem1"),
        )
        assertEquals(
            "AJAX_SEM2_SANITIZED_1",
            SimePortalParser.findReportAjaxId(htmlA, "sem2"),
        )
        assertEquals(
            "AJAX_CAL_SANITIZED_1",
            SimePortalParser.findCalendarAjaxId(htmlA),
        )

        val metaA = SimePortalParser.parseStudentMetadata(docA)
        val metaB = SimePortalParser.parseStudentMetadata(docB)

        assertEquals("ID_101", metaA.id)
        assertEquals("Elev 1", metaA.fullName)
        assertEquals("10A", metaA.className)
        assertEquals("Liceul Teoretic Sanitize", metaA.schoolName)
        assertEquals("Profesor 1", metaA.classMaster)
        assertEquals(1, metaA.currentSemester)
        assertEquals("Activ", metaA.enrollmentStatus)

        assertEquals("ID_201", metaB.id)
        assertEquals("Elev 2", metaB.fullName)
        assertEquals("7B", metaB.className)
        assertEquals("Gimnaziul Sanitize Beta", metaB.schoolName)
        assertEquals("Profesor 3", metaB.classMaster)

        val linksA = SimePortalParser.parseStudentLinks(docA)
        assertEquals(2, linksA.size)
        assertEquals("ID_501", linksA[0].studentId)
        assertTrue(linksA[0].isCurrent)
        assertEquals("ID_601", linksA[1].studentId)
        assertFalse(linksA[1].isCurrent)

        val mergedA = SimePortalParser.mergeStudents(SimePortalParser.parseStudentProfile(docA), linksA)
        assertEquals(2, mergedA.students.size)
        assertEquals("ID_101", mergedA.students[0].id)
        assertEquals("Elev 1", mergedA.students[0].fullName)
        assertEquals("10A", mergedA.students[0].className)
        assertEquals("ID_601", mergedA.students[1].id)
        assertEquals("Elev 2", mergedA.students[1].fullName)
        assertNull(mergedA.students[1].className)
        assertTrue(mergedA.linkMap.containsKey("id_101"))
        assertTrue(mergedA.linkMap.containsKey("id_501"))
        assertTrue(mergedA.linkMap.containsKey("id_601"))
    }

    @Test
    fun `toStudentAgendaSnapshot enforces STUDENT_MISMATCH when parsed page belongs to another student`() {
        val htmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val htmlB = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_B_HTML)

        val parsedA = SimePortalParser.parseFullPortal(htmlA, todayIso = "2026-10-27")
        val parsedB = SimePortalParser.parseFullPortal(htmlB, todayIso = "2026-10-27")

        val snapA = SimePortalParser.toStudentAgendaSnapshot(
            portalPage = parsedA,
            referenceIsoDate = "2026-10-27",
            referenceTimeHm = "09:15",
            expectedStudentId = StudentId("ID_101"),
        )
        assertEquals(StudentId("ID_101"), snapA.studentId)
        assertEquals(DataSourceMode.LIVE_PORTAL, snapA.dataSourceMode)
        assertEquals("Elev 1", snapA.student.fullName)
        assertEquals("10A", snapA.student.className)
        assertEquals("Profesor 1", snapA.student.classMasterName)

        // Attempting to map Student B's parsed page while expecting Student A must fail closed with STUDENT_MISMATCH
        val mismatchErr = assertThrows(SimeStudentMismatchError::class.java) {
            SimePortalParser.toStudentAgendaSnapshot(
                portalPage = parsedB,
                referenceIsoDate = "2026-10-27",
                referenceTimeHm = "09:15",
                expectedStudentId = StudentId("ID_101"),
            )
        }
        assertEquals(SimeErrorCode.STUDENT_MISMATCH, mismatchErr.code)
        assertEquals(StudentId("ID_101"), mismatchErr.requestedStudentId)
        assertEquals(StudentId("ID_201"), mismatchErr.actualStudentId)
    }

    // =========================================================================
    // 3. Marks, Qualifiers, Decimal Marks, Theses & Average Honesty Rules
    // =========================================================================

    @Test
    fun `parseSemesterReport and buildCatalog handle numeric, decimal, thesis, qualifier, and descriptor marks`() {
        val reportHtml = SimePortalFixtures.readFixture(SimePortalFixtures.REPORT_SEM1_HTML)
        val reportMap = SimePortalParser.parseSemesterReport(reportHtml)

        assertEquals(3, reportMap.size)

        // 1. Fizica: 8, 10 + Teză 9, official average 9.00
        val physicsRow = reportMap["Fizica"]
        assertNotNull(physicsRow)
        assertEquals(3, physicsRow!!.marks.size)
        assertEquals(9.0, physicsRow.semesterAverage!!, 0.001)
        assertEquals("Profesor 1", physicsRow.teacher)
        assertEquals(8.0, physicsRow.marks[0].value!!, 0.001)
        assertEquals("2026-10-12", physicsRow.marks[0].date)
        assertFalse(physicsRow.marks[0].isThesis)
        assertEquals(10.0, physicsRow.marks[1].value!!, 0.001)
        assertTrue(physicsRow.marks[2].isThesis)
        assertEquals(9.0, physicsRow.marks[2].value!!, 0.001)
        assertEquals("2026-11-15", physicsRow.marks[2].date)

        // 2. Educație muzicală: FB, B — all qualifiers, no numeric average
        val musicRow = reportMap["Educație muzicală"]
        assertNotNull(musicRow)
        assertEquals(2, musicRow!!.marks.size)
        assertTrue(musicRow.marks.all { it.kind == "qualifier" && it.value == null })
        assertEquals(listOf("FB", "B"), musicRow.marks.map { it.label })

        // 3. Educație pentru societate: descriptor "25" -> kind = "other", value = null
        val civicsRow = reportMap["Educație pentru societate"]
        assertNotNull(civicsRow)
        assertEquals(1, civicsRow!!.marks.size)
        assertEquals("other", civicsRow.marks[0].kind)
        assertNull(civicsRow.marks[0].value)
        assertEquals("25", civicsRow.marks[0].label)

        // Also test a subject with decimal marks (9.5, 8) and blank MED_SEM to verify estimated average labeling
        val extraDecimalHtml = """
            <table>
              <tr>
                <td headers="DISCIPLINA">Istoria românilor și universală</td>
                <td headers="LISTA_EVALUATION"><span title="10.10.2026">9.5</span>, <span title="18.10.2026" class="nota-teza">8</span></td>
                <td headers="MED_SEM"></td>
                <td headers="PROFESOR">Profesor 4</td>
              </tr>
            </table>
        """.trimIndent()
        val combinedSem1 = LinkedHashMap(reportMap).apply {
            putAll(SimePortalParser.parseSemesterReport(extraDecimalHtml))
        }

        val dashboardHtmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val portalPage = SimePortalParser.parseFullPortal(
            html = dashboardHtmlA,
            todayIso = "2026-10-27",
            extraReports = listOf(SemesterReportInput(semester = 1, report = combinedSem1)),
        )
        val snapshot = SimePortalParser.toStudentAgendaSnapshot(
            portalPage = portalPage,
            referenceIsoDate = "2026-10-27",
            expectedStudentId = StudentId("ID_101"),
        )

        val summariesBySubject = snapshot.subjectSummaries.associateBy { it.subject }

        val physicsSummary = summariesBySubject.getValue("Fizica")
        val physicsAvg = physicsSummary.resolveAverage()
        assertNotNull(physicsAvg)
        assertEquals(9.0, physicsAvg!!.value, 0.001)
        assertFalse("Portal-provided MED_SEM must not be marked estimated", physicsAvg.isEstimated)
        assertTrue(physicsSummary.marks.any { it.isThesis })

        val historySummary = summariesBySubject.getValue("Istoria românilor și universală")
        val historyAvg = historySummary.resolveAverage()
        assertNotNull(historyAvg)
        assertEquals(8.75, historyAvg!!.value, 0.001)
        assertTrue("Missing MED_SEM must be marked isEstimated = true", historyAvg.isEstimated)
        assertTrue(historySummary.marks.any { (it.value as? MarkValue.Numeric)?.decimalScore == 9.5 })

        val musicSummary = summariesBySubject.getValue("Educație muzicală")
        assertEquals(SubjectScale.QUALIFIER, musicSummary.scale)
        assertNull("Qualifier-only subject must never have a numeric average", musicSummary.resolveAverage())
        assertEquals(
            listOf(QualifierCode.FB, QualifierCode.B),
            musicSummary.marks.map { (it.value as MarkValue.Qualifier).qualifier },
        )

        val civicsSummary = summariesBySubject.getValue("Educație pentru societate")
        assertEquals(SubjectScale.DESCRIPTOR, civicsSummary.scale)
        assertNull("Descriptor subject must never have a numeric average", civicsSummary.resolveAverage())
        assertEquals(MarkValue.Other("25"), civicsSummary.marks.single().value)
    }

    @Test
    fun `normalizeMark handles comma decimals, spelled-out qualifiers, thesis titles, and mixed cells`() {
        val commaDecimal = SimePortalParser.normalizeMark("8,75", "14.10.2026", false, 1)
        assertNotNull(commaDecimal)
        assertEquals("numeric", commaDecimal!!.kind)
        assertEquals(8.75, commaDecimal.value!!, 0.001)
        assertEquals("8.75", commaDecimal.label)
        assertEquals("2026-10-14", commaDecimal.date)

        val spelledFb = SimePortalParser.normalizeMark("Foarte bine", null, false, 1)
        assertNotNull(spelledFb)
        assertEquals("qualifier", spelledFb!!.kind)
        assertEquals("FB", spelledFb.label)
        assertNull(spelledFb.value)

        val spelledInsuf = SimePortalParser.normalizeMark("Insuficient", null, false, 2)
        assertNotNull(spelledInsuf)
        assertEquals("qualifier", spelledInsuf!!.kind)
        assertEquals("I", spelledInsuf.label)

        val thesisFromAttr = SimePortalParser.normalizeMark("9", "Teză semestrială 20.11.2026", false, 1)
        assertNotNull(thesisFromAttr)
        assertTrue(thesisFromAttr!!.isThesis)
        assertEquals("2026-11-20", thesisFromAttr.date)

        assertNull(SimePortalParser.normalizeMark("-"))
        assertNull(SimePortalParser.normalizeMark("—"))
        assertNull(SimePortalParser.normalizeMark("   "))
    }

    @Test
    fun `multi-semester subject without explicit MED_AN marks overall average as estimated when Sem 2 lacks MED_SEM`() {
        val sem1Report = SimePortalParser.parseSemesterReport(
            """
            <table>
              <tr>
                <td headers="DISCIPLINA">Matematica</td>
                <td headers="LISTA_EVALUATION"><span>9</span>, <span>10</span></td>
                <td headers="MED_SEM">9,50</td>
                <td headers="PROFESOR">Profesor 1</td>
              </tr>
            </table>
            """.trimIndent(),
        )
        val sem2Report = SimePortalParser.parseSemesterReport(
            """
            <table>
              <tr>
                <td headers="DISCIPLINA">Matematica</td>
                <td headers="LISTA_EVALUATION"><span>8</span>, <span>7</span></td>
                <td headers="MED_SEM"></td>
                <td headers="PROFESOR">Profesor 1</td>
              </tr>
            </table>
            """.trimIndent(),
        )

        val dashboardHtmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val portalPage = SimePortalParser.parseFullPortal(
            html = dashboardHtmlA,
            todayIso = "2026-10-27",
            extraReports = listOf(
                SemesterReportInput(semester = 1, report = sem1Report),
                SemesterReportInput(semester = 2, report = sem2Report),
            ),
        )
        val snapshot = SimePortalParser.toStudentAgendaSnapshot(
            portalPage = portalPage,
            referenceIsoDate = "2026-10-27",
            expectedStudentId = StudentId("ID_101"),
        )
        val mathSummary = snapshot.subjectSummaries.first { it.subject == "Matematica" }
        val resolvedAvg = mathSummary.resolveAverage()
        assertNotNull(resolvedAvg)
        assertTrue(
            "When Sem 2 has no official MED_SEM and no MED_AN is present, overall subject average must be marked isEstimated = true",
            resolvedAvg!!.isEstimated,
        )

        // Verify MarkValue.Numeric rejects inconsistent integer score vs decimalScore
        assertThrows(IllegalArgumentException::class.java) {
            MarkValue.Numeric(score = 5, decimalScore = 9.5)
        }
    }

    // =========================================================================
    // 4. Absences & Semester Integrity Gate (`buildSemesterAbsences`)
    // =========================================================================

    @Test
    fun `absence gate preserves per-lesson records when count matches portal total and gates to empty on mismatch`() {
        val htmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val parsedA = SimePortalParser.parseFullPortal(htmlA, todayIso = "2026-10-27")

        // Student A fixture has P1_MOTIVATED=1, P1_UNMOTIVATED=1, P1_TOTAL_ABSENCES1=2 and 2 records in P1_JSON_ABSENCES
        assertEquals(2, parsedA.absenceSummary.total)
        assertEquals(95, parsedA.absenceSummary.presencePercent)
        assertEquals(2, parsedA.absences.sem1.total)
        assertEquals(1, parsedA.absences.sem1.motivate)
        assertEquals(1, parsedA.absences.sem1.nemotivate)
        assertEquals(2, parsedA.absences.sem1.records.size)

        val snapA = SimePortalParser.toStudentAgendaSnapshot(parsedA, referenceIsoDate = "2026-10-27")
        val sem1Block = snapA.absencesForSemester(1)
        assertEquals(2, sem1Block.totalCount)
        assertEquals(1, sem1Block.excusedCount)
        assertEquals(1, sem1Block.unexcusedCount)
        assertEquals(2, sem1Block.records.size)

        // Now test when portal summary reports 5 absences (3 excused, 2 unexcused) in Sem 1,
        // but only 2 lesson records were detected: gate must empty records while preserving totals!
        val mismatchedSummary = ParsedAbsenceSummary(
            presencePercent = 94,
            sem1Excused = 3,
            sem1Unexcused = 2,
            sem1Total = 5,
            sem2Excused = 0,
            sem2Unexcused = 0,
            sem2Total = 0,
            totalExcused = 3,
            totalUnexcused = 2,
            total = 5,
        )
        val gatedResult = SimePortalParser.buildSemesterAbsences(
            summary = mismatchedSummary,
            rawRecords = parsedA.absences.sem1.records,
            studentKey = "ID_101",
        )
        assertEquals(5, gatedResult.sem1.total)
        assertEquals(3, gatedResult.sem1.motivate)
        assertEquals(2, gatedResult.sem1.nemotivate)
        assertTrue("Gate must empty detailed records when record count != portal total", gatedResult.sem1.records.isEmpty())

        val gatedSnapshot = SimePortalParser.toStudentAgendaSnapshot(
            portalPage = parsedA.copy(
                absenceSummary = gatedResult.summary,
                absente = gatedResult,
                absences = gatedResult,
            ),
            referenceIsoDate = "2026-10-27",
        )
        val gatedSem1Block = gatedSnapshot.absencesForSemester(1)
        assertEquals(5, gatedSem1Block.totalCount)
        assertEquals(3, gatedSem1Block.excusedCount)
        assertEquals(2, gatedSem1Block.unexcusedCount)
        assertTrue(gatedSem1Block.records.isEmpty())
    }

    @Test
    fun `absence reconciliation and integrity gate across portal summary and 17-record embedded json absences`() {
        val portalHtml = """
            <input type="hidden" id="P1_ELEVE_ID" name="P1_ELEVE_ID" value="ID_101">
            <input type="hidden" id="P1_ELEVE" name="P1_ELEVE" value="Popescu Mihai">
            <input type="hidden" id="P1_CLASS_NAME" name="P1_CLASS_NAME" value="10A">
            <input type="hidden" id="P1_SEMESTER_NUMBER" name="P1_SEMESTER_NUMBER" value="1">
            <input type="hidden" name="P1_PRESENCE_PERCENT" id="P1_PRESENCE_PERCENT" value="93">
            <input type="hidden" name="P1_MOTIVATED" id="P1_MOTIVATED" value="0">
            <input type="hidden" name="P1_MOTIVATED2" id="P1_MOTIVATED2" value="0">
            <input type="hidden" name="P1_UNMOTIVATED" id="P1_UNMOTIVATED" value="17">
            <input type="hidden" name="P1_UNMOTIVATED2" id="P1_UNMOTIVATED2" value="0">
            <input type="hidden" name="P1_TOTAL_ABSENCES1" id="P1_TOTAL_ABSENCES1" value="17">
            <input type="hidden" name="P1_TOTAL_ABSENCES2" id="P1_TOTAL_ABSENCES2" value="0">
            <input type="hidden" name="P1_JSON_ABSENCES" id="P1_JSON_ABSENCES" value="[{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;10.09.2026&quot;,&quot;lessonOrderId&quot;:4,&quot;discipliny&quot;:&quot;Fizica. Astronomia&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:1},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;14.09.2026&quot;,&quot;lessonOrderId&quot;:2,&quot;discipliny&quot;:&quot;Fizica. Astronomia&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:2},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;18.09.2026&quot;,&quot;lessonOrderId&quot;:2,&quot;discipliny&quot;:&quot;Geografie&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:3},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;18.09.2026&quot;,&quot;lessonOrderId&quot;:4,&quot;discipliny&quot;:&quot;Informatica&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:4},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;18.09.2026&quot;,&quot;lessonOrderId&quot;:5,&quot;discipliny&quot;:&quot;Matematică&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:5},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;25.09.2026&quot;,&quot;lessonOrderId&quot;:2,&quot;discipliny&quot;:&quot;Geografie&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:6},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;25.09.2026&quot;,&quot;lessonOrderId&quot;:3,&quot;discipliny&quot;:&quot;Chimie&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:7},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;25.09.2026&quot;,&quot;lessonOrderId&quot;:4,&quot;discipliny&quot;:&quot;Informatica&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:8},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;30.09.2026&quot;,&quot;lessonOrderId&quot;:4,&quot;discipliny&quot;:&quot;Educație pentru societate&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:9},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;30.09.2026&quot;,&quot;lessonOrderId&quot;:5,&quot;discipliny&quot;:&quot;Limba și literatura română&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:10},{&quot;monthYear&quot;:&quot;September 2026&quot;,&quot;lessonDate&quot;:&quot;30.09.2026&quot;,&quot;lessonOrderId&quot;:6,&quot;discipliny&quot;:&quot;Limba și literatura română&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:11},{&quot;monthYear&quot;:&quot;October   2026&quot;,&quot;lessonDate&quot;:&quot;01.10.2026&quot;,&quot;lessonOrderId&quot;:6,&quot;discipliny&quot;:&quot;Limba și literatura română&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:12},{&quot;monthYear&quot;:&quot;October   2026&quot;,&quot;lessonDate&quot;:&quot;02.10.2026&quot;,&quot;lessonOrderId&quot;:2,&quot;discipliny&quot;:&quot;Geografie&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:13},{&quot;monthYear&quot;:&quot;October   2026&quot;,&quot;lessonDate&quot;:&quot;13.10.2026&quot;,&quot;lessonOrderId&quot;:1,&quot;discipliny&quot;:&quot;Biologie&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:14},{&quot;monthYear&quot;:&quot;October   2026&quot;,&quot;lessonDate&quot;:&quot;13.10.2026&quot;,&quot;lessonOrderId&quot;:2,&quot;discipliny&quot;:&quot;Geografie&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:15},{&quot;monthYear&quot;:&quot;October   2026&quot;,&quot;lessonDate&quot;:&quot;13.10.2026&quot;,&quot;lessonOrderId&quot;:3,&quot;discipliny&quot;:&quot;Fizica. Astronomia&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:16},{&quot;monthYear&quot;:&quot;October   2026&quot;,&quot;lessonDate&quot;:&quot;13.10.2026&quot;,&quot;lessonOrderId&quot;:7,&quot;discipliny&quot;:&quot;Fizica. Astronomia&quot;,&quot;absenceName&quot;:&quot;nemotivat&quot;,&quot;cumulativeAbsences&quot;:17}]">
            <div id="t_TreeNav"><ul class="a-TreeView-node"><li class="a-TreeView-node"><a href="f?p=100:1:SESSION::::P1_ELEVE_ID:ID_101"><span>Popescu Mihai</span></a></li></ul></div>
        """.trimIndent()

        val doc = org.jsoup.Jsoup.parse(portalHtml)
        val summary = SimePortalParser.parseAbsenceSummary(doc)
        assertEquals(93, summary.presencePercent)
        assertEquals(0, summary.sem1Excused)
        assertEquals(17, summary.sem1Unexcused)
        assertEquals(17, summary.sem1Total)
        assertEquals(17, summary.total)

        val rawRecords = SimePortalParser.extractEmbeddedAbsenceRecords(doc, todayIso = "2026-10-25", studentKey = "ID_101")
        assertEquals(17, rawRecords.size)

        // Multiple lessons on the same day in the same subject are preserved
        val romLessons = rawRecords.filter { it.date == "2026-09-30" && it.subject.contains("română") }
        assertEquals(2, romLessons.size)
        assertEquals(5, romLessons[0].lessonNumber)
        assertEquals(6, romLessons[1].lessonNumber)

        val fizLessons = rawRecords.filter { it.date == "2026-10-13" && it.subject.contains("Fizica") }
        assertEquals(2, fizLessons.size)
        assertEquals(3, fizLessons[0].lessonNumber)
        assertEquals(7, fizLessons[1].lessonNumber)

        // Full semester build passes integrity gate (17 == 17) and populates sem1.records
        val absencesResult = SimePortalParser.buildSemesterAbsences(summary, rawRecords, studentKey = "ID_101")
        assertEquals(17, absencesResult.sem1.total)
        assertEquals(0, absencesResult.sem1.motivate)
        assertEquals(17, absencesResult.sem1.nemotivate)
        assertEquals(17, absencesResult.sem1.records.size)
        assertEquals(17, absencesResult.records.size)

        // Snapshot domain mapping and per-subject grouping
        val parsedPage = SimePortalParser.parseFullPortal(portalHtml, todayIso = "2026-10-25")
        val snapshot = SimePortalParser.toStudentAgendaSnapshot(parsedPage, referenceIsoDate = "2026-10-25")
        val semBlock = snapshot.absencesForSemester(1)

        assertEquals(17, semBlock.totalCount)
        assertEquals(0, semBlock.excusedCount)
        assertEquals(17, semBlock.unexcusedCount)
        assertEquals(17, semBlock.records.size)

        val bySubject = semBlock.records
            .groupBy { it.subject }
            .entries
            .sortedByDescending { it.value.size }

        // There are 8 distinct subjects with absences
        assertEquals(8, bySubject.size)

        // Subject breakdown
        val subjectCounts = bySubject.associate { it.key to it.value.size }
        assertEquals(4, subjectCounts["Geografie"])
        assertEquals(4, subjectCounts["Fizica. Astronomia"])
        assertEquals(3, subjectCounts["Limba și literatura română"])
        assertEquals(2, subjectCounts["Informatica"])
        assertEquals(1, subjectCounts["Biologie"])
        assertEquals(1, subjectCounts["Educație pentru societate"])
        assertEquals(1, subjectCounts["Chimie"])
        assertEquals(1, subjectCounts["Matematică"])

        // The first 7 visible subjects sum to 16
        val top7Sum = bySubject.take(7).sumOf { it.value.size }
        assertEquals(16, top7Sum)

        // The 8th subject (Matematică) has 1 absence, completing the total of 17
        val eighthSubject = bySubject[7]
        assertEquals("Matematică", eighthSubject.key)
        assertEquals(1, eighthSubject.value.size)
        assertEquals(17, top7Sum + eighthSubject.value.size)
        assertEquals(semBlock.totalCount, bySubject.sumOf { it.value.size })
    }

    // =========================================================================
    // 5. Calendar Events, Naive Chişinău Timestamps & Homework False-Positive Protection
    // =========================================================================

    @Test
    fun `parseCalendarEvents parses week lessons and ignores motivat text inside homework descriptions`() {
        val eventsJson = SimePortalFixtures.readFixture(SimePortalFixtures.CALENDAR_EVENTS_WEEK_JSON)
        val entries = SimePortalParser.parseCalendarEvents(eventsJson)

        assertEquals(3, entries.size)

        // Event 1: 2026-10-24 08:00 Chimia (not an absence)
        assertEquals("Chimia", entries[0].subject)
        assertEquals("101", entries[0].classroom)
        assertEquals("Profesor 1", entries[0].teacher)
        assertFalse(entries[0].isAbsence)

        // Event 2: 2026-10-24 09:00 Fizica with class="absent-nemotivat lesson-text-wrap"
        assertEquals("Fizica", entries[1].subject)
        assertTrue(entries[1].isAbsence)
        assertTrue(entries[1].isUnexcused)
        assertFalse(entries[1].isExcused)

        // Event 3: 2026-10-24 10:00 Biologie with className="motivat"
        assertEquals("Biologie", entries[2].subject)
        assertTrue(entries[2].isAbsence)
        assertTrue(entries[2].isExcused)
        assertFalse(entries[2].isUnexcused)

        val byDay = SimePortalParser.groupByDay(entries)
        assertEquals(3, byDay["2026-10-24"]?.size)
        assertEquals("08:00", byDay["2026-10-24"]!![0].startLocal)
        assertEquals("08:45", byDay["2026-10-24"]!![0].endLocal)
        assertEquals(1, byDay["2026-10-24"]!![0].periodIndex)
        assertEquals(2, byDay["2026-10-24"]!![1].periodIndex)
        assertEquals(3, byDay["2026-10-24"]!![2].periodIndex)

        val calAbsences = SimePortalParser.absenceRecords(entries, studentKey = "ID_101")
        assertEquals(2, calAbsences.size)

        // Verify that "motivarea" or "absent" inside .lesson-homework text does NOT trigger a false-positive absence
        val homeworkWithMotivatJson = """
            [
              {
                "start": "2026-10-26T08:55:00",
                "end": "2026-10-26T09:40:00",
                "title": "<span class=\"lesson-text-wrap\">Limba și literatura română</span><span class=\"lesson-homework\">Eseu: motivarea personajului și tema absenței</span>"
              }
            ]
        """.trimIndent()
        val hwEntries = SimePortalParser.parseCalendarEvents(homeworkWithMotivatJson)
        assertEquals(1, hwEntries.size)
        assertTrue(hwEntries[0].topic!!.contains("motivarea personajului"))
        assertFalse(
            "Homework text containing 'motivarea' or 'absenței' must NOT trigger a false-positive absence",
            hwEntries[0].isAbsence,
        )
    }

    // =========================================================================
    // 6. Homework Grouping & Teacher Deduplication
    // =========================================================================

    @Test
    fun `parseFullPortal extracts recent grades, homework grouped by date headers, and deduplicated teachers`() {
        val htmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val reportHtml = SimePortalFixtures.readFixture(SimePortalFixtures.REPORT_SEM1_HTML)
        val calJson = SimePortalFixtures.readFixture(SimePortalFixtures.CALENDAR_EVENTS_WEEK_JSON)

        val extraReport = SemesterReportInput(
            semester = 1,
            report = SimePortalParser.parseSemesterReport(reportHtml),
        )
        val extraCal = SimePortalParser.parseCalendarEvents(calJson)

        val parsed = SimePortalParser.parseFullPortal(
            html = htmlA,
            todayIso = "2026-10-27",
            extraReports = listOf(extraReport),
            extraCalendar = extraCal,
        )

        // Recent grades from P1_LAST_3_EVAL_LIST (Matematica: 10, Educație fizică: FB)
        assertEquals(2, parsed.grades.size)
        assertEquals("Matematica", parsed.grades[0].subject)
        assertEquals(10.0, parsed.grades[0].value!!, 0.001)
        assertEquals("Educație fizică", parsed.grades[1].subject)
        assertEquals("qualifier", parsed.grades[1].kind)
        assertEquals("FB", parsed.grades[1].label)

        // Homework from P1_HOMEWORK grouped by <b>25.10.2026</b>
        assertEquals(1, parsed.homework.size)
        assertEquals("Matematica", parsed.homework[0].subject)
        assertEquals("Ex. 12 pag. 44", parsed.homework[0].taskDescription)
        assertEquals("2026-10-25", parsed.homework[0].dueDate)

        // Teachers: Diriginte (Profesor 1) sorted first with note = "Diriginte"
        assertTrue(parsed.teachers.isNotEmpty())
        assertEquals("Profesor 1", parsed.teachers.first().name)
        assertEquals("Diriginte", parsed.teachers.first().note)
        assertTrue(parsed.teachers.first().subjects.contains("Fizica"))

        // Verify teacher name similarity / deduplication with initials and prefixes
        assertTrue(SimePortalParser.areTeacherNamesSimilar("Prof. Rusu Ion", "Rusu I."))
        assertTrue(SimePortalParser.areTeacherNamesSimilar("d-na Cazacu Elena", "Elena Cazacu"))
        assertFalse(SimePortalParser.areTeacherNamesSimilar("Rusu Ion", "Rusu Vasile"))
    }

    // =========================================================================
    // 7. Malformed, Empty, Maintenance & Health Diagnostics
    // =========================================================================

    @Test
    fun `malformed or empty HTML and JSON inputs return safe empty structures without crashing`() {
        val emptyPortal = SimePortalParser.parseFullPortal("", todayIso = "2026-10-27")
        assertEquals("", emptyPortal.student.id)
        assertTrue(emptyPortal.grades.isEmpty())
        assertTrue(emptyPortal.homework.isEmpty())
        assertTrue(emptyPortal.catalog.subjects.isEmpty())
        assertEquals(0, emptyPortal.absenceSummary.total)

        val brokenHtmlPortal = SimePortalParser.parseFullPortal(
            "<div><ul><li><span>Unclosed tags<b>28.10",
            todayIso = "2026-10-27",
        )
        assertEquals("", brokenHtmlPortal.student.id)
        assertTrue(brokenHtmlPortal.grades.isEmpty())

        assertTrue(SimePortalParser.parseCalendarEvents("not json").isEmpty())
        assertTrue(SimePortalParser.parseCalendarEvents("").isEmpty())
        assertTrue(SimePortalParser.parseSemesterReport("").isEmpty())
        assertTrue(SimePortalParser.parseRecentGrades("").isEmpty())
        assertTrue(SimePortalParser.parseHomework("").isEmpty())

        assertThrows(SimeParseError::class.java) {
            SimePortalParser.toStudentAgendaSnapshot(emptyPortal, referenceIsoDate = "2026-10-27")
        }
    }

    @Test
    fun `isMaintenancePage detects maintenance notices on non-dashboard HTML only`() {
        val maintenanceHtml = "<html><body><h1>Lucrări tehnice: portalul este în mentenanță temporar indisponibil</h1></body></html>"
        assertTrue(SimePortalParser.isMaintenancePage(maintenanceHtml))

        val dashboardHtmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        assertFalse(SimePortalParser.isMaintenancePage(dashboardHtmlA))
        assertFalse(SimePortalParser.isMaintenancePage(""))
    }

    @Test
    fun `inspectParserHealth reports matched selectors and field names without leaking personal data`() {
        val dashboardHtmlA = SimePortalFixtures.readFixture(SimePortalFixtures.DASHBOARD_STUDENT_A_HTML)
        val health = SimePortalParser.inspectParserHealth(dashboardHtmlA)

        assertEquals(2, health.students.count)
        assertTrue(health.students.selectorsMatched.contains("#P1_ELEVE_ID"))
        assertTrue(health.students.selectorsMatched.contains("#t_TreeNav"))
        assertEquals(listOf("id", "fullName", "className", "schoolName"), health.students.fieldsFound)

        assertEquals(2, health.grades.count)
        assertEquals(1, health.catalog.count)
        assertEquals(2, health.absencesSummary.count)
        assertEquals(0, health.calendar.count)
        assertTrue(health.teachers.count >= 1)

        val serializedHealth = health.toString()
        assertFalse("Parser health must never include student names", serializedHealth.contains("Elev 1"))
        assertFalse("Parser health must never include teacher names", serializedHealth.contains("Profesor 1"))
        assertFalse("Parser health must never include school names", serializedHealth.contains("Liceul Teoretic Sanitize"))
    }

    @Test
    fun `buildTeachers synthesizes class master, catalog teachers, and calendar entries without duplicates`() {
        val metadata = ParsedStudentMetadata(
            id = "s_101",
            fullName = "Elev Exemplar",
            className = "10A",
            schoolName = "Liceul Sanitize",
            classMaster = "Popescu Ion",
            currentSemester = 1,
            studyYear = "2024-2025",
        )
        val catalogSubjects = listOf(
            ParsedCatalogSubject(
                subject = "Matematica",
                discipline = "Matematica",
                teacher = "Popescu I.",
                scale = "1-10",
                semesters = emptyList(),
                markDetails = emptyList(),
                markCount = 0,
                sem1Average = null,
                sem2Average = null,
                annualAverage = null,
                average = null,
            ),
            ParsedCatalogSubject(
                subject = "Fizica",
                discipline = "Fizica",
                teacher = "Ionescu Maria",
                scale = "1-10",
                semesters = emptyList(),
                markDetails = emptyList(),
                markCount = 0,
                sem1Average = null,
                sem2Average = null,
                annualAverage = null,
                average = null,
            ),
        )
        val calendarEntries = listOf(
            ParsedCalendarEntry(
                start = 1729900800000L,
                end = 1729903500000L,
                subject = "Fizica",
                classroom = "101",
                teacher = "Ionescu Maria",
                topic = null,
                isAbsence = false,
                isExcused = false,
                isUnexcused = false,
                semester = 1,
                note = null,
                lessonNumber = 1,
            ),
            ParsedCalendarEntry(
                start = 1729904400000L,
                end = 1729907100000L,
                subject = "Informatica",
                classroom = "202",
                teacher = "Vasilescu Dan",
                topic = null,
                isAbsence = false,
                isExcused = false,
                isUnexcused = false,
                semester = 1,
                note = null,
                lessonNumber = 2,
            ),
        )

        val teachers = SimePortalParser.buildTeachers(
            entries = calendarEntries,
            metadata = metadata,
            catalogSubjects = catalogSubjects,
        )

        // 3 unique teachers: Popescu Ion (Diriginte), Ionescu Maria, Vasilescu Dan
        assertEquals(3, teachers.size)

        val diriginte = teachers[0]
        assertEquals("Popescu Ion", diriginte.name)
        assertEquals("Diriginte", diriginte.note)
        assertTrue(diriginte.subjects.contains("Matematica"))

        val fizicaTeacher = teachers.first { it.name == "Ionescu Maria" }
        assertTrue(fizicaTeacher.subjects.contains("Fizica"))
        assertEquals(1, fizicaTeacher.lessons)

        val infoTeacher = teachers.first { it.name == "Vasilescu Dan" }
        assertTrue(infoTeacher.subjects.contains("Informatica"))
        assertEquals(1, infoTeacher.lessons)
    }
}
