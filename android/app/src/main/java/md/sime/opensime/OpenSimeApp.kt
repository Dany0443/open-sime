package md.sime.opensime


import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import md.sime.opensime.core.data.AgendaRepository
import md.sime.opensime.core.data.LiveAgendaRepository
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.ErrorStateCard
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.i18n.errorMessageFor
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.portal.SimeAuthCoordinator
import md.sime.opensime.core.portal.SimeCredentialVault
import md.sime.opensime.core.portal.SimeSessionExpiredError
import md.sime.opensime.core.portal.SimeStudentMismatchError
import md.sime.opensime.feature.absences.AbsencesScreen
import md.sime.opensime.feature.auth.LoginScreen
import md.sime.opensime.core.designsystem.PageEntranceScope
import md.sime.opensime.feature.home.HomeScreen
import md.sime.opensime.feature.homework.HomeworkScreen
import md.sime.opensime.feature.marks.MarksScreen
import md.sime.opensime.feature.onboarding.OnboardingScreen
import md.sime.opensime.feature.profile.ProfileScreen
import md.sime.opensime.feature.settings.SettingsDialog
import md.sime.opensime.feature.timetable.TimetableScreen
import md.sime.opensime.navigation.OpenSimeAdaptiveShell
import md.sime.opensime.navigation.TopLevelDestination

private fun debugInspectionBridge(method: String, types: Array<Class<*>>, vararg args: Any) {
    try {
        Class.forName("md.sime.opensime.core.portal.DebugInspectionBridge")
            .getMethod(method, *types).invoke(null, *args)
    } catch (_: Exception) {
        // Debug controls are optional and must not alter the normal app flow.
    }
}

/**
 * Student-isolated state coordinator.
 *
 * Rules enforced:
 * 1. Every request names its [StudentId] explicitly.
 * 2. Every cached snapshot is keyed by [StudentId].
 * 3. Every returned snapshot is verified with [StudentAgendaSnapshot.verifyStudentOwnership].
 * 4. Switching to an uncached student clears [activeSnapshot] before fetching so prior student
 *    data is never retained during loading or after a failure/mismatch.
 * 5. A late/delayed response for one student never overwrites another student's active state.
 */
class OpenSimeSessionCoordinator(
    private var repository: AgendaRepository,
) {
    private val cacheByStudent = mutableMapOf<StudentId, StudentAgendaSnapshot>()
    private var latestRequestTicket: Long = 0L
    private var repositoryGeneration: Long = 0L

    var activeStudentId: StudentId? = null
        private set

    var activeSnapshot: StudentAgendaSnapshot? = null
        private set

    var lastError: Throwable? = null
        private set

    var repositoryLoadCalls: Int = 0
        private set

    val currentRepository: AgendaRepository
        get() = repository

    fun clear() {
        repositoryGeneration += 1L
        latestRequestTicket += 1L
        cacheByStudent.clear()
        activeStudentId = null
        activeSnapshot = null
        lastError = null
    }

    fun switchRepository(newRepository: AgendaRepository) {
        clear()
        repository = newRepository
    }

    fun availableStudents(): List<StudentProfile> = repository.listStudents()

    fun cachedSnapshotFor(studentId: StudentId): StudentAgendaSnapshot? =
        cacheByStudent[studentId]?.verifyStudentOwnership(studentId)

    /**
     * Switches to [studentId] and loads its verified snapshot.
     * If another switch occurs before [repository.getStudentSnapshot] completes,
     * the late response is cached under its own [StudentId] (if valid) but never overwrites
     * [activeSnapshot] for the newer active student.
     */
    suspend fun selectStudent(
        studentId: StudentId,
        forceRefresh: Boolean = false,
    ): StudentAgendaSnapshot? {
        val myGeneration = repositoryGeneration
        latestRequestTicket += 1L
        val myTicket = latestRequestTicket
        activeStudentId = studentId
        lastError = null

        if (!forceRefresh) {
            val cached = cacheByStudent[studentId]
            if (cached != null) {
                return try {
                    val verified = cached.verifyStudentOwnership(studentId)
                    activeSnapshot = verified
                    verified
                } catch (err: Throwable) {
                    cacheByStudent.remove(studentId)
                    activeSnapshot = null
                    lastError = err
                    throw err
                }
            }
        }

        // Clear any previous student's snapshot before loading the new student.
        activeSnapshot = cacheByStudent[studentId]?.verifyStudentOwnership(studentId)

        return try {
            repositoryLoadCalls += 1
            val source = repository
            val loaded = (if (source is LiveAgendaRepository) withContext(Dispatchers.IO) {
                if (forceRefresh) source.reconcileStudent(studentId) else source.getStudentSnapshot(studentId)
            } else source.getStudentSnapshot(studentId)).verifyStudentOwnership(studentId)
            currentCoroutineContext().ensureActive()
            if (myGeneration == repositoryGeneration) {
                cacheByStudent[studentId] = loaded
                if (myTicket == latestRequestTicket && activeStudentId == studentId) {
                    activeSnapshot = loaded
                    loaded
                } else {
                    // A newer student switch superseded this request; keep activeSnapshot untouched.
                    activeSnapshot
                }
            } else {
                // Obsolete response from a prior repository / cleared session. Discard.
                null
            }
        } catch (mismatch: StudentMismatchException) {
            if (myGeneration == repositoryGeneration && myTicket == latestRequestTicket && activeStudentId == studentId) {
                activeSnapshot = null
                lastError = mismatch
            }
            throw mismatch
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Throwable) {
            if (myGeneration == repositoryGeneration && myTicket == latestRequestTicket && activeStudentId == studentId) {
                activeSnapshot = cacheByStudent[studentId]?.copy(stale = true)
                lastError = err
            }
            throw err
        }
    }

    /**
     * Non-throwing wrapper around [selectStudent] for UI callers so failures and
     * [StudentMismatchException] are surfaced as a [Result] without crashing the coroutine scope.
     */
    suspend fun loadStudentSafely(
        studentId: StudentId,
        forceRefresh: Boolean = false,
    ): Result<StudentAgendaSnapshot?> = try {
        Result.success(selectStudent(studentId = studentId, forceRefresh = forceRefresh))
    } catch (cancel: CancellationException) {
        // An upstream single-flight request can be cancelled while this UI caller still owns its load.
        currentCoroutineContext().ensureActive()
        Result.failure(cancel)
    } catch (err: Throwable) {
        Result.failure(err)
    }
}


/**
 * True when the walkthrough owns the screen: either the user reopened it from Login
 * (never persisted), or first-launch onboarding is still incomplete.
 */
internal fun isWalkthroughRoute(
    hasActiveRepository: Boolean,
    hasInjectedRepository: Boolean,
    walkthroughReopened: Boolean,
    onboardingCompleted: Boolean,
): Boolean = (walkthroughReopened && !hasActiveRepository) || (!onboardingCompleted && !hasInjectedRepository)

/**
 * True when Login owns the screen. With no active repository Login is the default route,
 * so there is no signed-out fallback left to fall into.
 */
internal fun isLoginRoute(
    hasActiveRepository: Boolean,
    hasInjectedRepository: Boolean,
    showLoginScreen: Boolean,
    walkthroughReopened: Boolean,
    onboardingCompleted: Boolean,
): Boolean {
    if (walkthroughReopened && !hasActiveRepository) return false
    return showLoginScreen || (!hasActiveRepository && (onboardingCompleted || hasInjectedRepository))
}

@Composable
fun OpenSimeApp(
    repository: AgendaRepository? = null,
    authCoordinator: SimeAuthCoordinator = viewModel<OpenSimeRetainedSession>().auth,
    credentialVault: SimeCredentialVault? = null,
) {
    val context = LocalContext.current
    val effectiveVault = remember(credentialVault) { credentialVault ?: SimeCredentialVault(context) }
    val retained = viewModel<OpenSimeRetainedSession>()
    if (BuildConfig.DEBUG) SideEffect {
        debugInspectionBridge("attach", arrayOf(OpenSimeRetainedSession::class.java, SimeAuthCoordinator::class.java), retained, authCoordinator)
    }
    if (repository != null && retained.repository !== repository && !retained.logoutRequested) retained.repository = repository
    val activeRepository = retained.repository
    val isLiveAuthenticated = retained.isLiveAuthenticated && authCoordinator.isAuthenticated
    val isRestoringSession = retained.isRestoringSession
    var showLoginScreen by rememberSaveable { mutableStateOf(false) }
    var recoveryLogin by rememberSaveable { mutableStateOf(false) }
    // Walkthrough reopened from Login. Pure UI navigation: never persisted, never touches session state.
    var walkthroughReopened by rememberSaveable { mutableStateOf(false) }
    // The first frame precedes the restore effect below; hold the route until restore has been started
    // so neither Login nor the restoring screen is shown for the wrong frame.
    var startupResolved by remember { mutableStateOf(false) }
    val students = remember(activeRepository) { activeRepository?.listStudents().orEmpty() }

    var settings by rememberSaveable(stateSaver = OpenSimeSettingsState.Saver) {
        mutableStateOf(OpenSimeSettingsState.loadFromPreferences(context))
    }

    fun updateSettings(newSettings: OpenSimeSettingsState) {
        settings = newSettings
        OpenSimeSettingsState.saveToPreferences(context, newSettings)
    }

    val selectedStudentIdValue = retained.selectedStudentIdValue
    val currentSnapshot = retained.currentSnapshot
    val loadError = retained.loadError
    val isLoading = retained.isLoading

    var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.HOME) }
    var isProfileSelected by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    val isAuthenticating = retained.isAuthenticating
    val authError = retained.authError

    fun handleSignIn(username: String, password: String, role: AccountRole, rememberMe: Boolean) {
        retained.signIn(context, authCoordinator, effectiveVault, username, password, role, rememberMe)
    }

    fun handleSignOut() {
        showSettingsDialog = false
        walkthroughReopened = false
        showLoginScreen = true
        currentDestination = TopLevelDestination.HOME
        isProfileSelected = false
        retained.viewModelScope.launch { retained.logout(context, authCoordinator, effectiveVault) }
    }

    fun retryData() {
        if (activeRepository is md.sime.opensime.core.data.CachedAgendaRepository || !authCoordinator.isAuthenticated) {
            showLoginScreen = true
        } else if (selectedStudentIdValue.isNotBlank()) {
            retained.selectStudent(StudentId(selectedStudentIdValue), forceRefresh = true, coordinator = authCoordinator)
        }
    }

    LaunchedEffect(Unit) {
        if (repository == null) retained.restore(context, authCoordinator, effectiveVault)
        else retained.recoverPendingLogout(context, authCoordinator, effectiveVault)
        startupResolved = true
    }
    LaunchedEffect(retained.loginRequired, retained.isLiveAuthenticated) {
        if (retained.loginRequired) showLoginScreen = true
        else if (retained.isLiveAuthenticated) showLoginScreen = false
    }
    LaunchedEffect(retained.recoveryCompleted) {
        if (retained.recoveryCompleted) recoveryLogin = false
    }
    LaunchedEffect(activeRepository) {
        if (activeRepository != null && selectedStudentIdValue.isBlank()) {
            activeRepository.listStudents().firstOrNull()?.let { retained.selectStudent(it.id, coordinator = authCoordinator) }
        }
    }


    OpenSimeTheme(settings = settings) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            val strings = LocalAppStrings.current
            val snapshot = currentSnapshot

        val walkthroughRoute = isWalkthroughRoute(
            hasActiveRepository = activeRepository != null,
            hasInjectedRepository = repository != null,
            walkthroughReopened = walkthroughReopened,
            onboardingCompleted = settings.onboardingCompleted,
        )
        val loginRoute = isLoginRoute(
            hasActiveRepository = activeRepository != null,
            hasInjectedRepository = repository != null,
            showLoginScreen = showLoginScreen,
            walkthroughReopened = walkthroughReopened,
            onboardingCompleted = settings.onboardingCompleted,
        )

        when {
            !startupResolved && activeRepository == null -> Box(Modifier.fillMaxSize())
            retained.logoutInProgress -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(strings.logoutInProgress)
                    }
                }
            }
            retained.unknownLogoutCache && recoveryLogin -> {
                LoginScreen(
                    isAuthenticating = isAuthenticating,
                    authError = authError,
                    onSignIn = { username, password, role, _ ->
                        retained.recoverUnknownCache(context, authCoordinator, username, password, role)
                    },
                    onBack = { recoveryLogin = false },
                    onOpenSettings = { showSettingsDialog = true },
                    showRememberMe = false,
                )
            }
            retained.logoutError != null -> {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorStateCard(
                        title = strings.logoutCleanupFailed,
                        message = if (retained.unknownLogoutCache) strings.logoutUnknownCacheDesc else strings.logoutCleanupFailedDesc,
                        retryLabel = strings.actionRetry,
                        onRetry = { handleSignOut() },
                    )
                    if (retained.unknownLogoutCache) OutlinedButton(onClick = { recoveryLogin = true }) {
                        Text(strings.logoutRecoverAccount)
                    }
                    if (retained.unknownLogoutCache) OutlinedButton(onClick = { retained.acknowledgeUnknownCache(context, effectiveVault) }) {
                        Text(strings.logoutContinueUnknown)
                    }
                  }
                }
            }
            isRestoringSession && activeRepository == null && !retained.logoutRequested -> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CookieAvatar(
                                fullName = "Open SIME",
                                size = 64.dp,
                                fontSize = 22.sp,
                                activeHighlight = true,
                            )
                            CircularProgressIndicator()
                            Text(
                                text = strings.sessionRestoring,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            retained.restorationError != null && activeRepository == null && !showLoginScreen && !retained.loginRequired -> {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorStateCard(title = strings.sessionRestoreFailed,
                        message = strings.errorMessageFor(retained.restorationError), retryLabel = strings.actionRetry,
                        onRetry = { showLoginScreen = true })
                    OutlinedButton(onClick = { showLoginScreen = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text(strings.loginSubmit)
                    }
                }
            }

            loginRoute -> {
                LoginScreen(
                    isAuthenticating = isAuthenticating,
                    authError = authError,
                    onSignIn = { username, password, role, rememberMe ->
                        handleSignIn(username, password, role, rememberMe)
                    },
                    onBack = {
                        if (activeRepository == null) {
                            // Login is the root route: nothing sits behind it, so the arrow reopens the walkthrough.
                            walkthroughReopened = true
                        } else {
                            showLoginScreen = false
                            retained.dismissLogin()
                        }
                    },
                    // At the root, system Back leaves the app instead of bouncing Login -> Login.
                    interceptSystemBack = activeRepository != null,
                    onOpenSettings = { showSettingsDialog = true },
                    showRememberMe = !retained.unknownLogoutCache,
                )
            }

            walkthroughRoute -> {
                // Registered before OnboardingScreen's own handler, so page 2 -> page 1 wins first,
                // then Back from page 1 of a reopened walkthrough returns to Login.
                if (walkthroughReopened) BackHandler { walkthroughReopened = false }
                OnboardingScreen(
                    onContinueToSignIn = {
                        updateSettings(settings.copy(onboardingCompleted = true))
                        walkthroughReopened = false
                        showLoginScreen = true
                    },
                    onOpenSettings = { showSettingsDialog = true },
                )
            }

            selectedStudentIdValue.isBlank() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            else -> {
                OpenSimeAdaptiveShell(
                    currentDestination = currentDestination,
                    isProfileSelected = isProfileSelected,
                    onSelectDestination = { dest ->
                        isProfileSelected = false
                        currentDestination = dest
                    },
                    onSelectProfile = { isProfileSelected = true },
                    onOpenSettings = { showSettingsDialog = true },
                    availableStudents = students,
                    selectedStudentId = StudentId(selectedStudentIdValue),
                    onSelectStudent = { newId ->
                        if (BuildConfig.DEBUG) debugInspectionBridge("selectionChanged", emptyArray())
                        retained.selectStudent(newId, coordinator = authCoordinator)
                    },
                    simpleMode = settings.simpleMode,
                    isSamplePreview = activeRepository is SampleAgendaRepository,
                    onSignInClick = { showLoginScreen = true },
                    onSignOutClick = { handleSignOut() },
                ) {
                    Column(Modifier.fillMaxSize()) {
                    if (retained.unknownLogoutCache) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.logoutUnknownCacheDesc, modifier = Modifier.padding(16.dp))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                    val err = loadError
                    when {
                        err != null && snapshot == null -> {
                            val isSessionExpired = err is SimeSessionExpiredError
                            val isMismatch = err is SimeStudentMismatchError || err is StudentMismatchException
                            val errorTitle = when {
                                isSessionExpired -> strings.errSessionExpired
                                isMismatch -> strings.errStudentMismatch
                                else -> strings.dataLoadErrorTitle
                            }
                            val errorMessage = strings.errorMessageFor(err)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ErrorStateCard(
                                        title = errorTitle,
                                        message = errorMessage,
                                        retryLabel = if (isSessionExpired) strings.loginSubmit else strings.actionRetry,
                                        onRetry = {
                                            if (isSessionExpired) {
                                                showLoginScreen = true
                                            } else {
                                                retryData()
                                            }
                                        },
                                    )
                                    if (isSessionExpired || isMismatch || isLiveAuthenticated) {
                                        OutlinedButton(
                                            onClick = { handleSignOut() },
                                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                                        ) {
                                            Text(strings.logoutAction)
                                        }
                                    }
                                }
                            }
                        }

                        snapshot == null -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        else -> {
                            val navState = currentDestination to isProfileSelected

                    @Composable
                    fun RenderActiveScreen(target: Pair<TopLevelDestination, Boolean>) {
                        val (destination, showProfile) = target
                        PageEntranceScope(snapshot.studentId.value, settings.reduceMotion) {
                        if (showProfile) {
                            ProfileScreen(
                                snapshot = snapshot,
                                onSignInClick = { showLoginScreen = true },
                                onSignOutClick = { handleSignOut() },
                            )
                        } else {
                            when (destination) {
                                TopLevelDestination.HOME -> HomeScreen(
                                    snapshot = snapshot,
                                    isRefreshing = isLoading || isRestoringSession,
                                    onRefresh = { if (!isLoading && !isRestoringSession) retryData() },
                                    refreshError = loadError?.let { strings.errorMessageFor(it) },
                                    reduceMotion = settings.reduceMotion,
                                    onNavigateToMarks = {
                                        isProfileSelected = false
                                        currentDestination = TopLevelDestination.MARKS
                                    },
                                    onNavigateToTimetable = {
                                        isProfileSelected = false
                                        currentDestination = TopLevelDestination.TIMETABLE
                                    },
                                    onNavigateToAbsences = {
                                        isProfileSelected = false
                                        currentDestination = TopLevelDestination.ABSENCES
                                    },
                                    onNavigateToHomework = {
                                        isProfileSelected = false
                                        currentDestination = TopLevelDestination.HOMEWORK
                                    },
                                    simpleMode = settings.simpleMode,
                                )

                                TopLevelDestination.MARKS -> MarksScreen(snapshot = snapshot)
                                TopLevelDestination.TIMETABLE -> TimetableScreen(snapshot = snapshot)
                                TopLevelDestination.ABSENCES -> AbsencesScreen(snapshot = snapshot)
                                TopLevelDestination.HOMEWORK -> HomeworkScreen(snapshot = snapshot)
                            }
                        }
                    }

                    }
                    if (settings.reduceMotion) {
                        RenderActiveScreen(navState)
                    } else {
                        Crossfade(
                            targetState = navState,
                            label = "destination_crossfade",
                        ) { target ->
                            RenderActiveScreen(target)
                        }
                    }
                    }
                    }
                }
            }
        }
    }
}

        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                onUpdateSettings = { updateSettings(it) },
                onDismiss = { showSettingsDialog = false },
                isLoggedIn = activeRepository != null && activeRepository !is SampleAgendaRepository,
                onLogout = {
                    showSettingsDialog = false
                    handleSignOut()
                },
            )
        }
        }
    }
}
