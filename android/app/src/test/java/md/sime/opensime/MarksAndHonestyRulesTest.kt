package md.sime.opensime

import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.GradeBand
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.model.SubjectMarksSummary
import md.sime.opensime.core.model.SubjectScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksAndHonestyRulesTest {

    @Test
    fun `qualifier-only subject returns null numeric average`() {
        val summary = SubjectMarksSummary(
            subject = "Educația tehnologică",
            teacherName = "Prof. Exemplu",
            semester = 1,
            marks = listOf(
                MarkItem("1", "Educația tehnologică", MarkValue.Qualifier(QualifierCode.FB), "2026-10-20", 1),
                MarkItem("2", "Educația tehnologică", MarkValue.Qualifier(QualifierCode.B), "2026-10-10", 1),
            ),
        )

        assertNull(summary.resolveAverage())
    }

    @Test
    fun `subject with mixed numeric and qualifier marks excludes qualifiers from estimated average`() {
        val summary = SubjectMarksSummary(
            subject = "Educația plastică",
            teacherName = "Prof. Exemplu",
            semester = 1,
            marks = listOf(
                MarkItem("1", "Educația plastică", MarkValue.Numeric(10), "2026-10-20", 1),
                MarkItem("2", "Educația plastică", MarkValue.Qualifier(QualifierCode.FB), "2026-10-15", 1),
                MarkItem("3", "Educația plastică", MarkValue.Numeric(8), "2026-10-10", 1),
            ),
        )

        val avg = summary.resolveAverage()
        assertNotNull(avg)
        assertEquals(9.0, avg!!.value, 0.001)
        assertTrue(avg.isEstimated)
    }

    @Test
    fun `portal provided average is marked as not estimated`() {
        val summary = SubjectMarksSummary(
            subject = "Matematica",
            teacherName = "Prof. Exemplu",
            semester = 1,
            marks = listOf(
                MarkItem("1", "Matematica", MarkValue.Numeric(9), "2026-10-20", 1),
            ),
            portalAverage = 9.45,
        )

        val avg = summary.resolveAverage()
        assertNotNull(avg)
        assertEquals(9.45, avg!!.value, 0.001)
        assertFalse(avg.isEstimated)
    }

    @Test
    fun `semester averages exclude qualifiers and match per-semester numeric subject averages`() {
        val snapshots = SampleAgendaRepository.buildDefaultSampleSnapshots()
        val snap7A = snapshots.getValue(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)

        val sem1Avg = snap7A.resolveSemesterAverage(1)
        val sem2Avg = snap7A.resolveSemesterAverage(2)
        assertNotNull(sem1Avg)
        assertTrue(sem1Avg!!.isEstimated)
        assertTrue(sem1Avg.value in 8.0..10.0)
        // Student 7A is in Semester 1 and has no Semester 2 marks yet
        assertNull(sem2Avg)
    }

    @Test
    fun `subject scale inference distinguishes descriptor, qualifier, and numeric subjects`() {
        assertEquals(
            SubjectScale.DESCRIPTOR,
            SubjectMarksSummary.inferSubjectScale("Educație pentru societate", emptyList()),
        )
        assertEquals(
            SubjectScale.QUALIFIER,
            SubjectMarksSummary.inferSubjectScale("Educația muzicală", emptyList()),
        )
        assertEquals(
            SubjectScale.NUMERIC,
            SubjectMarksSummary.inferSubjectScale("Matematica", emptyList()),
        )
    }

    @Test
    fun `grade band classification matches web UI thresholds`() {
        assertEquals(GradeBand.TOP, GradeBand.fromAverage(9.50))
        assertEquals(GradeBand.GOOD, GradeBand.fromAverage(8.75))
        assertEquals(GradeBand.OK, GradeBand.fromAverage(6.50))
        assertEquals(GradeBand.LOW, GradeBand.fromAverage(4.80))
        assertEquals(GradeBand.NONE, GradeBand.fromAverage(null))

        assertEquals(GradeBand.TOP, GradeBand.fromMark(MarkValue.Qualifier(QualifierCode.FB)))
        assertEquals(GradeBand.GOOD, GradeBand.fromMark(MarkValue.Qualifier(QualifierCode.B)))
        assertEquals(GradeBand.OK, GradeBand.fromMark(MarkValue.Qualifier(QualifierCode.S)))
        assertEquals(GradeBand.LOW, GradeBand.fromMark(MarkValue.Qualifier(QualifierCode.I)))
    }

    @Test
    fun `absencesForSemester returns semester-specific block and empty block when semester has no absences`() {
        val snapshots = SampleAgendaRepository.buildDefaultSampleSnapshots()
        val snap7A = snapshots.getValue(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)

        val sem1 = snap7A.absencesForSemester(1)
        val sem2 = snap7A.absencesForSemester(2)

        assertTrue(sem1.totalCount > 0)
        assertTrue(sem1.records.isNotEmpty())
        assertEquals(0, sem2.totalCount)
        assertTrue(sem2.records.isEmpty())
    }

    @Test
    fun `sample snapshots are explicitly tagged as SAMPLE_PREVIEW and include thesis and qualifier examples`() {
        val snapshots = SampleAgendaRepository.buildDefaultSampleSnapshots()
        assertEquals(2, snapshots.size)

        snapshots.values.forEach { snapshot ->
            assertEquals(DataSourceMode.SAMPLE_PREVIEW, snapshot.dataSourceMode)
            val generalAvg = snapshot.resolveGeneralAverage()
            assertNotNull(generalAvg)
            assertTrue(generalAvg!!.isEstimated)
        }

        val snap11R = snapshots.getValue(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE)
        assertTrue(snap11R.recentMarks.any { it.isThesis })
        assertTrue(snap11R.recentMarks.any { it.value is MarkValue.Qualifier })
    }
}
