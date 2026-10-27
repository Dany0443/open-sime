package md.sime.opensime.core.portal

import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.core.portal.SimeDomHelpers.classContains
import md.sime.opensime.core.portal.SimeDomHelpers.headerHas
import md.sime.opensime.core.portal.SimeDomHelpers.mean
import md.sime.opensime.core.portal.SimeDomHelpers.parseFragment
import md.sime.opensime.core.portal.SimeDomHelpers.parseHtml
import md.sime.opensime.core.portal.SimeDomHelpers.textOf
import md.sime.opensime.core.portal.SimeDomHelpers.toFloat
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.Locale

/**
 * Marks, qualifiers, theses, recent evaluations, semester/combined gradebook reports,
 * catalog aggregation, and homework parsers ported from `web/server.js:487-924`.
 */
internal object SimeGradesParser {

    private val FALLBACK_GRADE_LI_RE =
        Regex("""^\s*(\S+)\s+(.+?)[:\s]+(f[oó]arte\s+bine|\d+(?:[.,]\d+)?|[A-Za-z.]+)\s*$""", RegexOption.IGNORE_CASE)
    private val MARK_TOKENS_RE = Regex("""f[oó]arte\s+bine|[^\s,]+""", RegexOption.IGNORE_CASE)
    private val TEZA_RE = Regex("""tez[aă]""", RegexOption.IGNORE_CASE)
    private val EDUCATIA_WORD_RE = Regex("""\beducatia\b""")

    // UNVERIFIED: Portal spelling variations for qualifiers.
    // "Foarte bine" reported by maintainer; exact casing/diacritics beyond that are unverified.
    val QUALIFIERS: Map<String, QualifierCode> = linkedMapOf(
        "fb" to QualifierCode.FB,
        "f.b." to QualifierCode.FB,
        "f.b" to QualifierCode.FB,
        "foarte bine" to QualifierCode.FB, /* UNVERIFIED */
        "b" to QualifierCode.B,
        "bine" to QualifierCode.B, /* UNVERIFIED */
        "s" to QualifierCode.S,
        "suficient" to QualifierCode.S, /* UNVERIFIED */
        "i" to QualifierCode.I,
        "insuficient" to QualifierCode.I, /* UNVERIFIED */
    )

    // UNVERIFIED: Subject scale lookup based on maintainer description.
    // Portal captures needed to confirm exact naming variations.
    val SUBJECT_SCALE_REGISTRY: List<SubjectScaleEntry> = listOf(
        SubjectScaleEntry(stem = "educatie fizica", scale = "qualifier"),
        SubjectScaleEntry(stem = "educatie muzicala", scale = "qualifier"),
        SubjectScaleEntry(stem = "educatie plastica", scale = "qualifier"),
        SubjectScaleEntry(stem = "educatie tehnologica", scale = "qualifier"),
        SubjectScaleEntry(stem = "educatie pentru societate", scale = "descriptor"),
    )

    fun normalizeQualifierKey(str: String?): String {
        if (str.isNullOrBlank()) return ""
        val replaced = str.trim()
            .lowercase(Locale.ROOT)
            .replace('\u015F', 's')
            .replace('\u0219', 's')
            .replace('\u0163', 't')
            .replace('\u021B', 't')
        val decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("""[\u0300-\u036f]"""), "")
            .replace(Regex("""\s+"""), " ")
    }

    fun splitMarkTokens(rawText: String?): List<String> {
        if (rawText.isNullOrBlank()) return emptyList()
        val text = rawText.trim()
        if (text.isEmpty()) return emptyList()
        return MARK_TOKENS_RE.findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun normalizeSubjectName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.lowercase(Locale.ROOT).trim()
        s = s.replace('\u015F', 's')
            .replace('\u0219', 's')
            .replace('\u0163', 't')
            .replace('\u021B', 't')
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("""[\u0300-\u036f]"""), "")
        s = EDUCATIA_WORD_RE.replace(s, "educatie")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun getSubjectScale(subjectName: String?): String? {
        val norm = normalizeSubjectName(subjectName)
        if (norm.isEmpty()) return null
        for (entry in SUBJECT_SCALE_REGISTRY) {
            if (norm.contains(entry.stem)) {
                return entry.scale
            }
        }
        return null
    }

    fun normalizeMark(
        rawText: String?,
        rawAttr: String? = null,
        isThesisExplicit: Boolean = false,
        semester: Int? = null,
    ): ParsedMark? {
        if (rawText == null) return null
        val raw = rawText.trim()
        if (raw.isEmpty() || raw == "-" || raw == "–" || raw == "—") return null

        val date = ChisinauTime.parseFullDate(rawAttr)
        val isThesis = isThesisExplicit || (!rawAttr.isNullOrEmpty() && TEZA_RE.containsMatchIn(rawAttr))

        val qualKey = normalizeQualifierKey(raw)
        val qualifier = QUALIFIERS[qualKey]
        if (qualifier != null) {
            return ParsedMark(
                kind = "qualifier",
                value = null,
                label = qualifier.code,
                raw = raw,
                isThesis = isThesis,
                date = date,
                semester = semester,
            )
        }

        val numVal = toFloat(raw)
        if (numVal != null && numVal in 0.0..10.0) {
            val formattedLabel = if (numVal == numVal.toLong().toDouble()) {
                numVal.toLong().toString()
            } else {
                numVal.toString()
            }
            return ParsedMark(
                kind = "numeric",
                value = numVal,
                label = formattedLabel,
                raw = raw,
                isThesis = isThesis,
                date = date,
                semester = semester,
            )
        }

        return ParsedMark(
            kind = "other",
            value = null,
            label = raw,
            raw = raw,
            isThesis = isThesis,
            date = date,
            semester = semester,
        )
    }

    fun parseMarkCell(cell: Element?, semester: Int? = null): List<ParsedMark> {
        if (cell == null) return emptyList()
        val marks = mutableListOf<ParsedMark>()
        val descendants = cell.allElements.drop(1)
        val leaves = descendants.filter { it.childrenSize() == 0 }
        for (leaf in leaves) {
            val text = textOf(leaf)
            val rawAttr = listOf("title", "data-date", "data-title", "aria-label")
                .map { leaf.attr(it) }
                .firstOrNull { it.isNotEmpty() }
                ?: leaf.parent()?.attr("title")?.takeIf { it.isNotEmpty() }
            val leafClass = leaf.attr("class")
            val parentClass = leaf.parent()?.attr("class").orEmpty()
            val isThesis =
                (leafClass.isNotEmpty() && TEZA_RE.containsMatchIn(leafClass)) ||
                    (!rawAttr.isNullOrEmpty() && TEZA_RE.containsMatchIn(rawAttr)) ||
                    (parentClass.isNotEmpty() && TEZA_RE.containsMatchIn(parentClass))

            val tokens = splitMarkTokens(text)
            if (tokens.size > 1) {
                for (tok in tokens) {
                    val m = normalizeMark(tok, rawAttr, isThesis, semester)
                    if (m != null) marks.add(m)
                }
            } else {
                val m = normalizeMark(text, rawAttr, isThesis, semester)
                if (m != null) marks.add(m)
            }
        }
        if (marks.isNotEmpty()) return marks

        for (token in splitMarkTokens(textOf(cell, " "))) {
            val m = normalizeMark(token, null, false, semester)
            if (m != null) marks.add(m)
        }
        return marks
    }

    fun parseRecentGradesFromRoot(
        root: Element,
        defaultSemester: Int? = null,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedMark> {
        val grades = mutableListOf<ParsedMark>()
        for (li in root.getElementsByTag("li")) {
            val dateEl = classContains(li, "eval-date")
            val subjectEl = classContains(li, "eval-discipline")
            val gradeEl = classContains(li, "grade")

            val dateText: String
            val subject: String
            val rawGrade: String

            if (dateEl != null && subjectEl != null && gradeEl != null) {
                dateText = textOf(dateEl)
                subject = textOf(subjectEl).replace(Regex(""":+$"""), "").trim()
                rawGrade = textOf(gradeEl)
            } else {
                val match = FALLBACK_GRADE_LI_RE.matchEntire(textOf(li, " ")) ?: continue
                dateText = match.groupValues[1]
                subject = match.groupValues[2].trim()
                rawGrade = match.groupValues[3]
            }

            if (rawGrade.isEmpty() || subject.isEmpty()) continue
            val day = ChisinauTime.parseDate(dateText, todayIso)
            val sem = ChisinauTime.inferSemester(day, defaultSemester)
            val markObj = normalizeMark(rawGrade, null, false, sem) ?: continue
            grades.add(markObj.copy(date = day, subject = subject))
        }
        return grades
    }

    fun parseRecentGrades(
        htmlSnippet: String?,
        defaultSemester: Int? = null,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedMark> {
        if (htmlSnippet.isNullOrBlank()) return emptyList()
        return parseRecentGradesFromRoot(parseFragment(htmlSnippet), defaultSemester, todayIso)
    }

    fun parseSemesterReportFromRoot(root: Element): Map<String, ParsedSubjectReportRow> {
        val result = linkedMapOf<String, ParsedSubjectReportRow>()
        for (tr in root.getElementsByTag("tr")) {
            val tds = tr.getElementsByTag("td")
            if (tds.isEmpty()) continue
            val disc = tds.firstOrNull {
                headerHas(it, listOf("DISCIPLINA", "DISCIPLINE" /* UNVERIFIED */, "MATERIA" /* UNVERIFIED */, "OBIECT" /* UNVERIFIED */))
            } ?: continue
            val subject = textOf(disc)
            if (subject.isEmpty()) continue

            val evals = tds.firstOrNull {
                headerHas(it, listOf("LISTA_EVALUATION", "EVALUATION" /* UNVERIFIED */, "NOTE" /* UNVERIFIED */, "MARKS" /* UNVERIFIED */))
            }
            val tezaTd = tds.firstOrNull {
                headerHas(it, listOf("TEZA" /* UNVERIFIED */, "NOTA_TEZA" /* UNVERIFIED */, "THESIS" /* UNVERIFIED */))
            }
            val avg = tds.firstOrNull {
                headerHas(it, listOf("MED_SEM" /* UNVERIFIED */, "MEDIA_SEM" /* UNVERIFIED */, "MEDIA" /* UNVERIFIED */))
            }
            val teacherTd = tds.firstOrNull {
                headerHas(it, listOf("PROFESOR", "CADRU" /* UNVERIFIED */, "TEACHER" /* UNVERIFIED */))
            }

            val parsedMarks = mutableListOf<ParsedMark>()
            if (evals != null) {
                parsedMarks.addAll(parseMarkCell(evals))
            }
            if (tezaTd != null) {
                val tezaMarks = parseMarkCell(tezaTd).map { it.copy(isThesis = true) }
                parsedMarks.addAll(tezaMarks)
            }

            result[subject] = ParsedSubjectReportRow(
                subject = subject,
                marks = parsedMarks,
                semesterAverage = if (avg != null) toFloat(textOf(avg)) else null,
                teacher = if (teacherTd != null) textOf(teacherTd).ifEmpty { null } else null,
            )
        }
        return result
    }

    fun parseSemesterReport(reportHtml: String?): Map<String, ParsedSubjectReportRow> {
        if (reportHtml.isNullOrBlank()) return emptyMap()
        return parseSemesterReportFromRoot(parseHtml(reportHtml))
    }

    fun parseCombinedReportFromRoot(
        root: Element,
        annualMap: MutableMap<String, Double> = linkedMapOf(),
    ): CombinedReportResult {
        val sem1 = linkedMapOf<String, ParsedSubjectReportRow>()
        val sem2 = linkedMapOf<String, ParsedSubjectReportRow>()

        for (tr in root.getElementsByTag("tr")) {
            val tds = tr.getElementsByTag("td")
            if (tds.isEmpty()) continue
            val disc = tds.firstOrNull {
                headerHas(it, listOf("DISCIPLINA", "DISCIPLINE", "MATERIA", "OBIECT"))
            } ?: continue
            val subject = textOf(disc)
            if (subject.isEmpty()) continue

            val teacherTd = tds.firstOrNull {
                headerHas(it, listOf("PROFESOR", "CADRU", "TEACHER"))
            }
            val teacher = if (teacherTd != null) textOf(teacherTd).ifEmpty { null } else null

            val annualTd = tds.firstOrNull {
                headerHas(it, listOf("MED_AN" /* UNVERIFIED */, "MEDIA_AN" /* UNVERIFIED */, "MEDIA_GEN" /* UNVERIFIED */, "ANUAL" /* UNVERIFIED */, "ANNUAL" /* UNVERIFIED */))
            }
            if (annualTd != null) {
                val valNum = toFloat(textOf(annualTd))
                if (valNum != null) annualMap[subject] = valNum
            }

            val evals1 = tds.firstOrNull {
                headerHas(it, listOf("LISTA_EVALUATION_1" /* UNVERIFIED */, "EVAL_SEM1" /* UNVERIFIED */, "NOTE_SEM1" /* UNVERIFIED */, "SEM1_NOTE" /* UNVERIFIED */))
            }
            val teza1 = tds.firstOrNull {
                headerHas(it, listOf("TEZA_SEM1" /* UNVERIFIED */, "TEZA1" /* UNVERIFIED */, "NOTA_TEZA1" /* UNVERIFIED */))
            }
            val evals2 = tds.firstOrNull {
                headerHas(it, listOf("LISTA_EVALUATION_2" /* UNVERIFIED */, "EVAL_SEM2" /* UNVERIFIED */, "NOTE_SEM2" /* UNVERIFIED */, "SEM2_NOTE" /* UNVERIFIED */))
            }
            val teza2 = tds.firstOrNull {
                headerHas(it, listOf("TEZA_SEM2" /* UNVERIFIED */, "TEZA2" /* UNVERIFIED */, "NOTA_TEZA2" /* UNVERIFIED */))
            }
            val avg1 = tds.firstOrNull {
                headerHas(it, listOf("MED_SEM1" /* UNVERIFIED */, "MED_SEM_1" /* UNVERIFIED */, "MEDIA_SEM1" /* UNVERIFIED */, "SEM1_MED" /* UNVERIFIED */))
            }
            val avg2 = tds.firstOrNull {
                headerHas(it, listOf("MED_SEM2" /* UNVERIFIED */, "MED_SEM_2" /* UNVERIFIED */, "MEDIA_SEM2" /* UNVERIFIED */, "SEM2_MED" /* UNVERIFIED */))
            }

            if (evals1 != null || avg1 != null || teza1 != null) {
                val m1 = mutableListOf<ParsedMark>()
                if (evals1 != null) m1.addAll(parseMarkCell(evals1))
                if (teza1 != null) {
                    m1.addAll(parseMarkCell(teza1).map { it.copy(isThesis = true) })
                }
                sem1[subject] = ParsedSubjectReportRow(
                    subject = subject,
                    marks = m1,
                    semesterAverage = if (avg1 != null) toFloat(textOf(avg1)) else null,
                    teacher = teacher,
                )
            }
            if (evals2 != null || avg2 != null || teza2 != null) {
                val m2 = mutableListOf<ParsedMark>()
                if (evals2 != null) m2.addAll(parseMarkCell(evals2))
                if (teza2 != null) {
                    m2.addAll(parseMarkCell(teza2).map { it.copy(isThesis = true) })
                }
                sem2[subject] = ParsedSubjectReportRow(
                    subject = subject,
                    marks = m2,
                    semesterAverage = if (avg2 != null) toFloat(textOf(avg2)) else null,
                    teacher = teacher,
                )
            }
        }
        return CombinedReportResult(sem1 = sem1, sem2 = sem2, annualMap = annualMap)
    }

    fun buildCatalog(
        reports: List<SemesterReportInput>,
        annualMap: Map<String, Double> = emptyMap(),
    ): ParsedCatalog {
        class MutableSemEntry(
            val semester: Int,
            var marks: List<ParsedMark>,
            var average: Double?,
            var officialAverage: Double?,
        )
        class MutableSubjectAccum(
            val subject: String,
            var teacher: String? = null,
            val semesters: MutableList<MutableSemEntry> = mutableListOf(),
        )

        val subjects = linkedMapOf<String, MutableSubjectAccum>()
        for ((semester, report) in reports) {
            for ((name, entry) in report) {
                val s = subjects.getOrPut(name) { MutableSubjectAccum(subject = name) }
                if (s.teacher == null) s.teacher = entry.teacher
                val values = entry.marks
                    .filter { it.kind == "numeric" && it.value != null }
                    .mapNotNull { it.value }
                val existing = s.semesters.firstOrNull { it.semester == semester }
                if (existing != null) {
                    if (existing.marks.isEmpty() && entry.marks.isNotEmpty()) {
                        existing.marks = entry.marks
                    }
                    val existVals = existing.marks
                        .filter { it.kind == "numeric" && it.value != null }
                        .mapNotNull { it.value }
                    existing.average = mean(existVals)
                    if (existing.officialAverage == null && entry.semesterAverage !== null) {
                        existing.officialAverage = entry.semesterAverage
                    }
                } else {
                    s.semesters.add(
                        MutableSemEntry(
                            semester = semester,
                            marks = entry.marks,
                            average = mean(values),
                            officialAverage = entry.semesterAverage,
                        ),
                    )
                }
            }
        }

        val list = subjects.values.map { s ->
            val sortedSemesters = s.semesters
                .sortedBy { it.semester }
                .map {
                    ParsedSemesterSubjectEntry(
                        semester = it.semester,
                        marks = it.marks,
                        average = it.average,
                        officialAverage = it.officialAverage,
                    )
                }
            val sem1 = sortedSemesters.firstOrNull { it.semester == 1 }
            val sem2 = sortedSemesters.firstOrNull { it.semester == 2 }
            val sem1Average = sem1?.let { it.officialAverage ?: it.average }
            val sem2Average = sem2?.let { it.officialAverage ?: it.average }

            val markDetails = sortedSemesters.flatMap { semEntry ->
                semEntry.marks.map { m ->
                    ParsedMark(
                        kind = m.kind.ifEmpty { if (m.value != null) "numeric" else "qualifier" },
                        value = m.value,
                        label = m.label.ifEmpty { m.value?.toString() ?: m.raw },
                        raw = m.raw.ifEmpty { m.value?.toString() ?: m.label },
                        isThesis = m.isThesis,
                        date = m.date,
                        semester = semEntry.semester,
                        subject = s.subject,
                    )
                }
            }

            val numericMarks = markDetails
                .filter { it.kind == "numeric" && it.value != null }
                .mapNotNull { it.value }
            val markCount = if (numericMarks.isNotEmpty()) numericMarks.size else markDetails.size
            val official = sortedSemesters.mapNotNull { it.officialAverage }
            val computedAverage = if (numericMarks.isNotEmpty()) {
                mean(numericMarks)
            } else {
                mean(official)
            }
            val explicitAnnual = annualMap[s.subject]
            val annualAverage = explicitAnnual ?: if (sem1Average != null && sem2Average != null) {
                mean(listOf(sem1Average, sem2Average))
            } else {
                computedAverage
            }
            val average = computedAverage ?: annualAverage
            val scale = getSubjectScale(s.subject)

            // Only treat a subject's overall average as non-estimated when the portal provided an explicit
            // annual average (MED_AN), or when only one semester is present and it has an official MED_SEM.
            val officialPortalAverage = explicitAnnual ?: if (sortedSemesters.size == 1) {
                sortedSemesters[0].officialAverage
            } else {
                null
            }

            ParsedCatalogSubject(
                subject = s.subject,
                discipline = s.subject,
                teacher = s.teacher,
                scale = scale,
                semesters = sortedSemesters,
                markDetails = markDetails,
                markCount = markCount,
                sem1Average = sem1Average,
                sem2Average = sem2Average,
                annualAverage = annualAverage,
                average = average,
                officialPortalAverage = officialPortalAverage,
            )
        }.sortedWith { a, b -> a.subject.compareTo(b.subject) }

        val averages = list.mapNotNull { it.average }
        val sem1Avgs = list.mapNotNull { it.sem1Average }
        val sem2Avgs = list.mapNotNull { it.sem2Average }
        val annualAvgs = list.mapNotNull { it.annualAverage }
        val generalAverage = mean(averages)

        return ParsedCatalog(
            subjects = list,
            disciplines = list,
            sem1GeneralAverage = mean(sem1Avgs),
            sem2GeneralAverage = mean(sem2Avgs),
            annualGeneralAverage = mean(annualAvgs) ?: generalAverage,
            generalAverage = generalAverage,
        )
    }

    fun findReportAjaxId(pageHtml: String?, regionStaticId: String): String? {
        if (pageHtml.isNullOrBlank() || regionStaticId.isBlank()) return null
        val escapedId = Regex.escape(regionStaticId)
        val re = Regex("""apex\.widget\.report\.init\(\s*"$escapedId"\s*,\s*"([^"]+)"""")
        val match = re.find(pageHtml) ?: return null
        return SimeUrlValidator.unescapeJs(match.groupValues[1])
    }

    fun parseHomeworkFromRoot(
        root: Element,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedHomeworkTask> {
        val tasks = mutableListOf<ParsedHomeworkTask>()
        var currentDueDate: String? = null
        for (el in root.select("b, li")) {
            if (el.normalName() == "b") {
                currentDueDate = ChisinauTime.parseDate(textOf(el), todayIso)
                continue
            }
            val spans = el.getElementsByTag("span")
            if (spans.size < 2) continue
            val subject = textOf(spans[0]).replace(Regex(""":+$"""), "").trim()
            val taskDescription = textOf(spans[1], " ")
            if (subject.isNotEmpty() || taskDescription.isNotEmpty()) {
                tasks.add(
                    ParsedHomeworkTask(
                        subject = subject,
                        taskDescription = taskDescription,
                        dueDate = currentDueDate,
                        isCompleted = false,
                    ),
                )
            }
        }
        return tasks
    }

    fun parseHomework(
        htmlSnippet: String?,
        todayIso: String = ChisinauTime.todayChisinau(),
    ): List<ParsedHomeworkTask> {
        if (htmlSnippet.isNullOrBlank()) return emptyList()
        return parseHomeworkFromRoot(parseFragment(htmlSnippet), todayIso)
    }
}
