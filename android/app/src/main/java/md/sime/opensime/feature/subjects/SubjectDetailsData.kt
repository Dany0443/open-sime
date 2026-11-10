package md.sime.opensime.feature.subjects

import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.AverageMetric
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.HomeworkItem
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId

data class SubjectDetailsKey(val studentId: StudentId, val subjectLabel: String)

data class SubjectAbsenceCounts(val excused: Int, val unexcused: Int)

data class SubjectDetailsData(
    val subject: String,
    val teacherName: String?,
    val className: String?,
    val semesterOneGrades: List<MarkItem>,
    val semesterTwoGrades: List<MarkItem>,
    val unidentifiedPeriodGrades: List<MarkItem>,
    val provisionalAverage: AverageMetric?,
    val provisionalQualifierAverage: QualifierCode?,
    val eligibleNumericMarkCount: Int,
    val absenceCounts: SubjectAbsenceCounts?,
    val absenceRecords: List<AbsenceItem>?,
    val homework: List<HomeworkItem>,
    val homeworkAvailable: Boolean,
    val timetable: List<LessonItem>,
    val timetableAvailable: Boolean,
    val stale: Boolean,
)

/** Uses exact labels only. Student ownership and ambiguous labels fail closed. */
fun subjectDetailsFor(snapshot: StudentAgendaSnapshot, key: SubjectDetailsKey): SubjectDetailsData? {
    if (snapshot.studentId != key.studentId) return null
    val matches = snapshot.subjectSummaries.filter { it.subject == key.subjectLabel }
    val summary = matches.singleOrNull() ?: return null
    val marks = summary.marks
    val semester = snapshot.student.currentSemester.takeIf { it in 1..2 }
    val eligible = marks.count { mark ->
        mark.semester == semester && !mark.isThesis &&
            (mark.value as? md.sime.opensime.core.model.MarkValue.Numeric)?.decimalScore?.let { it in 1.0..10.0 } == true
    }
    val allAbsenceDetailsComplete = snapshot.dataSourceMode == DataSourceMode.SAMPLE_PREVIEW ||
        snapshot.successfulSyncMillis["absences"] != null
    val sem1 = snapshot.absencesForSemester(1)
    val sem2 = snapshot.absencesForSemester(2)
    val absenceCoverageComplete = allAbsenceDetailsComplete &&
        sem1.records.size == sem1.totalCount && sem2.records.size == sem2.totalCount &&
        sem1.records.count { it.status == AbsenceStatus.EXCUSED } == sem1.excusedCount &&
        sem1.records.count { it.status == AbsenceStatus.UNEXCUSED } == sem1.unexcusedCount &&
        sem2.records.count { it.status == AbsenceStatus.EXCUSED } == sem2.excusedCount &&
        sem2.records.count { it.status == AbsenceStatus.UNEXCUSED } == sem2.unexcusedCount &&
        snapshot.absences.all { it.semester in 1..2 }
    val subjectAbsences = snapshot.absences.filter { it.subject == summary.subject }
    val statusesKnown = subjectAbsences.all { it.status != AbsenceStatus.UNKNOWN }
    val absenceRecords = if (absenceCoverageComplete && statusesKnown) subjectAbsences else null
    val absenceCounts = absenceRecords?.let { records ->
        SubjectAbsenceCounts(
            excused = records.count { it.status == AbsenceStatus.EXCUSED },
            unexcused = records.count { it.status == AbsenceStatus.UNEXCUSED },
        )
    }
    val homeworkAvailable = snapshot.dataSourceMode == DataSourceMode.SAMPLE_PREVIEW ||
        snapshot.successfulSyncMillis["homework"] != null
    val timetableAvailable = snapshot.dataSourceMode == DataSourceMode.SAMPLE_PREVIEW ||
        snapshot.successfulSyncMillis["timetable"] != null
    val timetable = (snapshot.todayLessons + snapshot.weekLessonsByDay.values.flatten() +
        snapshot.lessonsByDate.values.flatten()).distinctBy { it.id }
        .filter { it.subject == summary.subject }
        .sortedWith(compareBy<LessonItem> { it.dayOfWeekIndex }.thenBy { it.periodNumber })

    return SubjectDetailsData(
        subject = summary.subject,
        teacherName = summary.teacherName.takeIf { it.isNotBlank() },
        className = snapshot.student.className.takeIf { it.isNotBlank() },
        semesterOneGrades = marks.filter { it.semester == 1 }.sortedBy { it.isoDate },
        semesterTwoGrades = marks.filter { it.semester == 2 }.sortedBy { it.isoDate },
        unidentifiedPeriodGrades = marks.filter { it.semester !in 1..2 }.sortedBy { it.isoDate },
        provisionalAverage = semester?.let(summary::resolveProvisionalSemesterAverage),
        provisionalQualifierAverage = semester?.let(summary::resolveProvisionalSemesterQualifierAverage),
        eligibleNumericMarkCount = eligible,
        absenceCounts = absenceCounts,
        absenceRecords = absenceRecords,
        homework = if (homeworkAvailable) snapshot.homeworkItems.filter { it.subject == summary.subject } else emptyList(),
        homeworkAvailable = homeworkAvailable,
        timetable = if (timetableAvailable) timetable else emptyList(),
        timetableAvailable = timetableAvailable,
        stale = snapshot.stale,
    )
}
