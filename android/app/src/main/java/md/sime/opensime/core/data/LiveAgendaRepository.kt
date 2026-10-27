package md.sime.opensime.core.data

import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentNotFoundException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.portal.ParsedStudentProfile
import md.sime.opensime.core.portal.SimeAuthCoordinator
import md.sime.opensime.core.portal.SimeNetworkError
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
) : AgendaRepository {

    private data class CachedSnapshot(
        val snapshot: StudentAgendaSnapshot,
        val cachedAtEpochMillis: Long,
        val sessionGeneration: Long,
    )

    private val cacheLock = Any()
    private val snapshotCache = linkedMapOf<StudentId, CachedSnapshot>()

    override fun listStudents(): List<StudentProfile> {
        val coordinatorStudents = authCoordinator?.availableStudents()
        if (!coordinatorStudents.isNullOrEmpty()) {
            return coordinatorStudents
        }
        return client.session.listStudents().map { it.toDomainProfile() }
    }

    override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
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
            val entry = snapshotCache[canonicalId]
            if (entry != null && entry.sessionGeneration == currentGen) entry else null
        }
        if (cached != null && (now - cached.cachedAtEpochMillis) < overviewTtlMillis) {
            return cached.snapshot.verifyStudentOwnership(canonicalId)
        }

        // 2. Check circuit breakers: serve stale cached data if circuit breaker is tripped (same session only)
        if (cached != null && isBreakerOpen()) {
            return cached.snapshot.verifyStudentOwnership(canonicalId)
        }

        // 3. Fetch fresh snapshot from the direct portal client
        return try {
            val fresh = client.fetchStudentSnapshot(canonicalId, forceRefresh = false)
            val verified = fresh.verifyStudentOwnership(canonicalId)
            synchronized(cacheLock) {
                snapshotCache[canonicalId] = CachedSnapshot(verified, nowMillis(), client.session.sessionGeneration)
            }
            verified
        } catch (err: Throwable) {
            // Serve stale cache on transient upstream network or rate limit failure (same session only)
            if (cached != null && isUpstreamTransientFailure(err)) {
                return cached.snapshot.verifyStudentOwnership(canonicalId)
            }
            if (err is SimeStudentMismatchError) {
                throw StudentMismatchException(
                    requestedStudentId = err.requestedStudentId ?: canonicalId,
                    actualStudentId = err.actualStudentId ?: StudentId("unknown"),
                )
            }
            if (err is SimeStudentNotFoundError) {
                throw StudentNotFoundException(err.studentId ?: canonicalId)
            }
            throw err
        }
    }

    fun cachedSnapshotFor(studentId: StudentId): StudentAgendaSnapshot? {
        val canonicalKey = client.session.canonicalStudentKey(studentId.value)
        if (canonicalKey.isEmpty()) return null
        val canonicalId = StudentId(canonicalKey)
        val currentGen = client.session.sessionGeneration
        return synchronized(cacheLock) {
            val entry = snapshotCache[canonicalId]
            if (entry != null && entry.sessionGeneration == currentGen) {
                entry.snapshot.verifyStudentOwnership(canonicalId)
            } else {
                null
            }
        }
    }

    fun isBreakerOpen(): Boolean = client.isBreakerOpen()

    fun clearCache() {
        synchronized(cacheLock) {
            snapshotCache.clear()
        }
    }

    private fun isUpstreamTransientFailure(err: Throwable): Boolean =
        err is SimeRateLimitError || err is SimeNetworkError

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
