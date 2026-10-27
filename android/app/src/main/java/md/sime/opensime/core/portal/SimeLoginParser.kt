package md.sime.opensime.core.portal

import md.sime.opensime.core.portal.SimeDomHelpers.getInputValue
import md.sime.opensime.core.portal.SimeDomHelpers.getItemText
import md.sime.opensime.core.portal.SimeDomHelpers.parseHtml
import md.sime.opensime.core.portal.SimeDomHelpers.textOf
import org.jsoup.nodes.Element
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Login dialog discovery, token extraction, redirect decoding/validation, and dashboard/maintenance
 * detection ported from `web/server.js:125`, `286-288`, `1906-2030`, and `2058-2071`.
 */
internal object SimeLoginParser {

    private val MAINTENANCE_RE =
        Regex("""maintenance|mentenan[țt]|lucr[ăa]ri tehnice|temporar indisponibil""", RegexOption.IGNORE_CASE)
    private val ATOB_REDIRECT_RE = Regex("""atob\("([^"]+)"\)""")
    private val SESSION_QUERY_RE = Regex("""[?&;]session=(\d+)""")
    private val NOTIFICATION_MSG_RE = Regex("""notification_msg=([^&"]+)""")
    private val B64_SAFE_RE = Regex("""^[A-Za-z0-9+/=_-]*$""")
    private val WAIT_SECONDS_RE = Regex("""(\d+)\s+seconds""", RegexOption.IGNORE_CASE)

    fun isDashboard(doc: Element): Boolean =
        getInputValue(doc, SimeUrlValidator.ITEM_STUDENT_ID).isNotEmpty() ||
            getItemText(doc, "P1_ELEVE").isNotEmpty() ||
            doc.getElementById("t_TreeNav") != null

    fun isDashboard(html: String?): Boolean = isDashboard(parseHtml(html))

    fun isMaintenancePage(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val doc = parseHtml(html)
        if (isDashboard(doc)) return false
        val bodyText = textOf(doc.body() ?: doc, " ").take(4000)
        return MAINTENANCE_RE.containsMatchIn(bodyText)
    }

    fun findDialogUrl(publicHtml: String?, userType: String): String {
        val doc = parseHtml(publicHtml)
        val needle = "p101_user_type=$userType"
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (!href.contains("login_desktop") || !href.contains(needle)) continue
            val start = href.indexOf('\'')
            val end = if (start != -1) href.indexOf('\'', start + 1) else -1
            if (start == -1 || end == -1) continue
            val rawTarget = SimeUrlValidator.unescapeJs(href.substring(start + 1, end))
            return SimeUrlValidator.resolveSimeUrl(rawTarget)
        }
        throw SimeParseError("Login dialog link not found on the public page")
    }

    fun parseLoginDialogTokens(dialogHtml: String?): PortalLoginDialogTokens {
        val doc = parseHtml(dialogHtml)
        val form = doc.getElementsByTag("form").firstOrNull()
        val action = form?.attr("action")?.trim().orEmpty()
        val inputs = doc.getElementsByTag("input")

        fun inputById(id: String): String? {
            val el = inputs.firstOrNull { it.attr("id") == id } ?: return null
            return if (el.hasAttr("value")) el.attr("value") else null
        }

        val pFlowId = inputById("pFlowId")
        val pFlowStepId = inputById("pFlowStepId")
        val pInstance = inputById("pInstance")
        val pPageSubmissionId = inputById("pPageSubmissionId")
        val pSalt = inputById("pSalt")
        val pPageItemsProtected = inputById("pPageItemsProtected")

        if (
            action.isEmpty() ||
            pFlowId == null ||
            pFlowStepId == null ||
            pInstance == null ||
            pPageSubmissionId == null ||
            pSalt == null ||
            pPageItemsProtected == null
        ) {
            throw SimeParseError("Login form is missing required APEX fields")
        }

        val reloadOnSubmit = inputById("pReloadOnSubmit")?.takeIf { it.isNotEmpty() } ?: "S"
        val checksums = linkedMapOf<String, String>()
        for (input in inputs) {
            if (input.hasAttr("data-for")) {
                checksums[input.attr("data-for")] = input.attr("value")
            }
        }
        val postUrl = SimeUrlValidator.resolveSimeUrl(action, "${SimeUrlValidator.BASE_URL}/ords/")

        return PortalLoginDialogTokens(
            actionUrl = postUrl,
            pFlowId = pFlowId,
            pFlowStepId = pFlowStepId,
            pInstance = pInstance,
            pPageSubmissionId = pPageSubmissionId,
            pSalt = pSalt,
            pPageItemsProtected = pPageItemsProtected,
            pReloadOnSubmit = reloadOnSubmit,
            checksums = checksums,
        )
    }

    fun parseLoginResponseRedirect(responseBody: String?): PortalLoginRedirect {
        val rootObj = try {
            SimpleJson.parse(responseBody.orEmpty()) as? Map<*, *>
        } catch (err: Exception) {
            throw SimeParseError("Login response is not valid JSON", err)
        } ?: throw SimeParseError("Login response is not valid JSON")

        val redirect = rootObj["redirectURL"] as? String
            ?: throw SimeParseError("Login response has no redirectURL")

        if (redirect.contains("notification_msg=")) {
            raiseLoginFailure(redirect)
        }

        val atobMatch = ATOB_REDIRECT_RE.find(redirect)
            ?: throw SimeParseError("Login response has an unrecognized redirect format")

        val target = try {
            val rawBytes = Base64.getDecoder().decode(atobMatch.groupValues[1])
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(rawBytes)).toString()
        } catch (err: Exception) {
            throw SimeParseError("Login redirect target could not be decoded", err)
        }

        val portalUrl = SimeUrlValidator.resolveSimeUrl(target)
        val sessionMatch = SESSION_QUERY_RE.find(portalUrl)
            ?: throw SimeParseError("Login redirect has no session id")

        return PortalLoginRedirect(
            portalUrl = portalUrl,
            sessionId = sessionMatch.groupValues[1],
        )
    }

    private fun raiseLoginFailure(redirect: String): Nothing {
        var message = "Authentication failed"
        var waitSeconds = SimePortalError.DEFAULT_WAIT_SECONDS

        val match = NOTIFICATION_MSG_RE.find(redirect)
        if (match != null) {
            val raw = SimeUrlValidator.safeUnquote(match.groupValues[1]).substringBefore('/')
            val padCount = (4 - (raw.length % 4)) % 4
            val b64 = raw + "=".repeat(padCount)
            if (B64_SAFE_RE.matches(b64)) {
                try {
                    val normalizedB64 = b64.replace('-', '+').replace('_', '/')
                    val decoded = String(Base64.getDecoder().decode(normalizedB64), StandardCharsets.UTF_8)
                        .replace("\uFFFD", "")
                    val text = decoded.replace(Regex("""<[^>]+>"""), " ")
                        .trim()
                        .split(Regex("""\s+"""))
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                        .take(200)
                    if (text.isNotEmpty()) {
                        message = text
                    }
                    val secondsMatch = WAIT_SECONDS_RE.find(text)
                    if (secondsMatch != null) {
                        val parsedSec = secondsMatch.groupValues[1].toIntOrNull()
                        if (parsedSec != null) {
                            waitSeconds = parsedSec.coerceIn(1, SimePortalError.MAX_WAIT_SECONDS)
                        }
                    }
                } catch (_: Exception) {
                    // Keep generic Authentication failed message on malformed base64
                }
            }
        }

        val lowered = message.lowercase(Locale.ROOT)
        if (lowered.contains("blocked") || lowered.contains("wait")) {
            throw SimeRateLimitError(message = message, waitSeconds = waitSeconds)
        }
        throw SimeLoginRejectedError(message = message)
    }

    fun extractPageFlowInfo(html: String?): PortalPageFlowInfo {
        if (html.isNullOrBlank()) {
            return PortalPageFlowInfo(flowId = SimeUrlValidator.FLOW_ID, flowStepId = SimeUrlValidator.FLOW_STEP_ID)
        }
        return extractPageFlowInfo(parseHtml(html))
    }

    fun extractPageFlowInfo(doc: Element): PortalPageFlowInfo {
        val inputs = doc.getElementsByTag("input")
        val flowId = inputs.firstOrNull { it.attr("id") == "pFlowId" || it.attr("name") == "p_flow_id" }
            ?.attr("value")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SimeUrlValidator.FLOW_ID
        val flowStepId = inputs.firstOrNull { it.attr("id") == "pFlowStepId" || it.attr("name") == "p_flow_step_id" }
            ?.attr("value")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SimeUrlValidator.FLOW_STEP_ID
        return PortalPageFlowInfo(flowId = flowId, flowStepId = flowStepId)
    }
}
