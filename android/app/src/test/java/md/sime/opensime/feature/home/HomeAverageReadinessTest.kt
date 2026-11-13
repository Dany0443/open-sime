package md.sime.opensime.feature.home

import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.model.SubjectMarksSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAverageReadinessTest {
    private fun subject(name: String, mark: Int?) = SubjectMarksSummary(
        subject = name,
        teacherName = "",
        semester = 1,
        marks = mark?.let { listOf(MarkItem(name, name, MarkValue.Numeric(it), "2026-10-01", 1)) }.orEmpty(),
    )
    private val snapshot = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first().let {
        it.copy(student = it.student.copy(currentSemester = 1), subjectSummaries = listOf(subject("A", 8), subject("B", 10)))
    }

    @Test fun incompleteCoverageNeverShowsPartialAverage() {
        assertNull(readyHomeAverage(snapshot.copy(gradesCoverageComplete = false)))
        assertEquals(8.0, readyHomeAverage(snapshot.copy(gradesCoverageComplete = true, subjectSummaries = listOf(subject("A", 8), subject("B", null))))!!.value, 0.0)
    }

    @Test fun incompleteCoverageRemainsUnavailableEvenWithLoadedGrades() {
        assertNull(readyHomeAverage(snapshot.copy(gradesCoverageComplete = false)))
    }

    @Test fun completedAvailableGradeLoadDisplaysAverageWithoutClaimingCoverage() {
        val loaded = snapshot.copy(gradesCoverageComplete = false, successfulSyncMillis = mapOf("grades" to 123L))
        assertEquals(9.0, readyHomeAverage(loaded)!!.value, 0.0)
        assertTrue(!loaded.gradesCoverageComplete)
    }

    @Test fun previouslyCompletedAverageRemainsAvailableForRefresh() {
        val loaded = snapshot.copy(successfulSyncMillis = mapOf("grades" to 123L))
        assertEquals(9.0, readyHomeAverage(loaded)!!.value, 0.0)
        assertEquals(9.0, readyHomeAverage(loaded.copy(stale = true))!!.value, 0.0)
    }

    @Test fun unrelatedSyncAndNoNumericMarksDoNotProduceAverage() {
        assertNull(readyHomeAverage(snapshot.copy(gradesCoverageComplete = false, successfulSyncMillis = mapOf("timetable" to 123L))))
        assertNull(readyHomeAverage(snapshot.copy(gradesCoverageComplete = false, successfulSyncMillis = mapOf("grades" to 123L), subjectSummaries = listOf(subject("A", null)))))
    }

    @Test fun completeCachedGradesAreIndependentOfTimetable() {
        val complete = snapshot.copy(gradesCoverageComplete = true, stale = true, todayLessons = emptyList(), weekLessonsByDay = emptyMap())
        assertEquals(9.0, readyHomeAverage(complete)!!.value, 0.0)
    }

    @Test fun futureSemesterWithoutGradesDoesNotBlockCompleteCurrentSemester() {
        val complete = snapshot.copy(gradesCoverageComplete = true)
        assertTrue(complete.subjectSummaries.flatMap { it.marks }.none { it.semester == 2 })
        assertEquals(9.0, readyHomeAverage(complete)!!.value, 0.0)
        assertNull(readyHomeAverage(complete.copy(gradesCoverageComplete = false)))
    }

    @Test fun completeQualitativeSubjectsDoNotBlockNumericAverage() {
        val qualifier = subject("C", null).copy(marks = listOf(MarkItem("q", "C", MarkValue.Qualifier(QualifierCode.FB), "2026-10-01", 1)), scale = md.sime.opensime.core.model.SubjectScale.QUALIFIER)
        val complete = snapshot.copy(gradesCoverageComplete = true, subjectSummaries = listOf(subject("A", 8), qualifier))
        assertEquals(8.0, readyHomeAverage(complete)!!.value, 0.0)
    }

    @Test fun unresolvedSemesterDoesNotSilentlyExcludeSubject() {
        val uncertain = subject("B", 10).let { it.copy(marks = it.marks.map { mark -> mark.copy(semester = 0) }) }
        assertNull(readyHomeAverage(snapshot.copy(gradesCoverageComplete = true, subjectSummaries = listOf(subject("A", 8), uncertain))))
    }
}
