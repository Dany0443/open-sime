package md.sime.opensime.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentNotFoundException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.portal.ParsedStudentProfile
import md.sime.opensime.core.portal.SimeAuthCoordinator
import md.sime.opensime.core.portal.SimeNetworkError
import md.sime.opensime.core.portal.SimeServerError
import md.sime.opensime.core.portal.SimeMaintenanceError
import md.sime.opensime.core.portal.SimePortalClient
import md.sime.opensime.core.portal.SimeRateLimitError
import md.sime.opensime.core.portal.SimeStudentMismatchError
import md.sime.opensime.core.portal.SimeStudentNotFoundError
import md.sime.opensime.core.portal.SimeStudentParser

/**
 * Production implementation of [AgendaRepository] reading live data directly from `agenda.sime.md`.
 *
 * Enforces all student isolation and resilience rules (`web/server.js:2539-2640`, `AGENTS.md:149-160`):
 * 1. **Explicit student scoping:** Every request requires an explicit [StudentId]. Never falls back to
 *    "the first student".
 * 2. **Canonical alias mapping:** Normalizes aliases and nav-tree URLs to the canonical student key.
 * 3. **Student-keyed in-memory TTL caching:** Stored snapshots are strictly partitioned by [StudentId].
 *    Fresh entries are served for [overviewTtlMillis] (`60_000ms`).
 * 4. **Resilience & stale-while-error fallback:** When upstream rate limiting or network failure occurs,
 *    or when the circuit breaker is open, an existing cached snapshot for that student is served as stale
 *    instead of failing.
 * 5. **Ownership invariant:** Every returned snapshot is verified to belong to the requested student via
 *    [StudentAgendaSnapshot.verifyStudentOwnership]; mismatches fail with [StudentMismatchException].
 */
class LiveAgendaRepository(
    val client: SimePortalClient,
    val authCoordinator: SimeAuthCoordinator? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    val overviewTtlMillis: Long = DEFAULT_OVERVIEW_TTL_MS,
    private val snapshotStore: AgendaSnapshotStore? = null,
) : AgendaRepository {

    private data class CachedSnapshot(
        val snapshot: StudentAgendaSnapshot,
        val cachedAtEpochMillis: Long,
        val sessionGeneration: Long,
    )

    private val cacheLock = Any()
    private var loggedOut = false
    private val snapshotCache = linkedMapOf<StudentId, CachedSnapshot>()
    private val inFlight = mutableMapOf<Pair<Long, StudentId>, CompletableDeferred<StudentAgendaSnapshot>>()

    override fun listStudents(): List<StudentProfile> {
        if (synchronized(cacheLock) { loggedOut }) return emptyList()
        val coordinatorStudents = authCoordinator?.availableStudents()
        if (!coordinatorStudents.isNullOrEmpty()) {
            return coordinatorStudents
        }
        return client.session.listStudents().map { it.toDomainProfile() }
    }

    override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
        ensureNotLoggedOut()
        val rawInput = studentId.value.trim()
        if (rawInput.isEmpty()) {
            throw StudentNotFoundException(studentId)
        }

        val canonicalKey = client.session.canonicalStudentKey(rawInput)
        if (canonicalKey.isEmpty()) {
            throw StudentNotFoundException(studentId)
        }
        val canonicalId = StudentId(canonicalKey)

        val now = nowMillis()
        val currentGen = client.session.sessionGeneration

        // 1. Check in-memory student-keyed TTL cache for current session generation
        val cached = synchronized(cacheLock) {
            if (loggedOut) throw CancellationException("Logged out")
            val entry = snapshotCache[canonicalId]
            if (entry != null && entry.sessionGeneration == currentGen) entry else null
        } ?: restorePersisted(canonicalId, currentGen)
        if (cached != null && (now - cached.cachedAtEpochMillis) in 0 until overviewTtlMillis) {
            return cached.snapshot.verifyStudentOwnership(canonicalId)
        }

        // 2. Check circuit breakers: serve stale cached data if circuit breaker is tripped (same session only)
        if (cached != null && isBreakerOpen()) {
            return cached.snapshot.verifyStudentOwnership(canonicalId).copy(stale = true)
        }

        return try {
            reconcileStudent(canonicalId)
        } catch (err: Throwable) {
            ensureNotLoggedOut()
            if (cached != null && currentGen == client.session.sessionGeneration && isUpstreamTransientFailure(err)) {
                return cached.snapshot.verifyStudentOwnership(canonicalId).copy(stale = true)
            }
            throw err
        }
    }

    /** One authoritative transaction per student/session; failures never publish partial data. */
    suspend fun reconcileStudent(studentId: StudentId): StudentAgendaSnapshot {
        ensureNotLoggedOut()
        val key = client.session.canonicalStudentKey(studentId.value.trim())
        if (key.isEmpty()) throw StudentNotFoundException(studentId)
        val id = StudentId(key)
        val generation = client.session.sessionGeneration
        val requestKey = generation to id
        val mine = CompletableDeferred<StudentAgendaSnapshot>()
        val request = synchronized(cacheLock) {
            if (loggedOut) throw CancellationException("Logged out")
            inFlight.getOrPut(requestKey) { mine }
        }
        if (request !== mine) return request.await().verifyStudentOwnership(id)
        try {
            val fresh = client.fetchStudentSnapshot(id, forceRefresh = true).verifyStudentOwnership(id)
            currentCoroutineContext().ensureActive()
            val completedAt = nowMillis()
            val verified = fresh.copy(
                stale = false,
                successfulSyncMillis = listOf("grades", "absences", "timetable", "homework", "teachers")
                    .associateWith { completedAt },
            )
            synchronized(cacheLock) {
                if (loggedOut || generation != client.session.sessionGeneration) throw CancellationException("Session changed")
                snapshotCache[id] = CachedSnapshot(verified, completedAt, generation)
                // Logout invalidates under this same lock before deleting account files.
                runCatching { snapshotStore?.write(verified) }
            }
            mine.complete(verified)
            return verified
        } catch (err: Throwable) {
            val failure = when (err) {
                is SimeStudentMismatchError -> StudentMismatchException(err.requestedStudentId ?: id,
                    err.actualStudentId ?: StudentId("unknown"))
                is SimeStudentNotFoundError -> StudentNotFoundException(err.studentId ?: id)
                else -> err
            }
            mine.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(cacheLock) { if (inFlight[requestKey] === mine) inFlight.remove(requestKey) }
        }
    }

    fun cachedSnapshotFor(studentId: StudentId): StudentAgendaSnapshot? {
        if (synchronized(cacheLock) { loggedOut }) return null
        val canonicalKey = client.session.canonicalStudentKey(studentId.value)
        if (canonicalKey.isEmpty()) return null
        val canonicalId = StudentId(canonicalKey)
        val currentGen = client.session.sessionGeneration
        return synchronized(cacheLock) {
            if (loggedOut) return null
            val entry = snapshotCache[canonicalId]
            if (entry != null && entry.sessionGeneration == currentGen) {
                entry.snapshot.verifyStudentOwnership(canonicalId)
            } else {
                null
            }
        } ?: restorePersisted(canonicalId, currentGen)?.snapshot
    }

    fun isBreakerOpen(): Boolean = client.isBreakerOpen()

    fun clearCache() {
        synchronized(cacheLock) {
            snapshotCache.clear()
        }
    }

    fun invalidateForLogout() {
        synchronized(cacheLock) {
            loggedOut = true
            snapshotCache.clear()
            inFlight.values.forEach { it.cancel(CancellationException("Logged out")) }
            inFlight.clear()
        }
    }

    private fun ensureNotLoggedOut() {
        if (synchronized(cacheLock) { loggedOut }) throw CancellationException("Logged out")
    }

    fun clearPersistentCache() {
        snapshotStore?.clear()
    }

    private fun restorePersisted(studentId: StudentId, sessionGeneration: Long): CachedSnapshot? {
        ensureNotLoggedOut()
        val restored = snapshotStore?.read(studentId) ?: return null
        val cachedAt = restored.successfulSyncMillis.values.maxOrNull() ?: 0L
        val entry = CachedSnapshot(restored, cachedAt, sessionGeneration)
        synchronized(cacheLock) {
            if (loggedOut) return null
            val existing = snapshotCache[studentId]
            if (existing == null || existing.sessionGeneration != sessionGeneration) {
                snapshotCache[studentId] = entry
                return entry
            }
            return existing
        }
    }

    private fun isUpstreamTransientFailure(err: Throwable): Boolean =
        err is SimeRateLimitError || err is SimeNetworkError || err is SimeServerError || err is SimeMaintenanceError

    private fun ParsedStudentProfile.toDomainProfile(): StudentProfile {
        val canonicalKey = client.session.canonicalStudentKey(id).ifEmpty { SimeStudentParser.studentKey(id) }
        val canonicalId = StudentId(canonicalKey)
        val cachedSnap = synchronized(cacheLock) { snapshotCache[canonicalId]?.snapshot }
        return StudentProfile(
            id = canonicalId,
            fullName = fullName,
            className = className ?: cachedSnap?.student?.className.orEmpty(),
            schoolName = schoolName ?: cachedSnap?.student?.schoolName.orEmpty(),
            classMasterName = cachedSnap?.student?.classMasterName.orEmpty(),
            currentSemester = cachedSnap?.student?.currentSemester ?: 1,
            academicYear = cachedSnap?.student?.academicYear.orEmpty(),
            enrollmentStatus = cachedSnap?.student?.enrollmentStatus,
            birthDate = cachedSnap?.student?.birthDate,
        )
    }

    companion object {
        const val DEFAULT_OVERVIEW_TTL_MS: Long = 60_000L
    }
}
