package md.sime.opensime.core.portal

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Strict URL validation and Oracle APEX URL builders for `https://agenda.sime.md`.
 *
 * Verified source behavior ported from `web/server.js:86-111`, `127-149`, `221-227`, and `443-449`:
 * - Only `https://agenda.sime.md` on the default HTTPS port (443) with no embedded credentials is permitted.
 * - Any other host, scheme, port, or malformed URI fails closed with [SimeNetworkError].
 */
object SimeUrlValidator {
    const val HOST: String = "agenda.sime.md"
    const val BASE_URL: String = "https://$HOST"
    const val PUBLIC_PORTAL_URL: String = "$BASE_URL/ords/r/agenda/main/public"

    const val FLOW_ID: String = "104"
    const val FLOW_STEP_ID: String = "1"
    const val ITEM_STUDENT_ID: String = "P1_ELEVE_ID"
    const val ITEM_LAST_EVALS: String = "P1_LAST_3_EVAL_LIST"
    const val ITEM_HOMEWORK: String = "P1_HOMEWORK"

    private const val REFUSED_HOST_MESSAGE = "Refused to contact a host other than agenda.sime.md"

    private val JS_ESCAPE_RE = Regex("""\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})|\\([\s\S])""")
    private val FP_SESSION_RE = Regex("""(f\?p=[^:]+:[^:]+:)([^:]*)""")
    private val QUERY_SESSION_RE = Regex("""([?&;:]session=)\d+""", RegexOption.IGNORE_CASE)

    /**
     * Validates that [url] is an absolute `https://agenda.sime.md` URL on the default port (443)
     * with no userinfo (username/password), returning its normalized string representation.
     *
     * Mirrors `requireSimeUrl(url)` in `web/server.js:127-139`.
     */
    fun requireSimeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) {
            throw SimeNetworkError(REFUSED_HOST_MESSAGE)
        }
        // Reject backslash-smuggled authority tricks (e.g. https://agenda.sime.md\@evil.example)
        if (trimmed.contains('\\')) {
            throw SimeNetworkError(REFUSED_HOST_MESSAGE)
        }

        val uri = try {
            URI(trimmed)
        } catch (err: Exception) {
            throw SimeNetworkError(REFUSED_HOST_MESSAGE, err)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        val port = uri.port
        val rawUserInfo = uri.rawUserInfo
        val rawAuthority = uri.rawAuthority

        if (
            scheme != "https" ||
            host != HOST ||
            (port != -1 && port != 443) ||
            !rawUserInfo.isNullOrEmpty() ||
            rawAuthority == null ||
            rawAuthority.contains('@')
        ) {
            throw SimeNetworkError(REFUSED_HOST_MESSAGE)
        }

        val normalizedPath = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
        val queryPart = uri.rawQuery?.let { "?$it" } ?: ""
        val fragmentPart = uri.rawFragment?.let { "#$it" } ?: ""
        return "https://$HOST$normalizedPath$queryPart$fragmentPart"
    }

    /**
     * Resolves a relative or absolute [target] URL against [base] (defaults to `https://agenda.sime.md/`)
     * and validates that the resolved destination stays strictly on `https://agenda.sime.md`.
     *
     * Mirrors `resolveSimeUrl(target, base)` in `web/server.js:141-149`.
     */
    fun resolveSimeUrl(target: String, base: String = "$BASE_URL/"): String {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty() || cleanTarget.contains('\\')) {
            throw SimeNetworkError(REFUSED_HOST_MESSAGE)
        }
        val validatedBase = requireSimeUrl(base)
        val resolved = try {
            URI(validatedBase).resolve(cleanTarget).toString()
        } catch (err: Exception) {
            throw SimeNetworkError(REFUSED_HOST_MESSAGE, err)
        }
        return requireSimeUrl(resolved)
    }

    /**
     * Builds the Oracle APEX AJAX endpoint URL for [sessionId].
     * Mirrors `ajaxUrl(sessionId)` in `web/server.js:89`.
     */
    fun ajaxUrl(sessionId: String): String {
        val cleanSession = sessionId.trim()
        if (cleanSession.isEmpty()) {
            throw SimeParseError("Cannot build AJAX URL without an active APEX session ID")
        }
        val encoded = encodeUriComponent(cleanSession)
        return requireSimeUrl("$BASE_URL/ords/wwv_flow.ajax?p_context=main/main-page/$encoded")
    }

    /**
     * Builds the checksummed student switch URL for [studentId].
     * Never emits a checksum-less switch URL (`web/server.js:90-98`).
     */
    fun studentSwitchUrl(sessionId: String, studentId: String, checksum: String?): String {
        val cleanChecksum = checksum?.trim().orEmpty()
        if (cleanChecksum.isEmpty()) {
            throw SimeStudentNotFoundError("Cannot build student switch URL without APEX checksum")
        }
        val cleanSession = sessionId.trim()
        val cleanStudent = studentId.trim()
        if (cleanSession.isEmpty() || cleanStudent.isEmpty()) {
            throw SimeStudentNotFoundError("Student switch URL requires non-empty sessionId and studentId")
        }
        val relative =
            "f?p=AGENDA:1:${encodeUriComponent(cleanSession)}::NO::$ITEM_STUDENT_ID:${encodeUriComponent(cleanStudent)}&cs=${encodeUriComponent(cleanChecksum)}"
        return resolveSimeUrl(relative, "$BASE_URL/ords/")
    }

    /**
     * Rewrites the session ID inside a scraped `#t_TreeNav` student link (`rawHref`) to [sessionId]
     * while preserving its `cs=` checksum, then validates the resulting URL via [resolveSimeUrl].
     *
     * Mirrors `formatSwitchUrl(rawHref, sessionId)` in `web/server.js:100-105`.
     */
    fun formatSwitchUrl(rawHref: String?, sessionId: String): String? {
        if (rawHref.isNullOrBlank()) return null
        val cleanSession = sessionId.trim()
        var url = FP_SESSION_RE.replaceFirst(rawHref.trim(), "$1$cleanSession")
        url = QUERY_SESSION_RE.replace(url) { match -> "${match.groupValues[1]}$cleanSession" }
        return resolveSimeUrl(url)
    }

    /**
     * Unescapes JavaScript string escapes (`\uXXXX`, `\xXX`, `\'`, etc.) used in APEX dialog links.
     * Mirrors `unescapeJs(value)` in `web/server.js:221-227`.
     */
    fun unescapeJs(value: String): String =
        JS_ESCAPE_RE.replace(value) { match ->
            val uHex = match.groupValues[1]
            val xHex = match.groupValues[2]
            val charEsc = match.groupValues[3]
            when {
                uHex.isNotEmpty() -> uHex.toInt(16).toChar().toString()
                xHex.isNotEmpty() -> xHex.toInt(16).toChar().toString()
                else -> charEsc
            }
        }

    /**
     * URL-decodes [value] if valid percent-encoding is present, or returns [value] unchanged on error.
     * Mirrors `safeUnquote(s)` in `web/server.js:443-449`.
     */
    fun safeUnquote(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }

    internal fun encodeUriComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
}

