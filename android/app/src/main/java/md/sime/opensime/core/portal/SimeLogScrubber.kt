package md.sime.opensime.core.portal

import java.util.Locale

/**
 * Redacts passwords, session tokens, cookies, APEX salts, submission IDs, and checksums
 * from strings, maps, and lists before any diagnostic or error reporting.
 *
 * Verified source behavior ported from `scrubLog` in `web/server.js:2739-2769`, extended
 * with `P101_PASSWORD` for direct APEX login payload safety.
 */
object SimeLogScrubber {
    const val REDACTED: String = "[REDACTED]"

    private val JSON_SECRET_FIELD_RE = Regex(
        """"(password|P101_PASSWORD|p_t02|token|secret|pSalt|pPageSubmissionId)"\s*:\s*"[^"]*"""",
        RegexOption.IGNORE_CASE,
    )
    private val APEX_ITEM_PASSWORD_JSON_RE = Regex(
        """("n"\s*:\s*"P101_PASSWORD"\s*,\s*"v"\s*:\s*)"[^"]*"""",
        RegexOption.IGNORE_CASE,
    )
    private val QUERY_PASSWORD_RE = Regex(
        """\b(password|P101_PASSWORD|p_t02|pPassword)=[^&\s]+""",
        RegexOption.IGNORE_CASE,
    )
    private val BEARER_TOKEN_RE = Regex(
        """\bBearer\s+[A-Za-z0-9_\-.]+""",
        RegexOption.IGNORE_CASE,
    )
    private val COOKIE_HEADER_RE = Regex(
        """\b(cookie|set-cookie):\s*[^;\r\n]+""",
        RegexOption.IGNORE_CASE,
    )
    private val CHECKSUM_PARAM_RE = Regex(
        """\b(cs|checksum)=[A-Za-z0-9_\-]+""",
        RegexOption.IGNORE_CASE,
    )
    private val SALT_SUBMISSION_PARAM_RE = Regex(
        """\b(pSalt|pPageSubmissionId)=[0-9A-Za-z_\-]+""",
        RegexOption.IGNORE_CASE,
    )
    private val URL_SESSION_STUDENT_PARAM_RE = Regex(
        """\b(f_elev|p_session|p_instance|x01|x02)=[0-9A-Za-z_\-:]+""",
        RegexOption.IGNORE_CASE,
    )

    private val SENSITIVE_KEYS: Set<String> = setOf(
        "password",
        "p101_password",
        "p_t02",
        "token",
        "secret",
        "psalt",
        "ppagesubmissionid",
        "cookie",
        "cookies",
        "set-cookie",
        "checksum",
        "cs",
        "rawpassword",
    )

    /**
     * Redacts sensitive tokens, passwords, cookies, and checksums from [input].
     */
    fun scrubString(input: String): String =
        input
            .replace(JSON_SECRET_FIELD_RE) { match -> "\"${match.groupValues[1]}\":\"$REDACTED\"" }
            .replace(APEX_ITEM_PASSWORD_JSON_RE) { match -> "${match.groupValues[1]}\"$REDACTED\"" }
            .replace(QUERY_PASSWORD_RE) { match -> "${match.groupValues[1]}=$REDACTED" }
            .replace(BEARER_TOKEN_RE, "Bearer $REDACTED")
            .replace(COOKIE_HEADER_RE) { match -> "${match.groupValues[1]}: $REDACTED" }
            .replace(CHECKSUM_PARAM_RE) { match -> "${match.groupValues[1]}=$REDACTED" }
            .replace(SALT_SUBMISSION_PARAM_RE) { match -> "${match.groupValues[1]}=$REDACTED" }
            .replace(URL_SESSION_STUDENT_PARAM_RE) { match -> "${match.groupValues[1]}=$REDACTED" }

    /**
     * Redacts query tokens (`f_elev`, `cs`, `p_session`, `pSalt`, etc.) from a URL string.
     */
    fun scrubUrl(url: String): String = scrubString(url)

    /**
     * Recursively scrubs strings, maps, and collections.
     * Mirrors `scrubLog(input)` in `web/server.js:2739-2769`.
     */
    fun scrubValue(input: Any?): Any? = when (input) {
        null -> null
        is String -> scrubString(input)
        is Map<*, *> -> input.entries.associate { (rawKey, rawValue) ->
            val keyStr = rawKey?.toString()
            if (keyStr != null && keyStr.lowercase(Locale.ROOT) in SENSITIVE_KEYS) {
                rawKey to REDACTED
            } else {
                rawKey to scrubValue(rawValue)
            }
        }
        is Iterable<*> -> input.map { scrubValue(it) }
        else -> input
    }
}

