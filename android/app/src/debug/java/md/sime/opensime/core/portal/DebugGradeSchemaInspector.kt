package md.sime.opensime.core.portal

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import md.sime.opensime.MainActivity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Installs no exported component and does not start work or contact the network. */
class DebugSchemaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugGradeSchemaInspector.initialize(cacheDir)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                val marker = File(cacheDir, "enable_schema_inspection")
                if (marker.isFile && marker.delete()) {
                    activity.startActivity(Intent(activity, DebugSchemaActivity::class.java))
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * One-shot, debug-only schema observation. No student identity or response text is retained.
 * Output exists only in memory and contains fixed field names and enum values.
 */
object DebugGradeSchemaInspector {
    private enum class ValueType { UNKNOWN, EMPTY, NUMERIC, NON_NUMERIC, MIXED }
    private data class Observation(
        var dashboard: Boolean = false,
        var report1: Boolean = false,
        var report2: Boolean = false,
        var region1: Boolean = false,
        var region2: Boolean = false,
        var subject: Boolean = false,
        var evaluation: Boolean = false,
        var thesis: Boolean = false,
        var thesisMarker: Boolean = false,
        var semesterAverage: Boolean = false,
        var annualAverage: Boolean = false,
        var semesterType: ValueType = ValueType.UNKNOWN,
        var annualType: ValueType = ValueType.UNKNOWN,
    )

    private var directory: File? = null
    private var sequence = 0L
    private var activeToken = 0L
    private var active: Observation? = null
    private var summary: String? = null
    private data class Permit(
        val session: SimePortalSession,
        val student: String,
        val generation: Long,
        val expiresAt: Long,
        var requests: Int = 0,
        var navigations: Int = 0,
        var xhrs: Int = 0,
        var invalid: Boolean = false,
        var complete: Boolean = false,
        var closed: Boolean = false,
    )
    private var permit: Permit? = null
    private var inFlight = 0
    internal var monotonicNanos: () -> Long = System::nanoTime

    @JvmStatic @Synchronized
    fun initialize(cacheDir: File) {
        if (permit != null || inFlight != 0) return
        directory = cacheDir
        activeToken = 0L
        active = null
        summary = null
        permit = null
    }

    @Synchronized fun takeSummary(): String? = summary.also { summary = null }
    @Synchronized internal fun peekSummary(): String? = summary

    /** The explicit selection and permit exist only for this in-process operation. */
    suspend fun <T> inspect(session: SimePortalSession, student: String, operation: suspend () -> T): T {
        val p = synchronized(this) {
            check(permit == null && inFlight == 0 && directory != null)
            val key = session.canonicalStudentKey(student)
            check(key.isNotEmpty() && session.isAuthenticated && session.currentStudentKey == key)
            summary = null
            Permit(session, key, session.sessionGeneration, monotonicNanos() + 60_000_000_000L).also { permit = it }
        }
        try {
            val result = withTimeout(60_000L) { operation() }
            val context = currentCoroutineContext()
            context.ensureActive()
            synchronized(this) {
                checkValid(p)
                if (inFlight != 0) throw CancellationException("Schema inspection has unfinished requests")
                if (!p.complete || active == null) throw CancellationException("Schema inspection incomplete")
                if (!p.session.debugWithVerifiedOwner(p.generation, p.student) {
                        context.ensureActive()
                        summary = render(active!!)
                    }) throw CancellationException("Schema inspection ownership changed")
            }
            return result
        } finally {
            synchronized(this) {
                p.closed = true
                if (inFlight == 0 && permit === p) permit = null else p.invalid = true
                active = null
                activeToken = 0L
            }
        }
    }

    private fun checkValid(p: Permit) {
        if (monotonicNanos() >= p.expiresAt) {
            p.invalid = true
            throw DebugInspectionExpired()
        }
        if (p.invalid || !p.session.isAuthenticated ||
            p.session.sessionGeneration != p.generation || p.session.currentStudentKey != p.student) {
            p.invalid = true
            throw CancellationException("Schema inspection invalidated")
        }
    }

    @JvmStatic @Synchronized
    fun enterRequest(transport: SimeHttpTransport) {
        permit?.let { p ->
            checkValid(p)
            if (p.session.http !== transport) {
                p.invalid = true
                throw CancellationException("Schema inspection transport changed")
            }
        }
        inFlight++
    }

    @JvmStatic @Synchronized
    fun leaveRequest(success: Boolean) {
        inFlight--
        val p = permit ?: return
        if (!success) p.invalid = true
        if (p.closed && inFlight == 0) permit = null
    }

    @JvmStatic @Synchronized
    fun beforeHop(transport: SimeHttpTransport, kind: String, isReport: Boolean) {
        val p = permit
        if (p != null) {
            checkValid(p)
            if (p.session.http !== transport || p.requests >= 3 ||
                (kind == "NAVIGATE" && p.navigations >= 1) || (kind == "XHR" && p.xhrs >= 2) ||
                (kind == "XHR" && !isReport) ||
                kind !in setOf("NAVIGATE", "XHR")) {
                p.invalid = true
                throw CancellationException("Schema inspection request limit")
            }
            p.requests++
            if (kind == "NAVIGATE") p.navigations++ else p.xhrs++
        }
    }

    @JvmStatic @Synchronized
    fun afterHop(success: Boolean) {
        if (!success) permit?.invalid = true
    }

    @JvmStatic @Synchronized
    fun checkStudent(session: SimePortalSession, student: String) {
        val p = permit ?: return
        checkValid(p)
        if (p.session !== session || p.student != student) {
            p.invalid = true
            throw CancellationException("Schema inspection student changed")
        }
    }

    @JvmStatic @Synchronized
    fun begin(session: SimePortalSession, student: String, pageStudent: String, html: String): Long {
        val p = permit ?: return 0L
        checkStudent(session, student)
        if (student != pageStudent || active != null) {
            p.invalid = true
            throw CancellationException("Schema inspection ownership mismatch")
        }
        return try {
            val observation = Observation(dashboard = true)
            observe(observation, html)
            sequence += 1
            activeToken = sequence
            active = observation
            activeToken
        } catch (_: Exception) {
            activeToken = 0L
            active = null
            0L
        }
    }

    @JvmStatic @Synchronized
    fun report(token: Long, semester: Int, html: String) {
        if (token == 0L || token != activeToken || semester !in 1..2) return
        checkValid(permit ?: return)
        val observation = active ?: return
        try {
            if (semester == 1) observation.report1 = true else observation.report2 = true
            observe(observation, html)
        } catch (_: Exception) {
            active = null
            activeToken = 0L
        }
    }

    @JvmStatic @Synchronized
    fun finish(token: Long, verified: Boolean) {
        if (token == 0L || token != activeToken) return
        val p = permit ?: return
        if (!verified) { p.invalid = true; return }
        checkValid(p)
        p.complete = true
    }

    private fun observe(result: Observation, html: String) {
        val doc = Jsoup.parse(html)
        result.region1 = result.region1 || doc.getElementById("SEMESTER_REG_1") != null
        result.region2 = result.region2 || doc.getElementById("SEMESTER_REG_2") != null
        for (cell in doc.select("td[headers]")) {
            val headers = cell.attr("headers").uppercase(Locale.ROOT).split(Regex("\\s+")).toSet()
            result.subject = result.subject || "DISCIPLINA" in headers
            result.evaluation = result.evaluation || headers.any { it in setOf("LISTA_EVALUATION", "LISTA_EVALUATION_1", "LISTA_EVALUATION_2") }
            result.thesis = result.thesis || headers.any { it in setOf("TEZA", "TEZA_SEM1", "TEZA_SEM2") }
            if (headers.any { it in setOf("MED_SEM", "MED_SEM1", "MED_SEM2", "MED_SEM_1", "MED_SEM_2") }) {
                result.semesterAverage = true
                result.semesterType = merge(result.semesterType, cell)
            }
            if (headers.any { it in setOf("MED_AN", "MEDIA_AN") }) {
                result.annualAverage = true
                result.annualType = merge(result.annualType, cell)
            }
        }
        result.thesisMarker = result.thesisMarker || doc.select("td .nota-teza, td .teza").isNotEmpty()
    }

    private fun merge(previous: ValueType, cell: Element): ValueType {
        val text = cell.text().trim()
        val next = when {
            text.isEmpty() -> ValueType.EMPTY
            text.matches(Regex("[0-9]{1,2}([.,][0-9]{1,2})?")) -> ValueType.NUMERIC
            else -> ValueType.NON_NUMERIC
        }
        return if (previous == ValueType.UNKNOWN || previous == next) next else ValueType.MIXED
    }

    private fun render(o: Observation): String = buildString {
        appendLine("dashboard_observed=${o.dashboard}")
        appendLine("report_semester_1_observed=${o.report1}")
        appendLine("report_semester_2_observed=${o.report2}")
        appendLine("semester_1_region=${o.region1}")
        appendLine("semester_2_region=${o.region2}")
        appendLine("subject_column=${o.subject}")
        appendLine("evaluation_column=${o.evaluation}")
        appendLine("thesis_column=${o.thesis}")
        appendLine("thesis_marker=${o.thesisMarker}")
        appendLine("semester_average_column=${o.semesterAverage}")
        appendLine("annual_average_column=${o.annualAverage}")
        appendLine("semester_average_type=${o.semesterType.name.lowercase(Locale.ROOT)}")
        appendLine("annual_average_type=${o.annualType.name.lowercase(Locale.ROOT)}")
        appendLine("completeness=unknown")
        appendLine("finalization=unknown")
        appendLine("deduplication=unknown")
    }
}

class DebugInspectionExpired : CancellationException("Schema inspection expired")
