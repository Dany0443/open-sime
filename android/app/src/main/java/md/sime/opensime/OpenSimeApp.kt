package md.sime.opensime

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import md.sime.opensime.core.data.AgendaRepository
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.ErrorStateCard
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.feature.absences.AbsencesScreen
import md.sime.opensime.feature.home.HomeScreen
import md.sime.opensime.feature.homework.HomeworkScreen
import md.sime.opensime.feature.marks.MarksScreen
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
    private val repository: AgendaRepository,
) {
    private val cacheByStudent = mutableMapOf<StudentId, StudentAgendaSnapshot>()
    private var latestRequestTicket: Long = 0L

    var activeStudentId: StudentId? = null
        private set

    var activeSnapshot: StudentAgendaSnapshot? = null
        private set

    var lastError: Throwable? = null
        private set

    var repositoryLoadCalls: Int = 0
        private set

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
            cacheByStudent[studentId] = loaded
            if (myTicket == latestRequestTicket && activeStudentId == studentId) {
                activeSnapshot = loaded
                loaded
            } else {
                // A newer student switch superseded this request; keep activeSnapshot untouched.
                activeSnapshot
            }
        } catch (mismatch: StudentMismatchException) {
            if (myTicket == latestRequestTicket && activeStudentId == studentId) {
                activeSnapshot = null
                lastError = mismatch
            }
            throw mismatch
        } catch (err: Throwable) {
            if (myTicket == latestRequestTicket && activeStudentId == studentId) {
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
    repository: AgendaRepository = remember { SampleAgendaRepository() },
) {
    val context = LocalContext.current
    val coordinator = remember(repository) { OpenSimeSessionCoordinator(repository) }
    val students = remember(repository) { coordinator.availableStudents() }

    var settings by rememberSaveable(stateSaver = OpenSimeSettingsState.Saver) {
        mutableStateOf(OpenSimeSettingsState.loadFromPreferences(context))
    }

    fun updateSettings(newSettings: OpenSimeSettingsState) {
        settings = newSettings
        OpenSimeSettingsState.saveToPreferences(context, newSettings)
    }

    var selectedStudentIdValue by rememberSaveable {
        mutableStateOf(
            students.firstOrNull()?.id?.value
                ?: SampleAgendaRepository.STUDENT_SEVENTH_GRADE.value,
        )
    }

    var currentSnapshot by remember(repository) {
        mutableStateOf<StudentAgendaSnapshot?>(null)
    }
    var loadError by remember(repository) {
        mutableStateOf<Throwable?>(null)
    }
    var isLoading by remember(repository) {
        mutableStateOf(true)
    }
    var reloadTrigger by rememberSaveable { mutableIntStateOf(0) }

    var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.HOME) }
    var isProfileSelected by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedStudentIdValue, reloadTrigger, repository) {
        val targetId = StudentId(selectedStudentIdValue)
        currentSnapshot = null
        loadError = null
        isLoading = true

        val result = coordinator.loadStudentSafely(
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
                if (coordinator.activeStudentId == targetId) {
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
            isSamplePreview = snapshot == null || snapshot.dataSourceMode == DataSourceMode.SAMPLE_PREVIEW,
        ) {
            val err = loadError
            when {
                err != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ErrorStateCard(
                            title = strings.dataLoadErrorTitle,
                            message = err.message ?: strings.dataLoadErrorTitle,
                            retryLabel = strings.actionRetry,
                            onRetry = { reloadTrigger += 1 },
                        )
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
                            ProfileScreen(snapshot = snapshot)
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

        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                onUpdateSettings = { updateSettings(it) },
                onDismiss = { showSettingsDialog = false },
            )
        }
    }
}
