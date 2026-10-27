package md.sime.opensime.core.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentProfile
import java.util.Locale

/**
 * Result of a successful [SimeAuthCoordinator.login] call.
 * Overrides [toString] so student identifiers and session details are redacted.
 */
data class SimeAuthenticatedSession(
    val role: AccountRole,
    val initialStudent: StudentProfile,
    val students: List<StudentProfile>,
    val initialSnapshot: StudentAgendaSnapshot,
) {
    override fun toString(): String =
        "SimeAuthenticatedSession(role=$role, studentCount=${students.size}, initialStudent=${SimeLogScrubber.REDACTED})"
}

/**
 * Isolated authentication and student-context coordinator for direct `agenda.sime.md` access.
 *
 * Enforces all Phase 4 requirements (`web/server.js:1896-2386`, `2552-2640`, `2993-3256`):
 * 1. **In-memory-only authentication:** No passwords, cookies, tokens, or session IDs are persisted.
 * 2. **Serialized lifecycle:** Concurrent `login` and `switchStudent` calls are serialized via [operationMutex].
 * 3. **Repeated-rejection guard:** 4 consecutive rejected login attempts for an account lock login for 60s
 *    with [SimeRateLimitError] (`LOGIN_MAX_REJECTIONS = 4`, `LOGIN_LOCK_MS = 60_000`).
 * 4. **Strict student isolation:**
 *    - Never falls back to the first student when a student ID is missing, blank, or unknown.
 *    - Every returned [StudentAgendaSnapshot] is verified to belong to the requested student; any mismatch
 *      throws [SimeStudentMismatchError] (`SimeErrorCode.STUDENT_MISMATCH`).
 *    - Ticket and generation fencing ensures a late response for student A can never overwrite or be
 *      returned as student B's state.
 * 5. **Terminal cleanup:** Logout, cancellation during login, and terminal authentication errors wipe all
 *    in-memory state, cached snapshots, and cookies.
 */
class SimeAuthCoordinator(
    val session: SimePortalSession = SimePortalSession(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class RejectionGuardEntry(
        var rejections: Int = 0,
        var lockedUntilEpochMillis: Long = 0L,
    )

    private val stateLock = Any()
    val operationMutex: Mutex get() = session.sessionLock

    private var authGeneration: Long = 0L
    private var latestSwitchTicket: Long = 0L
    private var desiredStudentKey: String = ""

    private var _activeStudentId: StudentId? = null
    private var _activeSnapshot: StudentAgendaSnapshot? = null
    private var _lastError: SimePortalError? = null

    private val snapshotsByStudent = linkedMapOf<StudentId, StudentAgendaSnapshot>()
    private val loginGuardByAccount = linkedMapOf<String, RejectionGuardEntry>()

    val isAuthenticated: Boolean
        get() = session.isAuthenticated

    val role: AccountRole?
        get() = session.role

    val activeStudentId: StudentId?
        get() = synchronized(stateLock) { _activeStudentId }

    val activeSnapshot: StudentAgendaSnapshot?
        get() = synchronized(stateLock) { _activeSnapshot }

    val lastError: SimePortalError?
        get() = synchronized(stateLock) { _lastError }

    fun availableStudents(): List<StudentProfile> =
        session.listStudents().map { it.toDomainStudentProfile() }

    fun cachedSnapshotFor(studentId: StudentId): StudentAgendaSnapshot? {
        val canonicalKey = session.canonicalStudentKey(studentId.value)
        if (canonicalKey.isEmpty()) return null
        val canonicalId = StudentId(canonicalKey)
        return synchronized(stateLock) {
            snapshotsByStudent[canonicalId]
        }?.verifyStudentOwnership(canonicalId)
    }

    /**
     * Authenticates with `agenda.sime.md`, verifies the landing dashboard, and populates the initial
     * student's verified [StudentAgendaSnapshot].
     *
     * Serialized via [operationMutex]. On any failure or cancellation, all cookies and session state are cleared.
     */
    suspend fun login(
        username: String,
        password: String,
        role: AccountRole = AccountRole.PARENT,
    ): SimeAuthenticatedSession = operationMutex.withLock {
        val cleanUsername = username.trim()
        val accountKey = cleanUsername.lowercase(Locale.ROOT)

        synchronized(stateLock) {
            _lastError = null
            if (accountKey.isNotEmpty()) {
                val guard = loginGuardByAccount[accountKey]
                val now = nowMillis()
                if (guard != null && guard.lockedUntilEpochMillis > now) {
                    val waitSeconds = kotlin.math.ceil((guard.lockedUntilEpochMillis - now) / 1000.0)
                        .toInt()
                        .coerceAtLeast(1)
                    val err = SimeRateLimitError("Login locked after repeated rejections", waitSeconds)
                    _lastError = err
                    throw err
                }
            }
        }

        val myGeneration = synchronized(stateLock) {
            authGeneration += 1L
            clearCoordinatorStateLocked(keepRejectionGuards = true)
            authGeneration
        }

        try {
            val handshake = session.login(
                username = cleanUsername,
                password = password,
                role = role,
                force = true,
            )
            currentCoroutineContext().ensureActive()

            val initialKey = handshake.initialStudentKey.ifEmpty {
                handshake.students.firstOrNull()?.id?.let { session.canonicalStudentKey(it) }.orEmpty()
            }
            if (initialKey.isEmpty()) {
                throw SimeSessionExpiredError("Portal returned a non-dashboard page")
            }

            val initialSnapshot = session.parseVerifiedSnapshot(
                page = handshake.landingPage,
                requestedStudentId = initialKey,
            )
            val canonicalStudentId = StudentId(initialKey)
            val domainStudents = handshake.students.map { it.toDomainStudentProfile() }
            val initialProfile = domainStudents.firstOrNull { it.id == canonicalStudentId }
                ?: initialSnapshot.student

            synchronized(stateLock) {
                if (authGeneration != myGeneration) {
                    session.clear()
                    throw SimeAppSessionError("Sesiunea a fost închisă în timpul autentificării.")
                }
                loginGuardByAccount.remove(accountKey)
                latestSwitchTicket += 1L
                desiredStudentKey = initialKey
                _activeStudentId = canonicalStudentId
                _activeSnapshot = initialSnapshot
                _lastError = null
                snapshotsByStudent[canonicalStudentId] = initialSnapshot
            }

            SimeAuthenticatedSession(
                role = role,
                initialStudent = initialProfile,
                students = domainStudents,
                initialSnapshot = initialSnapshot,
            )
        } catch (cancel: CancellationException) {
            logout()
            throw cancel
        } catch (err: Throwable) {
            val typed = SimePortalError.fromThrowable(err)
            synchronized(stateLock) {
                if (typed is SimeLoginRejectedError && accountKey.isNotEmpty()) {
                    val entry = loginGuardByAccount.getOrPut(accountKey) { RejectionGuardEntry() }
                    entry.rejections += 1
                    if (entry.rejections >= LOGIN_MAX_REJECTIONS) {
                        entry.lockedUntilEpochMillis = nowMillis() + LOGIN_LOCK_MS
                        entry.rejections = 0
                    }
                }
                clearCoordinatorStateLocked(keepRejectionGuards = true)
                _lastError = typed
            }
            session.clear(preserveRejectedFlag = typed is SimeLoginRejectedError)
            throw typed
        }
    }

    /**
     * Immediately clears all in-memory authentication state, cached student pages/snapshots, and cookies.
     * Mirrors `/api/auth/logout` (`web/server.js:3174-3204`).
     */
    fun logout() {
        synchronized(stateLock) {
            authGeneration += 1L
            clearCoordinatorStateLocked(keepRejectionGuards = true)
        }
        session.clear(preserveRejectedFlag = false)
    }

    /**
     * Switches the active student context to [rawStudentId] and returns that student's verified
     * [StudentAgendaSnapshot].
     *
     * Rules enforced:
     * - Never falls back to the first student when [rawStudentId] is null, blank, or unknown.
     * - Rejects switching when logged in with [AccountRole.STUDENT].
     * - If a newer [switchStudent] call is initiated while an earlier switch for student A is still in flight,
     *   student A's late response is cached under student A's key (if valid) but **never** overwrites
     *   [activeStudentId] or [activeSnapshot] for student B.
     */
    suspend fun switchStudent(
        rawStudentId: String?,
        forceRefresh: Boolean = false,
        gated: Boolean = false,
    ): StudentAgendaSnapshot {
        val cleanInput = rawStudentId?.trim().orEmpty()
        if (!session.isAuthenticated) {
            val err = SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
            synchronized(stateLock) {
                clearCoordinatorStateLocked(keepRejectionGuards = true)
                _lastError = err
            }
            session.clear()
            throw err
        }

        if (session.role == AccountRole.STUDENT) {
            val err = SimeStudentNotFoundError(
                message = "Students cannot switch students",
                studentId = cleanInput.takeIf { it.isNotEmpty() }?.let(::StudentId),
            )
            synchronized(stateLock) {
                _activeSnapshot = null
                _lastError = err
            }
            throw err
        }

        if (cleanInput.isEmpty()) {
            val err = SimeStudentNotFoundError("Student ID is required", studentId = null)
            synchronized(stateLock) {
                _activeSnapshot = null
                _lastError = err
            }
            throw err
        }

        val canonicalKey = session.canonicalStudentKey(cleanInput)
        if (canonicalKey.isEmpty()) {
            val unknownId = StudentId(cleanInput)
            val err = SimeStudentNotFoundError("No such student", studentId = unknownId)
            synchronized(stateLock) {
                _activeStudentId = unknownId
                _activeSnapshot = null
                _lastError = err
            }
            throw err
        }

        val targetStudentId = StudentId(canonicalKey)
        val (myGeneration, myTicket) = synchronized(stateLock) {
            latestSwitchTicket += 1L
            desiredStudentKey = canonicalKey
            _activeStudentId = targetStudentId
            // Clear prior student's active snapshot immediately so stale student data is never exposed
            _activeSnapshot = null
            _lastError = null
            authGeneration to latestSwitchTicket
        }

        return operationMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (authGeneration != myGeneration || !session.isAuthenticated) {
                    throw SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
                }
            }

            try {
                if (!forceRefresh && session.currentStudentKey == canonicalKey) {
                    val cached = synchronized(stateLock) { snapshotsByStudent[targetStudentId] }
                    if (cached != null) {
                        val verified = cached.verifyStudentOwnership(targetStudentId)
                        synchronized(stateLock) {
                            if (authGeneration == myGeneration && latestSwitchTicket == myTicket && desiredStudentKey == canonicalKey) {
                                _activeStudentId = targetStudentId
                                _activeSnapshot = verified
                            }
                        }
                        return@withLock verified
                    }
                }

                val page = session.switchStudent(canonicalKey, gated = gated)
                currentCoroutineContext().ensureActive()

                val verifiedSnapshot = session.parseVerifiedSnapshot(
                    page = page,
                    requestedStudentId = canonicalKey,
                ).verifyStudentOwnership(targetStudentId)

                synchronized(stateLock) {
                    if (authGeneration != myGeneration || !session.isAuthenticated) {
                        throw SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
                    }
                    snapshotsByStudent[targetStudentId] = verifiedSnapshot
                    if (latestSwitchTicket == myTicket && desiredStudentKey == canonicalKey) {
                        _activeStudentId = targetStudentId
                        _activeSnapshot = verifiedSnapshot
                    }
                }

                verifiedSnapshot
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                val typed = SimePortalError.fromThrowable(err)
                handleOperationError(typed, myGeneration, myTicket, targetStudentId)
                throw typed
            }
        }
    }

    /**
     * Convenience overload accepting a typed [StudentId].
     */
    suspend fun switchStudent(
        studentId: StudentId,
        forceRefresh: Boolean = false,
        gated: Boolean = false,
    ): StudentAgendaSnapshot = switchStudent(
        rawStudentId = studentId.value,
        forceRefresh = forceRefresh,
        gated = gated,
    )

    /**
     * Loads and verifies the [StudentAgendaSnapshot] for [rawStudentId] without mutating [activeStudentId]
     * if another student is currently selected as active.
     *
     * Never falls back to the first student when [rawStudentId] is null, blank, or unknown.
     */
    suspend fun getStudentSnapshot(
        rawStudentId: String?,
        forceRefresh: Boolean = false,
        gated: Boolean = false,
    ): StudentAgendaSnapshot {
        val cleanInput = rawStudentId?.trim().orEmpty()
        if (!session.isAuthenticated) {
            val err = SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
            synchronized(stateLock) {
                clearCoordinatorStateLocked(keepRejectionGuards = true)
                _lastError = err
            }
            session.clear()
            throw err
        }
        if (cleanInput.isEmpty()) {
            throw SimeStudentNotFoundError("Student ID is required", studentId = null)
        }
        val canonicalKey = session.canonicalStudentKey(cleanInput)
        if (canonicalKey.isEmpty()) {
            throw SimeStudentNotFoundError("No such student", studentId = StudentId(cleanInput))
        }
        val targetStudentId = StudentId(canonicalKey)
        val myGeneration = synchronized(stateLock) { authGeneration }

        return operationMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (authGeneration != myGeneration || !session.isAuthenticated) {
                    throw SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
                }
                if (!forceRefresh) {
                    val cached = snapshotsByStudent[targetStudentId]
                    if (cached != null) {
                        return@withLock cached.verifyStudentOwnership(targetStudentId)
                    }
                }
            }

            try {
                val page = session.loadStudentPage(
                    rawStudentId = canonicalKey,
                    gated = gated,
                    bypassCache = forceRefresh,
                    assertCurrent = false,
                )
                currentCoroutineContext().ensureActive()

                val verifiedSnapshot = session.parseVerifiedSnapshot(
                    page = page,
                    requestedStudentId = canonicalKey,
                ).verifyStudentOwnership(targetStudentId)

                synchronized(stateLock) {
                    if (authGeneration != myGeneration || !session.isAuthenticated) {
                        throw SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
                    }
                    snapshotsByStudent[targetStudentId] = verifiedSnapshot
                    if (desiredStudentKey == canonicalKey && _activeStudentId == targetStudentId) {
                        _activeSnapshot = verifiedSnapshot
                    }
                }

                verifiedSnapshot
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                val typed = SimePortalError.fromThrowable(err)
                handleOperationError(typed, myGeneration, myTicket = null, targetStudentId = targetStudentId)
                throw typed
            }
        }
    }

    private fun handleOperationError(
        typed: SimePortalError,
        myGeneration: Long,
        myTicket: Long?,
        targetStudentId: StudentId,
    ) {
        val isTerminalAuthFailure = typed is SimeAuthError || typed is SimeForbiddenError
        if (isTerminalAuthFailure) {
            synchronized(stateLock) {
                if (authGeneration == myGeneration) {
                    clearCoordinatorStateLocked(keepRejectionGuards = true)
                    _lastError = typed
                }
            }
            session.clear(preserveRejectedFlag = false)
            return
        }

        synchronized(stateLock) {
            if (authGeneration != myGeneration) return
            if (typed is SimeStudentMismatchError) {
                snapshotsByStudent.remove(targetStudentId)
            }
            if (myTicket == null || latestSwitchTicket == myTicket) {
                if (_activeStudentId == targetStudentId) {
                    _activeSnapshot = null
                }
                _lastError = typed
            }
        }
    }

    private fun clearCoordinatorStateLocked(keepRejectionGuards: Boolean) {
        desiredStudentKey = ""
        _activeStudentId = null
        _activeSnapshot = null
        _lastError = null
        snapshotsByStudent.clear()
        if (!keepRejectionGuards) {
            loginGuardByAccount.clear()
        }
    }

    private fun ParsedStudentProfile.toDomainStudentProfile(): StudentProfile {
        val canonicalKey = session.canonicalStudentKey(id).ifEmpty { SimeStudentParser.studentKey(id) }
        val cachedSnap = synchronized(stateLock) { snapshotsByStudent[StudentId(canonicalKey)] }
        return StudentProfile(
            id = StudentId(canonicalKey),
            fullName = fullName,
            className = className.orEmpty(),
            schoolName = schoolName.orEmpty(),
            classMasterName = cachedSnap?.student?.classMasterName.orEmpty(),
            academicYear = cachedSnap?.student?.academicYear.orEmpty(),
            currentSemester = cachedSnap?.student?.currentSemester ?: 1,
            enrollmentStatus = cachedSnap?.student?.enrollmentStatus,
        )
    }

    override fun toString(): String =
        "SimeAuthCoordinator(isAuthenticated=$isAuthenticated, role=$role, cachedStudentCount=${ synchronized(stateLock) { snapshotsByStudent.size } })"

    companion object {
        const val LOGIN_MAX_REJECTIONS: Int = 4
        const val LOGIN_LOCK_MS: Long = 60_000L
    }
}
