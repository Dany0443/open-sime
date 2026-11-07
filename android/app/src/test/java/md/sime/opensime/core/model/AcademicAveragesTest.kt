package md.sime.opensime.core.model

import org.junit.Assert.*
import org.junit.Test

class AcademicAveragesTest {
    private fun mark(id: String, n: Double, semester: Int = 1, thesis: Boolean = false) =
        MarkItem(id, "Synthetic", MarkValue.Numeric(n), "2026-10-01", semester, isThesis = thesis)
    private val regular = SemesterGradeEvidence(SchoolLevel.GYMNASIUM, ThesisRequirement.NONE,
        complete = true, classificationsConfirmed = true, deduplicated = true)
    private val thesis = regular.copy(level = SchoolLevel.LYCEUM, thesisRequirement = ThesisRequirement.REQUIRED)
    private fun subject(marks: List<MarkItem>, evidence: SemesterGradeEvidence = regular) =
        SubjectMarksSummary("Synthetic", "", 1, marks, semesterEvidence = mapOf(1 to evidence))

    @Test fun truncatesDecimalMeansAndSeparatesSemesters() {
        val result = subject(listOf(mark("a", 8.0), mark("b", 9.0), mark("c", 9.0), mark("other", 1.0, 2)))
        assertEquals(8.66, result.resolveSemesterAverage(1)!!.value, 0.0)
        assertNull(result.resolveSemesterAverage(2))
    }

    @Test fun thesisUsesHalfWeightAfterRegularMeanIsTruncated() {
        val result = subject(listOf(mark("a", 7.0), mark("b", 8.0), mark("t", 8.0, thesis = true)), thesis)
        assertEquals(7.75, result.resolveSemesterAverage(1)!!.value, 0.0)
        assertTrue(result.resolveSemesterAverage(1)!!.isEstimated)
        val fraction = subject(listOf(mark("a", 7.0), mark("b", 7.0), mark("c", 8.0), mark("t", 8.0, thesis = true)), thesis)
        assertEquals(7.66, fraction.resolveSemesterAverage(1)!!.value, 0.0)
    }

    @Test fun missingEvidenceAndUnsafeThesisSituationsAreUnavailable() {
        val marks = listOf(mark("a", 7.0), mark("b", 8.0), mark("t", 8.0, thesis = true))
        assertNull(SubjectMarksSummary("Synthetic", "", 1, marks).resolveSemesterAverage(1))
        assertNull(subject(marks, thesis.copy(classificationsConfirmed = false)).resolveSemesterAverage(1))
        assertNull(subject(marks, thesis.copy(complete = false)).resolveSemesterAverage(1))
        assertNull(subject(marks, thesis.copy(deduplicated = false)).resolveSemesterAverage(1))
        assertNull(subject(marks.dropLast(1), thesis).resolveSemesterAverage(1))
        assertNull(subject(marks.dropLast(1) + mark("t", 4.0, thesis = true), thesis).resolveSemesterAverage(1))
        assertNull(subject(marks + mark("t2", 8.0, thesis = true), thesis).resolveSemesterAverage(1))
        assertNull(subject(marks, regular).resolveSemesterAverage(1))
    }

    @Test fun qualifiersAndUnknownNumericRangesDoNotYieldNumericResults() {
        val invalid = subject(listOf(mark("a", 0.0), mark("b", 8.0)))
        assertNull(invalid.resolveSemesterAverage(1))
        val qualifying = subject(listOf(mark("a", 8.0), mark("b", 9.0))).copy(scale = SubjectScale.QUALIFIER)
        assertNull(qualifying.resolveSemesterAverage(1))
        val unknown = subject(listOf(mark("a", 8.0), MarkItem("b", "Synthetic", MarkValue.Other("unknown"), "", 1)))
        assertNull(unknown.resolveSemesterAverage(1))
    }

    @Test fun subjectMeansAreEquallyWeightedAndIncompleteSubjectsBlockAggregate() {
        val a = subject(listOf(mark("a", 7.0), mark("b", 8.0)))
        val b = subject(listOf(mark("c", 10.0), mark("d", 10.0), mark("e", 10.0)))
            .let { it.copy(subject = "B", marks = it.marks.map { mark -> mark.copy(subject = "B") }) }
        val snapshot = md.sime.opensime.core.data.SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
            .copy(subjectSummaries = listOf(a, b), gradesCoverageComplete = true)
        assertEquals(8.75, snapshot.resolveSemesterAverage(1)!!.value, 0.0)
        assertNull(snapshot.copy(subjectSummaries = listOf(a, b.copy(semesterEvidence = emptyMap()))).resolveSemesterAverage(1))
        assertNull(snapshot.resolveGeneralAverage())
    }

    @Test fun provisionalAverageUsesAvailableSubjectsWithoutThesisOrCompleteCoverage() {
        val oneMark = subject(listOf(mark("a", 8.0)))
        val twoMarks = subject(listOf(mark("b", 9.0), mark("c", 10.0)))
            .copy(subject = "B", marks = listOf(mark("b", 9.0), mark("c", 10.0)).map { it.copy(subject = "B") })
        val snapshot = md.sime.opensime.core.data.SampleAgendaRepository.buildDefaultSampleSnapshots().values.first().copy(
            subjectSummaries = listOf(oneMark, twoMarks),
            gradesCoverageComplete = false,
        )

        val result = snapshot.resolveProvisionalGeneralAverage()

        assertNotNull(result)
        assertEquals(8.75, result!!.value, 0.0)
        assertEquals(2, result.includedSubjects)
        assertTrue(result.isEstimated)
    }

    @Test fun provisionalAverageExcludesEmptyQualifierAndUnknownSemesterSubjects() {
        val qualifier = SubjectMarksSummary(
            subject = "Educație muzicală",
            teacherName = "",
            semester = 1,
            marks = listOf(MarkItem("q", "Educație muzicală", MarkValue.Qualifier(QualifierCode.B), "2026-10-01", 1)),
            scale = SubjectScale.QUALIFIER,
        )
        val unknownMark = mark("unknown", 9.0, semester = 0).copy(subject = "Unknown")
        val unknown = subject(listOf(unknownMark)).copy(subject = "Unknown", marks = listOf(unknownMark))
        val snapshot = md.sime.opensime.core.data.SampleAgendaRepository.buildDefaultSampleSnapshots().values.first().copy(
            subjectSummaries = listOf(qualifier, unknown),
            gradesCoverageComplete = false,
        )

        assertNull(snapshot.resolveProvisionalGeneralAverage())
    }

    @Test fun annualMeanUsesSemesterMeansRatherThanPooledMarks() {
        val marks = listOf(mark("a", 7.0), mark("b", 8.0),
            mark("c", 9.0, 2), mark("d", 9.0, 2), mark("e", 9.0, 2))
        val result = subject(marks).copy(semesterEvidence = mapOf(1 to regular, 2 to regular))
        assertEquals(8.25, result.resolveAverage()!!.value, 0.0)
    }

    @Test fun duplicateOrUnclassifiedThesisRepresentationsCannotBeCalculated() {
        val marks = listOf(mark("a", 7.0), mark("b", 8.0),
            mark("duplicate", 8.0), mark("duplicate", 8.0, thesis = true))
        assertNull(subject(marks, thesis).resolveSemesterAverage(1))
        assertNull(subject(marks.mapIndexed { i, m -> m.copy(id = "entry-$i") },
            thesis.copy(deduplicated = false)).resolveSemesterAverage(1))
    }

    @Test fun decimalBoundariesTruncateWithoutBinaryRounding() {
        assertEquals(7.99, subject(listOf(mark("a", 7.99), mark("b", 8.0))).resolveSemesterAverage(1)!!.value, 0.0)
        assertEquals(8.00, subject(listOf(mark("a", 8.0), mark("b", 8.01))).resolveSemesterAverage(1)!!.value, 0.0)
    }

    @Test fun unverifiedPortalCandidatesNeverOverrideEvidenceOrSupplyMissingPeriods() {
        val s = subject(listOf(mark("a", 7.0), mark("b", 8.0))).copy(
            portalAverage = 10.0, portalSemesterAverageCandidates = mapOf(1 to 10.0, 2 to 10.0),
            portalAnnualAverageCandidate = 10.0)
        assertEquals(7.5, s.resolveSemesterAverage(1)!!.value, 0.0)
        assertNull(s.resolveSemesterAverage(2))
        assertNull(s.resolveAverage())
        assertNull(s.copy(semesterEvidence = emptyMap()).resolveSemesterAverage(1))
    }

    @Test fun unknownEligibilityAndInvalidValuesFailClosed() {
        val marks = listOf(mark("a", 7.0), mark("b", 8.0))
        assertNull(subject(marks, regular.copy(thesisRequirement = ThesisRequirement.UNKNOWN)).resolveSemesterAverage(1))
        for (invalid in listOf(-1.0, 10.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            try { MarkValue.Numeric(invalid); fail("Invalid numeric mark was accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertNull(subject(emptyList()).resolveSemesterAverage(1))
        assertNull(subject(marks + mark("unknown-period", 9.0, 0)).resolveSemesterAverage(1))
    }
}
