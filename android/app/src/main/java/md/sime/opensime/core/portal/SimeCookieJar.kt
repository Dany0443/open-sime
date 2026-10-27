package md.sime.opensime.core.portal

import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Thread-safe, in-memory cookie jar scoped strictly to `https://agenda.sime.md`.
 *
 * Verified source behavior (`web/server.js:1770-1790`, `1809-1818`) extended with RFC 6265
 * domain, path, secure, and expiry (`Max-Age` / `Expires`) enforcement:
 * - Never persists cookies to disk.
 * - Rejects cookies targeting any domain other than `agenda.sime.md`.
 * - Evicts cookies immediately when `value` is empty, `Max-Age <= 0`, or `Expires` is in the past.
 * - Enforces path prefix matching and `Secure` HTTPS transport when building the `Cookie` header.
 * - Never prints cookie names or values in [toString].
 */
class SimeCookieJar(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val expiresAtEpochMillis: Long?, // null = session cookie
    ) {
        fun isExpired(currentEpochMillis: Long): Boolean =
            expiresAtEpochMillis != null && currentEpochMillis >= expiresAtEpochMillis
    }

    private data class CookieKey(
        val name: String,
        val domain: String,
        val path: String,
    )

    private val lock = Any()
    private val cookies = linkedMapOf<CookieKey, StoredCookie>()

    /**
     * Clears all stored cookies in this session jar.
     * Mirrors `clearCookies()` in `web/server.js:1770-1773`.
     */
    fun clear() {
        synchronized(lock) {
            cookies.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) {
            evictExpiredLocked(nowMillis())
            cookies.size
        }

    fun isEmpty(): Boolean = size == 0

    /**
     * Parses and stores `Set-Cookie` header lines received from [requestUrl].
     * Mirrors `storeCookies(res)` in `web/server.js:1775-1786` with domain/path/secure/expiry checks.
     */
    fun storeFromResponse(
        requestUrl: String,
        setCookieHeaders: List<String>,
    ) {
        if (setCookieHeaders.isEmpty()) return
        val validatedUrl = SimeUrlValidator.requireSimeUrl(requestUrl)
        val uri = URI(validatedUrl)
        val requestHost = uri.host.lowercase(Locale.ROOT)
        val defaultPath = defaultCookiePath(uri.rawPath)
        val now = nowMillis()

        synchronized(lock) {
            evictExpiredLocked(now)
            for (rawHeader in setCookieHeaders) {
                if (rawHeader.isBlank()) continue
                val segments = rawHeader.split(';')
                val nameValuePair = segments.firstOrNull()?.trim().orEmpty()
                val eqIdx = nameValuePair.indexOf('=')
                if (eqIdx < 1) continue

                val name = nameValuePair.substring(0, eqIdx).trim()
                val value = nameValuePair.substring(eqIdx + 1).trim()
                if (name.isEmpty()) continue

                var cookieDomain = requestHost
                var cookiePath = defaultPath
                var secure = false
                var httpOnly = false
                var maxAgeSeconds: Long? = null
                var expiresMillis: Long? = null
                var domainRejected = false

                for (attrIdx in 1 until segments.size) {
                    val attr = segments[attrIdx].trim()
                    if (attr.isEmpty()) continue
                    val attrEq = attr.indexOf('=')
                    val attrName = (if (attrEq == -1) attr else attr.substring(0, attrEq))
                        .trim()
                        .lowercase(Locale.ROOT)
                    val attrVal = if (attrEq == -1) "" else attr.substring(attrEq + 1).trim()

                    when (attrName) {
                        "domain" -> {
                            val cleanDomain = attrVal.trimStart('.').lowercase(Locale.ROOT)
                            if (cleanDomain.isEmpty() || cleanDomain != SimeUrlValidator.HOST) {
                                domainRejected = true
                            } else {
                                cookieDomain = cleanDomain
                            }
                        }
                        "path" -> {
                            if (attrVal.startsWith("/")) {
                                cookiePath = attrVal
                            }
                        }
                        "secure" -> secure = true
                        "httponly" -> httpOnly = true
                        "max-age" -> {
                            maxAgeSeconds = attrVal.toLongOrNull()
                        }
                        "expires" -> {
                            expiresMillis = parseHttpDateMillis(attrVal)
                        }
                    }
                }

                if (domainRejected) continue

                // If value is empty or Max-Age <= 0, delete matching cookie name across paths
                if (value.isEmpty() || (maxAgeSeconds != null && maxAgeSeconds <= 0L)) {
                    cookies.keys.removeAll { it.name == name && it.domain == cookieDomain && (it.path == cookiePath || cookiePath == defaultPath) }
                    continue
                }

                // RFC 6265 §5.3: Max-Age takes precedence over Expires when both are present
                val effectiveExpiry: Long? = when {
                    maxAgeSeconds != null -> now + maxAgeSeconds * 1000L
                    expiresMillis != null -> expiresMillis
                    else -> null
                }

                if (effectiveExpiry != null && effectiveExpiry <= now) {
                    cookies.keys.removeAll { it.name == name && it.domain == cookieDomain && (it.path == cookiePath || cookiePath == defaultPath) }
                    continue
                }

                val key = CookieKey(name = name, domain = cookieDomain, path = cookiePath)
                cookies[key] = StoredCookie(
                    name = name,
                    value = value,
                    domain = cookieDomain,
                    path = cookiePath,
                    secure = secure,
                    httpOnly = httpOnly,
                    expiresAtEpochMillis = effectiveExpiry,
                )
            }
        }
    }

    /**
     * Builds the `Cookie` request header value for [requestUrl], or returns `null` when no cookies match.
     * Mirrors `cookieHeader()` in `web/server.js:1788-1790`.
     */
    fun cookieHeader(requestUrl: String): String? {
        val validatedUrl = SimeUrlValidator.requireSimeUrl(requestUrl)
        val uri = URI(validatedUrl)
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host.lowercase(Locale.ROOT)
        val requestPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val now = nowMillis()

        synchronized(lock) {
            evictExpiredLocked(now)
            if (cookies.isEmpty()) return null

            val matching = cookies.values
                .filter { cookie ->
                    cookie.domain == host &&
                        (!cookie.secure || isHttps) &&
                        pathMatches(requestPath, cookie.path)
                }
                // More specific paths first per RFC 6265 §5.4
                .sortedByDescending { it.path.length }

            if (matching.isEmpty()) return null

            // Deduplicate by cookie name (most specific path wins) while preserving insertion order
            val chosenByName = linkedMapOf<String, String>()
            for (c in matching) {
                if (!chosenByName.containsKey(c.name)) {
                    chosenByName[c.name] = c.value
                }
            }
            return chosenByName.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    private fun evictExpiredLocked(now: Long) {
        val iterator = cookies.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired(now)) {
                iterator.remove()
            }
        }
    }

    override fun toString(): String = "SimeCookieJar(size=$size)"

    companion object {
        private val HTTP_DATE_FORMATTERS = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        )

        internal fun defaultCookiePath(rawUriPath: String?): String {
            if (rawUriPath.isNullOrEmpty() || !rawUriPath.startsWith("/")) return "/"
            val lastSlash = rawUriPath.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else rawUriPath.substring(0, lastSlash)
        }

        internal fun pathMatches(requestPath: String, cookiePath: String): Boolean {
            if (requestPath == cookiePath) return true
            if (!requestPath.startsWith(cookiePath)) return false
            return cookiePath.endsWith("/") || requestPath.getOrNull(cookiePath.length) == '/'
        }

        internal fun parseHttpDateMillis(raw: String): Long? {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return null
            for (formatter in HTTP_DATE_FORMATTERS) {
                try {
                    return ZonedDateTime.parse(cleaned, formatter).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    // try next format
                }
            }
            return null
        }
    }
}
