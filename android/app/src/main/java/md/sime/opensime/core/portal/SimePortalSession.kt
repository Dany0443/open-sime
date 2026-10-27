package md.sime.opensime.core.portal

import kotlinx.coroutines.sync.Mutex
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Verified dashboard HTML page for a single student in the current in-memory session.
 * Overrides [toString] so HTML bodies, URL tokens, and student identifiers never leak to logs.
 */
data class PortalStudentDashboardPage(
    val url: String,
    val html: String,
    val studentKey: String,
    val fetchedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "PortalStudentDashboardPage(url=${SimeLogScrubber.scrubUrl(url)}, studentKey=${SimeLogScrubber.REDACTED}, htmlChars=${html.length})"
}

/**
 * Result of a completed 4-step Oracle APEX login handshake and landing dashboard verification.
 * Overrides [toString] to redact session and student identifiers.
 */
data class PortalHandshakeResult(
    val sessionId: String,
    val portalUrl: String,
    val role: AccountRole,
    val initialStudentKey: String,
    val students: List<ParsedStudentProfile>,
    val landingPage: PortalStudentDashboardPage,
) {
    override fun toString(): String =
        "PortalHandshakeResult(role=$role, sessionId=${SimeLogScrubber.REDACTED}, portalUrl=${SimeLogScrubber.scrubUrl(portalUrl)}, studentCount=${students.size})"
}

/**
 * Low-level in-memory Oracle APEX portal session owning the 4-step login sequence,
 * `#t_TreeNav` student link registration, alias canonicalization, and checksummed student switching.
 *
 * Verified source behavior ported from `SimeAuth`, `executeHandshake`, and `SimeClient` in
 * `web/server.js:1876-2427`:
 * - Never persists passwords, cookies, tokens, or session IDs to disk.
 * - Never retains the plaintext password after [login] completes.
 * - Dynamically extracts the login form `action` URL and required APEX tokens from the dialog HTML.
 * - Verifies landing and switched pages with [SimePortalParser.isDashboard] and student ownership checks.
 * - Clears all in-memory state and cookies on [clear] and on any terminal authentication failure.
 */
class SimePortalSession(
    val http: SimeHttpTransport = SimeHttpTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val todayProvider: () -> String = ChisinauTime::todayChisinau,
    val pageTtlMillis: Long = DEFAULT_PAGE_TTL_MS,
) {
    val sessionLock = Mutex()

    private val stateLock = Any()

    private var _sessionId: String? = null
    private var _portalUrl: String? = null
    private var _role: AccountRole? = null
    private var _credentialsRejected: Boolean = false
    private var _currentStudentKey: String = ""
    private var _sessionGeneration: Long = 0L

    val sessionGeneration: Long
        get() = synchronized(stateLock) { _sessionGeneration }

    // alias key (normalized lowercase) -> canonical student key (normalized lowercase)
    private val aliases = linkedMapOf<String, String>()
    // canonical student key -> merged student profile
    private val knownStudents = linkedMapOf<String, ParsedStudentProfile>()
    // canonical or alias student key -> validated checksummed switch URL
    private val studentUrls = linkedMapOf<String, String>()
    // canonical student key -> verified dashboard page for that student
    private val studentPages = linkedMapOf<String, PortalStudentDashboardPage>()

    val sessionId: String?
        get() = synchronized(stateLock) { _sessionId }

    val portalUrl: String?
        get() = synchronized(stateLock) { _portalUrl }

    val role: AccountRole?
        get() = synchronized(stateLock) { _role }

    val credentialsRejected: Boolean
        get() = synchronized(stateLock) { _credentialsRejected }

    val currentStudentKey: String
        get() = synchronized(stateLock) { _currentStudentKey }

    val isAuthenticated: Boolean
        get() = synchronized(stateLock) { _sessionId != null && _portalUrl != null }

    /**
     * Clears all in-memory authentication state, student mappings, cached pages, and session cookies.
     * Mirrors `SimeClient.resetSession()` (`web/server.js:2091-2099`).
     */
    fun clear(preserveRejectedFlag: Boolean = false) {
        synchronized(stateLock) {
            _sessionId = null
            _portalUrl = null
            _role = null
            _currentStudentKey = ""
            _sessionGeneration += 1L
            if (!preserveRejectedFlag) {
                _credentialsRejected = false
            }
            aliases.clear()
            knownStudents.clear()
            studentUrls.clear()
            studentPages.clear()
        }
        http.clearCookies()
    }

    /**
     * Lists known students in first-seen order with their canonical IDs.
     * Mirrors `SimeClient.listStudents()` (`web/server.js:2171-2173`).
     */
    fun listStudents(): List<ParsedStudentProfile> = synchronized(stateLock) {
        knownStudents.values.toList()
    }

    /**
     * Resolves any known alias of a student to its canonical key, or returns `""` if unknown.
     * Never falls back to the first student.
     * Mirrors `SimeClient.canon(id)` (`web/server.js:2166-2168`).
     */
    fun canonicalStudentKey(rawStudentId: String?): String {
        val normalized = SimeStudentParser.studentKey(rawStudentId)
        if (normalized.isEmpty()) return ""
        return synchronized(stateLock) {
            aliases[normalized].orEmpty()
        }
    }

    /**
     * Seeds known students for testing or pre-verified session state.
     * Mirrors `SimeClient.seedStudents(list)` (`web/server.js:2176-2183`).
     */
    internal fun seedStudents(
        students: List<ParsedStudentProfile>,
        switchUrlsByStudentId: Map<String, String> = emptyMap(),
    ) {
        synchronized(stateLock) {
            for (student in students) {
                val k = SimeStudentParser.studentKey(student.id)
                if (k.isEmpty()) continue
                val canon = aliases.getOrPut(k) { k }
                mergeKnownLocked(canon, student)
            }
            for ((rawId, rawUrl) in switchUrlsByStudentId) {
                val k = SimeStudentParser.studentKey(rawId)
                if (k.isEmpty()) continue
                val validated = SimeUrlValidator.resolveSimeUrl(rawUrl)
                studentUrls[k] = validated
                val canon = aliases[k]
                if (!canon.isNullOrEmpty()) {
                    studentUrls[canon] = validated
                }
            }
        }
    }

    /**
     * Executes the 4-step Oracle APEX login sequence (`web/server.js:1896-2054`, `2115-2125`):
     * 1. `GET /ords/r/agenda/main/public` -> extract role-specific `login_desktop` dialog URL.
     * 2. `GET <dialogUrl>` -> extract dynamic `<form action>` and required hidden APEX inputs + checksums.
     * 3. `POST <actionUrl>` (`wwv_flow.ajax` / `wwv_flow.accept`) with `p_request=LOGIN` and `p_json`.
     * 4. `GET <redirect.portalUrl>` -> verify dashboard markers and register student profiles/links.
     */
    suspend fun login(
        username: String,
        password: String,
        role: AccountRole = AccountRole.PARENT,
        force: Boolean = false,
    ): PortalHandshakeResult {
        http.globalBreaker.ensureClosed()
        http.breaker.ensureClosed()

        synchronized(stateLock) {
            if (_credentialsRejected && !force) {
                throw SimeLoginRejectedError("Credentials were rejected earlier; log in again to retry")
            }
        }

        val cleanUsername = username.trim()
        if (
            cleanUsername.isEmpty() ||
            password.isEmpty() ||
            cleanUsername.length > MAX_USERNAME_LENGTH ||
            password.length > MAX_PASSWORD_LENGTH
        ) {
            clear(preserveRejectedFlag = false)
            throw SimeLoginRejectedError("Username and password are required")
        }

        clear(preserveRejectedFlag = false)

        val userType = if (role == AccountRole.PARENT) USER_TYPE_PARENT else USER_TYPE_STUDENT
        val userTypeName = if (role == AccountRole.PARENT) USER_TYPE_NAME_PARENT else USER_TYPE_NAME_STUDENT

        try {
            // Step 1: Fetch public entry page and locate login dialog URL
            val publicResp = http.request(
                method = "GET",
                url = SimeUrlValidator.PUBLIC_PORTAL_URL,
                kind = SimeRequestKind.NAVIGATE,
                gated = false,
            )
            val dialogUrl = SimePortalParser.findDialogUrl(publicResp.text, userType)

            // Step 2: Fetch login dialog HTML and parse required APEX tokens & dynamic form action
            val dialogResp = http.request(
                method = "GET",
                url = dialogUrl,
                kind = SimeRequestKind.NAVIGATE,
                gated = false,
            )
            val tokens = SimePortalParser.parseLoginDialogTokens(dialogResp.text)

            // Step 3: Submit credentials via XHR POST to the portal-supplied form action URL
            val payloadJson = buildLoginPayloadJson(
                salt = tokens.pSalt,
                protectedItems = tokens.pPageItemsProtected,
                userType = userType,
                userTypeChecksum = tokens.checksums["P101_USER_TYPE"],
                userTypeName = userTypeName,
                userTypeNameChecksum = tokens.checksums["P101_USER_TYPE_NAME"],
                username = cleanUsername,
                password = password,
            )

            val loginResp = http.request(
                method = "POST",
                url = tokens.actionUrl,
                data = linkedMapOf(
                    "p_flow_id" to tokens.pFlowId,
                    "p_flow_step_id" to tokens.pFlowStepId,
                    "p_instance" to tokens.pInstance,
                    "p_page_submission_id" to tokens.pPageSubmissionId,
                    "p_request" to "LOGIN",
                    "p_reload_on_submit" to tokens.pReloadOnSubmit,
                    "p_json" to payloadJson,
                ),
                headers = linkedMapOf(
                    "Referer" to dialogUrl,
                    "Origin" to SimeUrlValidator.BASE_URL,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                kind = SimeRequestKind.XHR,
                gated = false,
            )

            val redirect = try {
                SimePortalParser.parseLoginResponseRedirect(loginResp.text)
            } catch (err: SimeRateLimitError) {
                http.breaker.trip(err.waitSeconds)
                throw err
            } catch (err: SimeLoginRejectedError) {
                synchronized(stateLock) {
                    _credentialsRejected = true
                }
                throw err
            }

            // Step 4: Fetch landing dashboard and verify it is an authenticated dashboard page
            val landingResp = http.request(
                method = "GET",
                url = redirect.portalUrl,
                kind = SimeRequestKind.NAVIGATE,
                gated = false,
            )
            val landingPath = URI(landingResp.url).rawPath?.lowercase(Locale.ROOT).orEmpty()
            if (landingPath.endsWith("/public") || landingPath.contains("login")) {
                throw SimeSessionExpiredError("Portal returned a non-dashboard page")
            }
            if (SimePortalParser.isMaintenancePage(landingResp.text)) {
                http.breaker.trip(SimePortalError.DEFAULT_WAIT_SECONDS)
                http.globalBreaker.trip(SimePortalError.DEFAULT_WAIT_SECONDS)
                throw SimeRateLimitError("Portal reports maintenance", SimePortalError.DEFAULT_WAIT_SECONDS)
            }

            val doc = SimeDomHelpers.parseHtml(landingResp.text)
            if (!SimePortalParser.isDashboard(doc)) {
                throw SimeSessionExpiredError("Portal returned a non-dashboard page")
            }

            synchronized(stateLock) {
                _sessionId = redirect.sessionId
                _portalUrl = redirect.portalUrl
                _role = role
                _credentialsRejected = false
                _sessionGeneration += 1L

                val pageKey = noteFetchedLocked(
                    url = redirect.portalUrl,
                    html = landingResp.text,
                    doc = doc,
                    fetchedAt = nowMillis(),
                )
                val students = registerStudentsLocked(redirect.portalUrl, doc)
                if (pageKey.isEmpty() && students.isEmpty()) {
                    throw SimeSessionExpiredError("Portal returned a non-dashboard page")
                }

                val landingPage = studentPages[pageKey] ?: PortalStudentDashboardPage(
                    url = redirect.portalUrl,
                    html = landingResp.text,
                    studentKey = pageKey,
                    fetchedAtEpochMillis = nowMillis(),
                )

                return PortalHandshakeResult(
                    sessionId = redirect.sessionId,
                    portalUrl = redirect.portalUrl,
                    role = role,
                    initialStudentKey = pageKey,
                    students = students,
                    landingPage = landingPage,
                )
            }
        } catch (err: Throwable) {
            val keepRejected = err is SimeLoginRejectedError && synchronized(stateLock) { _credentialsRejected }
            clear(preserveRejectedFlag = keepRejected)
            throw err
        }
    }

    /**
     * Switches the portal's global active student via their checksummed `#t_TreeNav` link,
     * verifies ownership of the returned dashboard page, and returns the verified page.
     *
     * Mirrors `SimeClient.switchStudent` and `fetchStudentPage` (`web/server.js:2342-2363`, `3208`).
     */
    suspend fun switchStudent(
        rawStudentId: String?,
        gated: Boolean = false,
    ): PortalStudentDashboardPage {
        val targetKey = requireAuthorizedStudentKey(rawStudentId, isExplicitSwitch = true)
        return fetchStudentPage(targetKey, gated = gated)
    }

    /**
     * Loads the verified dashboard page for [rawStudentId], using the in-memory student-keyed cache
     * when fresh unless [bypassCache] is true or [assertCurrent] requires re-asserting the portal's
     * global active student before an AJAX request.
     *
     * Mirrors `SimeClient.loadStudent` (`web/server.js:2367-2375`).
     */
    suspend fun loadStudentPage(
        rawStudentId: String?,
        gated: Boolean = false,
        bypassCache: Boolean = false,
        assertCurrent: Boolean = false,
    ): PortalStudentDashboardPage {
        val targetKey = requireAuthorizedStudentKey(rawStudentId, isExplicitSwitch = false)
        val now = nowMillis()

        val cached = synchronized(stateLock) {
            val own = studentPages[targetKey]
            val isFresh = own != null && (now - own.fetchedAtEpochMillis) < pageTtlMillis
            val isCurrentSatisfied = !assertCurrent || _currentStudentKey == targetKey
            if (!bypassCache && isFresh && isCurrentSatisfied) own else null
        }

        if (cached != null) {
            assertPageBelongsToStudent(cached, targetKey)
            return cached
        }

        return fetchStudentPage(targetKey, gated = gated)
    }

    /**
     * Parses and maps a verified [PortalStudentDashboardPage] into a domain [StudentAgendaSnapshot],
     * enforcing both [assertPageBelongsToStudent] and [StudentAgendaSnapshot.verifyStudentOwnership].
     */
    fun parseVerifiedSnapshot(
        page: PortalStudentDashboardPage,
        requestedStudentId: String?,
    ): StudentAgendaSnapshot {
        val expectedKey = requireAuthorizedStudentKey(requestedStudentId, isExplicitSwitch = false)
        assertPageBelongsToStudent(page, expectedKey)

        val parsed = SimePortalParser.parseFullPortal(
            html = page.html,
            todayIso = todayProvider(),
        )
        val parsedPageKey = synchronized(stateLock) {
            val doc = SimeDomHelpers.parseHtml(page.html)
            learnPageLocked(doc)
        }
        if (parsedPageKey.isEmpty() || parsedPageKey != expectedKey) {
            throw SimeStudentMismatchError(
                message = "Portal page belongs to another student",
                requestedStudentId = StudentId(expectedKey),
                actualStudentId = parsedPageKey.takeIf { it.isNotEmpty() }?.let(::StudentId),
            )
        }

        val knownProfile = synchronized(stateLock) { knownStudents[expectedKey] }
        val canonicalProfile = ParsedStudentProfile(
            id = expectedKey,
            fullName = parsed.metadata.fullName.ifEmpty { knownProfile?.fullName ?: expectedKey },
            className = parsed.metadata.className ?: knownProfile?.className,
            schoolName = parsed.metadata.schoolName ?: knownProfile?.schoolName,
        )
        val canonicalMetadata = parsed.metadata.copy(
            id = expectedKey,
            fullName = canonicalProfile.fullName,
            className = canonicalProfile.className,
            schoolName = canonicalProfile.schoolName,
        )
        val enrichedParsed = parsed.copy(
            student = canonicalProfile,
            metadata = canonicalMetadata,
            students = listStudents(),
        )

        val snapshot = SimePortalParser.toStudentAgendaSnapshot(
            portalPage = enrichedParsed,
            referenceIsoDate = todayProvider(),
            expectedStudentId = StudentId(expectedKey),
        )
        return snapshot.verifyStudentOwnership(StudentId(expectedKey))
    }

    /**
     * Verifies that [page] belongs to [expectedCanonicalKey], throwing [SimeStudentMismatchError] on mismatch.
     */
    fun assertPageBelongsToStudent(
        page: PortalStudentDashboardPage,
        expectedCanonicalKey: String,
    ) {
        val cleanExpected = SimeStudentParser.studentKey(expectedCanonicalKey)
        if (cleanExpected.isEmpty() || page.studentKey.isEmpty() || page.studentKey != cleanExpected) {
            throw SimeStudentMismatchError(
                message = "Portal page belongs to another student",
                requestedStudentId = StudentId(cleanExpected.ifEmpty { expectedCanonicalKey }),
                actualStudentId = page.studentKey.takeIf { it.isNotEmpty() }?.let(::StudentId),
            )
        }
    }

    private fun requireAuthorizedStudentKey(
        rawStudentId: String?,
        isExplicitSwitch: Boolean,
    ): String {
        synchronized(stateLock) {
            if (_sessionId == null || _portalUrl == null) {
                throw SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
            }
            if (isExplicitSwitch && _role == AccountRole.STUDENT) {
                throw SimeStudentNotFoundError(
                    message = "Students cannot switch students",
                    studentId = rawStudentId?.trim()?.takeIf { it.isNotEmpty() }?.let(::StudentId),
                )
            }
            val cleanId = rawStudentId?.trim()?.take(MAX_STUDENT_ID_LENGTH).orEmpty()
            if (cleanId.isEmpty()) {
                throw SimeStudentNotFoundError(
                    message = "Student ID is required",
                    studentId = null,
                )
            }
            val key = aliases[SimeStudentParser.studentKey(cleanId)].orEmpty()
            if (key.isEmpty()) {
                throw SimeStudentNotFoundError(
                    message = "No such student",
                    studentId = StudentId(cleanId),
                )
            }
            if (_role == AccountRole.STUDENT && _currentStudentKey.isNotEmpty() && key != _currentStudentKey) {
                throw SimeStudentNotFoundError(
                    message = "Students cannot access another student",
                    studentId = StudentId(cleanId),
                )
            }
            return key
        }
    }

    private suspend fun fetchStudentPage(
        targetKey: String,
        gated: Boolean,
    ): PortalStudentDashboardPage {
        val (rawHref, activeSessionId) = synchronized(stateLock) {
            val sid = _sessionId ?: throw SimeAppSessionError("Sesiunea a expirat. Autentifică-te din nou.")
            val href = studentUrls[targetKey]
            href to sid
        }

        if (rawHref.isNullOrBlank() || !hasChecksumParam(rawHref)) {
            throw SimeStudentNotFoundError(
                message = "No switch URL with checksum found for student",
                studentId = StudentId(targetKey),
            )
        }

        val switchUrl = SimeUrlValidator.formatSwitchUrl(rawHref, activeSessionId)
            ?: throw SimeStudentNotFoundError(
                message = "No switch URL with checksum found for student",
                studentId = StudentId(targetKey),
            )

        var lastActualKey: String? = null
        for (attempt in 0 until MAX_SWITCH_ATTEMPTS) {
            val page = fetchDashboard(
                url = switchUrl,
                gated = gated && attempt == 0,
            )
            if (page.studentKey == targetKey) {
                return page
            }
            lastActualKey = page.studentKey.takeIf { it.isNotEmpty() }
        }

        // Remove any mismatched active state and fail closed with STUDENT_MISMATCH
        synchronized(stateLock) {
            studentPages.remove(targetKey)
            if (_currentStudentKey == targetKey) {
                _currentStudentKey = lastActualKey.orEmpty()
            }
        }
        throw SimeStudentMismatchError(
            message = "Portal did not switch to requested student",
            requestedStudentId = StudentId(targetKey),
            actualStudentId = lastActualKey?.let(::StudentId),
        )
    }

    private suspend fun fetchDashboard(
        url: String,
        gated: Boolean,
    ): PortalStudentDashboardPage {
        val resp = try {
            http.request(
                method = "GET",
                url = url,
                kind = SimeRequestKind.NAVIGATE,
                gated = gated,
            )
        } catch (err: SimeAuthError) {
            clear(preserveRejectedFlag = false)
            throw err
        } catch (err: SimeForbiddenError) {
            clear(preserveRejectedFlag = false)
            throw err
        }

        val path = URI(resp.url).rawPath?.lowercase(Locale.ROOT).orEmpty()
        if (path.endsWith("/public") || path.contains("login")) {
            clear(preserveRejectedFlag = false)
            throw SimeSessionExpiredError("Portal session expired (redirected to public or login)")
        }

        val doc = SimeDomHelpers.parseHtml(resp.text)
        if (!SimePortalParser.isDashboard(doc)) {
            if (SimePortalParser.isMaintenancePage(resp.text)) {
                http.globalBreaker.trip(SimePortalError.DEFAULT_WAIT_SECONDS)
                throw SimeMaintenanceError("Portal reports maintenance", SimePortalError.DEFAULT_WAIT_SECONDS)
            }
            throw SimeUnexpectedPageError("Portal returned a non-dashboard page", step = "dashboard_fetch")
        }

        return synchronized(stateLock) {
            val key = noteFetchedLocked(
                url = url,
                html = resp.text,
                doc = doc,
                fetchedAt = nowMillis(),
            )
            registerStudentsLocked(url, doc)
            studentPages[key] ?: PortalStudentDashboardPage(
                url = url,
                html = resp.text,
                studentKey = key,
                fetchedAtEpochMillis = nowMillis(),
            )
        }
    }

    /**
     * Learns the student IDs on a dashboard page and returns the canonical key of the page's own student.
     * Mirrors `SimeClient.learnPage(page)` (`web/server.js:2196-2212`).
     */
    private fun learnPageLocked(doc: Element): String {
        val profile = SimeStudentParser.parseStudentProfile(doc)
        val links = SimeStudentParser.parseStudentLinks(doc)
        val pageId = SimeStudentParser.studentKey(profile.id)
        val name = SimeStudentParser.normalizeName(profile.fullName)

        // Match the page's student link: prefer exact ID match, then normalized name match, then data-current="true"
        val curLink = links.firstOrNull { pageId.isNotEmpty() && SimeStudentParser.studentKey(it.studentId) == pageId }
            ?: (if (name.isNotEmpty()) links.firstOrNull { SimeStudentParser.normalizeName(it.name) == name } else null)
            ?: links.firstOrNull { it.isCurrent }
        val curNav = curLink?.let { SimeStudentParser.studentKey(it.studentId) }.orEmpty()

        val key = (if (pageId.isNotEmpty()) aliases[pageId] else null)
            ?: (if (curNav.isNotEmpty()) aliases[curNav] else null)
            ?: pageId.ifEmpty { curNav }

        if (key.isNotEmpty()) {
            for (id in listOf(pageId, curNav, key)) {
                if (id.isNotEmpty() && !aliases.containsKey(id)) {
                    aliases[id] = key
                }
            }
        }
        for (link in links) {
            val k = SimeStudentParser.studentKey(link.studentId)
            if (k.isNotEmpty() && !aliases.containsKey(k)) {
                aliases[k] = k
            }
        }
        return key
    }

    private fun noteFetchedLocked(
        url: String,
        html: String,
        doc: Element,
        fetchedAt: Long,
    ): String {
        val key = learnPageLocked(doc)
        val page = PortalStudentDashboardPage(
            url = url,
            html = html,
            studentKey = key,
            fetchedAtEpochMillis = fetchedAt,
        )
        if (key.isNotEmpty()) {
            studentPages[key] = page
            _currentStudentKey = key
        }
        return key
    }

    private fun registerStudentsLocked(
        pageUrl: String,
        doc: Element,
    ): List<ParsedStudentProfile> {
        val profile = SimeStudentParser.parseStudentProfile(doc)
        val links = SimeStudentParser.parseStudentLinks(doc)
        val merged = SimeStudentParser.mergeStudents(profile, links)
        learnPageLocked(doc)

        for ((rawKey, target) in merged.linkMap) {
            try {
                val resolved = SimeUrlValidator.resolveSimeUrl(target.href)
                if (hasChecksumParam(resolved)) {
                    studentUrls[rawKey] = resolved
                    val canonical = aliases[rawKey]
                    if (!canonical.isNullOrEmpty()) {
                        studentUrls[canonical] = resolved
                    }
                }
            } catch (_: SimeNetworkError) {
                // Ignore untrusted/malformed link hrefs
            }
        }

        val curKey = SimeStudentParser.studentKey(profile.id)
        if (curKey.isNotEmpty() && !studentUrls.containsKey(curKey) && hasChecksumParam(pageUrl)) {
            studentUrls[curKey] = pageUrl
        }

        for (student in merged.students) {
            val k = SimeStudentParser.studentKey(student.id)
            if (k.isEmpty()) continue
            val canonical = aliases[k] ?: k
            aliases.putIfAbsent(k, canonical)
            mergeKnownLocked(canonical, student)
        }

        return knownStudents.values.toList()
    }

    private fun mergeKnownLocked(
        canonicalKey: String,
        student: ParsedStudentProfile,
    ) {
        val prev = knownStudents[canonicalKey]
        knownStudents[canonicalKey] = ParsedStudentProfile(
            id = canonicalKey,
            fullName = student.fullName.ifEmpty { prev?.fullName ?: canonicalKey },
            className = student.className ?: prev?.className,
            schoolName = student.schoolName ?: prev?.schoolName,
        )
    }

    override fun toString(): String =
        "SimePortalSession(isAuthenticated=$isAuthenticated, role=$role, studentCount=${listStudents().size})"

    companion object {
        const val DEFAULT_PAGE_TTL_MS: Long = 60_000L
        const val MAX_USERNAME_LENGTH: Int = 128
        const val MAX_PASSWORD_LENGTH: Int = 256
        const val MAX_STUDENT_ID_LENGTH: Int = 128
        private const val MAX_SWITCH_ATTEMPTS: Int = 2

        const val USER_TYPE_PARENT: String = "1"
        const val USER_TYPE_STUDENT: String = "2"
        const val USER_TYPE_NAME_PARENT: String = "Autentificare părinte"
        const val USER_TYPE_NAME_STUDENT: String = "Autentificare elev"

        internal fun hasChecksumParam(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("&cs=") ||
                lower.contains("?cs=") ||
                lower.contains("&checksum=") ||
                lower.contains("?checksum=")
        }

        internal fun buildLoginPayloadJson(
            salt: String,
            protectedItems: String,
            userType: String,
            userTypeChecksum: String?,
            userTypeName: String,
            userTypeNameChecksum: String?,
            username: String,
            password: String,
        ): String {
            val ckUserType = userTypeChecksum?.let(SimpleJson::quoteString) ?: "null"
            val ckUserTypeName = userTypeNameChecksum?.let(SimpleJson::quoteString) ?: "null"
            return buildString {
                append("""{"salt":""")
                append(SimpleJson.quoteString(salt))
                append(""","pageItems":{"itemsToSubmit":[""")
                append("""{"n":"P101_USER_TYPE","v":""")
                append(SimpleJson.quoteString(userType))
                append(""","ck":""")
                append(ckUserType)
                append("""},{"n":"P101_USER_TYPE_NAME","v":""")
                append(SimpleJson.quoteString(userTypeName))
                append(""","ck":""")
                append(ckUserTypeName)
                append("""},{"n":"P101_USERNAME","v":""")
                append(SimpleJson.quoteString(username))
                append("""},{"n":"P101_PASSWORD","v":""")
                append(SimpleJson.quoteString(password))
                append("""}],"protected":""")
                append(SimpleJson.quoteString(protectedItems))
                append(""","rowVersion":"","formRegionChecksums":[]}}""")
            }
        }
    }
}
