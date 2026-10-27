package md.sime.opensime.core.portal

import md.sime.opensime.core.portal.SimeDomHelpers.getInputValue
import md.sime.opensime.core.portal.SimeDomHelpers.getItemText
import md.sime.opensime.core.portal.SimeDomHelpers.headerHas
import md.sime.opensime.core.portal.SimeDomHelpers.textOf
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Absence summary, embedded JSON absences, HTML table absences, calendar absence records,
 * and the semester count integrity gate (`buildSemesterAbsences`), ported from
 * `web/server.js:891-917` and `1031-1254`.
 */
internal object SimeAbsencesParser {

    val EXCUSED_MARKERS = listOf("motivat")
    val UNEXCUSED_MARKERS = listOf("nemotivat", "unmotivat", "unexcused")

    private val INT_RE = Regex("""^[+-]?\d+$""")
    private val DIGITS_ONLY_RE = Regex("""^\d+$""")

    fun parseAbsenceSummary(doc: Element): ParsedAbsenceSummary {
        fun asInt(item: String, default: Int): Int {
            val raw = getItemText(doc, item, default.toString()).trim()
            return if (INT_RE.matches(raw)) raw.toIntOrNull() ?: default else default
        }

        val presenceRaw = getItemText(doc, "P1_PRESENCE_PERCENT", "").ifEmpty {
            getItemText(doc, "P1_PROC_PREZ", "")
        }.trim()
        val presencePercent = if (INT_RE.matches(presenceRaw)) presenceRaw.toIntOrNull() ?: 100 else 100

        val sem1Excused = asInt("P1_MOTIVATED", 0)
        val sem1Unexcused = asInt("P1_UNMOTIVATED", 0)
        val sem2Excused = asInt("P1_MOTIVATED2", 0)
        val sem2Unexcused = asInt("P1_UNMOTIVATED2", 0)
        val sem1Total = asInt("P1_TOTAL_ABSENCES1", sem1Excused + sem1Unexcused)
        val sem2Total = asInt("P1_TOTAL_ABSENCES2", sem2Excused + sem2Unexcused)

        val totalExcused = sem1Excused + sem2Excused
        val totalUnexcused = sem1Unexcused + sem2Unexcused

        return ParsedAbsenceSummary(
            presencePercent = presencePercent,
            sem1Excused = sem1Excused,
            sem1Unexcused = sem1Unexcused,
            sem1Total = sem1Total,
            sem2Excused = sem2Excused,
            sem2Unexcused = sem2Unexcused,
            sem2Total = sem2Total,
            totalExcused = totalExcused,
            totalUnexcused = totalUnexcused,
            total = totalExcused + totalUnexcused,
        )
    }

    fun inferAbsenceSemester(isoDay: String?, explicitSemester: Int? = null): Int =
        ChisinauTime.inferAbsenceSemester(isoDay, explicitSemester)

    fun absenceRecords(
        entries: List<ParsedCalendarEntry>,
        studentKey: String? = null,
    ): List<ParsedAbsenceRecord> {
        val seen = mutableSetOf<String>()
        val records = mutableListOf<ParsedAbsenceRecord>()
        for (e in entries) {
            if (!e.isAbsence) continue
            val key = "${e.start}|${e.subject}"
            if (!seen.add(key)) continue
            val date = ChisinauTime.chisinauDate(e.start)
            val startHm = ChisinauTime.chisinauHm(e.start)
            val endHm = ChisinauTime.chisinauHm(e.end)
            val status = when {
                e.isExcused -> "excused"
                e.isUnexcused -> "unexcused"
                else -> "unknown"
            }
            val rawStatus = when {
                e.isExcused -> "motivat"
                e.isUnexcused -> "nemotivat"
                else -> "necunoscut"
            }
            val subject = e.subject.ifEmpty { "Disciplină" }
            records.add(
                ParsedAbsenceRecord(
                    studentKey = studentKey?.takeIf { it.isNotEmpty() },
                    date = date,
                    lessonNumber = e.periodIndex ?: e.lessonNumber,
                    startTime = startHm,
                    endTime = endHm,
                    subject = subject,
                    room = e.classroom,
                    teacher = e.teacher,
                    status = status,
                    reason = e.note,
                    semester = inferAbsenceSemester(date, e.semester),
                    startLocal = startHm,
                    endLocal = endHm,
                    discipline = subject,
                    note = e.note,
                    rawStatus = rawStatus,
                ),
            )
        }
        return records.sortedWith { a, b ->
            val dateCmp = b.date.orEmpty().compareTo(a.date.orEmpty())
            if (dateCmp != 0) {
                dateCmp
            } else {
                val timeB = b.startTime.ifEmpty { b.startLocal }
                val timeA = a.startTime.ifEmpty { a.startLocal }
                timeB.compareTo(timeA)
            }
        }
    }

    fun extractHtmlAbsenceRecords(
        doc: Element,
        todayIso: String = ChisinauTime.todayChisinau(),
        studentKey: String? = null,
    ): List<ParsedAbsenceRecord> {
        val records = mutableListOf<ParsedAbsenceRecord>()
        for (tr in doc.getElementsByTag("tr")) {
            val tds = tr.getElementsByTag("td")
            if (tds.isEmpty()) continue
            val statusTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "STATUT" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "MOTIVAT" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "TIP_ABS" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "ABSENT" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            val dateTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "DATA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "DATE" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "DATA_ABS" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            val discTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "DISCIPLINA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "DISCIPLINE" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "MATERIA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            if (statusTd == null || dateTd == null || discTd == null) continue

            val date = ChisinauTime.parseDate(textOf(dateTd), todayIso)
            val subject = textOf(discTd)
            if (date.isNullOrEmpty() || subject.isEmpty()) continue

            val rawStatusText = textOf(statusTd).lowercase(Locale.ROOT)
            val isUnexcused = UNEXCUSED_MARKERS.any { rawStatusText.contains(it) }
            val isExcused = !isUnexcused && EXCUSED_MARKERS.any { rawStatusText.contains(it) }
            val status = when {
                isExcused -> "excused"
                isUnexcused -> "unexcused"
                else -> "unknown"
            }

            val timeTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "ORA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "TIME" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "LECTIA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            val roomTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "SALA" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "CABINET" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "ROOM" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            val teacherTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "PROFESOR" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "CADRU" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "TEACHER" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            val noteTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "MOTIV" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "NOTE" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "OBSERVATII" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }
            val semTd = tds.firstOrNull {
                headerHas(
                    it,
                    listOf(
                        "SEMESTRU" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                        "SEM" /* UNVERIFIED: needs real capture from capture-absence-sources.mjs */,
                    ),
                )
            }

            val explicitSem = semTd?.let { textOf(it).toIntOrNull() }?.takeIf { it == 1 || it == 2 }
            val rawTime = if (timeTd != null) textOf(timeTd) else ""
            val lessonNum = if (rawTime.isNotEmpty() && DIGITS_ONLY_RE.matches(rawTime)) rawTime.toIntOrNull() else null
            val noteText = if (noteTd != null) textOf(noteTd).ifEmpty { null } else null

            records.add(
                ParsedAbsenceRecord(
                    studentKey = studentKey?.takeIf { it.isNotEmpty() },
                    date = date,
                    lessonNumber = lessonNum,
                    startTime = if (lessonNum != null) "" else rawTime,
                    endTime = "",
                    subject = subject,
                    room = if (roomTd != null) textOf(roomTd).ifEmpty { null } else null,
                    teacher = if (teacherTd != null) textOf(teacherTd).ifEmpty { null } else null,
                    status = status,
                    reason = noteText,
                    semester = inferAbsenceSemester(date, explicitSem),
                    startLocal = rawTime,
                    endLocal = "",
                    discipline = subject,
                    note = noteText,
                    rawStatus = when {
                        isExcused -> "motivat"
                        isUnexcused -> "nemotivat"
                        else -> "necunoscut"
                    },
                ),
            )
        }
        return records
    }

    fun extractEmbeddedAbsenceRecords(
        doc: Element,
        todayIso: String = ChisinauTime.todayChisinau(),
        studentKey: String? = null,
    ): List<ParsedAbsenceRecord> {
        val raw = getInputValue(doc, "P1_JSON_ABSENCES")
        if (raw.isBlank()) return emptyList()
        val list = try {
            SimpleJson.parse(raw) as? List<*>
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val out = mutableListOf<ParsedAbsenceRecord>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val lessonDateRaw = map["lessonDate"]?.toString()
            val date = ChisinauTime.parseDate(lessonDateRaw, todayIso)
            val subject = (map["discipliny"] ?: map["discipline"] ?: map["subject"] ?: "Disciplină")
                .toString()
                .trim()
            if (date.isNullOrEmpty() || subject.isEmpty()) continue

            val rawStatus = (map["absenceName"]?.toString().orEmpty()).lowercase(Locale.ROOT)
            val isUnexcused = UNEXCUSED_MARKERS.any { rawStatus.contains(it) }
            val isExcused = !isUnexcused && EXCUSED_MARKERS.any { rawStatus.contains(it) }
            val status = when {
                isExcused -> "excused"
                isUnexcused -> "unexcused"
                else -> "unknown"
            }
            val rawStatusStr = when {
                isExcused -> "motivat"
                isUnexcused -> "nemotivat"
                else -> "necunoscut"
            }

            val orderRaw = map["lessonOrderId"]
            val lessonNumber = when (orderRaw) {
                is Number -> orderRaw.toInt()
                is String -> orderRaw.trim().takeIf { DIGITS_ONLY_RE.matches(it) }?.toIntOrNull()
                else -> null
            }

            out.add(
                ParsedAbsenceRecord(
                    studentKey = studentKey?.takeIf { it.isNotEmpty() },
                    date = date,
                    lessonNumber = lessonNumber,
                    startTime = "",
                    endTime = "",
                    subject = subject,
                    room = null,
                    teacher = null,
                    status = status,
                    reason = null,
                    semester = inferAbsenceSemester(date),
                    startLocal = "",
                    endLocal = "",
                    discipline = subject,
                    note = null,
                    rawStatus = rawStatusStr,
                ),
            )
        }
        return out
    }

    fun buildSemesterAbsences(
        summary: ParsedAbsenceSummary?,
        rawRecords: List<ParsedAbsenceRecord> = emptyList(),
        studentKey: String? = null,
    ): ParsedAbsencesResult {
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<ParsedAbsenceRecord>()
        for (r in rawRecords) {
            val timeKey = r.startTime.ifEmpty { r.startLocal }
            val subjKey = r.subject.ifEmpty { r.discipline }
            val key = "${r.date.orEmpty()}|${r.lessonNumber ?: ""}|$timeKey|$subjKey"
            if (!seen.add(key)) continue
            val sem = inferAbsenceSemester(r.date, r.semester)
            val subj = subjKey.ifEmpty { "Disciplina" }
            val status = when {
                r.status.isNotEmpty() && r.status != "unknown" -> r.status
                r.rawStatus == "motivat" -> "excused"
                r.rawStatus.isNotEmpty() && r.rawStatus != "necunoscut" -> "unexcused"
                else -> "unknown"
            }
            val rawStatus = r.rawStatus.takeIf { it.isNotEmpty() && it != "necunoscut" } ?: when (status) {
                "excused" -> "motivat"
                "unexcused" -> "nemotivat"
                else -> "necunoscut"
            }
            val startStr = r.startTime.ifEmpty { r.startLocal }
            val endStr = r.endTime.ifEmpty { r.endLocal }
            val reasonStr = r.reason ?: r.note

            deduped.add(
                ParsedAbsenceRecord(
                    studentKey = r.studentKey ?: studentKey?.takeIf { it.isNotEmpty() },
                    date = r.date,
                    lessonNumber = r.lessonNumber,
                    startTime = startStr,
                    endTime = endStr,
                    subject = subj,
                    room = r.room,
                    teacher = r.teacher,
                    status = status,
                    reason = reasonStr,
                    semester = sem,
                    startLocal = startStr,
                    endLocal = endStr,
                    discipline = r.discipline.ifEmpty { subj },
                    note = reasonStr,
                    rawStatus = rawStatus,
                ),
            )
        }

        deduped.sortWith { a, b ->
            val dateCmp = b.date.orEmpty().compareTo(a.date.orEmpty())
            if (dateCmp != 0) return@sortWith dateCmp
            val numCmp = (a.lessonNumber ?: 0).compareTo(b.lessonNumber ?: 0)
            if (numCmp != 0) return@sortWith numCmp
            val timeB = b.startTime.ifEmpty { b.startLocal }
            val timeA = a.startTime.ifEmpty { a.startLocal }
            timeB.compareTo(timeA)
        }

        val sem1Records = deduped.filter { it.semester == 1 }
        val sem2Records = deduped.filter { it.semester == 2 }

        val sem1Motivate = summary?.sem1Excused ?: 0
        val sem1Nemotivate = summary?.sem1Unexcused ?: 0
        val sem1Total = summary?.sem1Total ?: (sem1Motivate + sem1Nemotivate)

        val sem2Motivate = summary?.sem2Excused ?: 0
        val sem2Nemotivate = summary?.sem2Unexcused ?: 0
        val sem2Total = summary?.sem2Total ?: (sem2Motivate + sem2Nemotivate)

        // Gate: Per-lesson details are shown for a semester only if the number of detected records
        // equals the portal's reported total for that semester. If they differ, return no detailed records
        // for that semester (records = emptyList()).
        val sem1DetailsValid = sem1Total > 0 && sem1Records.size == sem1Total
        val effectiveSem1Records = if (sem1DetailsValid) sem1Records else emptyList()

        val sem2DetailsValid = sem2Total > 0 && sem2Records.size == sem2Total
        val effectiveSem2Records = if (sem2DetailsValid) sem2Records else emptyList()

        val finalRecords = effectiveSem1Records + effectiveSem2Records

        val normalizedSummary = ParsedAbsenceSummary(
            presencePercent = summary?.presencePercent ?: 100,
            sem1Excused = sem1Motivate,
            sem1Unexcused = sem1Nemotivate,
            sem1Total = sem1Total,
            sem2Excused = sem2Motivate,
            sem2Unexcused = sem2Nemotivate,
            sem2Total = sem2Total,
            totalExcused = sem1Motivate + sem2Motivate,
            totalUnexcused = sem1Nemotivate + sem2Nemotivate,
            total = sem1Total + sem2Total,
        )

        return ParsedAbsencesResult(
            summary = normalizedSummary,
            records = finalRecords,
            sem1 = ParsedSemesterAbsenceGroup(
                semester = 1,
                total = sem1Total,
                motivate = sem1Motivate,
                nemotivate = sem1Nemotivate,
                records = effectiveSem1Records,
            ),
            sem2 = ParsedSemesterAbsenceGroup(
                semester = 2,
                total = sem2Total,
                motivate = sem2Motivate,
                nemotivate = sem2Nemotivate,
                records = effectiveSem2Records,
            ),
        )
    }
}
