package md.sime.opensime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import md.sime.opensime.core.data.AgendaRepository
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentNotFoundException
import md.sime.opensime.core.model.StudentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentIsolationTest {

    @Test
    fun `two sample students have distinct profile, class, classMaster, and marks`() = runBlocking {
        val repo = SampleAgendaRepository()
        val snap7A = repo.getStudentSnapshot(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
        val snap11R = repo.getStudentSnapshot(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE)

        assertNotEquals(snap7A.studentId, snap11R.studentId)
        assertNotEquals(snap7A.student.fullName, snap11R.student.fullName)
        assertNotEquals(snap7A.student.className, snap11R.student.className)
        assertNotEquals(snap7A.student.classMasterName, snap11R.student.classMasterName)
        assertNotEquals(snap7A.recentMarks, snap11R.recentMarks)
    }

    @Test
    fun `requesting unknown student throws StudentNotFoundException and never falls back to first student`() {
        val repo = SampleAgendaRepository()
        val unknownId = StudentId("non-existent-student-999")

        val ex = assertThrows(StudentNotFoundException::class.java) {
            runBlocking { repo.getStudentSnapshot(unknownId) }
        }
        assertEquals(unknownId, ex.requestedStudentId)
    }

    @Test
    fun `mismatched student snapshot throws StudentMismatchException and clears active state`() {
        val defaults = SampleAgendaRepository.buildDefaultSampleSnapshots()
        val snap7A = defaults.getValue(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)

        // Intentionally map student 11R's key to student 7A's snapshot to simulate an upstream APEX mismatch
        val brokenRepo = SampleAgendaRepository(
            customSnapshots = mapOf(
                SampleAgendaRepository.STUDENT_SEVENTH_GRADE to snap7A,
                SampleAgendaRepository.STUDENT_ELEVENTH_GRADE to snap7A,
            ),
        )
        val coordinator = OpenSimeSessionCoordinator(brokenRepo)

        val ex = assertThrows(StudentMismatchException::class.java) {
            runBlocking {
                coordinator.selectStudent(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE)
            }
        }
        assertEquals(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE, ex.requestedStudentId)
        assertEquals(SampleAgendaRepository.STUDENT_SEVENTH_GRADE, ex.actualStudentId)
        assertNull(coordinator.activeSnapshot)
        assertTrue(coordinator.lastError is StudentMismatchException)
    }

    @Test
    fun `switching from loaded student A to mismatched or failing student B clears activeSnapshot and never retains student A data`() = runBlocking {
        val defaults = SampleAgendaRepository.buildDefaultSampleSnapshots()
        val snap7A = defaults.getValue(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)

        val brokenRepo = SampleAgendaRepository(
            customSnapshots = mapOf(
                SampleAgendaRepository.STUDENT_SEVENTH_GRADE to snap7A,
                // 11R is mapped to 7A's snapshot (mismatch)
                SampleAgendaRepository.STUDENT_ELEVENTH_GRADE to snap7A,
            ),
        )
        val coordinator = OpenSimeSessionCoordinator(brokenRepo)

        // 1. Load Student 7A cleanly
        val firstResult = coordinator.loadStudentSafely(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
        assertTrue(firstResult.isSuccess)
        assertEquals(SampleAgendaRepository.STUDENT_SEVENTH_GRADE, coordinator.activeSnapshot?.studentId)

        // 2. Switch to Student 11R (which fails with StudentMismatchException)
        val secondResult = coordinator.loadStudentSafely(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE)
        assertTrue(secondResult.isFailure)
        assertTrue(secondResult.exceptionOrNull() is StudentMismatchException)
        // Student 7A's snapshot MUST NOT remain in activeSnapshot!
        assertNull(coordinator.activeSnapshot)
        assertEquals(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE, coordinator.activeStudentId)

        // 3. Switch to an unknown student (which fails with StudentNotFoundException)
        val unknownId = StudentId("student-missing")
        val thirdResult = coordinator.loadStudentSafely(unknownId)
        assertTrue(thirdResult.isFailure)
        assertTrue(thirdResult.exceptionOrNull() is StudentNotFoundException)
        assertNull(coordinator.activeSnapshot)
        assertEquals(unknownId, coordinator.activeStudentId)
    }

    @Test
    fun `selecting a student invokes repository exactly once per uncached student and reuses cache on repeat selection`() = runBlocking {
        val repo = SampleAgendaRepository()
        val coordinator = OpenSimeSessionCoordinator(repo)

        assertEquals(0, coordinator.repositoryLoadCalls)

        val snapA = coordinator.loadStudentSafely(SampleAgendaRepository.STUDENT_SEVENTH_GRADE).getOrThrow()
        assertNotNull(snapA)
        assertEquals(1, coordinator.repositoryLoadCalls)

        // Selecting the same student again without forceRefresh uses the student-keyed cache
        val snapAAgain = coordinator.loadStudentSafely(SampleAgendaRepository.STUDENT_SEVENTH_GRADE).getOrThrow()
        assertEquals(snapA, snapAAgain)
        assertEquals(1, coordinator.repositoryLoadCalls)

        // Switching to the second student invokes repository exactly once more
        val snapB = coordinator.loadStudentSafely(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE).getOrThrow()
        assertNotNull(snapB)
        assertEquals(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE, snapB?.studentId)
        assertEquals(2, coordinator.repositoryLoadCalls)
    }

    @Test
    fun `delayed response for earlier student never overwrites newer student active state`() = runBlocking {
        val defaults = SampleAgendaRepository.buildDefaultSampleSnapshots()
        val studentA = SampleAgendaRepository.STUDENT_SEVENTH_GRADE
        val studentB = SampleAgendaRepository.STUDENT_ELEVENTH_GRADE
        val gateForStudentA = CompletableDeferred<Unit>()

        val delayedRepo = object : AgendaRepository {
            override fun listStudents(): List<StudentProfile> =
                defaults.values.map { it.student }

            override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
                if (studentId == studentA) {
                    gateForStudentA.await()
                }
                return defaults.getValue(studentId).verifyStudentOwnership(studentId)
            }
        }

        val coordinator = OpenSimeSessionCoordinator(delayedRepo)

        // 1. Start loading Student A (slow / delayed)
        val deferredA = async {
            coordinator.selectStudent(studentA, forceRefresh = true)
        }

        // Allow coroutine A to enter getStudentSnapshot and suspend on gateForStudentA
        kotlinx.coroutines.yield()
        assertEquals(studentA, coordinator.activeStudentId)

        // 2. User switches to Student B before Student A's response arrives
        val resultB = coordinator.selectStudent(studentB, forceRefresh = true)
        assertNotNull(resultB)
        assertEquals(studentB, coordinator.activeStudentId)
        assertEquals(studentB, coordinator.activeSnapshot?.studentId)

        // 3. Now Student A's delayed response completes
        gateForStudentA.complete(Unit)
        val resultAfterLateA = deferredA.await()

        // Active student and active snapshot MUST still be Student B!
        assertEquals(studentB, coordinator.activeStudentId)
        assertEquals(studentB, coordinator.activeSnapshot?.studentId)
        assertEquals(studentB, resultAfterLateA?.studentId)

        // Both students are cached under their own distinct keys
        assertEquals(studentA, coordinator.cachedSnapshotFor(studentA)?.studentId)
        assertEquals(studentB, coordinator.cachedSnapshotFor(studentB)?.studentId)
    }
}
