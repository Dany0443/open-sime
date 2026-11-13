package md.sime.opensime.core.portal

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import md.sime.opensime.OpenSimeRetainedSession
import md.sime.opensime.core.data.LiveAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.i18n.AppLanguage

/** Holds only weak references; neither the selection nor approval is persisted. */
object DebugInspectionBridge {
    private var retainedRef = WeakReference<OpenSimeRetainedSession>(null)
    private var authRef = WeakReference<SimeAuthCoordinator>(null)
    private var revision = 0L
    private var lastSelected = ""

    @JvmStatic @Synchronized
    fun attach(retained: OpenSimeRetainedSession, auth: SimeAuthCoordinator) {
        if (retainedRef.get() !== retained || authRef.get() !== auth || lastSelected != retained.selectedStudentIdValue) {
            revision++
        }
        retainedRef = WeakReference(retained)
        authRef = WeakReference(auth)
        lastSelected = retained.selectedStudentIdValue
    }

    @JvmStatic @Synchronized fun selectionChanged() { revision++ }

    internal class Selection(
        val retained: OpenSimeRetainedSession,
        val auth: SimeAuthCoordinator,
        val student: String,
        val sessionGeneration: Long,
        val retainedGeneration: Long,
        val revision: Long,
    ) { var studentConfirmed = false }

    @Synchronized internal fun capture(): Selection? {
        val retained = retainedRef.get() ?: return null
        val auth = authRef.get() ?: return null
        val session = auth.session
        val repository = retained.repository as? LiveAgendaRepository ?: return null
        if (repository.client.session !== session || !retained.isLiveAuthenticated || !session.isAuthenticated ||
            retained.isLoading || retained.isAuthenticating || retained.isRestoringSession || retained.logoutInProgress) return null
        val selected = retained.selectedStudentIdValue
        val key = session.canonicalStudentKey(selected)
        if (selected.isBlank() || key.isEmpty() || key != session.currentStudentKey) return null
        if (session.listStudents().none { it.id == key }) return null
        return Selection(retained, auth, key, session.sessionGeneration, retained.sessionGeneration, revision)
    }

    @Synchronized internal fun valid(selection: Selection): Boolean {
        val current = capture() ?: return false
        return current.retained === selection.retained && current.auth === selection.auth &&
            current.student == selection.student && current.sessionGeneration == selection.sessionGeneration &&
            current.retainedGeneration == selection.retainedGeneration && current.revision == selection.revision
    }

    @Synchronized internal fun confirmStudent(selection: Selection) {
        if (!valid(selection)) throw CancellationException("Selection changed")
        selection.studentConfirmed = true
    }

    internal suspend fun confirm(selection: Selection, refresh: suspend () -> Unit): String {
        synchronized(this) {
            if (!valid(selection) || !selection.studentConfirmed) throw CancellationException("Student not confirmed")
            selection.studentConfirmed = false
        }
        DebugGradeSchemaInspector.inspect(selection.auth.session, selection.student) {
            if (!valid(selection)) throw CancellationException("Selection changed")
            refresh()
        }
        currentCoroutineContext().ensureActive()
        return checkNotNull(DebugGradeSchemaInspector.takeSummary())
    }
}

private data class Labels(
    val title: String, val selected: String, val description: String, val confirm: String,
    val close: String, val unavailable: String, val running: String, val complete: String,
    val failed: String, val expired: String, val select: String,
)

private fun labels(language: AppLanguage): Labels = when (language) {
    AppLanguage.RO -> Labels("Inspecție structurală de depanare", "Elev selectat", "Confirmă o singură inspecție structurală. Aceasta nu este o sincronizare obișnuită. Sunt permise cel mult trei cereri.", "Confirmă inspecția", "Închide", "Sesiunea sau elevul selectat nu este pregătit. Revino după autentificare și selectarea elevului.", "Inspecția este în curs…", "Inspecție finalizată", "Inspecția nu a putut fi finalizată.", "Inspecția a expirat.", "Alege acest elev")
    AppLanguage.RU -> Labels("Отладочная проверка структуры", "Выбранный ученик", "Подтвердите одну проверку структуры. Это не обычная синхронизация. Допустимо не более трёх запросов.", "Подтвердить проверку", "Закрыть", "Сеанс или выбранный ученик не готов. Войдите и выберите ученика.", "Проверка выполняется…", "Проверка завершена", "Не удалось завершить проверку.", "Время проверки истекло.", "Выбрать этого ученика")
    AppLanguage.EN -> Labels("Debug structure inspection", "Selected student", "Confirm one structural inspection. This is not normal synchronization. At most three requests are allowed.", "Confirm inspection", "Close", "The session or selected student is not ready. Sign in and select a student first.", "Inspection in progress…", "Inspection complete", "The inspection could not be completed.", "The inspection expired.", "Select this student")
}

// Fixed categories only: exception messages and portal responses may contain private data.
internal fun debugInspectionFailureCategory(error: Throwable): String = when (error) {
    is SimeAuthError -> "session"
    is SimeStudentMismatchError -> "ownership"
    is SimeNetworkError -> "network"
    is SimeRateLimitError -> "rate_limit"
    is SimeServerError, is SimeMaintenanceError -> "server"
    is CancellationException -> "cancelled_or_guard"
    else -> "other"
}

private sealed interface ScreenState {
    data object Select : ScreenState
    data object Confirm : ScreenState
    data object Running : ScreenState
    data object Unavailable : ScreenState
    class Failed(val stage: String, val category: String) : ScreenState
    data object Expired : ScreenState
    class Complete(val summary: String) : ScreenState
}

class DebugSchemaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val settings = OpenSimeSettingsState.loadFromPreferences(this)
        val text = labels(settings.language)
        setContent {
            val selection = remember { DebugInspectionBridge.capture() }
            var screen by remember { mutableStateOf<ScreenState>(if (selection == null) ScreenState.Unavailable else ScreenState.Select) }
            val scope = rememberCoroutineScope()
            DisposableEffect(Unit) { onDispose { DebugGradeSchemaInspector.takeSummary() } }
            OpenSimeTheme(settings) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text.title, style = MaterialTheme.typography.headlineSmall)
                        when (val current = screen) {
                            ScreenState.Select -> {
                                checkNotNull(selection)
                                Text(text.selected, modifier = Modifier.testTag("debug_inspection_selected"))
                                Button(onClick = {
                                    screen = try { DebugInspectionBridge.confirmStudent(selection); ScreenState.Confirm }
                                    catch (_: CancellationException) { ScreenState.Unavailable }
                                }, modifier = Modifier.testTag("debug_inspection_select")) { Text(text.select) }
                            }
                            ScreenState.Confirm -> {
                                checkNotNull(selection)
                                Text(text.selected, modifier = Modifier.testTag("debug_inspection_selected"))
                                Text(text.description)
                                Button(onClick = {
                                    if (screen !== ScreenState.Confirm) return@Button
                                    if (!DebugInspectionBridge.valid(selection)) {
                                        screen = ScreenState.Unavailable
                                    } else {
                                        screen = ScreenState.Running
                                        scope.launch {
                                            var stage = "authorization"
                                            try {
                                                val summary = DebugInspectionBridge.confirm(selection) {
                                                    val repository = selection.retained.repository as? LiveAgendaRepository
                                                        ?: throw CancellationException("Session unavailable")
                                                    withContext(Dispatchers.IO) {
                                                        val session = selection.auth.session
                                                        stage = "student_page"
                                                        val page = session.sessionLock.withLock {
                                                            session.loadStudentPage(selection.student, assertCurrent = true)
                                                        }
                                                        stage = "reports_or_parsing"
                                                        repository.client.enrichPortalPage(page, selection.student)
                                                        stage = "verification"
                                                    }
                                                }
                                                screen = ScreenState.Complete(summary)
                                            } catch (_: TimeoutCancellationException) {
                                                screen = ScreenState.Expired
                                            } catch (_: DebugInspectionExpired) {
                                                screen = ScreenState.Expired
                                            } catch (error: CancellationException) {
                                                currentCoroutineContext().ensureActive()
                                                screen = ScreenState.Failed(stage, debugInspectionFailureCategory(error))
                                            } catch (error: Exception) {
                                                screen = ScreenState.Failed(stage, debugInspectionFailureCategory(error))
                                            }
                                        }
                                    }
                                }, modifier = Modifier.testTag("debug_inspection_confirm")) { Text(text.confirm) }
                            }
                            ScreenState.Running -> Text(text.running, modifier = Modifier.testTag("debug_inspection_status"))
                            ScreenState.Unavailable -> Text(text.unavailable, modifier = Modifier.testTag("debug_inspection_status"))
                            is ScreenState.Failed -> Text("${text.failed}\nstage=${current.stage}\ncategory=${current.category}", modifier = Modifier.testTag("debug_inspection_status"))
                            ScreenState.Expired -> Text(text.expired, modifier = Modifier.testTag("debug_inspection_status"))
                            is ScreenState.Complete -> { Text(text.complete); Text(current.summary, modifier = Modifier.testTag("debug_inspection_summary")) }
                        }
                        TextButton(onClick = { finish() }) { Text(text.close) }
                    }
                }
            }
        }
    }
}
