package md.sime.opensime.feature.subjects

import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.AbsenceSummary
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.HomeworkItem
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.SubjectMarksSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectDetailsTest {
    @Test fun homeMarkRoutesOnlyToAnExactUniqueSubjectForItsStudent() {
        val recent = mark("home-a")
        val own = snapshot().copy(recentMarks = listOf(recent))
        val key = SubjectDetailsKey(studentA, "Matematica")
        assertEquals(key, subjectDetailsKeyForMark(own, recent))
        assertNull(subjectDetailsKeyForMark(own, recent.copy(id = "other-student-mark")))
        assertNull(subjectDetailsFor(own.copy(student = own.student.copy(id = studentB)), key))
        assertNull(subjectDetailsKeyForMark(own.copy(subjectSummaries = own.subjectSummaries + own.subjectSummaries), recent))
        val unmatched = recent.copy(subject = "matematica")
        assertNull(subjectDetailsKeyForMark(own.copy(recentMarks = listOf(unmatched)), unmatched))
    }

    private val studentA = StudentId("synthetic-student-a")
    private val studentB = StudentId("synthetic-student-b")
    private val base = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()

    private fun mark(
        id: String,
        subject: String = "Matematica",
        value: MarkValue = MarkValue.Numeric(8),
        semester: Int = 1,
        isThesis: Boolean = false,
    ) = MarkItem(id, subject, value, "2026-10-10", semester, isThesis)

    private fun snapshot(
        studentId: StudentId = studentA,
        summary: SubjectMarksSummary = SubjectMarksSummary(
            subject = "Matematica",
            teacherName = "Teacher A",
            semester = 1,
            marks = listOf(mark("a", value = MarkValue.Numeric(7)), mark("b", value = MarkValue.Numeric(9))),
        ),
        dataSourceMode: DataSourceMode = DataSourceMode.SAMPLE_PREVIEW,
        stale: Boolean = false,
        sync: Map<String, Long> = emptyMap(),
        absences: List<AbsenceItem> = emptyList(),
        sem1AbsenceSummary: AbsenceSummary? = null,
        sem2AbsenceSummary: AbsenceSummary? = null,
        homework: List<HomeworkItem> = emptyList(),
        lessons: List<LessonItem> = emptyList(),
    ): StudentAgendaSnapshot = base.copy(
        student = base.student.copy(
            id = studentId,
            className = if (studentId == studentA) "7A" else "10B",
        ),
        subjectSummaries = listOf(summary),
        absences = absences,
        sem1AbsenceSummary = sem1AbsenceSummary,
        sem2AbsenceSummary = sem2AbsenceSummary,
        homeworkItems = homework,
        todayLessons = lessons,
        weekLessonsByDay = emptyMap(),
        lessonsByDate = mapOf("2026-10-27" to lessons),
        successfulSyncMillis = sync,
        dataSourceMode = dataSourceMode,
        stale = stale,
    )

    private fun resolve(snapshot: StudentAgendaSnapshot, studentId: StudentId = snapshot.studentId) =
        subjectDetailsFor(snapshot, SubjectDetailsKey(studentId, "Matematica"))

    @Test
    fun `grades stay in their reported semester and unknown periods remain separate`() {
        val details = resolve(
            snapshot(
                summary = SubjectMarksSummary(
                    subject = "Matematica",
                    teacherName = "Teacher A",
                    semester = 2,
                    marks = listOf(
                        mark("s1", semester = 1),
                        mark("s2", semester = 2),
                        mark("conflict", semester = 0),
                    ),
                ),
            ),
        )!!

        assertEquals(listOf("s1"), details.semesterOneGrades.map { it.id })
        assertEquals(listOf("s2"), details.semesterTwoGrades.map { it.id })
        assertEquals(listOf("conflict"), details.unidentifiedPeriodGrades.map { it.id })
    }

    @Test
    fun `provisional average uses current semester numeric regular marks and ignores portal candidates`() {
        val details = resolve(
            snapshot(
                summary = SubjectMarksSummary(
                    subject = "Matematica",
                    teacherName = "Teacher A",
                    semester = 1,
                    marks = listOf(
                        mark("s1a", value = MarkValue.Numeric(7)),
                        mark("s1b", value = MarkValue.Numeric(9)),
                        mark("thesis", value = MarkValue.Numeric(10), isThesis = true),
                        mark("s2", value = MarkValue.Numeric(2), semester = 2),
                    ),
                    portalAverage = 10.0,
                    portalSemesterAverageCandidates = mapOf(1 to 10.0),
                    portalAnnualAverageCandidate = 10.0,
                ),
            ),
        )!!

        assertEquals(8.0, details.provisionalAverage!!.value, 0.0)
        assertTrue(details.provisionalAverage.isEstimated)
        assertEquals(2, details.eligibleNumericMarkCount)
    }

    @Test
    fun `subjects without grades have no average or eligible mark count`() {
        val details = resolve(
            snapshot(summary = SubjectMarksSummary("Matematica", "", 1, emptyList())),
        )!!

        assertTrue(details.semesterOneGrades.isEmpty())
        assertTrue(details.semesterTwoGrades.isEmpty())
        assertTrue(details.unidentifiedPeriodGrades.isEmpty())
        assertNull(details.provisionalAverage)
        assertEquals(0, details.eligibleNumericMarkCount)
    }

    @Test
    fun `subject absence counts use exact label and retain excused and unexcused status`() {
        val records = listOf(
            AbsenceItem("a1", "Matematica", "2026-10-10", 1, AbsenceStatus.EXCUSED),
            AbsenceItem("a2", "Matematica", "2026-10-11", 1, AbsenceStatus.UNEXCUSED),
            AbsenceItem("other", "Fizica", "2026-10-12", 2, AbsenceStatus.EXCUSED),
        )
        val details = resolve(
            snapshot(
                absences = records,
                sem1AbsenceSummary = AbsenceSummary(2, 1, 1),
                sem2AbsenceSummary = AbsenceSummary(1, 1, 0),
            ),
        )!!

        assertEquals(1, details.absenceCounts!!.excused)
        assertEquals(1, details.absenceCounts.unexcused)
        assertEquals(listOf("a1", "a2"), details.absenceRecords!!.map { it.id })
    }

    @Test
    fun `mismatched absence detail totals make subject counts unavailable`() {
        val details = resolve(
            snapshot(
                absences = listOf(AbsenceItem("a1", "Matematica", "2026-10-10", 1, AbsenceStatus.EXCUSED)),
                sem1AbsenceSummary = AbsenceSummary(2, 2, 0),
                sem2AbsenceSummary = AbsenceSummary(0, 0, 0),
            ),
        )!!

        assertNull(details.absenceCounts)
        assertNull(details.absenceRecords)
    }

    @Test
    fun `unknown absence status is not counted as unexcused`() {
        val details = resolve(
            snapshot(
                absences = listOf(AbsenceItem("a1", "Matematica", "2026-10-10", 1, AbsenceStatus.UNKNOWN)),
                sem1AbsenceSummary = AbsenceSummary(1, 0, 1),
                sem2AbsenceSummary = AbsenceSummary(0, 0, 0),
            ),
        )!!

        assertNull(details.absenceCounts)
    }

    @Test
    fun `homework and timetable only link on exact subject labels`() {
        val details = resolve(
            snapshot(
                homework = listOf(
                    HomeworkItem("h1", "Matematica", "Exact", "2026-10-27", "2026-10-28"),
                    HomeworkItem("h2", "Matematica grup", "Similar name", "2026-10-27", "2026-10-28"),
                ),
                lessons = listOf(
                    LessonItem("l1", 1, "08:00", "08:45", "Matematica", "", ""),
                    LessonItem("l2", 2, "08:55", "09:40", "matematica", "", ""),
                ),
            ),
        )!!

        assertEquals(listOf("h1"), details.homework.map { it.id })
        assertEquals(listOf("l1"), details.timetable.map { it.id })
    }

    @Test
    fun `student ownership mismatch and ambiguous subject labels fail closed`() {
        val snapshotA = snapshot(studentId = studentA)
        val snapshotB = snapshot(studentId = studentB)

        assertNull(resolve(snapshotB, studentA))
        assertNull(
            subjectDetailsFor(
                snapshotA.copy(subjectSummaries = listOf(snapshotA.subjectSummaries.single(), snapshotA.subjectSummaries.single())),
                SubjectDetailsKey(studentA, "Matematica"),
            ),
        )
        assertNull(subjectDetailsFor(snapshotA, SubjectDetailsKey(studentA, "matematica")))
    }

    @Test
    fun `offline snapshots do not claim empty absence homework or timetable data is complete`() {
        val details = resolve(
            snapshot(
                dataSourceMode = DataSourceMode.LIVE_PORTAL,
                stale = true,
                sync = emptyMap(),
                absences = emptyList(),
                homework = emptyList(),
                lessons = emptyList(),
            ),
        )!!

        assertNull(details.absenceCounts)
        assertTrue(details.homework.isEmpty())
        assertTrue(details.timetable.isEmpty())
        assertTrue(details.stale)
    }

    @Test
    fun `missing optional teacher and class fields remain absent`() {
        val details = resolve(
            snapshot(
                summary = SubjectMarksSummary("Matematica", "", 1, emptyList()),
            ).copy(student = base.student.copy(id = studentA, className = "")),
        )!!

        assertNull(details.teacherName)
        assertNull(details.className)
    }
}
