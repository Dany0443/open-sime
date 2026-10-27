package md.sime.opensime

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import md.sime.opensime.core.data.AgendaRepository
import md.sime.opensime.core.data.LiveAgendaRepository
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.ErrorStateCard
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.i18n.errorMessageFor
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.portal.SimeAuthCoordinator
import md.sime.opensime.core.portal.SimeCredentialVault
import md.sime.opensime.core.portal.SimeLoginRejectedError
import md.sime.opensime.core.portal.SimeNetworkError
import md.sime.opensime.core.portal.SimePortalClient
import md.sime.opensime.core.portal.SimeSessionExpiredError
import md.sime.opensime.core.portal.SimeStudentMismatchError
import md.sime.opensime.feature.absences.AbsencesScreen
import md.sime.opensime.feature.auth.LoginScreen
import md.sime.opensime.feature.auth.SignedOutScreen
import md.sime.opensime.feature.home.HomeScreen
import md.sime.opensime.feature.homework.HomeworkScreen
import md.sime.opensime.feature.marks.MarksScreen
import md.sime.opensime.feature.onboarding.OnboardingScreen
import md.sime.opensime.feature.profile.ProfileScreen
import md.sime.opensime.feature.settings.SettingsDialog
import md.sime.opensime.feature.timetable.TimetableScreen
import md.sime.opensime.navigation.OpenSimeAdaptiveShell
import md.sime.opensime.navigation.TopLevelDestination

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
        activeSnapshot = null

        return try {
            repositoryLoadCalls += 1
            val loaded = repository.getStudentSnapshot(studentId).verifyStudentOwnership(studentId)
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
        } catch (err: Throwable) {
            if (myGeneration == repositoryGeneration && myTicket == latestRequestTicket && activeStudentId == studentId) {
                activeSnapshot = null
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
    ): Result<StudentAgendaSnapshot?> = runCatching {
        selectStudent(studentId = studentId, forceRefresh = forceRefresh)
    }
}

@Composable
fun OpenSimeApp(
    repository: AgendaRepository? = null,
    authCoordinator: SimeAuthCoordinator = remember { SimeAuthCoordinator() },
    credentialVault: SimeCredentialVault? = null,
) {
    val context = LocalContext.current
    val effectiveVault = remember(credentialVault) { credentialVault ?: SimeCredentialVault(context) }
    var activeRepository by remember(repository) { mutableStateOf(repository) }
    var isLiveAuthenticated by rememberSaveable { mutableStateOf(repository != null && repository !is SampleAgendaRepository) }
    var hasSavedCredentialsState by remember { mutableStateOf(effectiveVault.hasSavedCredentials()) }
    var savedUsername by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hasSavedCredentialsState) {
        savedUsername = if (hasSavedCredentialsState) effectiveVault.loadCredentials()?.username else null
    }

    var isRestoringSession by remember { mutableStateOf(false) }
    var sessionRestorationAttempted by rememberSaveable { mutableStateOf(false) }
    var showLoginScreen by rememberSaveable { mutableStateOf(false) }

    val coordinator = remember(activeRepository) {
        activeRepository?.let { OpenSimeSessionCoordinator(it) }
    }
    val students = remember(activeRepository, coordinator) {
        coordinator?.availableStudents() ?: emptyList()
    }

    var settings by rememberSaveable(stateSaver = OpenSimeSettingsState.Saver) {
        mutableStateOf(OpenSimeSettingsState.loadFromPreferences(context))
    }

    fun updateSettings(newSettings: OpenSimeSettingsState) {
        settings = newSettings
        OpenSimeSettingsState.saveToPreferences(context, newSettings)
    }

    var selectedStudentIdValue by rememberSaveable {
        mutableStateOf(
            students.firstOrNull()?.id?.value ?: "",
        )
    }

    var currentSnapshot by remember(activeRepository) {
        mutableStateOf<StudentAgendaSnapshot?>(null)
    }
    var loadError by remember(activeRepository) {
        mutableStateOf<Throwable?>(null)
    }
    var isLoading by remember(activeRepository) {
        mutableStateOf(activeRepository != null)
    }
    var reloadTrigger by rememberSaveable { mutableIntStateOf(0) }

    var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.HOME) }
    var isProfileSelected by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    // Direct SIME authentication state
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<Throwable?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun handleSignIn(username: String, password: String, role: AccountRole, rememberMe: Boolean = false) {
        coroutineScope.launch {
            isAuthenticating = true
            authError = null
            val result = runCatching { authCoordinator.login(username, password, role) }
            result.fold(
                onSuccess = { session ->
                    OpenSimeSettingsState.setExplicitlyLoggedOut(context, false)
                    if (rememberMe) {
                        effectiveVault.saveCredentials(username, password, role)
                    } else {
                        effectiveVault.clearCredentials()
                    }
                    hasSavedCredentialsState = effectiveVault.hasSavedCredentials()
                    savedUsername = if (rememberMe) username.trim() else null
                    val liveClient = SimePortalClient(session = authCoordinator.session)
                    val liveRepo = LiveAgendaRepository(
                        client = liveClient,
                        authCoordinator = authCoordinator,
                    )
                    coordinator?.clear()
                    activeRepository = liveRepo
                    isLiveAuthenticated = true
                    showLoginScreen = false
                    isAuthenticating = false
                    authError = null
                    selectedStudentIdValue = session.initialStudent.id.value
                    reloadTrigger += 1
                },
                onFailure = { err ->
                    isAuthenticating = false
                    authError = err
                },
            )
        }
    }

    fun handleSignOut() {
        coroutineScope.launch {
            OpenSimeSettingsState.setExplicitlyLoggedOut(context, true)
            authCoordinator.logout()
            coordinator?.clear()
            isLiveAuthenticated = false
            activeRepository = null
            currentSnapshot = null
            loadError = null
            selectedStudentIdValue = ""
            showLoginScreen = false
            reloadTrigger += 1
        }
    }

    fun handleForgetCredentials() {
        effectiveVault.clearCredentials()
        hasSavedCredentialsState = false
        savedUsername = null
    }

    LaunchedEffect(Unit) {
        val explicitlyLoggedOut = OpenSimeSettingsState.isExplicitlyLoggedOut(context)
        if (!sessionRestorationAttempted && !explicitlyLoggedOut && activeRepository == null && effectiveVault.hasSavedCredentials()) {
            val saved = effectiveVault.loadCredentials()
            if (saved != null) {
                isRestoringSession = true
                sessionRestorationAttempted = true
                val result = withTimeoutOrNull(15_000L) {
                    runCatching { authCoordinator.login(saved.username, saved.password, saved.role) }
                }
                isRestoringSession = false
                if (result != null && result.isSuccess) {
                    val session = result.getOrThrow()
                    val liveClient = SimePortalClient(session = authCoordinator.session)
                    val liveRepo = LiveAgendaRepository(
                        client = liveClient,
                        authCoordinator = authCoordinator,
                    )
                    coordinator?.clear()
                    activeRepository = liveRepo
                    isLiveAuthenticated = true
                    selectedStudentIdValue = session.initialStudent.id.value
                    reloadTrigger += 1
                } else {
                    val err = result?.exceptionOrNull() ?: SimeNetworkError("Timeout connecting to SIME portal")
                    if (err is SimeLoginRejectedError) {
                        effectiveVault.clearCredentials()
                        hasSavedCredentialsState = false
                        savedUsername = null
                        authError = err
                        showLoginScreen = true
                    }
                    loadError = err
                }
            } else {
                sessionRestorationAttempted = true
            }
        }
    }

    LaunchedEffect(selectedStudentIdValue, reloadTrigger, activeRepository) {
        val currentCoord = coordinator ?: return@LaunchedEffect
        if (selectedStudentIdValue.isBlank()) {
            val firstStudent = currentCoord.availableStudents().firstOrNull()?.id?.value
            if (firstStudent != null) {
                selectedStudentIdValue = firstStudent
            }
            return@LaunchedEffect
        }
        val targetId = StudentId(selectedStudentIdValue)
        currentSnapshot = null
        loadError = null
        isLoading = true

        val result = currentCoord.loadStudentSafely(
            studentId = targetId,
            forceRefresh = reloadTrigger > 0,
        )
        result.fold(
            onSuccess = { loaded ->
                if (loaded != null && loaded.studentId == targetId) {
                    currentSnapshot = loaded
                    loadError = null
                    isLoading = false
                }
            },
            onFailure = { err ->
                if (currentCoord.activeStudentId == targetId) {
                    currentSnapshot = null
                    loadError = err
                    isLoading = false
                }
            },
        )
    }

    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    OpenSimeTheme(settings = settings) {
        val strings = LocalAppStrings.current
        val snapshot = currentSnapshot

        val showOnboarding = !settings.onboardingCompleted && repository == null

        when {
            isRestoringSession -> {
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

            showLoginScreen -> {
                LoginScreen(
                    isAuthenticating = isAuthenticating,
                    authError = authError,
                    onSignIn = { username, password, role, rememberMe ->
                        handleSignIn(username, password, role, rememberMe)
                    },
                    onBack = {
                        showLoginScreen = false
                        authError = null
                    },
                    onOpenSettings = { showSettingsDialog = true },
                )
            }

            showOnboarding -> {
                OnboardingScreen(
                    onContinueToSignIn = {
                        updateSettings(settings.copy(onboardingCompleted = true))
                        showLoginScreen = true
                    },
                    onOpenSettings = { showSettingsDialog = true },
                )
            }

            activeRepository == null -> {
                SignedOutScreen(
                    onSignInClick = { showLoginScreen = true },
                    onOpenSettings = { showSettingsDialog = true },
                    onViewOnboarding = {
                        updateSettings(settings.copy(onboardingCompleted = false))
                    },
                )
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
                    onToggleTheme = {
                        val nextMode = if (effectiveDark) ThemeMode.LIGHT else ThemeMode.DARK
                        updateSettings(settings.copy(themeMode = nextMode))
                    },
                    availableStudents = students,
                    selectedStudentId = StudentId(selectedStudentIdValue),
                    onSelectStudent = { newId ->
                        if (newId.value != selectedStudentIdValue) {
                            currentSnapshot = null
                            loadError = null
                            isLoading = true
                            selectedStudentIdValue = newId.value
                        }
                    },
                    simpleMode = settings.simpleMode,
                    isSamplePreview = !isLiveAuthenticated && (snapshot == null || snapshot.dataSourceMode == DataSourceMode.SAMPLE_PREVIEW),
                    onSignInClick = { showLoginScreen = true },
                    onSignOutClick = { handleSignOut() },
                ) {
                    val err = loadError
                    when {
                        err != null -> {
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
                                                reloadTrigger += 1
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

                        snapshot == null || isLoading -> {
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
                        if (showProfile) {
                            ProfileScreen(
                                snapshot = snapshot,
                                hasSavedCredentials = hasSavedCredentialsState,
                                onSignInClick = { showLoginScreen = true },
                                onSignOutClick = { handleSignOut() },
                                onForgetCredentialsClick = { handleForgetCredentials() },
                            )
                        } else {
                            when (destination) {
                                TopLevelDestination.HOME -> HomeScreen(
                                    snapshot = snapshot,
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

        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                onUpdateSettings = { updateSettings(it) },
                onDismiss = { showSettingsDialog = false },
                hasSavedCredentials = hasSavedCredentialsState,
                savedLoginUsername = savedUsername,
                onForgetSavedCredentials = { handleForgetCredentials() },
                isLoggedIn = isLiveAuthenticated,
                onLogout = {
                    showSettingsDialog = false
                    handleSignOut()
                },
            )
        }
    }
}
