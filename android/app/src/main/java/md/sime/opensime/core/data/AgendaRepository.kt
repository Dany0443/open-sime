package md.sime.opensime.core.data

import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentProfile

/**
 * Data boundary for reading student-scoped agenda snapshots.
 * Every student-scoped call requires an explicit [StudentId] and verifies that the returned
 * snapshot belongs to that student before returning.
 */
interface AgendaRepository {
    /**
     * Lists the students available in the current session/preview.
     */
    fun listStudents(): List<StudentProfile>

    /**
     * Returns the verified [StudentAgendaSnapshot] for [studentId].
     * Never falls back to "the first student"; throws [md.sime.opensime.core.model.StudentNotFoundException]
     * if [studentId] is unknown or [md.sime.opensime.core.model.StudentMismatchException] on ownership mismatch.
     */
    suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot
}

/** Read-only snapshots already authorized and verified against one account's encrypted store. */
internal class CachedAgendaRepository(snapshots: List<StudentAgendaSnapshot>) : AgendaRepository {
    private val snapshotsByStudent = snapshots.associate { it.studentId to it.copy(stale = true) }
    override fun listStudents(): List<StudentProfile> = snapshotsByStudent.values.map { it.student }
    override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot =
        snapshotsByStudent[studentId]?.verifyStudentOwnership(studentId)
            ?: throw md.sime.opensime.core.model.StudentNotFoundException(studentId)
}

