package md.sime.opensime

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import md.sime.opensime.core.data.*
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.model.*
import md.sime.opensime.core.portal.*

class OpenSimeRetainedSession(
    private val workScope: CoroutineScope? = null,
    private val snapshotCipher: VaultCipher = AndroidKeyStoreVaultCipher(),
) : ViewModel() {
    val auth = SimeAuthCoordinator()
    var repository by mutableStateOf<AgendaRepository?>(null)
    var snapshotStore by mutableStateOf<AgendaSnapshotStore?>(null)
    var logoutRequested by mutableStateOf(false)
    var logoutInProgress by mutableStateOf(false)
        private set
    var logoutError by mutableStateOf<Throwable?>(null)
        private set
    var unknownLogoutCache by mutableStateOf(false)
        private set
    var recoveryCompleted by mutableStateOf(false)
        private set
    var sessionGeneration = 0L
        private set
    private val jobsLock = Any()
    private val sessionJobs = mutableSetOf<Job>()
    private var pendingSnapshotStore: AgendaSnapshotStore? = null

    fun trackSessionJob(job: Job) {
        synchronized(jobsLock) { sessionJobs.add(job) }
        job.invokeOnCompletion { synchronized(jobsLock) { sessionJobs.remove(job) } }
    }

    var isLiveAuthenticated by mutableStateOf(false)
        private set
    var isRestoringSession by mutableStateOf(false)
        private set
    var restorationError by mutableStateOf<Throwable?>(null)
        private set
    var isAuthenticating by mutableStateOf(false)
        private set
    var authError by mutableStateOf<Throwable?>(null)
        private set
    var loginRequired by mutableStateOf(false)
        private set
    var currentSnapshot by mutableStateOf<StudentAgendaSnapshot?>(null)
        private set
    internal var completedGradeSnapshot by mutableStateOf<StudentAgendaSnapshot?>(null)
        private set
    var selectedStudentIdValue by mutableStateOf("")
        private set
    var loadError by mutableStateOf<Throwable?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    private var restorationAttempted = false
    private var restorationEligibilityChecked = false
    private var restorationJob: Job? = null
    private var dataJob: Job? = null
    private var dataCoordinator: OpenSimeSessionCoordinator? = null
    private val scope get() = workScope ?: viewModelScope

    fun dismissLogin() {
        authError = null
        loginRequired = false
    }

    private fun launchSessionWork(block: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.LAZY) { block() }.also(::trackSessionJob)
    private fun store(context: Context, username: String) = AgendaSnapshotStore(
        File(context.filesDir, "agenda-snapshots"), snapshotCipher, username.trim())
    private suspend fun ensureCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != sessionGeneration) throw CancellationException("Session changed")
    }

    /** Resume a recorded logout without ever authenticating from saved credentials. */
    fun recoverPendingLogout(context: Context, coordinator: SimeAuthCoordinator, vault: SimeCredentialVault): Job? {
        if (logoutInProgress || logoutRequested) return null
        val generation = sessionGeneration
        return launchSessionWork {
            try {
                if (withContext(Dispatchers.IO) { OpenSimeSettingsState.hasAuthTransition(context) }) {
                    withContext(Dispatchers.IO) { runCatching { OpenSimeSettingsState.markInterruptedAuthUnknown(context) } }
                    ensureCurrent(generation)
                    unknownLogoutCache = true
                    logoutError = IllegalStateException("Local logout cleanup incomplete")
                    loginRequired = true
                    return@launchSessionWork
                }
                val (showLogin, needsCleanup) = withContext(Dispatchers.IO) {
                    val guard = OpenSimeSettingsState.isExplicitlyLoggedOut(context)
                    val pending = OpenSimeSettingsState.pendingLogoutCache(context) != null
                    val pendingCredentials = vault.hasPendingCredentialCleanup()
                    val pendingOwnership = OpenSimeSettingsState.hasPendingOwnershipCheck(context)
                    val unknown = OpenSimeSettingsState.hasUnknownLogoutCache(context)
                    (guard || pending || pendingCredentials || pendingOwnership || unknown) to
                        (pending || pendingCredentials || pendingOwnership || (guard && vault.hasSavedCredentials()))
                }
                ensureCurrent(generation)
                unknownLogoutCache = withContext(Dispatchers.IO) { OpenSimeSettingsState.hasUnknownLogoutCache(context) }
                ensureCurrent(generation)
                if (unknownLogoutCache && !needsCleanup) logoutError = IllegalStateException("Local logout cleanup incomplete")
                if (showLogin) loginRequired = true
                if (needsCleanup) logout(context, coordinator, vault)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (generation == sessionGeneration) logoutError = IllegalStateException("Local logout cleanup incomplete")
            }
        }.also { it.start() }
    }

    fun restore(context: Context, coordinator: SimeAuthCoordinator, vault: SimeCredentialVault, retry: Boolean = false): Job? {
        if (isAuthenticating || isRestoringSession || logoutRequested || logoutInProgress || logoutError != null ||
            (repository != null && repository !is CachedAgendaRepository &&
                (repository !is LiveAgendaRepository || coordinator.isAuthenticated)) ||
            (restorationAttempted && !retry)) return restorationJob
        if (retry) {
            sessionGeneration += 1L
            dataJob?.cancel()
            (repository as? LiveAgendaRepository)?.invalidateForLogout()
        }
        restorationAttempted = true
        restorationEligibilityChecked = false
        isRestoringSession = true
        isLiveAuthenticated = false
        restorationError = null
        val generation = sessionGeneration
        restorationJob = launchSessionWork {
            try {
                if (withContext(Dispatchers.IO) { OpenSimeSettingsState.hasAuthTransition(context) }) {
                    withContext(Dispatchers.IO) { runCatching { OpenSimeSettingsState.markInterruptedAuthUnknown(context) } }
                    ensureCurrent(generation)
                    unknownLogoutCache = true
                    logoutError = IllegalStateException("Local logout cleanup incomplete")
                    loginRequired = true
                    return@launchSessionWork
                }
                val eligibility = withContext(Dispatchers.IO) {
                    Triple(OpenSimeSettingsState.isExplicitlyLoggedOut(context),
                        OpenSimeSettingsState.pendingLogoutCache(context) != null || vault.hasPendingCredentialCleanup() ||
                            OpenSimeSettingsState.hasPendingOwnershipCheck(context) ||
                            OpenSimeSettingsState.hasUnknownLogoutCache(context),
                        vault.hasSavedCredentials())
                }
                ensureCurrent(generation)
                val acknowledged = withContext(Dispatchers.IO) {
                    OpenSimeSettingsState.hasUnknownLogoutCache(context) &&
                        OpenSimeSettingsState.hasAcknowledgedUnknownCache(context) &&
                        OpenSimeSettingsState.pendingLogoutCache(context) == null &&
                        !OpenSimeSettingsState.hasPendingOwnershipCheck(context) && !vault.hasPendingCredentialCleanup() &&
                        !vault.hasSavedCredentials()
                }
                ensureCurrent(generation)
                if (acknowledged) {
                    unknownLogoutCache = true
                    loginRequired = true
                    return@launchSessionWork
                }
                if (eligibility.first || eligibility.second) {
                    loginRequired = true
                    if (eligibility.second || eligibility.third) logout(context, coordinator, vault)
                    return@launchSessionWork
                }
                restorationEligibilityChecked = true
                if (!eligibility.third) {
                    if (repository != null) loginRequired = true
                    return@launchSessionWork
                }
                val saved = withContext(Dispatchers.IO) { vault.loadCredentials() } ?: throw SimeCredentialRestoreError()
                ensureCurrent(generation)
                val accountStore = store(context, saved.username)
                snapshotStore = accountStore
                val cached = withContext(Dispatchers.IO) {
                    accountStore.list().filter { it.dataSourceMode == DataSourceMode.LIVE_PORTAL &&
                        it.successfulSyncMillis.values.any { time -> time > 0L } }.sortedBy { it.studentId.value }
                }
                ensureCurrent(generation)
                if (cached.isNotEmpty() && (repository == null || !coordinator.isAuthenticated)) {
                    val initial = cached.firstOrNull { it.studentId.value == selectedStudentIdValue } ?: cached.first()
                    repository = CachedAgendaRepository(cached)
                    currentSnapshot = initial.copy(stale = true)
                    selectStudent(initial.studentId, coordinator = coordinator)
                }
                val authenticated = withTimeoutOrNull(15_000L) {
                    withContext(Dispatchers.IO) { coordinator.login(saved.username, saved.password, saved.role) }
                } ?: throw SimeNetworkError("Session restoration timed out")
                ensureCurrent(generation)
                publishAuthenticated(authenticated, accountStore, coordinator)
            } catch (cancel: CancellationException) {
                if (generation == sessionGeneration) {
                    coordinator.logout()
                    restorationError = SimeNetworkError("Session restoration cancelled")
                }
                throw cancel
            } catch (error: Exception) {
                ensureCurrent(generation)
                restorationError = error
                authError = error
                if (error is SimeLoginRejectedError || error is SimeCredentialRestoreError) {
                    dataJob?.cancel()
                    dataCoordinator?.clear()
                    dataCoordinator = null
                    repository = null
                    completedGradeSnapshot = null
                    currentSnapshot = null
                    selectedStudentIdValue = ""
                    isLiveAuthenticated = false
                    val cleanupFailed = withContext(Dispatchers.IO) {
                        val guard = runCatching { OpenSimeSettingsState.recordExplicitLogout(context) }
                        val deletion = runCatching { vault.clearCredentials() }
                        guard.isFailure || deletion.isFailure
                    }
                    ensureCurrent(generation)
                    if (cleanupFailed) authError = SimeCredentialRestoreError()
                    loginRequired = true
                }
            } finally {
                if (generation == sessionGeneration) isRestoringSession = false
            }
        }
        restorationJob?.start()
        return restorationJob
    }

    fun signIn(context: Context, coordinator: SimeAuthCoordinator, vault: SimeCredentialVault,
        username: String, password: String, role: AccountRole, rememberMe: Boolean,
    ): Job? {
        if (isAuthenticating || (isRestoringSession && !restorationEligibilityChecked) || logoutInProgress || logoutError != null) return null
        sessionGeneration += 1L
        var generation = sessionGeneration
        restorationJob?.cancel()
        dataJob?.cancel()
        (repository as? LiveAgendaRepository)?.invalidateForLogout()
        dataCoordinator?.clear()
        dataCoordinator = null
        repository = null
        completedGradeSnapshot = null
        currentSnapshot = null
        isLiveAuthenticated = false
        isRestoringSession = false
        restorationAttempted = true
        restorationError = null
        authError = null
        isAuthenticating = true
        return launchSessionWork {
            try {
                val pendingCleanup = withContext(Dispatchers.IO) {
                    OpenSimeSettingsState.pendingLogoutCache(context) != null ||
                        OpenSimeSettingsState.hasPendingOwnershipCheck(context) || vault.hasPendingCredentialCleanup()
                }
                ensureCurrent(generation)
                if (pendingCleanup) {
                    logout(context, coordinator, vault)
                    if (logoutError != null) return@launchSessionWork
                    generation = sessionGeneration
                    currentCoroutineContext().ensureActive()
                    isAuthenticating = true
                }
                if (withContext(Dispatchers.IO) { OpenSimeSettingsState.hasAuthTransition(context) }) {
                    withContext(Dispatchers.IO) { runCatching { OpenSimeSettingsState.markInterruptedAuthUnknown(context) } }
                    ensureCurrent(generation)
                    unknownLogoutCache = true
                    logoutError = IllegalStateException("Local logout cleanup incomplete")
                    loginRequired = true
                    return@launchSessionWork
                }
                val authenticated = withContext(Dispatchers.IO) {
                    val transitionContext = currentCoroutineContext()
                    try {
                        OpenSimeSettingsState.beginAuthTransition(context) {
                            transitionContext.ensureActive()
                            if (generation != sessionGeneration) throw CancellationException("Session changed")
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { throw SimeCredentialStorageError() }
                    val result = coordinator.loginWithPersistence(username, password, role, rememberMe && !unknownLogoutCache, vault) {
                        OpenSimeSettingsState.recordVerifiedAuthOwner(context, store(context, username).accountStorageId) {
                            transitionContext.ensureActive()
                            if (generation != sessionGeneration) throw CancellationException("Session changed")
                        }
                    }
                    val persistenceContext = currentCoroutineContext()
                    try { OpenSimeSettingsState.recordSuccessfulLogin(context) {
                        persistenceContext.ensureActive()
                        if (generation != sessionGeneration) throw CancellationException("Session changed")
                    } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        val verifiedAccount = coordinator.logoutPreservingVerifiedAccount()
                        runCatching {
                            OpenSimeSettingsState.recordExplicitLogout(context,
                                verifiedAccount?.let { store(context, it).accountStorageId },
                                ownershipCheckPending = verifiedAccount == null)
                        }
                        runCatching { vault.clearCredentials() }
                        throw SimeCredentialStorageError()
                    }
                    result
                }
                ensureCurrent(generation)
                val accountStore = store(context, username)
                logoutRequested = false
                loginRequired = false
                publishAuthenticated(authenticated, accountStore, coordinator)
            } catch (cancel: CancellationException) {
                if (generation == sessionGeneration) coordinator.logoutPreservingVerifiedAccount()
                throw cancel
            } catch (error: Exception) {
                ensureCurrent(generation)
                if (error is SimePortalError && !coordinator.hasVerifiedCleanupAccount()) withContext(Dispatchers.IO) {
                    val failedContext = currentCoroutineContext()
                    runCatching { OpenSimeSettingsState.cancelUnverifiedAuthTransition(context) {
                        failedContext.ensureActive()
                        if (generation != sessionGeneration) throw CancellationException("Session changed")
                    } }
                }
                ensureCurrent(generation)
                if (error is SimeCredentialStorageError) withContext(Dispatchers.IO) {
                    runCatching { OpenSimeSettingsState.recordExplicitLogout(context) }
                }
                ensureCurrent(generation)
                authError = error
            } finally {
                if (generation == sessionGeneration) {
                    isAuthenticating = false
                    pendingSnapshotStore = null
                }
            }
        }.also { it.start() }
    }

    fun acknowledgeUnknownCache(context: Context, vault: SimeCredentialVault): Job? {
        if (!unknownLogoutCache || isAuthenticating || logoutInProgress) return null
        val generation = sessionGeneration
        return launchSessionWork {
            try {
                withContext(Dispatchers.IO) {
                    check(OpenSimeSettingsState.isExplicitlyLoggedOut(context))
                    check(OpenSimeSettingsState.pendingLogoutCache(context) == null &&
                        !OpenSimeSettingsState.hasPendingOwnershipCheck(context) && !vault.hasPendingCredentialCleanup())
                    vault.clearCredentials()
                    check(!vault.hasSavedCredentials() && !vault.hasPendingCredentialCleanup())
                    OpenSimeSettingsState.acknowledgeUnknownCache(context)
                }
                ensureCurrent(generation)
                logoutError = null
                loginRequired = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (generation == sessionGeneration) logoutError = IllegalStateException("Local logout cleanup incomplete") }
        }.also { it.start() }
    }

    fun recoverUnknownCache(context: Context, coordinator: SimeAuthCoordinator,
        username: String, password: String, role: AccountRole,
    ): Job? {
        if (!unknownLogoutCache || isAuthenticating || logoutInProgress) return null
        isAuthenticating = true
        recoveryCompleted = false
        authError = null
        val generation = sessionGeneration
        return launchSessionWork {
            try {
                withContext(Dispatchers.IO) { coordinator.login(username, password, role) }
                ensureCurrent(generation)
                withContext(Dispatchers.IO) {
                    val account = store(context, username)
                    OpenSimeSettingsState.recordExplicitLogout(context, account.accountStorageId)
                    account.clear()
                    OpenSimeSettingsState.completeLogoutCleanup(context)
                    if (!hasCachedAccountDirectories(context)) OpenSimeSettingsState.setUnknownLogoutCache(context, false)
                }
                ensureCurrent(generation)
                unknownLogoutCache = withContext(Dispatchers.IO) { OpenSimeSettingsState.hasUnknownLogoutCache(context) }
                ensureCurrent(generation)
                logoutError = if (unknownLogoutCache) IllegalStateException("Local logout cleanup incomplete") else null
                recoveryCompleted = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == sessionGeneration) authError = error
            } finally {
                coordinator.logout()
                if (generation == sessionGeneration) isAuthenticating = false
            }
        }.also { it.start() }
    }

    private fun hasCachedAccountDirectories(context: Context): Boolean {
        val root = File(context.filesDir, "agenda-snapshots")
        if (!root.exists()) return false
        return root.listFiles()?.any { it.isDirectory } ?: true
    }

    private fun publishAuthenticated(session: SimeAuthenticatedSession, accountStore: AgendaSnapshotStore,
        coordinator: SimeAuthCoordinator,
    ) {
        val previousAccount = snapshotStore?.accountStorageId
        val selected = selectedStudentIdValue.takeIf { previousAccount == accountStore.accountStorageId &&
            session.students.any { student -> student.id.value == it } } ?: session.initialStudent.id.value
        snapshotStore = accountStore
        completedGradeSnapshot = null
        repository = LiveAgendaRepository(SimePortalClient(coordinator.session), coordinator, snapshotStore = accountStore)
        isLiveAuthenticated = true
        restorationError = null
        authError = null
        loginRequired = false
        currentSnapshot = currentSnapshot?.takeIf { previousAccount == accountStore.accountStorageId && it.studentId.value == selected }
            ?: coordinator.cachedSnapshotFor(StudentId(selected))
        selectStudent(StudentId(selected), forceRefresh = true, coordinator = coordinator)
    }

    fun selectStudent(studentId: StudentId, forceRefresh: Boolean = false, coordinator: SimeAuthCoordinator = auth): Job? {
        val source = repository ?: return null
        if (!forceRefresh && selectedStudentIdValue == studentId.value && dataJob?.isActive == true) return dataJob
        dataJob?.cancel()
        val current = dataCoordinator?.takeIf { it.currentRepository === source }
            ?: OpenSimeSessionCoordinator(source).also { dataCoordinator = it }
        if (selectedStudentIdValue != studentId.value) completedGradeSnapshot = null
        selectedStudentIdValue = studentId.value
        currentSnapshot = current.cachedSnapshotFor(studentId) ?: currentSnapshot?.takeIf { it.studentId == studentId }
        loadError = null
        isLoading = true
        val generation = sessionGeneration
        dataJob = launchSessionWork {
            val job = currentCoroutineContext()[Job]
            val client = (source as? LiveAgendaRepository)?.client
            val gradeJob = client?.let {
                CoroutineScope(currentCoroutineContext()).launch {
                    it.completedGrades.collect { result ->
                        if (result != null && generation == sessionGeneration && dataJob === job &&
                            repository === source && selectedStudentIdValue == studentId.value &&
                            result.generation == it.session.sessionGeneration && result.snapshot.studentId == studentId) {
                            completedGradeSnapshot = result.snapshot.verifyStudentOwnership(studentId)
                        }
                    }
                }
            }
            try {
                val disk = if (source is LiveAgendaRepository) withContext(Dispatchers.IO) { source.cachedSnapshotFor(studentId) } else null
                ensureCurrent(generation)
                if (selectedStudentIdValue != studentId.value || repository !== source || dataJob !== job) return@launchSessionWork
                currentSnapshot = currentSnapshot ?: disk ?: coordinator.cachedSnapshotFor(studentId)
                val result = current.loadStudentSafely(studentId,
                    forceRefresh || (source is LiveAgendaRepository && current.cachedSnapshotFor(studentId) == null))
                ensureCurrent(generation)
                if (selectedStudentIdValue != studentId.value || repository !== source || dataJob !== job) return@launchSessionWork
                result.fold(onSuccess = { currentSnapshot = it; completedGradeSnapshot = null; loadError = null },
                    onFailure = { loadError = it; currentSnapshot = currentSnapshot?.copy(stale = true) })
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                gradeJob?.cancel()
                if (generation == sessionGeneration && dataJob === job) isLoading = false
            }
        }
        dataJob?.start()
        return dataJob
    }

    suspend fun logout(context: Context, coordinator: SimeAuthCoordinator, vault: SimeCredentialVault) {
        if (logoutInProgress || (logoutRequested && logoutError == null && !isAuthenticating &&
                !isRestoringSession && snapshotStore == null && !coordinator.hasVerifiedCleanupAccount())) return
        logoutInProgress = true
        logoutError = null
        logoutRequested = true
        sessionGeneration += 1L
        val verifiedAccount = coordinator.logoutAndGetVerifiedAccount()
        val caller = currentCoroutineContext()[Job]
        val jobs = synchronized(jobsLock) { sessionJobs.filter { it !== caller } }
        jobs.forEach { it.cancel() }
        (repository as? LiveAgendaRepository)?.invalidateForLogout()
        pendingSnapshotStore = verifiedAccount?.let {
            val verifiedStore = store(context, it)
            snapshotStore?.takeIf { current -> current.accountStorageId == verifiedStore.accountStorageId } ?: verifiedStore
        } ?: snapshotStore ?: pendingSnapshotStore
        dataCoordinator?.clear()
        dataCoordinator = null
        completedGradeSnapshot = null
        currentSnapshot = null
        selectedStudentIdValue = ""
        loadError = null
        isLoading = false
        isLiveAuthenticated = false
        isRestoringSession = false
        isAuthenticating = false
        loginRequired = true
        authError = null
        restorationError = null
        repository = null
        snapshotStore = null
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                var failed = false
                fun clean(action: () -> Unit) {
                    try { action() } catch (_: Exception) { failed = true }
                }
                val guardRecorded = runCatching {
                    OpenSimeSettingsState.recordExplicitLogout(context, pendingSnapshotStore?.accountStorageId,
                        ownershipCheckPending = pendingSnapshotStore == null && OpenSimeSettingsState.pendingLogoutCache(context) == null)
                }.isSuccess
                if (!guardRecorded) failed = true
                jobs.forEach { it.join() }
                // Drain the existing APEX operation lock before the final cookie/session wipe.
                coordinator.operationMutex.withLock { coordinator.logout() }
                // Restoration may have been cancelled before it could attach its store.
                // Keep credentials until the recovered account target is durable, so an
                // interrupted cleanup can find the same account in the next process.
                var accountTargetRecorded = guardRecorded
                if (pendingSnapshotStore == null && OpenSimeSettingsState.pendingLogoutCache(context) == null &&
                    !OpenSimeSettingsState.hasAuthTransition(context) && !OpenSimeSettingsState.hasUnknownLogoutCache(context)) {
                    vault.loadCredentials()?.let { saved ->
                        val recovered = store(context, saved.username)
                        pendingSnapshotStore = recovered
                        accountTargetRecorded = runCatching {
                            OpenSimeSettingsState.recordExplicitLogout(context, recovered.accountStorageId)
                        }.isSuccess
                        if (!accountTargetRecorded) failed = true
                    }
                }
                var ownershipCheckRecorded = false
                clean {
                    val unknown = pendingSnapshotStore == null && OpenSimeSettingsState.pendingLogoutCache(context) == null &&
                        hasCachedAccountDirectories(context)
                    if (accountTargetRecorded) {
                        OpenSimeSettingsState.completeOwnershipCheck(context, unknown)
                        ownershipCheckRecorded = true
                    }
                }
                var accountCacheCleared = false
                clean {
                    val store = pendingSnapshotStore
                    if (store != null) store.clear()
                    else OpenSimeSettingsState.pendingLogoutCache(context)?.let { accountStorageId ->
                        AgendaSnapshotStore.clearPendingLogout(File(context.filesDir, "agenda-snapshots"), accountStorageId)
                    }
                    accountCacheCleared = true
                }
                if (ownershipCheckRecorded || (accountCacheCleared && pendingSnapshotStore != null)) clean { vault.clearCredentials() }
                if (!failed) clean { OpenSimeSettingsState.completeLogoutCleanup(context) }
                if (OpenSimeSettingsState.hasUnknownLogoutCache(context) &&
                    !OpenSimeSettingsState.hasAcknowledgedUnknownCache(context)) failed = true
                check(!failed) { "Local logout cleanup incomplete" }
            }
            pendingSnapshotStore = null
        } catch (_: Exception) {
            logoutError = IllegalStateException("Local logout cleanup incomplete")
        } finally {
            unknownLogoutCache = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { OpenSimeSettingsState.hasUnknownLogoutCache(context) }.getOrDefault(false)
            }
            logoutInProgress = false
        }
    }
}
