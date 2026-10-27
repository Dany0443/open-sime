package md.sime.opensime.core.portal

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Reproducible, sanitized fixture corpus for `https://agenda.sime.md` authentication
 * and the first read-only student dashboard / gradebook / calendar flows.
 *
 * ### 1. Verified Source Behavior (`web/server.js` & `web/tests/server.test.js`)
 * - Public entry (`GET /ords/r/agenda/main/public`) exposes `login_desktop` links carrying
 *   `p101_user_type=1` (Parent) and `p101_user_type=2` (Student) (`web/server.js:1903-1908`, `2018-2030`).
 * - Login dialog (`GET <dialogUrl>`) contains `<form action="wwv_flow.accept">` and 6 mandatory
 *   hidden inputs: `pFlowId`, `pFlowStepId`, `pInstance`, `pPageSubmissionId`, `pSalt`, and
 *   `pPageItemsProtected`, plus `<input data-for="P101_USER_TYPE">` and
 *   `<input data-for="P101_USER_TYPE_NAME">` (`web/server.js:1910-1927`).
 * - Login submission (`POST /ords/wwv_flow.accept`) returns JSON `{ "redirectURL": "..." }`:
 *   - Success embeds `atob("<base64>")` decoding to a dashboard URL with `session=<digits>` (`web/server.js:1977-1990`).
 *   - Rejection/lockout embeds `notification_msg=<base64>/<checksum>` (`web/server.js:1975`, `1993-2015`).
 * - Dashboard HTML carries `P1_ELEVE_ID`, `data-for="P1_ELEVE_ID"`, `P1_ELEVE`, `P1_CLASS_NAME`,
 *   `P1_INSTITUTION_NAME`, `P1_DIRIGINTE`, `P1_SEMESTER_NUMBER`, `P1_PRESENCE_PERCENT`,
 *   `P1_MOTIVATED`, `P1_UNMOTIVATED`, `P1_TOTAL_ABSENCES1`, `P1_JSON_ABSENCES`,
 *   `P1_LAST_3_EVAL_LIST`, `P1_HOMEWORK`, `#t_TreeNav` student switch links, and
 *   `apex.widget.fullCalendar` / `apex.widget.report.init` AJAX identifiers (`web/server.js:340-485`, `891-929`, `1121-1167`).
 *
 * ### 2. Fixture Facts (`web/scripts/sanitize-capture.mjs` conventions)
 * - Every fixture under `src/test/resources/fixtures/portal/` uses synthetic sanitized placeholders:
 *   - Student names: `Elev 1`, `Elev 2`
 *   - Student IDs: `ID_101` / `ID_501` (Student A), `ID_201` / `ID_601` (Student B)
 *   - Teacher names: `Profesor 1` .. `Profesor 4`
 *   - School names: `Liceul Teoretic Sanitize`, `Gimnaziul Sanitize Beta`
 *   - Session IDs & tokens: `999999`, `111111`, `222222`, `PROT_CHECKSUM_SANITIZED`,
 *     `ITEM_CHECKSUM_SANITIZED`, `CHECKSUM_SANITIZED`
 * - Zero real student names, credentials, cookies, or unredacted portal tokens exist in the corpus.
 *
 * ### 3. Unresolved Portal Assumptions (`UNVERIFIED` in `web/server.js`)
 * - Inline semester report region fallback selectors (`#sem1`, `#SEMESTER_REG_1`, `#R_SEM1`,
 *   `#sem2`, `#SEMESTER_REG_2`, `#R_SEM2` at `web/server.js:113-114`).
 * - Spelled-out qualifier casing/diacritic variations beyond maintainer-reported `"Foarte bine"`
 *   (`"foarte bine"`, `"bine"`, `"suficient"`, `"insuficient"` at `web/server.js:539-545`).
 * - Subject scale registry stems (`SUBJECT_SCALE_REGISTRY` at `web/server.js:569-577`).
 * - Secondary column header aliases in semester/combined report tables (`web/server.js:690-697`, `734-745`).
 * - Calendar inner HTML classes (`.lesson-room`, `.lesson-teacher`, `.lesson-homework`,
 *   `.lesson-line3`, and absence class markers at `web/server.js:973-994`).
 * - Inline HTML absence table column headers (`STATUT`, `MOTIVAT`, `TIP_ABS`, etc. at `web/server.js:1080-1095`).
 */
object SimePortalFixtures {
    const val PUBLIC_ENTRY_HTML = "fixtures/portal/public_entry_parent_and_student.html"
    const val LOGIN_DIALOG_VALID_HTML = "fixtures/portal/login_dialog_valid.html"
    const val LOGIN_DIALOG_INCOMPLETE_HTML = "fixtures/portal/login_dialog_incomplete_missing_salt.html"
    const val LOGIN_RESPONSE_SUCCESS_JSON = "fixtures/portal/login_response_success.json"
    const val LOGIN_RESPONSE_REJECTED_JSON = "fixtures/portal/login_response_rejected.json"
    const val LOGIN_RESPONSE_THROTTLED_JSON = "fixtures/portal/login_response_throttled.json"
    const val LOGIN_RESPONSE_UNTRUSTED_REDIRECT_JSON = "fixtures/portal/login_response_untrusted_redirect.json"
    const val DASHBOARD_STUDENT_A_HTML = "fixtures/portal/dashboard_student_a_sanitized.html"
    const val DASHBOARD_STUDENT_B_HTML = "fixtures/portal/dashboard_student_b_sanitized.html"
    const val REPORT_SEM1_HTML = "fixtures/portal/report_sem1_marks_theses_qualifiers.html"
    const val CALENDAR_EVENTS_WEEK_JSON = "fixtures/portal/calendar_events_week_sanitized.json"

    val ALL_FIXTURE_PATHS: List<String> = listOf(
        PUBLIC_ENTRY_HTML,
        LOGIN_DIALOG_VALID_HTML,
        LOGIN_DIALOG_INCOMPLETE_HTML,
        LOGIN_RESPONSE_SUCCESS_JSON,
        LOGIN_RESPONSE_REJECTED_JSON,
        LOGIN_RESPONSE_THROTTLED_JSON,
        LOGIN_RESPONSE_UNTRUSTED_REDIRECT_JSON,
        DASHBOARD_STUDENT_A_HTML,
        DASHBOARD_STUDENT_B_HTML,
        REPORT_SEM1_HTML,
        CALENDAR_EVENTS_WEEK_JSON,
    )

    val REQUIRED_LOGIN_TOKENS: List<String> = listOf(
        "pFlowId",
        "pFlowStepId",
        "pInstance",
        "pPageSubmissionId",
        "pSalt",
        "pPageItemsProtected",
    )

    private val FORM_ACTION_RE = Regex("""<form\b[^>]*\baction=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val INPUT_ID_VALUE_RE = Regex(
        """<input\b[^>]*\bid=["']([^"']+)["'][^>]*\bvalue=["']([^"']*)["']|<input\b[^>]*\bvalue=["']([^"']*)["'][^>]*\bid=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val REDIRECT_URL_FIELD_RE = Regex(""""redirectURL"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val ATOB_RE = Regex("""atob\("([^"]+)"\)""")
    private val SESSION_RE = Regex("""[?&;]session=(\d+)""")
    private val NOTIFICATION_MSG_RE = Regex("""notification_msg=([^&"]+)""")
    private val WAIT_SECONDS_RE = Regex("""(\d+)\s+seconds""", RegexOption.IGNORE_CASE)

    /**
     * Reads a UTF-8 fixture file from `src/test/resources/`.
     */
    fun readFixture(resourcePath: String): String {
        val stream = SimePortalFixtures::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: throw SimeParseError("Missing test fixture resource: $resourcePath")
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    /**
     * Validates that a login dialog HTML fixture contains `<form action="...">` and all 6 required
     * hidden Oracle APEX tokens (`web/server.js:1911-1922`).
     * Throws [SimeParseError] if incomplete or malformed.
     */
    fun validateLoginDialogHtml(dialogHtml: String): Map<String, String> {
        val action = FORM_ACTION_RE.find(dialogHtml)?.groupValues?.get(1)?.trim()
        val inputs = mutableMapOf<String, String>()
        for (match in INPUT_ID_VALUE_RE.findAll(dialogHtml)) {
            val id = match.groupValues[1].ifEmpty { match.groupValues[4] }
            val value = if (match.groupValues[1].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            if (id.isNotEmpty()) {
                inputs[id] = value
            }
        }
        if (action.isNullOrEmpty() || REQUIRED_LOGIN_TOKENS.any { inputs[it].isNullOrEmpty() }) {
            throw SimeParseError("Login form is missing required APEX fields")
        }
        // Validate that the form action resolves onto https://agenda.sime.md/ords/
        val postUrl = SimeUrlValidator.resolveSimeUrl(action, "${SimeUrlValidator.BASE_URL}/ords/")
        return inputs + ("actionUrl" to postUrl)
    }

    /**
     * Validates a `wwv_flow.accept` JSON login response fixture (`web/server.js:1965-2015`),
     * returning the validated `(portalUrl, sessionId)` pair or throwing the exact [SimePortalError].
     */
    fun validateLoginResponseJson(responseBody: String): Pair<String, String> {
        val trimmed = responseBody.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw SimeParseError("Login response is not valid JSON")
        }
        val redirectMatch = REDIRECT_URL_FIELD_RE.find(trimmed)
            ?: throw SimeParseError("Login response has no redirectURL")
        val redirect = SimeUrlValidator.unescapeJs(redirectMatch.groupValues[1])

        if (redirect.contains("notification_msg=")) {
            var message = "Authentication failed"
            var waitSeconds = SimePortalError.DEFAULT_WAIT_SECONDS
            val msgMatch = NOTIFICATION_MSG_RE.find(redirect)
            if (msgMatch != null) {
                val raw = SimeUrlValidator.safeUnquote(msgMatch.groupValues[1]).substringBefore('/')
                val padded = raw + "=".repeat((4 - (raw.length % 4)) % 4)
                val decoded = try {
                    String(Base64.getUrlDecoder().decode(padded.replace('+', '-').replace('/', '_')), StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    ""
                }
                val cleanText = decoded
                    .replace("\uFFFD", "")
                    .replace(Regex("<[^>]+>"), " ")
                    .trim()
                    .replace(Regex("\\s+"), " ")
                    .take(200)
                if (cleanText.isNotEmpty()) {
                    message = cleanText
                }
                val secMatch = WAIT_SECONDS_RE.find(cleanText)
                if (secMatch != null) {
                    val parsed = secMatch.groupValues[1].toIntOrNull()
                    if (parsed != null) {
                        waitSeconds = parsed.coerceIn(1, SimePortalError.MAX_WAIT_SECONDS)
                    }
                }
            }
            val lowered = message.lowercase()
            if (lowered.contains("blocked") || lowered.contains("wait")) {
                throw SimeRateLimitError(message, waitSeconds)
            }
            throw SimeLoginRejectedError(message)
        }

        val atobMatch = ATOB_RE.find(redirect)
            ?: throw SimeParseError("Login response has an unrecognized redirect format")
        val decodedTarget = try {
            String(Base64.getDecoder().decode(atobMatch.groupValues[1]), StandardCharsets.UTF_8)
        } catch (err: Exception) {
            throw SimeParseError("Login redirect target could not be decoded", err)
        }

        val portalUrl = SimeUrlValidator.resolveSimeUrl(decodedTarget)
        val sessionId = SESSION_RE.find(portalUrl)?.groupValues?.get(1)
            ?: throw SimeParseError("Login redirect has no session id")
        return portalUrl to sessionId
    }
}

