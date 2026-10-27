package md.sime.opensime.core.portal

import md.sime.opensime.core.portal.SimeAbsencesParser.EXCUSED_MARKERS
import md.sime.opensime.core.portal.SimeAbsencesParser.UNEXCUSED_MARKERS
import md.sime.opensime.core.portal.SimeDomHelpers.classContains
import md.sime.opensime.core.portal.SimeDomHelpers.classTokens
import md.sime.opensime.core.portal.SimeDomHelpers.getInputValue
import md.sime.opensime.core.portal.SimeDomHelpers.parseFragment
import md.sime.opensime.core.portal.SimeDomHelpers.textOf
import org.jsoup.nodes.Element
import java.time.Instant
import java.util.Locale

/**
 * Calendar AJAX discovery, FullCalendar event parsing, timetable grouping (`Orar`),
 * and teacher deduplication (`Profesori`) ported from `web/server.js:926-1029` and `1256-1390`.
 */
internal object SimeCalendarAndTeachersParser {

    private val CALENDAR_AJAX_RE =
        Regex("""apex\.widget\.fullCalendar\(\{[\s\S]{0,6000}?"ajaxIdentifier"\s*:\s*"([^"]+)"""")
    private val TEACHER_PREFIX_RE =
        Regex("""\b(prof|profesor|d-na|dna|dl|invatator|învățător)\.?\b""", RegexOption.IGNORE_CASE)

    fun findCalendarAjaxId(pageHtml: String?): String? {
        if (pageHtml.isNullOrBlank()) return null
        val match = CALENDAR_AJAX_RE.find(pageHtml) ?: return null
        return SimeUrlValidator.unescapeJs(match.groupValues[1])
    }

    private fun asTokenList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> value.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        else -> emptyList()
    }

    fun parseCalendarEvents(eventsJson: String?): List<ParsedCalendarEntry> {
        if (eventsJson.isNullOrBlank()) return emptyList()
        val parsed = try {
            SimpleJson.parse(eventsJson) as? List<*>
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return parseCalendarEventMaps(parsed)
    }

    fun parseCalendarEvents(events: List<RawCalendarEvent>): List<ParsedCalendarEntry> {
        if (events.isEmpty()) return emptyList()
        val maps = events.map { ev ->
            mapOf(
                "start" to ev.start,
                "end" to ev.end,
                "title" to ev.title,
                "className" to ev.className,
                "classNames" to ev.classNames,
                "class" to ev.cssClass,
                "status" to ev.status,
                "absenceStatus" to ev.absenceStatus,
                "semester" to ev.semester,
            )
        }
        return parseCalendarEventMaps(maps)
    }

    private fun parseCalendarEventMaps(events: List<*>): List<ParsedCalendarEntry> {
        val entries = mutableListOf<ParsedCalendarEntry>()
        for (item in events) {
            val ev = item as? Map<*, *> ?: continue
            val start = ChisinauTime.parseEventTime(ev["start"] as? String) ?: continue
            val end = ChisinauTime.parseEventTime(ev["end"] as? String) ?: continue
            val titleHtml = ev["title"] as? String ?: ""
            val root = parseFragment("<div>$titleHtml</div>")

            fun textFor(vararg names: String): String? {
                val el = classContains(root, *names) ?: return null
                return textOf(el, " ").ifEmpty { null }
            }

            val subject = textFor("lesson-text-wrap")
                ?: textOf(root, " ").ifEmpty { null }
                ?: "Unknown"

            val markerParts = mutableListOf<String>()
            for (key in listOf("className", "classNames", "class", "status", "absenceStatus")) {
                markerParts.addAll(asTokenList(ev[key]).map { it.lowercase(Locale.ROOT) })
            }
            var marker = markerParts.joinToString(" ")

            val absenceEl = classContains(
                root,
                "absen" /* UNVERIFIED */,
                "nemotivat" /* UNVERIFIED */,
                "motivat" /* UNVERIFIED */,
                "unmotivat" /* UNVERIFIED */,
            )
            if (absenceEl != null) {
                marker += " " + classTokens(absenceEl).joinToString(" ").lowercase(Locale.ROOT)
                if (absenceEl.hasAttr("status")) {
                    marker += " " + absenceEl.attr("status").lowercase(Locale.ROOT)
                }
                if (absenceEl.hasAttr("data-status")) {
                    marker += " " + absenceEl.attr("data-status").lowercase(Locale.ROOT)
                }
            }

            val isUnexcused = UNEXCUSED_MARKERS.any { marker.contains(it) }
            val isExcused = !isUnexcused && EXCUSED_MARKERS.any { marker.contains(it) }
            val isAbsence = marker.contains("absen" /* UNVERIFIED */) || isUnexcused || isExcused
            val rawSem = (ev["semester"] as? Number)?.toInt()
            val explicitSem = if (rawSem == 1 || rawSem == 2) rawSem else null
            val note = textFor("absence-reason" /* UNVERIFIED */, "reason" /* UNVERIFIED */, "note" /* UNVERIFIED */)
                ?: absenceEl?.attr("title")?.takeIf { it.isNotEmpty() }

            entries.add(
                ParsedCalendarEntry(
                    start = start,
                    end = end,
                    subject = subject,
                    classroom = textFor("lesson-room" /* UNVERIFIED */),
                    teacher = textFor("lesson-teacher" /* UNVERIFIED */),
                    topic = textFor("lesson-homework" /* UNVERIFIED */, "lesson-line3" /* UNVERIFIED */),
                    isAbsence = isAbsence,
                    isExcused = isExcused,
                    isUnexcused = isUnexcused,
                    semester = explicitSem,
                    note = note,
                ),
            )
        }
        entries.sortBy { it.start }
        return entries
    }

    fun extractEmbeddedCalendarEntries(doc: Element): List<ParsedCalendarEntry> {
        val rawJson = getInputValue(doc, "P1_TIMETABLE")
            .ifEmpty { getInputValue(doc, "P1_CALENDAR_EVENTS") }
            .ifEmpty {
                doc.select("script#P1_CALENDAR_EVENTS, script[data-calendar-events]")
                    .firstOrNull()
                    ?.data()
                    ?.trim()
                    .orEmpty()
            }
        if (rawJson.isNotEmpty()) {
            val parsed = parseCalendarEvents(rawJson)
            if (parsed.isNotEmpty()) return parsed
        }

        val domEvents = mutableListOf<RawCalendarEvent>()
        for (el in doc.select("[data-start][data-end]")) {
            domEvents.add(
                RawCalendarEvent(
                    start = el.attr("data-start"),
                    end = el.attr("data-end"),
                    className = asTokenList(el.attr("class")),
                    title = el.html(),
                ),
            )
        }
        return if (domEvents.isNotEmpty()) parseCalendarEvents(domEvents) else emptyList()
    }

    fun groupByDay(entries: List<ParsedCalendarEntry>): Map<String, List<ParsedLessonView>> {
        val grouped = linkedMapOf<String, MutableList<ParsedCalendarEntry>>()
        for (e in entries) {
            val day = ChisinauTime.chisinauDate(e.start)
            grouped.getOrPut(day) { mutableListOf() }.add(e)
        }
        val out = linkedMapOf<String, List<ParsedLessonView>>()
        for ((day, list) in grouped) {
            out[day] = list.mapIndexed { idx, e ->
                ParsedLessonView(
                    periodIndex = idx + 1,
                    start = Instant.ofEpochMilli(e.start).toString(),
                    end = Instant.ofEpochMilli(e.end).toString(),
                    startLocal = ChisinauTime.chisinauHm(e.start),
                    endLocal = ChisinauTime.chisinauHm(e.end),
                    subject = e.subject,
                    classroom = e.classroom,
                    teacher = e.teacher,
                    topic = e.topic,
                )
            }
        }
        return out
    }

    fun parseTeacherTokens(name: String?): ParsedTeacherTokens {
        if (name.isNullOrBlank()) {
            return ParsedTeacherTokens(parts = emptyList(), fullTokens = emptyList(), initials = emptyList())
        }
        val cleaned = TEACHER_PREFIX_RE.replace(name, "")
            .replace('.', ' ')
            .replace(',', ' ')
            .trim()
        val parts = cleaned.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val fullTokens = mutableListOf<String>()
        val initials = mutableListOf<String>()
        for (p in parts) {
            val lower = p.lowercase(Locale.ROOT)
            if (lower.length == 1) initials.add(lower) else fullTokens.add(lower)
        }
        return ParsedTeacherTokens(parts = parts, fullTokens = fullTokens, initials = initials)
    }

    fun areTeacherNamesSimilar(nameA: String?, nameB: String?): Boolean {
        if (nameA.isNullOrBlank() || nameB.isNullOrBlank()) return false
        if (nameA.trim().equals(nameB.trim(), ignoreCase = true)) return true

        val a = parseTeacherTokens(nameA)
        val b = parseTeacherTokens(nameB)
        if (a.fullTokens.isEmpty() || b.fullTokens.isEmpty()) return false

        val sharedFull = a.fullTokens.filter { b.fullTokens.contains(it) }
        if (sharedFull.isEmpty()) return false

        val remA = a.fullTokens.filter { !sharedFull.contains(it) }
        val remB = b.fullTokens.filter { !sharedFull.contains(it) }

        if (remA.isNotEmpty() && remB.isNotEmpty()) {
            return remA.all { remB.contains(it) }
        }
        if (remA.isNotEmpty() && b.initials.isNotEmpty()) {
            return b.initials.any { init -> remA.any { full -> full.startsWith(init) } }
        }
        if (remB.isNotEmpty() && a.initials.isNotEmpty()) {
            return a.initials.any { init -> remB.any { full -> full.startsWith(init) } }
        }
        if (remA.isEmpty() && remB.isEmpty() && a.initials.isEmpty() && b.initials.isEmpty()) {
            return true
        }
        if (
            (a.initials.isNotEmpty() && b.initials.isEmpty() && remB.isEmpty()) ||
            (b.initials.isNotEmpty() && a.initials.isEmpty() && remA.isEmpty())
        ) {
            return true
        }
        return false
    }

    fun buildTeachers(
        entries: List<ParsedCalendarEntry>,
        metadata: ParsedStudentMetadata? = null,
        catalogSubjects: List<ParsedCatalogSubject> = emptyList(),
    ): List<ParsedTeacher> {
        class MutableTeacher(
            var name: String,
            val subjects: MutableSet<String> = linkedSetOf(),
            var lessons: Int = 0,
            var note: String? = null,
        )

        val teachersList = mutableListOf<MutableTeacher>()

        fun ensure(rawName: String?): MutableTeacher? {
            val cleanName = rawName?.trim().orEmpty()
            if (cleanName.isEmpty()) return null
            val existing = teachersList.firstOrNull { areTeacherNamesSimilar(it.name, cleanName) }
            if (existing == null) {
                val created = MutableTeacher(name = cleanName)
                teachersList.add(created)
                return created
            }
            if (cleanName.length > existing.name.length && !cleanName.contains('.')) {
                existing.name = cleanName
            }
            return existing
        }

        if (!metadata?.classMaster.isNullOrBlank()) {
            val cm = ensure(metadata?.classMaster)
            if (cm != null) cm.note = "Diriginte"
        }

        for (s in catalogSubjects) {
            if (s.teacher.isNullOrBlank()) continue
            val t = ensure(s.teacher)
            if (t != null && s.subject.isNotEmpty()) {
                t.subjects.add(s.subject)
            }
        }

        for (e in entries) {
            if (e.teacher.isNullOrBlank()) continue
            val t = ensure(e.teacher)
            if (t != null) {
                if (e.subject.isNotEmpty()) t.subjects.add(e.subject)
                t.lessons += 1
            }
        }

        return teachersList
            .map { t ->
                ParsedTeacher(
                    name = t.name,
                    subjects = t.subjects.sorted(),
                    lessons = t.lessons,
                    note = t.note,
                )
            }
            .sortedWith { a, b ->
                when {
                    a.note == "Diriginte" && b.note != "Diriginte" -> -1
                    b.note == "Diriginte" && a.note != "Diriginte" -> 1
                    else -> a.name.compareTo(b.name)
                }
            }
    }
}
