package md.sime.opensime.core.portal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.Permission
import java.security.Principal
import java.security.cert.Certificate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

class SimeHttpTransportTest {

    // -------------------------------------------------------------------------
    // 1. SimeCookieJar: Domain, Path, Secure, Expiry, Eviction, Redaction, Concurrency
    // -------------------------------------------------------------------------

    @Test
    fun `cookie jar stores valid agenda sime md cookies and rejects foreign domains`() {
        val jar = SimeCookieJar()
        val url = "https://agenda.sime.md/ords/r/agenda/agenda-sime/login"

        jar.storeFromResponse(
            requestUrl = url,
            setCookieHeaders = listOf(
                "ORA_WWV_APP_105=SESSION_123; Path=/ords; Secure; HttpOnly",
                "SIME_LANG=ro; Domain=.agenda.sime.md; Path=/",
                "PHISH_TOKEN=steal; Domain=evil.example; Path=/",
                "TLD_COOKIE=bad; Domain=.md; Path=/",
            ),
        )

        assertEquals(2, jar.size)
        val header = jar.cookieHeader("https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
        assertEquals("ORA_WWV_APP_105=SESSION_123; SIME_LANG=ro", header)
    }

    @Test
    fun `cookie jar enforces RFC 6265 path prefix matching and specificity ordering`() {
        val jar = SimeCookieJar()
        jar.storeFromResponse(
            requestUrl = "https://agenda.sime.md/ords/r/agenda/agenda-sime/login",
            setCookieHeaders = listOf(
                "ROOT_COOKIE=1; Path=/",
                "ORDS_COOKIE=2; Path=/ords",
                "AGENDA_COOKIE=3; Path=/ords/r/agenda",
                "OTHER_APP=4; Path=/ords/r/other",
            ),
        )

        val agendaHeader = jar.cookieHeader("https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
        assertEquals("AGENDA_COOKIE=3; ORDS_COOKIE=2; ROOT_COOKIE=1", agendaHeader)

        // /ords-other must NOT match /ords prefix (RFC 6265 boundary check)
        val boundaryHeader = jar.cookieHeader("https://agenda.sime.md/ords-other/page")
        assertEquals("ROOT_COOKIE=1", boundaryHeader)
    }

    @Test
    fun `cookie jar evicts cookies on empty value, non-positive Max-Age, and past Expires`() {
        val now = AtomicLong(1_700_000_000_000L)
        val jar = SimeCookieJar(nowMillis = now::get)
        val url = "https://agenda.sime.md/ords/r/agenda/agenda-sime/home"

        jar.storeFromResponse(
            requestUrl = url,
            setCookieHeaders = listOf(
                "C_EMPTY=initial; Path=/ords",
                "C_MAXAGE=initial; Path=/ords; Max-Age=60",
                "C_EXPIRES=initial; Path=/ords; Expires=Wed, 21 Oct 2099 07:28:00 GMT",
            ),
        )
        assertEquals(3, jar.size)

        // Evict C_EMPTY via empty value and C_EXPIRES via past Expires date
        jar.storeFromResponse(
            requestUrl = url,
            setCookieHeaders = listOf(
                "C_EMPTY=; Path=/ords",
                "C_EXPIRES=gone; Path=/ords; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            ),
        )
        assertEquals("C_MAXAGE=initial", jar.cookieHeader(url))

        // Advance clock by 61 seconds so C_MAXAGE expires naturally
        now.addAndGet(61_000L)
        assertNull(jar.cookieHeader(url))
        assertTrue(jar.isEmpty())

        // Also verify Max-Age=0 immediate deletion
        jar.storeFromResponse(url, listOf("TEMP=1; Path=/ords"))
        assertEquals("TEMP=1", jar.cookieHeader(url))
        jar.storeFromResponse(url, listOf("TEMP=1; Path=/ords; Max-Age=0"))
        assertNull(jar.cookieHeader(url))
    }

    @Test
    fun `cookie jar rejects non-HTTPS or off-host URLs and redacts values in toString`() {
        val jar = SimeCookieJar()
        jar.storeFromResponse(
            "https://agenda.sime.md/ords/r/agenda/agenda-sime/login",
            listOf("ORA_WWV_APP_105=TOP_SECRET_COOKIE_VALUE; Path=/ords; Secure; HttpOnly"),
        )

        val rendered = jar.toString()
        assertEquals("SimeCookieJar(size=1)", rendered)
        assertFalse(rendered.contains("ORA_WWV_APP_105"))
        assertFalse(rendered.contains("TOP_SECRET_COOKIE_VALUE"))

        try {
            jar.cookieHeader("http://agenda.sime.md/ords/r/agenda/agenda-sime/login")
            fail("Expected SimeNetworkError for http:// downgrade")
        } catch (err: SimeNetworkError) {
            assertEquals("Refused to contact a host other than agenda.sime.md", err.message)
        }
    }

    @Test
    fun `cookie jar is thread-safe under concurrent writes and reads`() = runBlocking {
        val jar = SimeCookieJar()
        val url = "https://agenda.sime.md/ords/r/agenda/agenda-sime/home"

        val jobs = (1..24).map { idx ->
            async {
                jar.storeFromResponse(url, listOf("COOKIE_$idx=VAL_$idx; Path=/ords; Secure"))
                val header = jar.cookieHeader(url)
                assertNotNull(header)
            }
        }
        jobs.awaitAll()
        assertEquals(24, jar.size)
    }

    // -------------------------------------------------------------------------
    // 2. RateGate and CircuitBreaker state transitions & concurrency
    // -------------------------------------------------------------------------

    @Test
    fun `RateGate serializes concurrent callers and enforces minInterval plus jitter`() = runBlocking {
        val fakeClock = AtomicLong(10_000L)
        val recordedDelays = Collections.synchronizedList(mutableListOf<Long>())
        val callOrder = Collections.synchronizedList(mutableListOf<Int>())

        val gate = RateGate(
            minIntervalMs = 3_000L,
            jitterMinMs = 200L,
            jitterMaxMs = 500L,
            nowMillis = fakeClock::get,
            sleepMillis = { delayMs ->
                recordedDelays.add(delayMs)
                fakeClock.addAndGet(delayMs)
            },
            randomDouble = { 0.5 }, // deterministic jitter = 200 + 0.5 * 300 = 350ms
        )

        val callers = (1..3).map { id ->
            async {
                gate.wait()
                callOrder.add(id)
            }
        }
        callers.awaitAll()

        assertEquals(listOf(1, 2, 3), callOrder)
        // Call 1 waits only jitter (350ms); Calls 2 and 3 each wait minInterval (3000ms) + jitter (350ms) = 3350ms
        assertEquals(listOf(350L, 3_350L, 3_350L), recordedDelays.toList())
    }

    @Test
    fun `CircuitBreaker trips, blocks with SimeRateLimitError, extends only for longer waits, and recovers`() {
        val fakeClock = AtomicLong(1_000_000L)
        val breaker = CircuitBreaker(nowMillis = fakeClock::get)

        assertFalse(breaker.isOpen)
        assertEquals(0, breaker.remainingSeconds())
        breaker.ensureClosed() // should not throw

        breaker.trip(60)
        assertTrue(breaker.isOpen)
        assertEquals(60, breaker.remainingSeconds())

        // Shorter trip (15s) must NOT shorten existing 60s lockout
        breaker.trip(15)
        assertEquals(60, breaker.remainingSeconds())

        // Longer trip (120s) extends the lockout
        breaker.trip(120)
        assertEquals(120, breaker.remainingSeconds())

        try {
            breaker.ensureClosed()
            fail("Expected SimeRateLimitError while breaker is open")
        } catch (err: SimeRateLimitError) {
            assertEquals(120, err.waitSeconds)
        }

        // Advance 119.5 seconds -> still 1 second remaining (ceil)
        fakeClock.addAndGet(119_500L)
        assertEquals(1, breaker.remainingSeconds())

        // Advance past 120 seconds -> closes automatically
        fakeClock.addAndGet(600L)
        assertFalse(breaker.isOpen)
        assertEquals(0, breaker.remainingSeconds())
        breaker.ensureClosed()
    }

    // -------------------------------------------------------------------------
    // 3. SimeHttpTransport: Redirects, Security Boundaries, Cookies across Hops
    // -------------------------------------------------------------------------

    @Test
    fun `transport follows valid redirects, rewrites 302 POST to GET, and forwards Set-Cookie across hops`() = runBlocking {
        val recordedRequests = mutableListOf<SimeHttpExecutor.RawHopRequest>()
        val transport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor { req ->
                recordedRequests.add(req)
                when (recordedRequests.size) {
                    1 -> SimeHttpExecutor.RawHopResponse(
                        status = 302,
                        headers = mapOf(
                            "Set-Cookie" to listOf("ORA_WWV_APP_105=HOP1_SESSION; Path=/ords; Secure; HttpOnly"),
                            "Location" to listOf("/ords/r/agenda/agenda-sime/step2?p_session=111111111111"),
                        ),
                        bodyBytes = ByteArray(0),
                    )
                    2 -> SimeHttpExecutor.RawHopResponse(
                        status = 307,
                        headers = mapOf(
                            "Set-Cookie" to listOf("SIME_FLOW=HOP2_TOKEN; Path=/ords; Secure"),
                            "Location" to listOf("https://agenda.sime.md/ords/r/agenda/agenda-sime/home?p_session=111111111111"),
                        ),
                        bodyBytes = ByteArray(0),
                    )
                    else -> SimeHttpExecutor.RawHopResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to listOf("text/html; charset=UTF-8")),
                        bodyBytes = "<html>Dashboard OK</html>".toByteArray(StandardCharsets.UTF_8),
                    )
                }
            },
        )

        val response = transport.request(
            method = "POST",
            url = "https://agenda.sime.md/ords/r/agenda/agenda-sime/login",
            data = mapOf("p_t01" to "user.test", "p_t02" to "secret-pass"),
        )

        assertEquals(200, response.status)
        assertEquals("https://agenda.sime.md/ords/r/agenda/agenda-sime/home?p_session=111111111111", response.url)
        assertEquals("<html>Dashboard OK</html>", response.text)
        assertEquals(3, recordedRequests.size)

        // Hop 1 was POST with form body
        assertEquals("POST", recordedRequests[0].method)
        assertNotNull(recordedRequests[0].bodyBytes)

        // Hop 2 followed 302 -> rewritten to GET with null body and carries Hop 1 cookie
        assertEquals("GET", recordedRequests[1].method)
        assertNull(recordedRequests[1].bodyBytes)
        assertEquals("ORA_WWV_APP_105=HOP1_SESSION", recordedRequests[1].headers["Cookie"])

        // Hop 3 followed 307 -> carries both Hop 1 and Hop 2 cookies
        assertEquals("GET", recordedRequests[2].method)
        assertEquals(
            "ORA_WWV_APP_105=HOP1_SESSION; SIME_FLOW=HOP2_TOKEN",
            recordedRequests[2].headers["Cookie"],
        )
    }

    @Test
    fun `transport preserves POST method and body across 307 and 308 redirects`() = runBlocking {
        val recordedRequests = mutableListOf<SimeHttpExecutor.RawHopRequest>()
        val transport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor { req ->
                recordedRequests.add(req)
                if (recordedRequests.size == 1) {
                    SimeHttpExecutor.RawHopResponse(
                        status = 307,
                        headers = mapOf("Location" to listOf("/ords/wwv_flow.accept")),
                        bodyBytes = ByteArray(0),
                    )
                } else {
                    SimeHttpExecutor.RawHopResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to listOf("application/json")),
                        bodyBytes = """{"redirectURL":"https://agenda.sime.md/ords/r/agenda/agenda-sime/home"}""".toByteArray(),
                    )
                }
            },
        )

        transport.request(
            method = "POST",
            url = "https://agenda.sime.md/ords/wwv_flow.ajax",
            data = mapOf("p_request" to "APPLICATION_PROCESS=GetReport"),
            kind = SimeRequestKind.XHR,
        )

        assertEquals(2, recordedRequests.size)
        assertEquals("POST", recordedRequests[1].method)
        assertNotNull(recordedRequests[1].bodyBytes)
    }

    @Test
    fun `transport blocks off-host and HTTP downgrade redirects immediately`() = runBlocking {
        val offHostTransport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor {
                SimeHttpExecutor.RawHopResponse(
                    status = 302,
                    headers = mapOf("Location" to listOf("https://evil.example/phish?token=123")),
                    bodyBytes = ByteArray(0),
                )
            },
        )

        try {
            offHostTransport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/login")
            fail("Expected off-host redirect to throw SimeNetworkError")
        } catch (err: SimeNetworkError) {
            assertEquals("Refused to contact a host other than agenda.sime.md", err.message)
        }

        val downgradeTransport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor {
                SimeHttpExecutor.RawHopResponse(
                    status = 301,
                    headers = mapOf("Location" to listOf("http://agenda.sime.md/ords/r/agenda/agenda-sime/login")),
                    bodyBytes = ByteArray(0),
                )
            },
        )

        try {
            downgradeTransport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/login")
            fail("Expected HTTP downgrade redirect to throw SimeNetworkError")
        } catch (err: SimeNetworkError) {
            assertEquals("Refused to contact a host other than agenda.sime.md", err.message)
        }
    }

    @Test
    fun `transport enforces max 5 redirect hops and detects redirect loops`() = runBlocking {
        val hopCounter = AtomicInteger(0)
        val loopTransport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor {
                val hop = hopCounter.incrementAndGet()
                SimeHttpExecutor.RawHopResponse(
                    status = 302,
                    headers = mapOf("Location" to listOf("/ords/r/agenda/agenda-sime/loop?step=$hop")),
                    bodyBytes = ByteArray(0),
                )
            },
        )

        try {
            loopTransport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/start")
            fail("Expected SimeNetworkError('Too many redirects')")
        } catch (err: SimeNetworkError) {
            assertEquals("Too many redirects", err.message)
            // Initial request + 5 redirects = 6 total hop executions before throwing
            assertEquals(6, hopCounter.get())
        }
    }

    @Test
    fun `transport fails closed when redirect response is missing Location header`() = runBlocking {
        val transport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor {
                SimeHttpExecutor.RawHopResponse(
                    status = 302,
                    headers = emptyMap(),
                    bodyBytes = ByteArray(0),
                )
            },
        )

        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/login")
            fail("Expected SimeNetworkError for missing Location header")
        } catch (err: SimeNetworkError) {
            assertEquals("Redirect without Location header", err.message)
        }
    }

    // -------------------------------------------------------------------------
    // 4. Oversized Bodies, Timeouts, and UrlConnectionHttpExecutor Verification
    // -------------------------------------------------------------------------

    @Test
    fun `readBoundedStream and transport reject responses larger than 8 MiB`() = runBlocking {
        val maxBytes = 1024 // small cap for fast unit test
        val oversizedPayload = ByteArray(maxBytes + 1) { 'A'.code.toByte() }

        try {
            SimeHttpTransport.readBoundedStream(ByteArrayInputStream(oversizedPayload), maxBytes)
            fail("Expected SimeNetworkError('Response body too large')")
        } catch (err: SimeNetworkError) {
            assertEquals("Response body too large", err.message)
        }

        // Also verify Default MAX_BODY_BYTES constant is exactly 8 MiB
        assertEquals(8 * 1024 * 1024, SimeHttpTransport.MAX_BODY_BYTES)

        val transport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            maxBodyBytes = maxBytes,
            executor = SimeHttpExecutor {
                SimeHttpExecutor.RawHopResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to listOf("text/html")),
                    bodyBytes = oversizedPayload,
                )
            },
        )

        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected SimeNetworkError('Response body too large') from transport")
        } catch (err: SimeNetworkError) {
            assertEquals("Response body too large", err.message)
        }
    }

    @Test
    fun `UrlConnectionHttpExecutor enforces 30s timeouts, manual redirects, and Content-Length cap`() = runBlocking {
        var capturedConnection: FakeHttpsURLConnection? = null
        val executor = UrlConnectionHttpExecutor { url ->
            FakeHttpsURLConnection(
                url = url,
                statusCode = 200,
                responseHeaders = mapOf(
                    "Content-Length" to listOf((SimeHttpTransport.MAX_BODY_BYTES + 100L).toString()),
                ),
                responseBody = ByteArray(16),
            ).also { capturedConnection = it }
        }

        val transport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = executor,
        )

        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected oversized Content-Length to throw SimeNetworkError")
        } catch (err: SimeNetworkError) {
            assertEquals("Response body too large", err.message)
        }

        val conn = capturedConnection
        assertNotNull(conn)
        assertFalse(conn!!.instanceFollowRedirects)
        assertEquals(30_000, conn.connectTimeout)
        assertEquals(30_000, conn.readTimeout)
        assertTrue(conn.disconnected)
    }

    @Test
    fun `transport maps SocketTimeoutException to SimeNetworkError with timeout message`() = runBlocking {
        val executor = UrlConnectionHttpExecutor { url ->
            object : FakeHttpsURLConnection(url, 200, emptyMap(), ByteArray(0)) {
                override fun getResponseCode(): Int {
                    throw SocketTimeoutException("Read timed out after 30000ms")
                }
            }
        }
        val transport = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = executor,
        )

        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected SimeNetworkError('Request timed out')")
        } catch (err: SimeNetworkError) {
            assertEquals("Request timed out", err.message)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Status Mapping, Breaker Integration, Session Isolation & Log Redaction
    // -------------------------------------------------------------------------

    @Test
    fun `transport maps 429, 503, 403, 404, 401, and 440 status codes and trips breakers`() = runBlocking {
        var nextStatus = 429
        var retryAfterHeader: String? = "75"
        val accountBreaker = CircuitBreaker()
        val sharedGlobalBreaker = CircuitBreaker()

        val transport = SimeHttpTransport(
            breaker = accountBreaker,
            globalBreaker = sharedGlobalBreaker,
            executor = SimeHttpExecutor {
                val headers = if (retryAfterHeader != null) {
                    mapOf("Retry-After" to listOf(retryAfterHeader!!))
                } else {
                    emptyMap()
                }
                SimeHttpExecutor.RawHopResponse(
                    status = nextStatus,
                    headers = headers,
                    bodyBytes = ByteArray(0),
                )
            },
        )

        // 1. HTTP 429 trips account breaker with Retry-After seconds
        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected SimeRateLimitError")
        } catch (err: SimeRateLimitError) {
            assertEquals(75, err.waitSeconds)
        }
        assertTrue(accountBreaker.isOpen)
        assertFalse(sharedGlobalBreaker.isOpen)
        accountBreaker.reset()

        // 2. HTTP 503 trips global breaker with Retry-After seconds
        nextStatus = 503
        retryAfterHeader = "120"
        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected SimeMaintenanceError")
        } catch (err: SimeMaintenanceError) {
            assertEquals(120, err.waitSeconds)
        }
        assertTrue(sharedGlobalBreaker.isOpen)
        sharedGlobalBreaker.reset()

        // 3. HTTP 403 -> SimeForbiddenError
        nextStatus = 403
        retryAfterHeader = null
        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected SimeForbiddenError")
        } catch (_: SimeForbiddenError) {
            // expected
        }

        // 4. HTTP 404 -> SimeNotFoundError
        nextStatus = 404
        try {
            transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
            fail("Expected SimeNotFoundError")
        } catch (_: SimeNotFoundError) {
            // expected
        }

        // 5. HTTP 401 and 440 -> SimeSessionExpiredError
        for (expiredCode in listOf(401, 440)) {
            nextStatus = expiredCode
            try {
                transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home")
                fail("Expected SimeSessionExpiredError for $expiredCode")
            } catch (_: SimeSessionExpiredError) {
                // expected
            }
        }
    }

    @Test
    fun `separate SimeHttpTransport instances isolate cookies and Referer state`() = runBlocking {
        val transportA = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor {
                SimeHttpExecutor.RawHopResponse(
                    status = 200,
                    headers = mapOf("Set-Cookie" to listOf("ORA_WWV_APP_105=SESSION_STUDENT_A; Path=/ords; Secure")),
                    bodyBytes = "OK A".toByteArray(),
                )
            },
        )

        var capturedHeadersB: Map<String, String> = emptyMap()
        val transportB = SimeHttpTransport(
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor { req ->
                capturedHeadersB = req.headers
                SimeHttpExecutor.RawHopResponse(
                    status = 200,
                    headers = mapOf("Set-Cookie" to listOf("ORA_WWV_APP_105=SESSION_STUDENT_B; Path=/ords; Secure")),
                    bodyBytes = "OK B".toByteArray(),
                )
            },
        )

        transportA.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home?f_elev=1001")
        transportB.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home?f_elev=2002")

        assertNull("Transport B must not receive Transport A cookies", capturedHeadersB["Cookie"])
        assertNull("Transport B must not receive Transport A Referer", capturedHeadersB["Referer"])
        assertEquals(
            "ORA_WWV_APP_105=SESSION_STUDENT_A",
            transportA.cookies.cookieHeader("https://agenda.sime.md/ords/r/agenda/agenda-sime/home"),
        )
        assertEquals(
            "ORA_WWV_APP_105=SESSION_STUDENT_B",
            transportB.cookies.cookieHeader("https://agenda.sime.md/ords/r/agenda/agenda-sime/home"),
        )
    }

    @Test
    fun `SimeHttpResponse toString scrubs URL tokens and never leaks response body text`() {
        val response = SimeHttpResponse(
            status = 200,
            url = "https://agenda.sime.md/ords/r/agenda/agenda-sime/clasa?f_elev=987654&cs=1A2B3C4D&p_session=555555555555",
            text = "<html>Elev: Popescu Ion, Nota: 10, Parola: secret</html>",
        )

        val rendered = response.toString()
        assertFalse("Must not leak student ID", rendered.contains("987654"))
        assertFalse("Must not leak checksum", rendered.contains("1A2B3C4D"))
        assertFalse("Must not leak APEX session", rendered.contains("555555555555"))
        assertFalse("Must not leak response body", rendered.contains("Popescu"))
        assertTrue(rendered.contains("bodyChars=${response.text.length}"))
    }

    @Test
    fun `transport invokes RateGate only when gated is true`() = runBlocking {
        val gateSleeps = AtomicInteger(0)
        val fakeClock = AtomicLong(50_000L)
        val gate = RateGate(
            nowMillis = fakeClock::get,
            sleepMillis = { delayMs ->
                gateSleeps.incrementAndGet()
                fakeClock.addAndGet(delayMs)
            },
            randomDouble = { 0.5 },
        )
        val transport = SimeHttpTransport(
            gate = gate,
            globalBreaker = CircuitBreaker(),
            executor = SimeHttpExecutor {
                SimeHttpExecutor.RawHopResponse(
                    status = 200,
                    headers = emptyMap(),
                    bodyBytes = "OK".toByteArray(),
                )
            },
        )

        // Default gated = false bypasses RateGate
        transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home", gated = false)
        assertEquals(0, gateSleeps.get())

        // Manual refresh with gated = true waits on RateGate
        transport.request("GET", "https://agenda.sime.md/ords/r/agenda/agenda-sime/home", gated = true)
        assertEquals(1, gateSleeps.get())
    }

    // -------------------------------------------------------------------------
    // Helper fake HttpsURLConnection for testing UrlConnectionHttpExecutor
    // -------------------------------------------------------------------------

    private open class FakeHttpsURLConnection(
        url: URL,
        private val statusCode: Int,
        private val responseHeaders: Map<String?, List<String>>,
        private val responseBody: ByteArray,
    ) : HttpsURLConnection(url) {
        var disconnected: Boolean = false
        private val outBuffer = ByteArrayOutputStream()

        override fun getResponseCode(): Int = statusCode
        override fun getHeaderFields(): Map<String?, List<String>> = responseHeaders
        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(responseBody)
        override fun getOutputStream(): OutputStream = outBuffer
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy(): Boolean = false
        override fun connect() {}
        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
        override fun getPermission(): Permission? = null
    }
}
