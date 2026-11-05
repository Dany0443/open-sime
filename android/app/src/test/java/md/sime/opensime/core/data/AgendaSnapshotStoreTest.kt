package md.sime.opensime.core.data

import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.SchoolLevel
import md.sime.opensime.core.model.SemesterGradeEvidence
import md.sime.opensime.core.model.ThesisRequirement
import md.sime.opensime.core.portal.StandardAesGcmVaultCipher
import md.sime.opensime.core.portal.VaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AgendaSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = StandardAesGcmVaultCipher()
    private val samples = SampleAgendaRepository.buildDefaultSampleSnapshots()
    private val original = samples.getValue(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
    private val snapshot = original.copy(
        recentMarks = original.recentMarks + original.recentMarks.first().copy(
            id = "synthetic-decimal", value = MarkValue.Numeric(8, 8.25),
        ),
        lessonsByDate = mapOf("2026-11-02" to original.todayLessons, "2026-11-03" to emptyList()),
        successfulSyncMillis = mapOf("grades" to 1000L, "absences" to 2000L, "timetable" to 3000L),
        stale = true,
    )

    @Test
    fun `legacy snapshots retain grades but do not gain academic evidence`() {
        val encoded = SnapshotCodec.encode(snapshot).toString(Charsets.UTF_8)
        val legacy = encoded.replace(",\"semesterEvidence\":{}", "")
            .replace(",\"portalSemesterAverageCandidates\":{}", "")
            .replace(",\"portalAnnualAverageCandidate\":null", "")
            .replace(",\"gradesCoverageComplete\":false", "")
        assertFalse(legacy.contains("semesterEvidence"))
        val decoded = SnapshotCodec.decode(legacy.toByteArray(Charsets.UTF_8))
        assertEquals(snapshot, decoded)
        assertFalse(decoded.gradesCoverageComplete)
        assertNull(decoded.resolveGeneralAverage())
    }

    @Test
    fun `academic evidence and separate period candidates roundtrip per student`() {
        val summary = snapshot.subjectSummaries.first().copy(
            semesterEvidence = mapOf(1 to SemesterGradeEvidence(SchoolLevel.LYCEUM,
                ThesisRequirement.REQUIRED, true, true, true)),
            portalSemesterAverageCandidates = mapOf(1 to 7.75, 2 to 8.5),
            portalAnnualAverageCandidate = 8.12,
        )
        val one = snapshot.copy(subjectSummaries = listOf(summary), gradesCoverageComplete = true)
        val sibling = samples.getValue(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE)
        val store = AgendaSnapshotStore(temporary.newFolder(), cipher, "synthetic-account-a")
        store.write(one)
        store.write(sibling)
        assertEquals(one, store.read(one.studentId))
        assertEquals(sibling, store.read(sibling.studentId))
        assertFalse(store.read(sibling.studentId)!!.gradesCoverageComplete)
    }

    @Test
    fun `copied ciphertext cannot become another accounts offline cache`() {
        val dir = temporary.newFolder()
        val a = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        val b = AgendaSnapshotStore(dir, cipher, "synthetic-account-b")
        a.write(snapshot)
        val original = dir.walkTopDown().first { it.extension == "snapshot" }
        val otherDirectory = java.io.File(dir, b.accountStorageId).apply { mkdirs() }
        original.copyTo(java.io.File(otherDirectory, original.name))
        assertNull(b.read(snapshot.studentId))
        assertTrue(b.list().isEmpty())
        assertEquals(snapshot, a.read(snapshot.studentId))
    }

    @Test
    fun `renamed snapshot cannot advertise another student during offline enumeration`() {
        val dir = temporary.newFolder()
        val store = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        store.write(snapshot)
        val original = dir.walkTopDown().first { it.extension == "snapshot" }
        original.renameTo(java.io.File(original.parentFile, "wrong-student.snapshot"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `encrypted snapshot roundtrips all fields across a fresh store instance`() {
        val dir = temporary.newFolder()
        AgendaSnapshotStore(dir, cipher, "synthetic-account-a").write(snapshot)
        val restarted = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        assertEquals(snapshot, restarted.read(snapshot.studentId))
        assertEquals(listOf(snapshot), restarted.list())
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val stored = file.readBytes().toString(Charsets.UTF_8)
            assertFalse(stored.contains(snapshot.student.fullName))
            assertFalse(stored.contains("synthetic-decimal"))
            assertFalse(file.name.contains(snapshot.studentId.value))
        }
    }

    @Test
    fun `different account cannot read same student identifier or enumerate another account`() {
        val dir = temporary.newFolder()
        AgendaSnapshotStore(dir, cipher, "synthetic-account-a").write(snapshot)
        val other = AgendaSnapshotStore(dir, cipher, "synthetic-account-b")
        assertNull(other.read(snapshot.studentId))
        assertTrue(other.list().isEmpty())
    }

    @Test
    fun `students remain isolated when records share a storage directory`() {
        val store = AgendaSnapshotStore(temporary.newFolder(), cipher, "synthetic-account-a")
        val sibling = samples.getValue(SampleAgendaRepository.STUDENT_ELEVENTH_GRADE)
        store.write(snapshot)
        store.write(sibling)
        assertEquals(snapshot, store.read(snapshot.studentId))
        assertEquals(sibling, store.read(sibling.studentId))
        assertNull(store.read(StudentId("synthetic-missing")))
        assertEquals(setOf(snapshot, sibling), store.list().toSet())
    }

    @Test
    fun `corrupt ciphertext returns unavailable and is excluded from listing`() {
        val dir = temporary.newFolder()
        val store = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        store.write(snapshot)
        dir.walkTopDown().filter { it.isFile }.forEach { it.writeBytes(byteArrayOf(0, 1, 2)) }
        assertNull(store.read(snapshot.studentId))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `wrong encryption key returns unavailable without exposing plaintext`() {
        val dir = temporary.newFolder()
        AgendaSnapshotStore(dir, cipher, "synthetic-account-a").write(snapshot)
        val wrongKey = AgendaSnapshotStore(dir, StandardAesGcmVaultCipher(), "synthetic-account-a")
        assertNull(wrongKey.read(snapshot.studentId))
        assertTrue(wrongKey.list().isEmpty())
    }

    @Test
    fun `clear removes only the intended account and preserves another account`() {
        val dir = temporary.newFolder()
        val a = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        val b = AgendaSnapshotStore(dir, cipher, "synthetic-account-b")
        val other = snapshot.copy(student = snapshot.student.copy(className = "other class"))
        a.write(snapshot)
        b.write(other)
        a.clear()
        assertNull(a.read(snapshot.studentId))
        assertTrue(a.list().isEmpty())
        assertEquals(other, b.read(snapshot.studentId))
    }

    @Test
    fun `account deletion failure is reported and can be retried`() {
        val dir = temporary.newFolder()
        var canDelete = false
        val store = AgendaSnapshotStore(dir, cipher, "synthetic-account-a",
            deleteAccountDirectory = { if (canDelete) it.deleteRecursively() else false })
        store.write(snapshot)
        assertTrue(runCatching { store.clear() }.isFailure)
        assertEquals(snapshot, store.read(snapshot.studentId))
        canDelete = true
        store.clear()
        store.clear()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `pending logout cleanup accepts only scoped hashes and preserves other accounts`() {
        val dir = temporary.newFolder()
        val a = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        val b = AgendaSnapshotStore(dir, cipher, "synthetic-account-b")
        a.write(snapshot)
        b.write(snapshot)
        assertTrue(runCatching { AgendaSnapshotStore.clearPendingLogout(dir, "../other") }.isFailure)
        assertEquals(snapshot, a.read(snapshot.studentId))
        AgendaSnapshotStore.clearPendingLogout(dir, a.accountStorageId)
        AgendaSnapshotStore.clearPendingLogout(dir, a.accountStorageId)
        assertTrue(a.list().isEmpty())
        assertEquals(snapshot, b.read(snapshot.studentId))
    }

    @Test
    fun `failed encryption leaves previous valid snapshot intact`() {
        val dir = temporary.newFolder()
        val store = AgendaSnapshotStore(dir, cipher, "synthetic-account-a")
        store.write(snapshot)
        val failingCipher = object : VaultCipher {
            override fun encrypt(plainText: ByteArray): Pair<ByteArray, ByteArray> = throw IOException("Synthetic write failure")
            override fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray = cipher.decrypt(iv, cipherText)
        }
        val failing = AgendaSnapshotStore(dir, failingCipher, "synthetic-account-a")
        runCatching { failing.write(snapshot.copy(recentMarks = emptyList())) }
        assertEquals(snapshot, store.read(snapshot.studentId))
    }
}
