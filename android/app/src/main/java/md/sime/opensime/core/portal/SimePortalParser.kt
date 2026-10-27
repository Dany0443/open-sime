package md.sime.opensime.core.portal

import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Unified entry point for `agenda.sime.md` Oracle APEX HTML/JSON parsing.
 *
 * Delegates to focused parser modules in `md.sime.opensime.core.portal`:
 * - [SimeLoginParser]: public entry dialog URLs, login form tokens, redirect validation, dashboard detection
 * - [SimeStudentParser]: student identity, profile, metadata, `#t_TreeNav` links, and `mergeStudents`
 * - [SimeGradesParser]: marks, qualifiers, theses, recent evaluations, reports, `buildCatalog`, and homework
 * - [SimeAbsencesParser]: absence summary, embedded/HTML/calendar records, and semester count gate
 * - [SimeCalendarAndTeachersParser]: FullCalendar events, timetable grouping, and teacher deduplication
 * - [SimePortalSnapshotMapper]: domain `StudentAgendaSnapshot` mapping and `inspectParserHealth`
 */
object SimePortalParser {

    const val FLOW_ID: String = SimeUrlValidator.FLOW_ID
    const val FLOW_STEP_ID: String = SimeUrlValidator.FLOW_STEP_ID
    const val ITEM_STUDENT_ID: String = SimeUrlValidator.ITEM_STUDENT_ID
    const val ITEM_LAST_EVALS: String = SimeUrlValidator.ITEM_LAST_EVALS
    const val ITEM_HOMEWORK: String = SimeUrlValidator.ITEM_HOMEWORK

    val REGIONS: List<SemesterRegionSpec> = listOf(
        SemesterRegionSpec(
            semester = 1,
            id = "sem1",
            selectors = listOf("#sem1", "#SEMESTER_REG_1", "#R_SEM1", "[id='sem1' i]", "[id*='semester_reg_1' i]", "[id*='sem_1' i]"), /* UNVERIFIED */
        ),
        SemesterRegionSpec(
            semester = 2,
            id = "sem2",
            selectors = listOf("#sem2", "#SEMESTER_REG_2", "#R_SEM2", "[id='sem2' i]", "[id*='semester_reg_2' i]", "[id*='sem_2' i]"), /* UNVERIFIED */
        ),
    )

    val QUALIFIERS: Map<String, QualifierCode> get() = SimeGradesParser.QUALIFIERS
    val SUBJECT_SCALE_REGISTRY: List<SubjectScaleEntry> get() = SimeGradesParser.SUBJECT_SCALE_REGISTRY

    // DOM & Text Helpers
    fun parseHtml(html: String?): Document = SimeDomHelpers.parseHtml(html)
    fun textOf(element: Element?, sep: String = ""): String = SimeDomHelpers.textOf(element, sep)
    fun classTokens(element: Element?): List<String> = SimeDomHelpers.classTokens(element)
    fun classContains(scope: Element?, vararg needles: String): Element? = SimeDomHelpers.classContains(scope, *needles)
    fun getInputValue(root: Element, id: String, default: String = ""): String = SimeDomHelpers.getInputValue(root, id, default)
    fun getItemChecksum(root: Element, item: String): String? = SimeDomHelpers.getItemChecksum(root, item)
    fun getItemText(root: Element, id: String, default: String = ""): String = SimeDomHelpers.getItemText(root, id, default)
    fun headerHas(td: Element, needles: List<String>): Boolean = SimeDomHelpers.headerHas(td, needles)

    // Login & Flow Discovery
    fun isDashboard(doc: Element): Boolean = SimeLoginParser.isDashboard(doc)
    fun isDashboard(html: String?): Boolean = SimeLoginParser.isDashboard(html)
    fun isMaintenancePage(html: String?): Boolean = SimeLoginParser.isMaintenancePage(html)
    fun findDialogUrl(publicHtml: String?, userType: String): String = SimeLoginParser.findDialogUrl(publicHtml, userType)
    fun parseLoginDialogTokens(dialogHtml: String?): PortalLoginDialogTokens = SimeLoginParser.parseLoginDialogTokens(dialogHtml)
    fun parseLoginResponseRedirect(responseBody: String?): PortalLoginRedirect = SimeLoginParser.parseLoginResponseRedirect(responseBody)
    fun extractPageFlowInfo(html: String?): PortalPageFlowInfo = SimeLoginParser.extractPageFlowInfo(html)
    fun extractPageFlowInfo(doc: Element): PortalPageFlowInfo = SimeLoginParser.extractPageFlowInfo(doc)

    // Student Identity, Profile & Links
    fun studentKey(id: String?): String = SimeStudentParser.studentKey(id)
    fun normalizeName(name: String?): String = SimeStudentParser.normalizeName(name)
    fun parseStudentProfile(doc: Element): ParsedStudentProfile = SimeStudentParser.parseStudentProfile(doc)
    fun parseCurrentSemester(doc: Element): Int? = SimeStudentParser.parseCurrentSemester(doc)
    fun parseStudentMetadata(doc: Element): ParsedStudentMetadata = SimeStudentParser.parseStudentMetadata(doc)
    fun parseStudentLinks(doc: Element): List<ParsedStudentLink> = SimeStudentParser.parseStudentLinks(doc)
    fun mergeStudents(baseStudent: ParsedStudentProfile?, links: List<ParsedStudentLink> = emptyList()): MergedStudentsResult =
        SimeStudentParser.mergeStudents(baseStudent, links)

    // Marks, Qualifiers, Theses, Catalog & Homework
    fun normalizeQualifierKey(str: String?): String = SimeGradesParser.normalizeQualifierKey(str)
    fun splitMarkTokens(rawText: String?): List<String> = SimeGradesParser.splitMarkTokens(rawText)
    fun normalizeSubjectName(raw: String?): String = SimeGradesParser.normalizeSubjectName(raw)
    fun getSubjectScale(subjectName: String?): String? = SimeGradesParser.getSubjectScale(subjectName)
    fun normalizeMark(
        rawText: String?,
        rawAttr: String? = null,
        isThesisExplicit: Boolean = false,
        semester: Int? = null,
    ): ParsedMark? = SimeGradesParser.normalizeMark(rawText, rawAttr, isThesisExplicit, semester)

    fun parseMarkCell(cell: Element?, semester: Int? = null): List<ParsedMark> =
        SimeGradesParser.parseMarkCell(cell, semester)

    fun parseRecentGradesFromRoot(
        root: Element,
        defaultSemester: Int? = null,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedMark> = SimeGradesParser.parseRecentGradesFromRoot(root, defaultSemester, todayIso)

    fun parseRecentGrades(
        htmlSnippet: String?,
        defaultSemester: Int? = null,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedMark> = SimeGradesParser.parseRecentGrades(htmlSnippet, defaultSemester, todayIso)

    fun parseSemesterReportFromRoot(root: Element): Map<String, ParsedSubjectReportRow> =
        SimeGradesParser.parseSemesterReportFromRoot(root)

    fun parseSemesterReport(reportHtml: String?): Map<String, ParsedSubjectReportRow> =
        SimeGradesParser.parseSemesterReport(reportHtml)

    fun parseCombinedReportFromRoot(
        root: Element,
        annualMap: MutableMap<String, Double> = linkedMapOf(),
    ): CombinedReportResult = SimeGradesParser.parseCombinedReportFromRoot(root, annualMap)

    fun buildCatalog(
        reports: List<SemesterReportInput>,
        annualMap: Map<String, Double> = emptyMap(),
    ): ParsedCatalog = SimeGradesParser.buildCatalog(reports, annualMap)

    fun findReportAjaxId(pageHtml: String?, regionStaticId: String): String? =
        SimeGradesParser.findReportAjaxId(pageHtml, regionStaticId)

    fun parseHomeworkFromRoot(
        root: Element,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedHomeworkTask> = SimeGradesParser.parseHomeworkFromRoot(root, todayIso)

    fun parseHomework(
        htmlSnippet: String?,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedHomeworkTask> = SimeGradesParser.parseHomework(htmlSnippet, todayIso)

    // Absences
    fun parseAbsenceSummary(doc: Element): ParsedAbsenceSummary = SimeAbsencesParser.parseAbsenceSummary(doc)
    fun inferAbsenceSemester(isoDay: String?, explicitSemester: Int? = null): Int =
        SimeAbsencesParser.inferAbsenceSemester(isoDay, explicitSemester)

    fun absenceRecords(
        entries: List<ParsedCalendarEntry>,
        studentKey: String? = null,
    ): List<ParsedAbsenceRecord> = SimeAbsencesParser.absenceRecords(entries, studentKey)

    fun extractHtmlAbsenceRecords(
        doc: Element,
        todayIso: String = ChisinauTime.todayChisinau(),
        studentKey: String? = null,
    ): List<ParsedAbsenceRecord> = SimeAbsencesParser.extractHtmlAbsenceRecords(doc, todayIso, studentKey)

    fun extractEmbeddedAbsenceRecords(
        doc: Element,
        todayIso: String = ChisinauTime.todayChisinau(),
        studentKey: String? = null,
    ): List<ParsedAbsenceRecord> = SimeAbsencesParser.extractEmbeddedAbsenceRecords(doc, todayIso, studentKey)

    fun buildSemesterAbsences(
        summary: ParsedAbsenceSummary?,
        rawRecords: List<ParsedAbsenceRecord> = emptyList(),
        studentKey: String? = null,
    ): ParsedAbsencesResult = SimeAbsencesParser.buildSemesterAbsences(summary, rawRecords, studentKey)

    // Calendar & Teachers
    fun findCalendarAjaxId(pageHtml: String?): String? = SimeCalendarAndTeachersParser.findCalendarAjaxId(pageHtml)
    fun parseCalendarEvents(eventsJson: String?): List<ParsedCalendarEntry> =
        SimeCalendarAndTeachersParser.parseCalendarEvents(eventsJson)

    fun parseCalendarEvents(events: List<RawCalendarEvent>): List<ParsedCalendarEntry> =
        SimeCalendarAndTeachersParser.parseCalendarEvents(events)

    fun extractEmbeddedCalendarEntries(doc: Element): List<ParsedCalendarEntry> =
        SimeCalendarAndTeachersParser.extractEmbeddedCalendarEntries(doc)

    fun groupByDay(entries: List<ParsedCalendarEntry>): Map<String, List<ParsedLessonView>> =
        SimeCalendarAndTeachersParser.groupByDay(entries)

    fun parseTeacherTokens(name: String?): ParsedTeacherTokens = SimeCalendarAndTeachersParser.parseTeacherTokens(name)
    fun areTeacherNamesSimilar(nameA: String?, nameB: String?): Boolean =
        SimeCalendarAndTeachersParser.areTeacherNamesSimilar(nameA, nameB)

    fun buildTeachers(
        entries: List<ParsedCalendarEntry>,
        metadata: ParsedStudentMetadata? = null,
        catalogSubjects: List<ParsedCatalogSubject> = emptyList(),
    ): List<ParsedTeacher> = SimeCalendarAndTeachersParser.buildTeachers(entries, metadata, catalogSubjects)

    internal fun findSemesterScope(doc: Element, region: SemesterRegionSpec): Element? {
        for (sel in region.selectors) {
            val matches = SimeDomHelpers.selectCss(doc, sel)
            if (matches.isNotEmpty()) return matches.first()
        }
        return null
    }

    /**
     * Single-pass unified parser over the APEX dashboard HTML document (`web/server.js:1401-1503`).
     */
    fun parseFullPortal(
        html: String?,
        todayIso: String = ChisinauTime.todayChisinau(),
        extraReports: List<SemesterReportInput> = emptyList(),
        extraCalendar: List<ParsedCalendarEntry> = emptyList(),
    ): ParsedPortalPage {
        val doc = parseHtml(html)
        val metadata = parseStudentMetadata(doc)
        val links = parseStudentLinks(doc)
        val baseStudent = ParsedStudentProfile(
            id = metadata.id,
            fullName = metadata.fullName,
            className = metadata.className,
            schoolName = metadata.schoolName,
        )
        val merged = mergeStudents(baseStudent, links)
        val students = merged.students
        val baseKey = studentKey(baseStudent.id)
        val baseName = normalizeName(baseStudent.fullName)
        val student = students.firstOrNull { s ->
            (baseKey.isNotEmpty() && studentKey(s.id) == baseKey) ||
                (baseName.isNotEmpty() && normalizeName(s.fullName) == baseName)
        } ?: students.filterIndexed { idx, _ -> links.getOrNull(idx)?.isCurrent == true }.firstOrNull()
            ?: students.firstOrNull()
            ?: baseStudent

        val evalVal = getInputValue(doc, ITEM_LAST_EVALS)
        val evalRoot = if (evalVal.isNotEmpty()) {
            SimeDomHelpers.parseFragment("<div>$evalVal</div>")
        } else {
            doc.getElementById(ITEM_LAST_EVALS)
        }
        val grades = if (evalRoot != null) {
            parseRecentGradesFromRoot(evalRoot, metadata.currentSemester, todayIso)
        } else {
            emptyList()
        }

        val hwVal = getInputValue(doc, ITEM_HOMEWORK)
        val hwRoot = if (hwVal.isNotEmpty()) {
            SimeDomHelpers.parseFragment("<div>$hwVal</div>")
        } else {
            doc.getElementById(ITEM_HOMEWORK)
        }
        val homework = if (hwRoot != null) {
            parseHomeworkFromRoot(hwRoot, todayIso)
        } else {
            emptyList()
        }

        val rawAbsenceSummary = parseAbsenceSummary(doc)

        val annualMap = linkedMapOf<String, Double>()
        val reports = mutableListOf<SemesterReportInput>()
        for (region in REGIONS) {
            val scope = findSemesterScope(doc, region)
            if (scope != null) {
                parseCombinedReportFromRoot(scope, annualMap)
                val rep = parseSemesterReportFromRoot(scope)
                if (rep.isNotEmpty()) {
                    reports.add(SemesterReportInput(semester = region.semester, report = rep))
                }
            }
        }
        for (extra in extraReports) {
            if (extra.report.isNotEmpty()) {
                reports.add(extra)
            }
        }
        val combined = parseCombinedReportFromRoot(doc, annualMap)
        if (combined.sem1.isNotEmpty() && reports.none { it.semester == 1 }) {
            reports.add(SemesterReportInput(semester = 1, report = combined.sem1))
        }
        if (combined.sem2.isNotEmpty() && reports.none { it.semester == 2 }) {
            reports.add(SemesterReportInput(semester = 2, report = combined.sem2))
        }
        if (reports.isEmpty()) {
            val rep = parseSemesterReportFromRoot(doc)
            if (rep.isNotEmpty()) {
                reports.add(SemesterReportInput(semester = metadata.currentSemester ?: 1, report = rep))
            }
        }
        if (reports.isEmpty() && grades.isNotEmpty()) {
            val bySem = linkedMapOf<Int, MutableMap<String, ParsedSubjectReportRow>>()
            for (g in grades) {
                val subj = g.subject ?: continue
                val sem = g.semester ?: metadata.currentSemester ?: 1
                val semMap = bySem.getOrPut(sem) { linkedMapOf() }
                val existing = semMap[subj]
                val updatedMarks = (existing?.marks.orEmpty()) + g
                semMap[subj] = ParsedSubjectReportRow(
                    subject = subj,
                    marks = updatedMarks,
                    semesterAverage = null,
                    teacher = null,
                )
            }
            for ((sem, rep) in bySem) {
                reports.add(SemesterReportInput(semester = sem, report = rep))
            }
        }

        val catalog = buildCatalog(reports, annualMap)
        val averages = catalog.subjects.mapNotNull { s ->
            s.average?.let { ParsedSubjectAverage(subject = s.subject, average = it) }
        }

        val embeddedEntries = extractEmbeddedCalendarEntries(doc)
        val calendarEntries = if (embeddedEntries.isNotEmpty()) embeddedEntries else extraCalendar
        val days = groupByDay(calendarEntries)
        val timetable = days[todayIso].orEmpty()
        val schedule = ParsedSchedule(weekStart = ChisinauTime.weekStart(todayIso), days = days)

        val currentStudentId = student.id.ifEmpty { null }
        val embeddedAbsences = extractEmbeddedAbsenceRecords(doc, todayIso, currentStudentId)
        val rawRecords = if (embeddedAbsences.isNotEmpty()) {
            embeddedAbsences
        } else {
            absenceRecords(calendarEntries, currentStudentId) + extractHtmlAbsenceRecords(doc, todayIso, currentStudentId)
        }
        val absences = buildSemesterAbsences(rawAbsenceSummary, rawRecords, currentStudentId)
        val teachers = buildTeachers(calendarEntries, metadata, catalog.subjects)

        return ParsedPortalPage(
            student = student,
            students = students,
            metadata = metadata,
            grades = grades,
            reusita = catalog,
            catalog = catalog,
            averages = averages,
            generalAverage = catalog.generalAverage,
            absenceSummary = absences.summary,
            absente = absences,
            absences = absences,
            timetable = timetable,
            orar = schedule,
            schedule = schedule,
            homework = homework,
            teachers = teachers,
        )
    }

    fun toStudentAgendaSnapshot(
        portalPage: ParsedPortalPage,
        referenceIsoDate: String = ChisinauTime.todayChisinau(),
        referenceTimeHm: String = "09:15",
        expectedStudentId: StudentId? = null,
    ): StudentAgendaSnapshot =
        SimePortalSnapshotMapper.toStudentAgendaSnapshot(portalPage, referenceIsoDate, referenceTimeHm, expectedStudentId)

    fun inspectParserHealth(html: String?): ParserHealthReport =
        SimePortalSnapshotMapper.inspectParserHealth(html)
}
