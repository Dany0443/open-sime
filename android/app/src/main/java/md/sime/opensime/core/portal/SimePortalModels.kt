package md.sime.opensime.core.portal

import md.sime.opensime.core.model.MarkValue
import md.sime.opensime.core.model.QualifierCode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import java.util.Locale
import kotlin.math.roundToInt

data class SemesterRegionSpec(
    val semester: Int,
    val id: String,
    val selectors: List<String>,
)

data class SubjectScaleEntry(
    val stem: String,
    val scale: String,
)

data class PortalLoginDialogTokens(
    val actionUrl: String,
    val pFlowId: String,
    val pFlowStepId: String,
    val pInstance: String,
    val pPageSubmissionId: String,
    val pSalt: String,
    val pPageItemsProtected: String,
    val pReloadOnSubmit: String,
    val checksums: Map<String, String>,
) {
    override fun toString(): String =
        "PortalLoginDialogTokens(actionUrl=${SimeLogScrubber.scrubUrl(actionUrl)}, pFlowId=$pFlowId, pFlowStepId=$pFlowStepId, pInstance=${SimeLogScrubber.REDACTED}, pPageSubmissionId=${SimeLogScrubber.REDACTED}, pSalt=${SimeLogScrubber.REDACTED}, pPageItemsProtected=${SimeLogScrubber.REDACTED}, checksumKeys=${checksums.keys})"
}

data class PortalLoginRedirect(
    val portalUrl: String,
    val sessionId: String,
) {
    override fun toString(): String =
        "PortalLoginRedirect(portalUrl=${SimeLogScrubber.scrubUrl(portalUrl)}, sessionId=${SimeLogScrubber.REDACTED})"
}

data class PortalPageFlowInfo(
    val flowId: String,
    val flowStepId: String,
)

data class ParsedStudentProfile(
    val id: String,
    val fullName: String,
    val className: String? = null,
    val schoolName: String? = null,
)

data class ParsedStudentMetadata(
    val id: String,
    val fullName: String,
    val className: String? = null,
    val schoolName: String? = null,
    val birthDate: String? = null,
    val permanentCode: String? = null,
    val classMaster: String? = null,
    val address: String? = null,
    val studyYear: String? = null,
    val currentSemester: Int? = null,
    val enrollmentStatus: String? = null,
)

data class ParsedStudentLink(
    val studentId: String,
    val name: String,
    val href: String,
    val isCurrent: Boolean,
)

data class StudentLinkTarget(
    val href: String,
    val displayName: String,
)

data class MergedStudentsResult(
    val students: List<ParsedStudentProfile>,
    val linkMap: Map<String, StudentLinkTarget>,
)

data class ParsedMark(
    val kind: String, // "numeric", "qualifier", or "other"
    val value: Double?,
    val label: String,
    val raw: String,
    val isThesis: Boolean,
    val date: String?,
    val semester: Int?,
    val subject: String? = null,
) {
    fun toDomainMarkValue(): MarkValue = when (kind) {
        "qualifier" -> {
            val code = SimeGradesParser.QUALIFIERS[SimeGradesParser.normalizeQualifierKey(label)]
                ?: QualifierCode.entries.firstOrNull { it.code.equals(label, ignoreCase = true) }
            if (code != null) MarkValue.Qualifier(code) else MarkValue.Other(label)
        }
        "numeric" -> {
            val v = value
            if (v != null && v in 0.0..10.0) {
                MarkValue.Numeric(v)
            } else {
                MarkValue.Other(label.ifBlank { raw })
            }
        }
        else -> MarkValue.Other(label.ifBlank { raw })
    }
}

data class ParsedSubjectReportRow(
    val subject: String,
    val marks: List<ParsedMark>,
    val semesterAverage: Double?,
    val teacher: String?,
)

data class SemesterReportInput(
    val semester: Int,
    val report: Map<String, ParsedSubjectReportRow>,
)

data class CombinedReportResult(
    val sem1: Map<String, ParsedSubjectReportRow>,
    val sem2: Map<String, ParsedSubjectReportRow>,
    val annualMap: MutableMap<String, Double>,
)

data class ParsedSemesterSubjectEntry(
    val semester: Int,
    val marks: List<ParsedMark>,
    val average: Double?,
    val officialAverage: Double?,
)

data class ParsedCatalogSubject(
    val subject: String,
    val discipline: String,
    val teacher: String?,
    val scale: String?,
    val semesters: List<ParsedSemesterSubjectEntry>,
    val markDetails: List<ParsedMark>,
    val markCount: Int,
    val sem1Average: Double?,
    val sem2Average: Double?,
    val annualAverage: Double?,
    val average: Double?,
    val officialPortalAverage: Double? = null,
)

data class ParsedSubjectAverage(
    val subject: String,
    val average: Double,
)

data class ParsedCatalog(
    val subjects: List<ParsedCatalogSubject>,
    val disciplines: List<ParsedCatalogSubject>,
    val sem1GeneralAverage: Double?,
    val sem2GeneralAverage: Double?,
    val annualGeneralAverage: Double?,
    val generalAverage: Double?,
)

data class ParsedHomeworkTask(
    val subject: String,
    val taskDescription: String,
    val dueDate: String?,
    val isCompleted: Boolean = false,
)

data class ParsedAbsenceSummary(
    val presencePercent: Int,
    val sem1Excused: Int,
    val sem1Unexcused: Int,
    val sem1Total: Int,
    val sem2Excused: Int,
    val sem2Unexcused: Int,
    val sem2Total: Int,
    val totalExcused: Int,
    val totalUnexcused: Int,
    val total: Int,
)

data class ParsedAbsenceRecord(
    val studentKey: String? = null,
    val date: String? = null,
    val lessonNumber: Int? = null,
    val startTime: String = "",
    val endTime: String = "",
    val subject: String = "Disciplină",
    val room: String? = null,
    val teacher: String? = null,
    val status: String = "unknown",
    val reason: String? = null,
    val semester: Int = 1,
    val startLocal: String = "",
    val endLocal: String = "",
    val discipline: String = "Disciplină",
    val note: String? = null,
    val rawStatus: String = "necunoscut",
)

data class ParsedSemesterAbsenceGroup(
    val semester: Int,
    val total: Int,
    val motivate: Int,
    val nemotivate: Int,
    val records: List<ParsedAbsenceRecord>,
)

data class ParsedAbsencesResult(
    val summary: ParsedAbsenceSummary,
    val records: List<ParsedAbsenceRecord>,
    val sem1: ParsedSemesterAbsenceGroup,
    val sem2: ParsedSemesterAbsenceGroup,
)

data class RawCalendarEvent(
    val start: String? = null,
    val end: String? = null,
    val title: String? = null,
    val className: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    val cssClass: List<String> = emptyList(),
    val status: List<String> = emptyList(),
    val absenceStatus: List<String> = emptyList(),
    val semester: Int? = null,
)

data class ParsedCalendarEntry(
    val start: Long,
    val end: Long,
    val subject: String,
    val classroom: String?,
    val teacher: String?,
    val topic: String?,
    val isAbsence: Boolean,
    val isExcused: Boolean,
    val isUnexcused: Boolean,
    val semester: Int?,
    val note: String?,
    val periodIndex: Int? = null,
    val lessonNumber: Int? = null,
)

data class ParsedLessonView(
    val periodIndex: Int,
    val start: String,
    val end: String,
    val startLocal: String,
    val endLocal: String,
    val subject: String,
    val classroom: String?,
    val teacher: String?,
    val topic: String?,
)

data class ParsedSchedule(
    val weekStart: String,
    val days: Map<String, List<ParsedLessonView>>,
)

data class ParsedTeacherTokens(
    val parts: List<String>,
    val fullTokens: List<String>,
    val initials: List<String>,
)

data class ParsedTeacher(
    val name: String,
    val subjects: List<String>,
    val lessons: Int,
    val note: String?,
)

data class ParsedPortalPage(
    val student: ParsedStudentProfile,
    val students: List<ParsedStudentProfile>,
    val metadata: ParsedStudentMetadata,
    val grades: List<ParsedMark>,
    val reusita: ParsedCatalog,
    val catalog: ParsedCatalog,
    val averages: List<ParsedSubjectAverage>,
    val generalAverage: Double?,
    val absenceSummary: ParsedAbsenceSummary,
    val absente: ParsedAbsencesResult,
    val absences: ParsedAbsencesResult,
    val timetable: List<ParsedLessonView>,
    val orar: ParsedSchedule,
    val schedule: ParsedSchedule,
    val homework: List<ParsedHomeworkTask>,
    val teachers: List<ParsedTeacher>,
)

data class ParserSectionHealth(
    val count: Int,
    val selectorsMatched: List<String>,
    val fieldsFound: List<String>,
)

data class ParserHealthReport(
    val students: ParserSectionHealth,
    val grades: ParserSectionHealth,
    val catalog: ParserSectionHealth,
    val absencesSummary: ParserSectionHealth,
    val calendar: ParserSectionHealth,
    val teachers: ParserSectionHealth,
)

/**
 * Shared DOM, text, numeric, and CSS selector helpers (`web/server.js:230-284`, `321-338`, `528-531`).
 */
internal object SimeDomHelpers {

    private val NUMBER_RE = Regex("""^[+-]?(\d+\.?\d*|\.\d+)$""")
    private val CASE_INSENSITIVE_ATTR_RE =
        Regex("""^\[\s*([a-zA-Z0-9_-]+)\s*(\*=|=)\s*['"]([^'"]+)['"]\s+i\s*]$""")

    fun parseHtml(html: String?): Document = Jsoup.parse(html.orEmpty())

    fun parseFragment(htmlSnippet: String?): Element =
        Jsoup.parseBodyFragment(htmlSnippet.orEmpty()).body()

    fun textOf(element: Element?, sep: String = ""): String {
        if (element == null) return ""
        if (sep.isEmpty()) return element.text().trim()
        val parts = mutableListOf<String>()
        fun walk(node: Node) {
            for (child in node.childNodes()) {
                when (child) {
                    is TextNode -> {
                        val trimmed = child.wholeText.trim()
                        if (trimmed.isNotEmpty()) parts.add(trimmed)
                    }
                    is Element -> walk(child)
                }
            }
        }
        walk(element)
        return parts.joinToString(sep)
    }

    fun classTokens(element: Element?): List<String> {
        if (element == null) return emptyList()
        val raw = element.attr("class")
        if (raw.isBlank()) return emptyList()
        return raw.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    }

    fun classContains(scope: Element?, vararg needles: String): Element? {
        if (scope == null) return null
        val descendants = scope.allElements.drop(1)
        for (needle in needles) {
            val found = descendants.firstOrNull { el ->
                classTokens(el).any { token -> token.contains(needle) }
            }
            if (found != null) return found
        }
        return null
    }

    fun getInputValue(root: Element, id: String, default: String = ""): String =
        getInputValueOrNull(root, id) ?: default

    fun getInputValueOrNull(root: Element, id: String): String? {
        val inputs = root.getElementsByTag("input")
        val inputEl = inputs.firstOrNull { it.attr("id") == id }
            ?: inputs.firstOrNull { it.attr("name") == id }
        if (inputEl != null) {
            return if (inputEl.hasAttr("value")) inputEl.attr("value") else null
        }
        val selects = root.getElementsByTag("select")
        val sel = selects.firstOrNull { it.attr("id") == id || it.attr("name") == id }
        if (sel != null) {
            val options = sel.getElementsByTag("option")
            val selectedOpt = options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull()
            if (selectedOpt != null) {
                val value = selectedOpt.attr("value")
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    fun getItemChecksum(root: Element, item: String): String? {
        val el = root.getElementsByTag("input").firstOrNull { it.attr("data-for") == item }
        val value = el?.attr("value").orEmpty()
        return value.ifEmpty { null }
    }

    fun getItemText(root: Element, id: String, default: String = ""): String {
        val inputVal = getInputValueOrNull(root, id)
        if (!inputVal.isNullOrEmpty()) return inputVal
        val node = root.allElements.firstOrNull { it.attr("id") == id }
        if (node != null) {
            val tag = node.normalName()
            if (tag != "input" && tag != "select") {
                val text = textOf(node, " ").trim()
                if (text.isNotEmpty()) return text
            }
        }
        return inputVal ?: default
    }

    fun headerHas(td: Element, needles: List<String>): Boolean {
        val raw = td.attr("headers").uppercase(Locale.ROOT)
        if (raw.isBlank()) return false
        val tokens = raw.trim().split(Regex("""\s+"""))
        return tokens.any { token -> needles.any { needle -> token.contains(needle) } }
    }

    fun toFloat(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val normalized = text.trim().replace(',', '.')
        if (!NUMBER_RE.matches(normalized)) return null
        return normalized.toDoubleOrNull()
    }

    fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    fun mean(values: List<Double>): Double? =
        if (values.isEmpty()) null else round2(values.average())

    fun selectCss(root: Element, selector: String): Elements {
        val ciMatch = CASE_INSENSITIVE_ATTR_RE.matchEntire(selector.trim())
        if (ciMatch != null) {
            val attrName = ciMatch.groupValues[1]
            val op = ciMatch.groupValues[2]
            val target = ciMatch.groupValues[3].lowercase(Locale.ROOT)
            val matched = root.allElements.filter { el ->
                if (el === root || !el.hasAttr(attrName)) return@filter false
                val attrVal = el.attr(attrName).lowercase(Locale.ROOT)
                if (op == "*=") attrVal.contains(target) else attrVal == target
            }
            return Elements(matched)
        }
        if (selector.endsWith(" i]")) {
            val prefixTag = selector.substringBefore('[', "").trim()
            val inside = selector.substringAfter('[', "").removeSuffix(" i]").trim()
            val op = if (inside.contains("*=")) "*=" else "="
            val attrName = inside.substringBefore(op).trim()
            val target = inside.substringAfter(op).trim().trim('\'', '"').lowercase(Locale.ROOT)
            val candidates = if (prefixTag.isNotEmpty()) root.getElementsByTag(prefixTag) else root.allElements
            val matched = candidates.filter { el ->
                if (!el.hasAttr(attrName)) return@filter false
                val attrVal = el.attr(attrName).lowercase(Locale.ROOT)
                if (op == "*=") attrVal.contains(target) else attrVal == target
            }
            return Elements(matched)
        }
        return try {
            root.select(selector)
        } catch (_: Exception) {
            Elements()
        }
    }
}

/**
 * Pure-Kotlin RFC 8259 JSON parser used for portal login responses, `P1_JSON_ABSENCES`,
 * and FullCalendar event arrays (avoids Android `org.json` unit-test stubs).
 */
internal object SimpleJson {
    fun parse(source: String): Any? {
        val parser = Reader(source)
        val result = parser.readValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd()) {
            throw IllegalArgumentException("Unexpected trailing JSON content at index ${parser.pos}")
        }
        return result
    }

    fun quoteString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private class Reader(private val s: String) {
        var pos: Int = 0

        fun isAtEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) {
                pos++
            }
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected char '${s[pos]}' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = linkedMapOf<String, Any?>()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                val value = readValue()
                map[key] = value
                skipWhitespace()
                when (val c = nextChar()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Expected ',' or '}' in object, got '$c' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = mutableListOf<Any?>()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                when (val c = nextChar()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Expected ',' or ']' in array, got '$c' at $pos")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < s.length) {
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IllegalArgumentException("Unterminated string escape")
                        when (val esc = s[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun readBoolean(): Boolean {
            if (s.startsWith("true", pos)) {
                pos += 4
                return true
            }
            if (s.startsWith("false", pos)) {
                pos += 5
                return false
            }
            throw IllegalArgumentException("Invalid boolean token at $pos")
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null token at $pos")
        }

        private fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            var isFloating = false
            if (pos < s.length && s[pos] == '.') {
                isFloating = true
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloating = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val token = s.substring(start, pos)
            return if (isFloating) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number '$token'")
            } else {
                token.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer '$token'")
            }
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        private fun nextChar(): Char {
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return s[pos++]
        }

        private fun expect(expected: Char) {
            val actual = nextChar()
            if (actual != expected) {
                throw IllegalArgumentException("Expected '$expected', got '$actual' at ${pos - 1}")
            }
        }
    }
}

