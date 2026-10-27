package md.sime.opensime.core.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Strongly-typed student identifier.
 * Every student-scoped request, cache entry, and UI snapshot is keyed by [StudentId].
 */
@JvmInline
value class StudentId(val value: String) {
    init {
        require(value.isNotBlank()) { "StudentId must not be blank" }
    }
}

/**
 * Thrown when a response or cached snapshot belongs to a different student than requested.
 * Maps to Open SIME's `STUDENT_MISMATCH` invariant.
 */
class StudentMismatchException(
    val requestedStudentId: StudentId,
    val actualStudentId: StudentId,
) : IllegalStateException(
    "STUDENT_MISMATCH: requested student '${requestedStudentId.value}', but snapshot belongs to '${actualStudentId.value}'",
)

/**
 * Thrown when an unknown student is requested.
 */
class StudentNotFoundException(
    val requestedStudentId: StudentId,
) : NoSuchElementException(
    "STUDENT_NOT_FOUND: no student found for id '${requestedStudentId.value}'",
)

/**
 * Identifies whether a snapshot is sample preview data or live portal data.
 */
enum class DataSourceMode {
    SAMPLE_PREVIEW,
    LIVE_PORTAL,
}

/**
 * Portal account role matching `role === "student" ? "student" : "parent"` in `web/server.js`.
 */
enum class AccountRole {
    PARENT,
    STUDENT,
}

/**
 * Standard qualifiers used in Moldovan schools alongside numeric 1..10 grades.
 * Qualifier marks are never counted in numeric averages.
 */
enum class QualifierCode(val code: String) {
    FB("FB"), // Foarte bine
    B("B"),   // Bine
    S("S"),   // Suficient
    I("I"),   // Insuficient
}

/**
 * Grading scale for a discipline, matching `web/public/app.js` (`scaleChip`).
 */
enum class SubjectScale {
    NUMERIC,
    QUALIFIER,
    DESCRIPTOR,
}

/**
 * Semantic performance band matching `bandOf` in `web/public/app.js`.
 */
enum class GradeBand {
    TOP,   // >= 9 or FB
    GOOD,  // >= 7 or B
    OK,    // >= 5 or S
    LOW,   // < 5 or I
    NONE,  // null
    ;

    companion object {
        fun fromAverage(value: Double?): GradeBand = when {
            value == null || value.isNaN() -> NONE
            value >= 9.0 -> TOP
            value >= 7.0 -> GOOD
            value >= 5.0 -> OK
            else -> LOW
        }

        fun fromMark(markValue: MarkValue): GradeBand = when (markValue) {
            is MarkValue.Numeric -> when {
                markValue.decimalScore >= 9.0 -> TOP
                markValue.decimalScore >= 7.0 -> GOOD
                markValue.decimalScore >= 5.0 -> OK
                else -> LOW
            }
            is MarkValue.Qualifier -> when (markValue.qualifier) {
                QualifierCode.FB -> TOP
                QualifierCode.B -> GOOD
                QualifierCode.S -> OK
                QualifierCode.I -> LOW
            }
            is MarkValue.Other -> NONE
        }
    }
}

/**
 * A single mark can be a numeric score (0..10, integer or decimal), a qualifier (`FB`, `B`, `S`, `I`),
 * or an unrecognized/descriptor token (`Other`) preserved verbatim from the portal without counting in numeric averages.
 */
sealed interface MarkValue {
    val displayText: String

    data class Numeric(
        val score: Int,
        val decimalScore: Double = score.toDouble(),
    ) : MarkValue {
        constructor(decimalValue: Double) : this(
            score = decimalValue.roundToInt(),
            decimalScore = decimalValue,
        )

        init {
            require(decimalScore in 0.0..10.0) { "Numeric mark must be between 0 and 10, got $decimalScore" }
            require(score in 0..10 && score == decimalScore.roundToInt()) {
                "Numeric integer score ($score) must match rounded decimalScore ($decimalScore)"
            }
        }

        override val displayText: String
            get() = if (decimalScore == decimalScore.toLong().toDouble()) {
                decimalScore.toLong().toString()
            } else {
                decimalScore.toString()
            }
    }

    data class Qualifier(val qualifier: QualifierCode) : MarkValue {
        override val displayText: String get() = qualifier.code
    }

    data class Other(val label: String) : MarkValue {
        init {
            require(label.isNotBlank()) { "Other mark label must not be blank" }
        }
        override val displayText: String get() = label
    }
}

/**
 * Represents a subject or general average, explicitly tracking whether the portal provided it
 * or whether it was estimated client-side from numeric marks.
 */
data class AverageMetric(
    val value: Double,
    val isEstimated: Boolean,
) {
    fun formatted(locale: Locale = Locale.forLanguageTag("ro-MD")): String =
        String.format(locale, "%.2f", value)
}

/**
 * Student profile metadata.
 * Class, school, and class master (diriginte) belong to the individual student, never to the parent account.
 */
data class StudentProfile(
    val id: StudentId,
    val fullName: String,
    val className: String,
    val schoolName: String,
    val classMasterName: String,
    val currentSemester: Int,
    val academicYear: String,
    val enrollmentStatus: String? = null,
)

data class MarkItem(
    val id: String,
    val subject: String,
    val value: MarkValue,
    val isoDate: String,
    val semester: Int,
    val isThesis: Boolean = false,
    val teacherName: String? = null,
    val topic: String? = null,
)

data class SubjectMarksSummary(
    val subject: String,
    val teacherName: String,
    val semester: Int,
    val marks: List<MarkItem>,
    val portalAverage: Double? = null,
    val scale: SubjectScale = inferSubjectScale(subject, marks),
) {
    /**
     * Returns the portal-provided average if available, or computes an explicitly labeled
     * estimated average from numeric marks only (never counting qualifiers `FB`, `B`, `S`, `I`).
     */
    fun resolveAverage(): AverageMetric? {
        if (portalAverage != null) {
            return AverageMetric(value = roundTwoDecimals(portalAverage), isEstimated = false)
        }
        val numericScores = marks.mapNotNull { (it.value as? MarkValue.Numeric)?.decimalScore }
        if (numericScores.isEmpty()) return null
        val mean = numericScores.average()
        return AverageMetric(value = roundTwoDecimals(mean), isEstimated = true)
    }

    fun marksForSemester(targetSemester: Int): List<MarkItem> =
        marks.filter { it.semester == targetSemester }.sortedBy { it.isoDate }

    companion object {
        fun inferSubjectScale(subject: String, marks: List<MarkItem>): SubjectScale {
            val normalized = subject.lowercase(Locale.ROOT)
                .replace('ș', 's')
                .replace('ş', 's')
                .replace('ț', 't')
                .replace('ţ', 't')
                .replace('ă', 'a')
                .replace('â', 'a')
                .replace('î', 'i')
            if (normalized.contains("educatie pentru societate")) {
                return SubjectScale.DESCRIPTOR
            }
            if (
                normalized.contains("educatie tehnologica") ||
                normalized.contains("educatia tehnologica") ||
                normalized.contains("educatie plastica") ||
                normalized.contains("educatia plastica") ||
                normalized.contains("educatie muzicala") ||
                normalized.contains("educatia muzicala") ||
                normalized.contains("educatie fizica") ||
                normalized.contains("educatia fizica")
            ) {
                return SubjectScale.QUALIFIER
            }
            if (marks.isNotEmpty() && marks.all { it.value is MarkValue.Qualifier }) {
                return SubjectScale.QUALIFIER
            }
            if (marks.isNotEmpty() && marks.all { it.value is MarkValue.Other }) {
                return SubjectScale.DESCRIPTOR
            }
            return SubjectScale.NUMERIC
        }
    }
}

enum class LessonStatus {
    COMPLETED,
    LIVE_NOW,
    NEXT_UP,
    UPCOMING,
}

data class LessonItem(
    val id: String,
    val periodNumber: Int,
    val startTime: String,
    val endTime: String,
    val subject: String,
    val room: String,
    val teacherName: String,
    val topic: String? = null,
    val homeworkSummary: String? = null,
    val status: LessonStatus = LessonStatus.UPCOMING,
    val dayOfWeekIndex: Int = 0, // 0 = Monday .. 4 = Friday
) {
    val startMinutesOfDay: Int? get() = parseHmToMinutes(startTime)
    val endMinutesOfDay: Int? get() = parseHmToMinutes(endTime)
}

enum class AbsenceStatus {
    EXCUSED,
    UNEXCUSED,
}

data class AbsenceItem(
    val id: String,
    val subject: String,
    val isoDate: String,
    val semester: Int,
    val status: AbsenceStatus,
    val periodNumber: Int? = null,
    val periodTime: String? = null,
    val room: String? = null,
    val teacherName: String? = null,
    val note: String? = null,
)

data class AbsenceSummary(
    val totalCount: Int,
    val excusedCount: Int,
    val unexcusedCount: Int,
    val presencePercent: Double? = null,
)

data class SemesterAbsenceBlock(
    val semester: Int,
    val totalCount: Int,
    val excusedCount: Int,
    val unexcusedCount: Int,
    val records: List<AbsenceItem>,
)

data class HomeworkItem(
    val id: String,
    val subject: String,
    val description: String,
    val assignedIsoDate: String,
    val dueIsoDate: String,
    val isDonePreview: Boolean = false,
)

data class TeacherItem(
    val name: String,
    val subjects: List<String>,
    val isClassMaster: Boolean = false,
)

/**
 * Complete student-scoped agenda snapshot.
 * Enforces ownership verification via [verifyStudentOwnership].
 */
data class StudentAgendaSnapshot(
    val student: StudentProfile,
    val dataSourceMode: DataSourceMode,
    val referenceIsoDate: String,
    val referenceTimeHm: String,
    val recentMarks: List<MarkItem>,
    val subjectSummaries: List<SubjectMarksSummary>,
    val todayLessons: List<LessonItem>,
    val weekLessonsByDay: Map<Int, List<LessonItem>>,
    val absenceSummary: AbsenceSummary,
    val absences: List<AbsenceItem>,
    val homeworkItems: List<HomeworkItem>,
    val teachers: List<TeacherItem>,
    val weekDayIsoDates: List<String> = listOf(
        "2026-10-26",
        "2026-10-27",
        "2026-10-28",
        "2026-10-29",
        "2026-10-30",
    ),
    val sem1AbsenceSummary: AbsenceSummary? = null,
    val sem2AbsenceSummary: AbsenceSummary? = null,
) {
    val studentId: StudentId get() = student.id

    /**
     * Verifies that this snapshot belongs to [expectedStudentId].
     * Fails closed with [StudentMismatchException] if mismatched.
     */
    fun verifyStudentOwnership(expectedStudentId: StudentId): StudentAgendaSnapshot {
        if (student.id != expectedStudentId) {
            throw StudentMismatchException(
                requestedStudentId = expectedStudentId,
                actualStudentId = student.id,
            )
        }
        return this
    }

    /**
     * Computes the general average across subjects that have a valid numeric average.
     * If any contributing subject average is estimated or no portal general average was supplied,
     * the result is marked `isEstimated = true`.
     */
    fun resolveGeneralAverage(portalGeneralAverage: Double? = null): AverageMetric? {
        if (portalGeneralAverage != null) {
            return AverageMetric(value = roundTwoDecimals(portalGeneralAverage), isEstimated = false)
        }
        val resolvedSubjectAverages = subjectSummaries.mapNotNull { it.resolveAverage() }
        if (resolvedSubjectAverages.isEmpty()) return null
        val mean = resolvedSubjectAverages.map { it.value }.average()
        return AverageMetric(
            value = roundTwoDecimals(mean),
            isEstimated = true,
        )
    }

    /**
     * Computes the estimated average for a specific semester from numeric marks in that semester.
     */
    fun resolveSemesterAverage(semester: Int): AverageMetric? {
        val semesterSubjectMeans = subjectSummaries.mapNotNull { subject ->
            val numericScores = subject.marks
                .filter { it.semester == semester }
                .mapNotNull { (it.value as? MarkValue.Numeric)?.decimalScore }
            if (numericScores.isEmpty()) null else roundTwoDecimals(numericScores.average())
        }
        if (semesterSubjectMeans.isEmpty()) return null
        return AverageMetric(
            value = roundTwoDecimals(semesterSubjectMeans.average()),
            isEstimated = true,
        )
    }

    /**
     * Returns the semester-scoped absence statistics and records matching `absenceSemester(m, n)` in `web/public/app.js`.
     * Preserves portal-provided semester totals ([sem1AbsenceSummary], [sem2AbsenceSummary]) even when per-lesson
     * [records] are gated to empty due to a count mismatch.
     */
    fun absencesForSemester(semester: Int): SemesterAbsenceBlock {
        val semRecords = absences
            .filter { it.semester == semester }
            .sortedByDescending { it.isoDate }
        val explicitSummary = when (semester) {
            1 -> sem1AbsenceSummary
            2 -> sem2AbsenceSummary
            else -> null
        }
        val excused = explicitSummary?.excusedCount ?: semRecords.count { it.status == AbsenceStatus.EXCUSED }
        val unexcused = explicitSummary?.unexcusedCount ?: semRecords.count { it.status == AbsenceStatus.UNEXCUSED }
        val total = explicitSummary?.totalCount ?: (excused + unexcused)
        return SemesterAbsenceBlock(
            semester = semester,
            totalCount = total,
            excusedCount = excused,
            unexcusedCount = unexcused,
            records = semRecords,
        )
    }
}

internal fun roundTwoDecimals(value: Double): Double =
    (value * 100.0).roundToInt() / 100.0

fun parseHmToMinutes(hm: String): Int? {
    val parts = hm.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
