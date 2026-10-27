package md.sime.opensime.core.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Request kind determining browser navigation vs. AJAX/XHR headers (`web/server.js:1656-1675`).
 */
enum class SimeRequestKind {
    NAVIGATE,
    XHR,
}

/**
 * Sanitized HTTP response returned by [SimeHttpTransport].
 * Overrides [toString] so response bodies never enter logs if a response instance is printed.
 */
data class SimeHttpResponse(
    val status: Int,
    val url: String,
    val text: String,
) {
    override fun toString(): String =
        "SimeHttpResponse(status=$status, url=${SimeLogScrubber.scrubUrl(url)}, bodyChars=${text.length})"
}

/**
 * Low-level single-hop HTTP exchange abstraction used by [SimeHttpTransport].
 * Default production implementation ([UrlConnectionHttpExecutor]) enforces `HttpsURLConnection`
 * with manual redirects disabled (`instanceFollowRedirects = false`), 30-second timeouts,
 * and streaming 8 MiB body size enforcement.
 */
fun interface SimeHttpExecutor {
    data class RawHopRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray?,
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
        val maxBodyBytes: Int,
    )

    data class RawHopResponse(
        val status: Int,
        val headers: Map<String, List<String>>,
        val bodyBytes: ByteArray,
    ) {
        fun firstHeader(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        fun allHeaders(name: String): List<String> =
            headers.entries
                .filter { it.key.equals(name, ignoreCase = true) }
                .flatMap { it.value }
    }

    suspend fun executeHop(request: RawHopRequest): RawHopResponse
}

/**
 * Production [SimeHttpExecutor] backed by `HttpsURLConnection`.
 */
class UrlConnectionHttpExecutor(
    private val connectionOpener: (URL) -> HttpURLConnection = { url ->
        val conn = url.openConnection()
        if (conn !is HttpsURLConnection) {
            throw SimeNetworkError("Refused non-HTTPS connection to agenda.sime.md")
        }
        conn
    },
) : SimeHttpExecutor {

    override suspend fun executeHop(request: SimeHttpExecutor.RawHopRequest): SimeHttpExecutor.RawHopResponse =
        withContext(Dispatchers.IO) {
            val validatedUrl = SimeUrlValidator.requireSimeUrl(request.url)
            val conn = try {
                connectionOpener(URL(validatedUrl))
            } catch (err: CancellationException) {
                throw err
            } catch (err: SimePortalError) {
                throw err
            } catch (err: Exception) {
                throw SimeNetworkError("Network error", err)
            }

            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = request.connectTimeoutMs
                conn.readTimeout = request.readTimeoutMs
                conn.requestMethod = request.method
                conn.useCaches = false

                for ((name, value) in request.headers) {
                    conn.setRequestProperty(name, value)
                }

                val payload = request.bodyBytes
                if (payload != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(payload.size)
                    conn.outputStream.use { out ->
                        out.write(payload)
                        out.flush()
                    }
                }

                val status = conn.responseCode
                val rawHeaders = linkedMapOf<String, MutableList<String>>()
                for ((key, values) in conn.headerFields) {
                    if (key != null && values != null) {
                        rawHeaders.getOrPut(key) { mutableListOf() }.addAll(values.filterNotNull())
                    }
                }

                val isRedirect = status in SimeHttpTransport.REDIRECT_STATUS_CODES
                val bodyBytes = if (isRedirect) {
                    // Drain/close redirect stream without buffering body
                    runCatching { conn.inputStream?.close() }
                    ByteArray(0)
                } else {
                    val declaredLength = rawHeaders.entries
                        .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                        ?.value
                        ?.firstOrNull()
                        ?.trim()
                        ?.toLongOrNull()
                    if (declaredLength != null && declaredLength > request.maxBodyBytes) {
                        throw SimeNetworkError("Response body too large")
                    }
                    val stream: InputStream? = try {
                        if (status >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
                    } catch (_: Exception) {
                        conn.errorStream
                    }
                    SimeHttpTransport.readBoundedStream(stream, request.maxBodyBytes)
                }

                SimeHttpExecutor.RawHopResponse(
                    status = status,
                    headers = rawHeaders,
                    bodyBytes = bodyBytes,
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: SimePortalError) {
                throw err
            } catch (err: SocketTimeoutException) {
                throw SimeNetworkError("Request timed out", err)
            } catch (err: Exception) {
                throw SimeNetworkError("Network error", err)
            } finally {
                conn.disconnect()
            }
        }
}

/**
 * Direct HTTPS transport for `https://agenda.sime.md` with manual redirect validation,
 * 5-hop redirect cap, 30-second timeouts, 8 MiB response size cap, in-memory [SimeCookieJar],
 * [RateGate], and [CircuitBreaker].
 *
 * Verified source behavior ported from `SimeHttp` in `web/server.js:117-121`, `1629-1675`, and `1745-1870`.
 */
class SimeHttpTransport(
    val cookies: SimeCookieJar = SimeCookieJar(),
    val gate: RateGate = RateGate(),
    val breaker: CircuitBreaker = CircuitBreaker(),
    val globalBreaker: CircuitBreaker = GLOBAL_BREAKER,
    private val connectTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val readTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    private val executor: SimeHttpExecutor = UrlConnectionHttpExecutor(),
) {
    private val stateLock = Any()
    private var lastNavigateUrl: String? = null

    val lastUrl: String?
        get() = synchronized(stateLock) { lastNavigateUrl }

    fun clearCookies() {
        cookies.clear()
        synchronized(stateLock) {
            lastNavigateUrl = null
        }
    }

    /**
     * Executes an HTTPS request to `agenda.sime.md`, validating every redirect hop via
     * [SimeUrlValidator.resolveSimeUrl] before following it.
     *
     * Mirrors `SimeHttp.request(method, url, { data, headers, kind, gated })` in `web/server.js:1794-1843`.
     */
    suspend fun request(
        method: String,
        url: String,
        data: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
        kind: SimeRequestKind = SimeRequestKind.NAVIGATE,
        gated: Boolean = false,
    ): SimeHttpResponse {
        var currentMethod = method.trim().uppercase(Locale.ROOT)
        var currentUrl = SimeUrlValidator.requireSimeUrl(url)
        var currentBodyBytes: ByteArray? = data?.let { encodeFormUrlEncoded(it) }

        if (gated) {
            globalBreaker.ensureClosed()
            breaker.ensureClosed()
            gate.wait()
        }

        for (hop in 0..maxRedirects) {
            globalBreaker.ensureClosed()
            breaker.ensureClosed()

            val prevNavigateUrl = synchronized(stateLock) { lastNavigateUrl }
            val mergedHeaders = linkedMapOf<String, String>()
            mergedHeaders.putAll(defaultHeaders(kind = kind, hasReferer = prevNavigateUrl != null))
            mergedHeaders.putAll(headers)

            if (kind == SimeRequestKind.NAVIGATE && prevNavigateUrl != null && !hasHeader(mergedHeaders, "Referer")) {
                mergedHeaders["Referer"] = prevNavigateUrl
            }
            if (currentBodyBytes != null && !hasHeader(mergedHeaders, "Content-Type")) {
                mergedHeaders["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"
            }
            val cookieHeader = cookies.cookieHeader(currentUrl)
            if (!cookieHeader.isNullOrEmpty()) {
                mergedHeaders["Cookie"] = cookieHeader
            }

            val hopResponse = try {
                executor.executeHop(
                    SimeHttpExecutor.RawHopRequest(
                        method = currentMethod,
                        url = currentUrl,
                        headers = mergedHeaders,
                        bodyBytes = currentBodyBytes,
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = readTimeoutMs,
                        maxBodyBytes = maxBodyBytes,
                    ),
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: SimeRateLimitError) {
                breaker.trip(err.waitSeconds)
                throw err
            } catch (err: SimeMaintenanceError) {
                globalBreaker.trip(err.waitSeconds)
                throw err
            } catch (err: SimePortalError) {
                throw err
            } catch (err: SocketTimeoutException) {
                throw SimeNetworkError("Request timed out", err)
            } catch (err: Exception) {
                throw SimeNetworkError("Network error", err)
            }

            cookies.storeFromResponse(currentUrl, hopResponse.allHeaders("Set-Cookie"))

            if (hopResponse.status in REDIRECT_STATUS_CODES) {
                val location = hopResponse.firstHeader("Location")?.trim()
                if (location.isNullOrEmpty()) {
                    throw SimeNetworkError("Redirect without Location header")
                }
                currentUrl = SimeUrlValidator.resolveSimeUrl(location, currentUrl)
                if (hopResponse.status in GET_REWRITE_REDIRECT_CODES) {
                    currentMethod = "GET"
                    currentBodyBytes = null
                }
                continue
            }

            try {
                raiseForStatus(hopResponse)
            } catch (err: SimeRateLimitError) {
                breaker.trip(err.waitSeconds)
                throw err
            } catch (err: SimeMaintenanceError) {
                globalBreaker.trip(err.waitSeconds)
                throw err
            }

            if (hopResponse.bodyBytes.size > maxBodyBytes) {
                throw SimeNetworkError("Response body too large")
            }

            val text = decodeBody(hopResponse.bodyBytes, hopResponse.firstHeader("Content-Type"))
            if (kind == SimeRequestKind.NAVIGATE) {
                synchronized(stateLock) {
                    lastNavigateUrl = currentUrl
                }
            }
            return SimeHttpResponse(
                status = hopResponse.status,
                url = currentUrl,
                text = text,
            )
        }

        throw SimeNetworkError("Too many redirects")
    }

    private fun raiseForStatus(response: SimeHttpExecutor.RawHopResponse) {
        val status = response.status
        if (status < 400) return
        val retryWait = parseRetryAfterSeconds(response.firstHeader("Retry-After"))
        when (status) {
            429 -> throw SimeRateLimitError("HTTP 429 de la agenda.sime.md", retryWait)
            503 -> throw SimeMaintenanceError("HTTP 503 de la agenda.sime.md", retryWait)
            403 -> throw SimeForbiddenError("HTTP 403 de la agenda.sime.md")
            404 -> throw SimeNotFoundError("HTTP 404 de la agenda.sime.md")
            401, 440 -> throw SimeSessionExpiredError("HTTP $status de la agenda.sime.md")
            else -> throw SimeNetworkError("HTTP $status de la agenda.sime.md")
        }
    }

    private fun hasHeader(headers: Map<String, String>, name: String): Boolean =
        headers.keys.any { it.equals(name, ignoreCase = true) }

    companion object {
        const val REQUEST_TIMEOUT_MS: Int = 30_000
        const val MAX_REDIRECTS: Int = 5
        const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        val REDIRECT_STATUS_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)
        private val GET_REWRITE_REDIRECT_CODES: Set<Int> = setOf(301, 302, 303)

        val GLOBAL_BREAKER: CircuitBreaker = CircuitBreaker()

        private const val ACCEPT_NAVIGATE =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        private const val ACCEPT_LANGUAGE = "ro-MD,ro;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val CHARSET_RE = Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)

        internal fun defaultHeaders(
            kind: SimeRequestKind,
            hasReferer: Boolean,
        ): Map<String, String> {
            val base = linkedMapOf(
                "User-Agent" to USER_AGENT,
                "Accept-Language" to ACCEPT_LANGUAGE,
            )
            return if (kind == SimeRequestKind.XHR) {
                base.apply {
                    put("Accept", "*/*")
                    put("Sec-Fetch-Dest", "empty")
                    put("Sec-Fetch-Mode", "cors")
                    put("Sec-Fetch-Site", "same-origin")
                }
            } else {
                base.apply {
                    put("Accept", ACCEPT_NAVIGATE)
                    put("Upgrade-Insecure-Requests", "1")
                    put("Sec-Fetch-Dest", "document")
                    put("Sec-Fetch-Mode", "navigate")
                    put("Sec-Fetch-Site", if (hasReferer) "same-origin" else "none")
                    put("Sec-Fetch-User", "?1")
                }
            }
        }

        internal fun parseRetryAfterSeconds(rawHeader: String?): Int {
            val parsed = rawHeader?.trim()?.toIntOrNull()
                ?: return SimePortalError.DEFAULT_WAIT_SECONDS
            return parsed.coerceIn(1, SimePortalError.MAX_WAIT_SECONDS)
        }

        internal fun decodeBody(bytes: ByteArray, contentType: String?): String {
            if (bytes.isEmpty()) return ""
            val charsetName = contentType?.let { CHARSET_RE.find(it)?.groupValues?.getOrNull(1) }
            val charset = if (!charsetName.isNullOrBlank()) {
                try {
                    Charset.forName(charsetName)
                } catch (_: Exception) {
                    StandardCharsets.UTF_8
                }
            } else {
                StandardCharsets.UTF_8
            }
            return String(bytes, charset)
        }

        internal fun encodeFormUrlEncoded(params: Map<String, String>): ByteArray {
            val encoded = params.entries.joinToString("&") { (k, v) ->
                val ek = java.net.URLEncoder.encode(k, StandardCharsets.UTF_8.name())
                val ev = java.net.URLEncoder.encode(v, StandardCharsets.UTF_8.name())
                "$ek=$ev"
            }
            return encoded.toByteArray(StandardCharsets.UTF_8)
        }

        internal fun readBoundedStream(stream: InputStream?, maxBytes: Int): ByteArray {
            if (stream == null) return ByteArray(0)
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) {
                        throw SimeNetworkError("Response body too large")
                    }
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        }
    }
}

