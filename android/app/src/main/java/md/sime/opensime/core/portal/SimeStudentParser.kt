package md.sime.opensime.core.portal

import md.sime.opensime.core.portal.SimeDomHelpers.getItemText
import md.sime.opensime.core.portal.SimeDomHelpers.textOf
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Student identity, profile, metadata, and `#t_TreeNav` student switch link parsers
 * ported from `web/server.js:340-485`.
 */
internal object SimeStudentParser {

    private val INT_RE = Regex("""^[+-]?\d+$""")
    private val STUDENT_HREF_ID_RE =
        Regex("""(?:user_eleve_id=|p1_eleve_id=|P1_ELEVE_ID:)([^&#:'"]+)""", RegexOption.IGNORE_CASE)

    fun studentKey(id: String?): String = id?.trim()?.lowercase(Locale.ROOT).orEmpty()

    fun normalizeName(name: String?): String =
        name?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("""\s+"""), " ").orEmpty()

    fun parseStudentProfile(doc: Element): ParsedStudentProfile = ParsedStudentProfile(
        id = getItemText(doc, SimeUrlValidator.ITEM_STUDENT_ID),
        fullName = getItemText(doc, "P1_ELEVE"),
        className = getItemText(doc, "P1_CLASS_NAME").ifEmpty { null },
        schoolName = getItemText(doc, "P1_INSTITUTION_NAME").ifEmpty { null },
    )

    fun parseCurrentSemester(doc: Element): Int? {
        val raw = getItemText(doc, "P1_SEMESTER_ID").ifEmpty {
            getItemText(doc, "P1_SEMESTER_NUMBER")
        }.trim()
        return if (INT_RE.matches(raw)) raw.toIntOrNull() else null
    }

    fun parseStudentMetadata(doc: Element): ParsedStudentMetadata {
        val profile = parseStudentProfile(doc)
        val statusRaw = getItemText(doc, "P1_STATUS").ifEmpty {
            getItemText(doc, "P1_ELEVE_STATUS")
        }
        return ParsedStudentMetadata(
            id = profile.id,
            fullName = profile.fullName,
            className = profile.className,
            schoolName = profile.schoolName,
            birthDate = getItemText(doc, "P1_BIRTH_DATE").ifEmpty { null },
            permanentCode = getItemText(doc, "P1_ELEVE_UK").ifEmpty { getItemText(doc, "P1_UK") }.ifEmpty { null },
            classMaster = getItemText(doc, "P1_DIRIGINTE").ifEmpty { null },
            address = getItemText(doc, "P1_ADDRESS").ifEmpty { null },
            studyYear = getItemText(doc, "P1_STUDY_YEAR").ifEmpty { null },
            currentSemester = parseCurrentSemester(doc),
            enrollmentStatus = statusRaw.ifEmpty {
                if (profile.id.isNotEmpty() || profile.fullName.isNotEmpty()) "Activ" else null
            },
        )
    }

    fun parseStudentLinks(doc: Element): List<ParsedStudentLink> {
        val links = mutableListOf<ParsedStudentLink>()
        val tree = doc.getElementById("t_TreeNav")
        if (tree != null) {
            for (li in tree.getElementsByTag("li")) {
                val a = li.select("a[href]").firstOrNull() ?: continue
                val href = a.attr("href")
                val match = STUDENT_HREF_ID_RE.find(href) ?: continue
                links.add(
                    ParsedStudentLink(
                        studentId = SimeUrlValidator.safeUnquote(match.groupValues[1]),
                        name = textOf(a),
                        href = href,
                        isCurrent = li.attr("data-current") == "true",
                    ),
                )
            }
        }
        if (links.isEmpty()) {
            val sel = doc.getElementsByTag("select")
                .firstOrNull {
                    it.attr("id") == SimeUrlValidator.ITEM_STUDENT_ID ||
                        it.attr("name") == SimeUrlValidator.ITEM_STUDENT_ID
                }
            if (sel != null) {
                for (opt in sel.getElementsByTag("option")) {
                    val value = opt.attr("value").trim()
                    if (value.isEmpty()) continue
                    val encodedVal = SimeUrlValidator.encodeUriComponent(value)
                    links.add(
                        ParsedStudentLink(
                            studentId = value,
                            name = textOf(opt).ifEmpty { value },
                            href = "/ords/f?p=AGENDA:1:0::NO::P1_ELEVE_ID:$encodedVal",
                            isCurrent = opt.hasAttr("selected"),
                        ),
                    )
                }
            }
        }
        return links
    }

    fun mergeStudents(
        baseStudent: ParsedStudentProfile?,
        links: List<ParsedStudentLink> = emptyList(),
    ): MergedStudentsResult {
        val baseKey = studentKey(baseStudent?.id)
        val baseName = normalizeName(baseStudent?.fullName)
        val students = mutableListOf<ParsedStudentProfile>()
        val linkMap = linkedMapOf<String, StudentLinkTarget>()

        fun findExistingIndex(id: String?, name: String?): Int {
            val k = studentKey(id)
            val n = normalizeName(name)
            return students.indexOfFirst { s ->
                (k.isNotEmpty() && studentKey(s.id) == k) ||
                    (n.isNotEmpty() && normalizeName(s.fullName) == n)
            }
        }

        for (link in links) {
            val matchesBase =
                (baseKey.isNotEmpty() && studentKey(link.studentId) == baseKey) ||
                    (baseName.isNotEmpty() && normalizeName(link.name) == baseName) ||
                    (link.isCurrent && (baseKey.isNotEmpty() || baseName.isNotEmpty()))
            val canonicalId = if (matchesBase && !baseStudent?.id.isNullOrEmpty()) {
                baseStudent!!.id
            } else {
                link.studentId
            }
            val displayName = when {
                matchesBase && !baseStudent?.fullName.isNullOrEmpty() -> baseStudent!!.fullName
                link.name.isNotEmpty() -> link.name
                else -> canonicalId
            }
            val className = if (matchesBase) baseStudent?.className else null
            val schoolName = if (matchesBase) baseStudent?.schoolName else null

            if (link.href.isNotEmpty()) {
                linkMap[studentKey(canonicalId)] = StudentLinkTarget(href = link.href, displayName = displayName)
                if (link.studentId.isNotEmpty()) {
                    linkMap[studentKey(link.studentId)] = StudentLinkTarget(href = link.href, displayName = displayName)
                }
            }

            val existingIdx = findExistingIndex(canonicalId, displayName)
            if (existingIdx != -1) {
                val ex = students[existingIdx]
                students[existingIdx] = ParsedStudentProfile(
                    id = ex.id.ifEmpty { canonicalId },
                    fullName = ex.fullName.ifEmpty { displayName },
                    className = ex.className ?: className,
                    schoolName = ex.schoolName ?: schoolName,
                )
            } else {
                students.add(
                    ParsedStudentProfile(
                        id = canonicalId,
                        fullName = displayName,
                        className = className,
                        schoolName = schoolName,
                    ),
                )
            }
        }

        if (baseStudent != null && (baseStudent.id.isNotEmpty() || baseStudent.fullName.isNotEmpty())) {
            val existingIdx = findExistingIndex(baseStudent.id, baseStudent.fullName)
            if (existingIdx != -1) {
                val ex = students[existingIdx]
                students[existingIdx] = ParsedStudentProfile(
                    id = baseStudent.id.ifEmpty { ex.id },
                    fullName = baseStudent.fullName.ifEmpty { ex.fullName },
                    className = baseStudent.className ?: ex.className,
                    schoolName = baseStudent.schoolName ?: ex.schoolName,
                )
            } else {
                students.add(
                    0,
                    ParsedStudentProfile(
                        id = baseStudent.id,
                        fullName = baseStudent.fullName,
                        className = baseStudent.className,
                        schoolName = baseStudent.schoolName,
                    ),
                )
            }
        }

        return MergedStudentsResult(students = students, linkMap = linkMap)
    }
}
