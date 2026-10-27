package md.sime.opensime.core.portal

import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.AbsenceSummary
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.HomeworkItem
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.LessonStatus
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.model.SubjectMarksSummary
import md.sime.opensime.core.model.SubjectScale
import md.sime.opensime.core.model.TeacherItem
import md.sime.opensime.core.model.parseHmToMinutes
import md.sime.opensime.core.portal.SimeDomHelpers.getInputValue
import md.sime.opensime.core.portal.SimeDomHelpers.parseFragment
import md.sime.opensime.core.portal.SimeDomHelpers.parseHtml
import md.sime.opensime.core.portal.SimeDomHelpers.selectCss

/**
 * Domain model mapper (`toStudentAgendaSnapshot`) and parser health diagnostics (`inspectParserHealth`).
 */
internal object SimePortalSnapshotMapper {

    fun toStudentAgendaSnapshot(
        portalPage: ParsedPortalPage,
        referenceIsoDate: String = ChisinauTime.todayChisinau(),
        referenceTimeHm: String = "09:15",
        expectedStudentId: StudentId? = null,
    ): StudentAgendaSnapshot {
        val rawId = portalPage.student.id.trim().ifEmpty { portalPage.metadata.id.trim() }
        if (rawId.isEmpty()) {
            throw SimeParseError("Parsed portal page has no student ID")
        }
        val actualStudentId = StudentId(rawId)
        if (
            expectedStudentId != null &&
            SimeStudentParser.studentKey(expectedStudentId.value) != SimeStudentParser.studentKey(actualStudentId.value)
        ) {
            throw SimeStudentMismatchError(
                message = "Portal page belongs to student '${actualStudentId.value}', expected '${expectedStudentId.value}'",
                requestedStudentId = expectedStudentId,
                actualStudentId = actualStudentId,
            )
        }

        val currentSemester = portalPage.metadata.currentSemester ?: 1
        val profile = StudentProfile(
            id = actualStudentId,
            fullName = portalPage.student.fullName.ifBlank { portalPage.metadata.fullName.ifBlank { rawId } },
            className = portalPage.student.className ?: portalPage.metadata.className.orEmpty(),
            schoolName = portalPage.student.schoolName ?: portalPage.metadata.schoolName.orEmpty(),
            classMasterName = portalPage.metadata.classMaster.orEmpty(),
            currentSemester = currentSemester,
            academicYear = portalPage.metadata.studyYear.orEmpty(),
            enrollmentStatus = portalPage.metadata.enrollmentStatus,
        )

        val recentMarks = portalPage.grades.mapIndexed { idx, g ->
            MarkItem(
                id = "${actualStudentId.value}-recent-$idx",
                subject = g.subject.orEmpty(),
                value = g.toDomainMarkValue(),
                isoDate = g.date.orEmpty(),
                semester = g.semester ?: currentSemester,
                isThesis = g.isThesis,
            )
        }

        val subjectSummaries = portalPage.catalog.subjects.mapIndexed { subjIdx, subj ->
            val domainMarks = subj.markDetails.mapIndexed { markIdx, m ->
                MarkItem(
                    id = "${actualStudentId.value}-subj-$subjIdx-m-$markIdx",
                    subject = subj.subject,
                    value = m.toDomainMarkValue(),
                    isoDate = m.date.orEmpty(),
                    semester = m.semester ?: currentSemester,
                    isThesis = m.isThesis,
                    teacherName = subj.teacher,
                )
            }
            val domainScale = when (subj.scale) {
                "qualifier" -> SubjectScale.QUALIFIER
                "descriptor" -> SubjectScale.DESCRIPTOR
                else -> SubjectMarksSummary.inferSubjectScale(subj.subject, domainMarks)
            }
            SubjectMarksSummary(
                subject = subj.subject,
                teacherName = subj.teacher.orEmpty(),
                semester = currentSemester,
                marks = domainMarks,
                portalAverage = subj.officialPortalAverage,
                scale = domainScale,
            )
        }

        val mondayIso = portalPage.schedule.weekStart
        val weekDayIsoDates = (0..4).map { dayOffset -> ChisinauTime.addDays(mondayIso, dayOffset) }
        val refMinutes = parseHmToMinutes(referenceTimeHm)

        fun mapDayLessons(dayIso: String, dayIndex: Int, rawLessons: List<ParsedLessonView>): List<LessonItem> {
            val isRefDay = dayIso == referenceIsoDate
            var nextUpAssigned = false
            return rawLessons.mapIndexed { idx, l ->
                val startMin = parseHmToMinutes(l.startLocal)
                val endMin = parseHmToMinutes(l.endLocal)
                val status = when {
                    !isRefDay || refMinutes == null || startMin == null || endMin == null -> LessonStatus.UPCOMING
                    refMinutes >= endMin -> LessonStatus.COMPLETED
                    refMinutes in startMin until endMin -> LessonStatus.LIVE_NOW
                    !nextUpAssigned && refMinutes < startMin -> {
                        nextUpAssigned = true
                        LessonStatus.NEXT_UP
                    }
                    else -> LessonStatus.UPCOMING
                }
                LessonItem(
                    id = "${actualStudentId.value}-$dayIso-${l.periodIndex}-$idx",
                    periodNumber = l.periodIndex,
                    startTime = l.startLocal,
                    endTime = l.endLocal,
                    subject = l.subject,
                    room = l.classroom.orEmpty(),
                    teacherName = l.teacher.orEmpty(),
                    topic = l.topic,
                    homeworkSummary = l.topic,
                    status = status,
                    dayOfWeekIndex = dayIndex.coerceIn(0, 4),
                )
            }
        }

        val weekLessonsByDay = linkedMapOf<Int, List<LessonItem>>()
        weekDayIsoDates.forEachIndexed { dayIndex, dayIso ->
            val rawDay = portalPage.schedule.days[dayIso].orEmpty()
            if (rawDay.isNotEmpty()) {
                weekLessonsByDay[dayIndex] = mapDayLessons(dayIso, dayIndex, rawDay)
            }
        }

        val refDayIndex = weekDayIsoDates.indexOf(referenceIsoDate).takeIf { it != -1 } ?: 0
        val todayLessons = mapDayLessons(referenceIsoDate, refDayIndex, portalPage.timetable)

        val absSum = portalPage.absenceSummary
        val domainAbsenceSummary = AbsenceSummary(
            totalCount = absSum.total,
            excusedCount = absSum.totalExcused,
            unexcusedCount = absSum.totalUnexcused,
            presencePercent = absSum.presencePercent.toDouble(),
        )
        val sem1Summary = AbsenceSummary(
            totalCount = portalPage.absences.sem1.total,
            excusedCount = portalPage.absences.sem1.motivate,
            unexcusedCount = portalPage.absences.sem1.nemotivate,
        )
        val sem2Summary = AbsenceSummary(
            totalCount = portalPage.absences.sem2.total,
            excusedCount = portalPage.absences.sem2.motivate,
            unexcusedCount = portalPage.absences.sem2.nemotivate,
        )

        val domainAbsences = portalPage.absences.records.mapIndexed { idx, r ->
            val periodTime = when {
                r.startTime.isNotEmpty() && r.endTime.isNotEmpty() -> "${r.startTime} - ${r.endTime}"
                r.startTime.isNotEmpty() -> r.startTime
                else -> null
            }
            AbsenceItem(
                id = "${actualStudentId.value}-abs-$idx",
                subject = r.subject,
                isoDate = r.date.orEmpty(),
                semester = r.semester,
                status = if (r.status == "excused") AbsenceStatus.EXCUSED else AbsenceStatus.UNEXCUSED,
                periodNumber = r.lessonNumber,
                periodTime = periodTime,
                room = r.room,
                teacherName = r.teacher,
                note = r.reason,
            )
        }

        val domainHomework = portalPage.homework.mapIndexed { idx, hw ->
            HomeworkItem(
                id = "${actualStudentId.value}-hw-$idx",
                subject = hw.subject,
                description = hw.taskDescription,
                assignedIsoDate = referenceIsoDate,
                dueIsoDate = hw.dueDate.orEmpty(),
                isDonePreview = hw.isCompleted,
            )
        }

        val domainTeachers = portalPage.teachers.map { t ->
            TeacherItem(
                name = t.name,
                subjects = t.subjects,
                isClassMaster = t.note == "Diriginte",
            )
        }

        val snapshot = StudentAgendaSnapshot(
            student = profile,
            dataSourceMode = DataSourceMode.LIVE_PORTAL,
            referenceIsoDate = referenceIsoDate,
            referenceTimeHm = referenceTimeHm,
            recentMarks = recentMarks,
            subjectSummaries = subjectSummaries,
            todayLessons = todayLessons,
            weekLessonsByDay = weekLessonsByDay,
            absenceSummary = domainAbsenceSummary,
            absences = domainAbsences,
            homeworkItems = domainHomework,
            teachers = domainTeachers,
            weekDayIsoDates = weekDayIsoDates,
            sem1AbsenceSummary = sem1Summary,
            sem2AbsenceSummary = sem2Summary,
        )
        return if (expectedStudentId != null) {
            snapshot.verifyStudentOwnership(expectedStudentId)
        } else {
            snapshot
        }
    }

    fun inspectParserHealth(html: String?): ParserHealthReport {
        val doc = parseHtml(html)

        // students
        val studentSelectors = listOf(
            "#P1_ELEVE_ID",
            "#P1_ELEVE",
            "#P1_CLASS_NAME",
            "#P1_INSTITUTION_NAME",
            "#t_TreeNav",
            "select#P1_ELEVE_ID",
        )
        val matchedStudentSel = studentSelectors.filter { selectCss(doc, it).isNotEmpty() }
        val links = SimeStudentParser.parseStudentLinks(doc)
        val profile = SimeStudentParser.parseStudentProfile(doc)
        val studentFields = buildList {
            if (profile.id.isNotEmpty()) add("id")
            if (profile.fullName.isNotEmpty()) add("fullName")
            if (!profile.className.isNullOrEmpty()) add("className")
            if (!profile.schoolName.isNullOrEmpty()) add("schoolName")
        }
        val studentsHealth = ParserSectionHealth(
            count = maxOf(links.size, if (profile.id.isNotEmpty() || profile.fullName.isNotEmpty()) 1 else 0),
            selectorsMatched = matchedStudentSel,
            fieldsFound = studentFields,
        )

        // grades
        val gradeSelectors = listOf(
            "#P1_LAST_3_EVAL_LIST",
            "input#P1_LAST_3_EVAL_LIST",
            ".eval-date",
            ".eval-discipline",
            ".grade",
        )
        val matchedGradeSel = gradeSelectors.filter { selectCss(doc, it).isNotEmpty() }
        val evalVal = getInputValue(doc, SimeUrlValidator.ITEM_LAST_EVALS)
        val evalRoot = if (evalVal.isNotEmpty()) {
            parseFragment("<div>$evalVal</div>")
        } else {
            doc.getElementById(SimeUrlValidator.ITEM_LAST_EVALS)
        }
        val grades = if (evalRoot != null) SimeGradesParser.parseRecentGradesFromRoot(evalRoot) else emptyList()
        val gradeFields = linkedSetOf<String>()
        for (g in grades) {
            if (!g.subject.isNullOrEmpty()) gradeFields.add("subject")
            if (g.value != null || g.raw.isNotEmpty()) gradeFields.add("value")
            if (!g.date.isNullOrEmpty()) gradeFields.add("date")
            if (g.semester != null) gradeFields.add("semester")
        }
        val gradesHealth = ParserSectionHealth(
            count = grades.size,
            selectorsMatched = matchedGradeSel,
            fieldsFound = gradeFields.toList(),
        )

        // catalog
        val catalogSelectors = listOf(
            "#SEMESTER_REG_1",
            "#SEMESTER_REG_2",
            ".t-Report-report",
            "td[headers*='DISCIPLINA' i]",
            "td[headers*='MED_SEM' i]",
        )
        val matchedCatSel = catalogSelectors.filter { selectCss(doc, it).isNotEmpty() }
        val catReports = mutableListOf<SemesterReportInput>()
        for (region in SimePortalParser.REGIONS) {
            val scope = SimePortalParser.findSemesterScope(doc, region)
            if (scope != null) {
                val rep = SimeGradesParser.parseSemesterReportFromRoot(scope)
                if (rep.isNotEmpty()) {
                    catReports.add(SemesterReportInput(semester = region.semester, report = rep))
                }
            }
        }
        val catalog = SimeGradesParser.buildCatalog(catReports)
        val catFields = linkedSetOf<String>()
        for (subj in catalog.subjects) {
            if (subj.subject.isNotEmpty()) catFields.add("subject")
            if (subj.markDetails.isNotEmpty()) catFields.add("marks")
            if (subj.average != null || subj.sem1Average != null) catFields.add("semesterAverage")
            if (!subj.teacher.isNullOrEmpty()) catFields.add("teacher")
        }
        val catalogHealth = ParserSectionHealth(
            count = catalog.subjects.size,
            selectorsMatched = matchedCatSel,
            fieldsFound = catFields.toList(),
        )

        // absences summary
        val absSummarySelectors = listOf(
            "#P1_MOTIVATED",
            "#P1_UNMOTIVATED",
            "#P1_MOTIVATED2",
            "#P1_UNMOTIVATED2",
            "#P1_TOTAL_ABSENCES1",
            "#P1_TOTAL_ABSENCES2",
            "#P1_JSON_ABSENCES",
            "#P1_PRESENCE_PERCENT",
            "#P1_PROC_PREZ",
        )
        val matchedAbsSel = absSummarySelectors.filter { selectCss(doc, it).isNotEmpty() }
        val absSummary = SimeAbsencesParser.parseAbsenceSummary(doc)
        val absFields = listOf(
            "presencePercent",
            "sem1Excused",
            "sem1Unexcused",
            "sem2Excused",
            "sem2Unexcused",
            "total",
        )
        val absencesHealth = ParserSectionHealth(
            count = absSummary.total,
            selectorsMatched = matchedAbsSel,
            fieldsFound = absFields,
        )

        // calendar
        val calSelectors = listOf(
            "#P1_CALENDAR_EVENTS",
            "script#P1_CALENDAR_EVENTS",
            "[data-start][data-end]",
        )
        val matchedCalSel = calSelectors.filter { selectCss(doc, it).isNotEmpty() }
        val calEntries = SimeCalendarAndTeachersParser.extractEmbeddedCalendarEntries(doc)
        val calFields = linkedSetOf<String>()
        for (c in calEntries) {
            if (c.start > 0L) calFields.add("start")
            if (c.end > 0L) calFields.add("end")
            if (c.subject.isNotEmpty()) calFields.add("subject")
            if (!c.classroom.isNullOrEmpty()) calFields.add("classroom")
            if (!c.teacher.isNullOrEmpty()) calFields.add("teacher")
        }
        val calendarHealth = ParserSectionHealth(
            count = calEntries.size,
            selectorsMatched = matchedCalSel,
            fieldsFound = calFields.toList(),
        )

        // teachers
        val teacherSelectors = listOf(
            "#P1_DIRIGINTE",
            "input#P1_DIRIGINTE",
            "td[headers*='PROFESOR' i]",
            ".lesson-teacher",
        )
        val matchedTeacherSel = teacherSelectors.filter { selectCss(doc, it).isNotEmpty() }
        val metadata = SimeStudentParser.parseStudentMetadata(doc)
        val teachers = SimeCalendarAndTeachersParser.buildTeachers(calEntries, metadata, catalog.subjects)
        val teacherFields = linkedSetOf<String>()
        for (t in teachers) {
            if (t.name.isNotEmpty()) teacherFields.add("name")
            if (t.subjects.isNotEmpty()) teacherFields.add("subjects")
            teacherFields.add("lessons")
            if (!t.note.isNullOrEmpty()) teacherFields.add("note")
        }
        val teachersHealth = ParserSectionHealth(
            count = teachers.size,
            selectorsMatched = matchedTeacherSel,
            fieldsFound = teacherFields.toList(),
        )

        return ParserHealthReport(
            students = studentsHealth,
            grades = gradesHealth,
            catalog = catalogHealth,
            absencesSummary = absencesHealth,
            calendar = calendarHealth,
            teachers = teachersHealth,
        )
    }
}
