package md.sime.opensime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import md.sime.opensime.core.data.AgendaRepository
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.core.i18n.errorMessageFor
import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.AccountRole
import md.sime.opensime.core.model.DataSourceMode
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentProfile
import md.sime.opensime.core.portal.SimeErrorCode
import md.sime.opensime.core.portal.SimeLoginRejectedError
import md.sime.opensime.core.portal.SimeNetworkError
import md.sime.opensime.core.portal.SimeRateLimitError
import md.sime.opensime.core.portal.SimeSessionExpiredError
import md.sime.opensime.core.portal.SimeStudentMismatchError
import md.sime.opensime.core.portal.SimeCredentialVault
import md.sime.opensime.core.portal.StandardAesGcmVaultCipher
import md.sime.opensime.feature.profile.formatBirthDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSimeUiIntegrationTest {

    @Test
    fun `session coordinator switchRepository clears caches and invalidates active snapshot`() = runBlocking {
        val sampleRepo = SampleAgendaRepository()
        val coordinator = OpenSimeSessionCoordinator(sampleRepo)

        val snap7A = coordinator.selectStudent(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
        assertNotNull(snap7A)
        assertEquals(SampleAgendaRepository.STUDENT_SEVENTH_GRADE, coordinator.activeStudentId)
        assertEquals(snap7A, coordinator.cachedSnapshotFor(SampleAgendaRepository.STUDENT_SEVENTH_GRADE))

        // Create a fake live repository with a different student
        val liveStudentId = StudentId("live_student_101")
        val liveProfile = StudentProfile(
            id = liveStudentId,
            fullName = "Elena Live",
            className = "10B",
            schoolName = "Liceul Live",
            classMasterName = "Profesor Live",
            currentSemester = 1,
            academicYear = "2024-2025",
        )
        val sampleSnapshot = sampleRepo.getStudentSnapshot(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
        val liveSnapshot = sampleSnapshot.copy(
            student = liveProfile,
            dataSourceMode = DataSourceMode.LIVE_PORTAL,
        )
        val fakeLiveRepo = object : AgendaRepository {
            override fun listStudents(): List<StudentProfile> = listOf(liveProfile)
            override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
                if (studentId == liveStudentId) return liveSnapshot
                throw StudentMismatchException(studentId, liveStudentId)
            }
        }

        // Switch to the live repository
        coordinator.switchRepository(fakeLiveRepo)

        // Previous state and caches must be completely wiped
        assertNull(coordinator.activeStudentId)
        assertNull(coordinator.activeSnapshot)
        assertNull(coordinator.lastError)
        assertNull(coordinator.cachedSnapshotFor(SampleAgendaRepository.STUDENT_SEVENTH_GRADE))
        assertEquals(listOf(liveProfile), coordinator.availableStudents())

        // Loading from the new repository works cleanly
        val loadedLive = coordinator.selectStudent(liveStudentId)
        assertNotNull(loadedLive)
        assertEquals(liveStudentId, coordinator.activeStudentId)
        assertEquals(DataSourceMode.LIVE_PORTAL, loadedLive?.dataSourceMode)
    }

    @Test
    fun `delayed in-flight request from previous repository is discarded after switchRepository`() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val slowStudentId = SampleAgendaRepository.STUDENT_SEVENTH_GRADE
        val slowRepo = object : AgendaRepository {
            override fun listStudents(): List<StudentProfile> = emptyList()
            override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
                slowGate.await()
                return SampleAgendaRepository().getStudentSnapshot(slowStudentId)
            }
        }

        val coordinator = OpenSimeSessionCoordinator(slowRepo)

        // Launch slow load in background
        val slowJob = async {
            coordinator.loadStudentSafely(slowStudentId)
        }

        // Allow coroutine to enter getStudentSnapshot and suspend on slowGate
        kotlinx.coroutines.yield()
        assertEquals(slowStudentId, coordinator.activeStudentId)

        // Before slow load completes, switch repository (simulating logout or switching to demo)
        val fastRepo = SampleAgendaRepository()
        coordinator.switchRepository(fastRepo)

        // Now let slow request finish
        slowGate.complete(Unit)
        slowJob.await()

        // Active snapshot must remain null (not polluted by the delayed response)
        assertNull(coordinator.activeSnapshot)
        assertNull(coordinator.activeStudentId)
    }

    @Test
    fun `error message mapping translates typed portal errors to localized strings`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        // Session expired
        val sessionExpired = SimeSessionExpiredError()
        assertEquals("Sesiunea a expirat. Autentifică-te din nou.", ro.errorMessageFor(sessionExpired))
        assertEquals("Сессия истекла. Войдите снова.", ru.errorMessageFor(sessionExpired))
        assertEquals("Your session has expired. Please sign in again.", en.errorMessageFor(sessionExpired))

        // Invalid credentials / login rejected
        val loginRejected = SimeLoginRejectedError("bad auth")
        assertEquals("Numele de utilizator sau parola sunt incorecte.", ro.errorMessageFor(loginRejected))
        assertEquals("Неверное имя пользователя или пароль.", ru.errorMessageFor(loginRejected))
        assertEquals("Incorrect username or password.", en.errorMessageFor(loginRejected))

        // Student mismatch
        val mismatch = SimeStudentMismatchError(
            requestedStudentId = StudentId("expected"),
            actualStudentId = StudentId("actual"),
        )
        assertEquals("Datele primite nu corespund elevului selectat.", ro.errorMessageFor(mismatch))
        assertEquals("Полученные данные не соответствуют выбранному ученику.", ru.errorMessageFor(mismatch))
        assertEquals("Received data does not match the selected student.", en.errorMessageFor(mismatch))

        // Network error
        val networkErr = SimeNetworkError("connection refused")
        assertEquals("Serverul nu răspunde. Verifică conexiunea.", ro.errorMessageFor(networkErr))
        assertEquals("Сервер не отвечает. Проверьте подключение.", ru.errorMessageFor(networkErr))
        assertEquals("Server is not responding. Check your connection.", en.errorMessageFor(networkErr))

        // Rate limit
        val throttled = SimeRateLimitError(waitSeconds = 60)
        assertEquals("Prea multe încercări. Așteaptă câteva minute.", ro.errorMessageFor(throttled))
        assertEquals("Слишком много попыток. Подождите несколько минут.", ru.errorMessageFor(throttled))
        assertEquals("Too many attempts. Please wait a few minutes.", en.errorMessageFor(throttled))
    }

    @Test
    fun `all newly introduced auth and live strings are defined and non-empty across RO, RU, and EN`() {
        listOf(AppLanguage.RO, AppLanguage.RU, AppLanguage.EN).forEach { lang ->
            val strings = AppStringCatalog.forLanguage(lang)
            assertFalse("authTitle blank in $lang", strings.authTitle.isBlank())
            assertFalse("authLead blank in $lang", strings.authLead.isBlank())
            assertFalse("roleStudent blank in $lang", strings.roleStudent.isBlank())
            assertFalse("roleParent blank in $lang", strings.roleParent.isBlank())
            assertFalse("usernameLabel blank in $lang", strings.usernameLabel.isBlank())
            assertFalse("passwordLabel blank in $lang", strings.passwordLabel.isBlank())
            assertFalse("showPassword blank in $lang", strings.showPassword.isBlank())
            assertFalse("hidePassword blank in $lang", strings.hidePassword.isBlank())
            assertFalse("loginSubmit blank in $lang", strings.loginSubmit.isBlank())
            assertFalse("loginChecking blank in $lang", strings.loginChecking.isBlank())
            assertFalse("loginRequired blank in $lang", strings.loginRequired.isBlank())
            assertFalse("logoutAction blank in $lang", strings.logoutAction.isBlank())
            assertFalse("connectToSime blank in $lang", strings.connectToSime.isBlank())
            assertFalse("liveConnectedBadge blank in $lang", strings.liveConnectedBadge.isBlank())
            assertFalse("switchToDemo blank in $lang", strings.switchToDemo.isBlank())
            assertFalse("errInvalidCredentials blank in $lang", strings.errInvalidCredentials.isBlank())
            assertFalse("errSessionExpired blank in $lang", strings.errSessionExpired.isBlank())
            assertFalse("errNetwork blank in $lang", strings.errNetwork.isBlank())
            assertFalse("errRateLimited blank in $lang", strings.errRateLimited.isBlank())
            assertFalse("errStudentMismatch blank in $lang", strings.errStudentMismatch.isBlank())
            assertFalse("errGeneric blank in $lang", strings.errGeneric.isBlank())
            // Onboarding & Credential saving strings
            assertFalse("onboardingActionStart blank in $lang", strings.onboardingActionStart.isBlank())
            assertFalse("onboardingPointAgenda blank in $lang", strings.onboardingPointAgenda.isBlank())
            assertFalse("onboardingPointPortal blank in $lang", strings.onboardingPointPortal.isBlank())
            assertFalse("onboardingPointSecurity blank in $lang", strings.onboardingPointSecurity.isBlank())
            assertFalse("rememberMeLabel blank in $lang", strings.rememberMeLabel.isBlank())
            assertFalse("rememberMeDesc blank in $lang", strings.rememberMeDesc.isBlank())
            assertFalse("forgetSavedLogin blank in $lang", strings.forgetSavedLogin.isBlank())
            assertFalse("forgetSavedLoginConfirm blank in $lang", strings.forgetSavedLoginConfirm.isBlank())
            assertFalse("sessionRestoring blank in $lang", strings.sessionRestoring.isBlank())
            assertFalse("sessionRestoreFailed blank in $lang", strings.sessionRestoreFailed.isBlank())
        }
    }

    @Test
    fun `onboarding action button and 3 points have expected localization`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        assertEquals("Începe acum", ro.onboardingActionStart)
        assertEquals("Начать", ru.onboardingActionStart)
        assertEquals("Get started", en.onboardingActionStart)

        assertEquals("Agenda, notele și absențele tale într-un singur loc", ro.onboardingPointAgenda)
        assertEquals("Conexiune directă cu portalul oficial agenda.sime.md", ro.onboardingPointPortal)
        assertEquals("Datele tale sunt protejate; salvarea lor este opțională", ro.onboardingPointSecurity)
    }

    @Test
    fun `session restoration simulation with valid saved credentials establishes live repository`() = runBlocking {
        val studentA = StudentProfile(
            id = StudentId("child_1"),
            fullName = "Alex Parent Child 1",
            className = "7A",
            schoolName = "Liceul Teoretic",
            classMasterName = "Profesor Master",
            currentSemester = 1,
            academicYear = "2024-2025",
        )
        val studentB = StudentProfile(
            id = StudentId("child_2"),
            fullName = "Maria Parent Child 2",
            className = "5B",
            schoolName = "Liceul Teoretic",
            classMasterName = "Profesor Doi",
            currentSemester = 1,
            academicYear = "2024-2025",
        )

        val sampleRepo = SampleAgendaRepository()
        val baseSnapshot = sampleRepo.getStudentSnapshot(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)

        val liveSnapshotA = baseSnapshot.copy(
            student = studentA,
            dataSourceMode = DataSourceMode.LIVE_PORTAL,
        )
        val liveSnapshotB = baseSnapshot.copy(
            student = studentB,
            dataSourceMode = DataSourceMode.LIVE_PORTAL,
        )

        val fakeLiveRepo = object : AgendaRepository {
            override fun listStudents(): List<StudentProfile> = listOf(studentA, studentB)
            override suspend fun getStudentSnapshot(studentId: StudentId): StudentAgendaSnapshot {
                return when (studentId) {
                    studentA.id -> liveSnapshotA
                    studentB.id -> liveSnapshotB
                    else -> throw StudentMismatchException(studentId, StudentId("unknown"))
                }
            }
        }

        val coordinator = OpenSimeSessionCoordinator(fakeLiveRepo)

        // Session restored for parent: initial student is child 1
        val snap1 = coordinator.selectStudent(studentA.id)
        assertNotNull(snap1)
        assertEquals(studentA.id, coordinator.activeStudentId)
        assertEquals("Alex Parent Child 1", snap1?.student?.fullName)

        // Switch to child 2: isolation maintained
        val snap2 = coordinator.selectStudent(studentB.id)
        assertNotNull(snap2)
        assertEquals(studentB.id, coordinator.activeStudentId)
        assertEquals("Maria Parent Child 2", snap2?.student?.fullName)

        // Cached snapshot for child 1 remains isolated
        assertEquals("Alex Parent Child 1", coordinator.cachedSnapshotFor(studentA.id)?.student?.fullName)
    }

    @Test
    fun `account role enum matches student and parent definitions`() {
        assertEquals(AccountRole.STUDENT, AccountRole.valueOf("STUDENT"))
        assertEquals(AccountRole.PARENT, AccountRole.valueOf("PARENT"))
        assertEquals(2, AccountRole.entries.size)
    }

    @Test
    fun `clearing session coordinator leaves no active student or cached snapshots`() = runBlocking {
        val sampleRepo = SampleAgendaRepository()
        val coordinator = OpenSimeSessionCoordinator(sampleRepo)
        coordinator.selectStudent(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
        assertNotNull(coordinator.activeSnapshot)

        // Clear coordinator (equivalent to user logout)
        coordinator.clear()

        assertNull(coordinator.activeStudentId)
        assertNull(coordinator.activeSnapshot)
        assertNull(coordinator.lastError)
        assertNull(coordinator.cachedSnapshotFor(SampleAgendaRepository.STUDENT_SEVENTH_GRADE))
    }

    @Test
    fun `birth date formatting formats valid dates and handles invalid inputs gracefully`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        // Standard valid date DD.MM.YYYY
        assertEquals("15 mai 2012", formatBirthDate("15.05.2012", ro))
        assertEquals("15 мая 2012 г.", formatBirthDate("15.05.2012", ru))
        assertEquals("May 15, 2012", formatBirthDate("15.05.2012", en))

        // Boundary single-digit day & month
        assertEquals("1 ianuarie 2010", formatBirthDate("01.01.2010", ro))
        assertEquals("1 января 2010 г.", formatBirthDate("01.01.2010", ru))
        assertEquals("January 1, 2010", formatBirthDate("01.01.2010", en))

        // Missing or null or empty dates
        assertNull(formatBirthDate(null, ro))
        assertNull(formatBirthDate("", ro))
        assertNull(formatBirthDate("   ", ro))
        assertNull(formatBirthDate("null", ro))
        assertNull(formatBirthDate("undefined", ro))

        // Corrupted strings containing placeholder '?'
        assertNull(formatBirthDate("15.?5.2012", ro))
        assertNull(formatBirthDate("??.??.????", ro))

        // Non-standard but clean date string fallback (length 4..30)
        assertEquals("2012-05-15", formatBirthDate("2012-05-15", ro))
    }

    @Test
    fun `system language detection respects locale with english fallback`() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ro-MD"))
            assertEquals(AppLanguage.RO, OpenSimeSettingsState.detectSystemLanguage())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-MD"))
            assertEquals(AppLanguage.RU, OpenSimeSettingsState.detectSystemLanguage())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("en-US"))
            assertEquals(AppLanguage.EN, OpenSimeSettingsState.detectSystemLanguage())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fr-FR"))
            assertEquals(AppLanguage.EN, OpenSimeSettingsState.detectSystemLanguage())

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-DE"))
            assertEquals(AppLanguage.EN, OpenSimeSettingsState.detectSystemLanguage())
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `explicit logout preference flag persists across simulated app lifecycles and suppresses auto-restoration`() {
        val testPrefs = InMemorySharedPreferences()
        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = testPrefs
        }

        // Fresh installation: not explicitly logged out
        assertFalse(OpenSimeSettingsState.isExplicitlyLoggedOut(fakeContext))

        // Explicit logout flag is set
        OpenSimeSettingsState.setExplicitlyLoggedOut(fakeContext, true)
        assertTrue(OpenSimeSettingsState.isExplicitlyLoggedOut(fakeContext))

        // Flag is cleared when user signs back in
        OpenSimeSettingsState.setExplicitlyLoggedOut(fakeContext, false)
        assertFalse(OpenSimeSettingsState.isExplicitlyLoggedOut(fakeContext))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `system theme detection distinguishes dark and light mode`() {
        val darkConfig = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val lightConfig = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }

        val darkResources = object : Resources(null, null, null) {
            override fun getConfiguration(): Configuration = darkConfig
        }
        val lightResources = object : Resources(null, null, null) {
            override fun getConfiguration(): Configuration = lightConfig
        }

        val darkContext = object : ContextWrapper(null) {
            override fun getResources(): Resources = darkResources
        }
        val lightContext = object : ContextWrapper(null) {
            override fun getResources(): Resources = lightResources
        }

        assertEquals(ThemeMode.DARK, OpenSimeSettingsState.detectSystemTheme(darkContext))
        assertEquals(ThemeMode.LIGHT, OpenSimeSettingsState.detectSystemTheme(lightContext))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `preferences migration converts legacy SYSTEM theme to detected system appearance`() {
        val testPrefs = InMemorySharedPreferences()
        testPrefs.edit()
            .putString("theme_mode", "SYSTEM")
            .putString("language", "RO")
            .apply()

        val darkConfig = Configuration().apply {
            uiMode = Configuration.UI_MODE_NIGHT_YES
        }
        val fakeResources = object : Resources(null, null, null) {
            override fun getConfiguration(): Configuration = darkConfig
        }
        val fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = testPrefs
            override fun getResources(): Resources = fakeResources
        }

        val state = OpenSimeSettingsState.loadFromPreferences(fakeContext)
        assertEquals(ThemeMode.DARK, state.themeMode)
        // Migration persisted ThemeMode.DARK, replacing SYSTEM
        assertEquals("DARK", testPrefs.getString("theme_mode", null))
    }

    @Test
    fun `credential vault preserves saved credentials on logout when remember me was checked`() {
        val testPrefs = InMemorySharedPreferences()
        val cipher = StandardAesGcmVaultCipher()
        val vault = SimeCredentialVault(preferences = testPrefs, cipher = cipher)

        val username = "parinte.test"
        val password = "StrongPassword456!"
        val role = AccountRole.PARENT

        // 1. User signs in with Remember me checked
        vault.saveCredentials(username, password, role)
        assertTrue(vault.hasSavedCredentials())
        assertEquals(username, vault.loadCredentials()?.username)
        assertEquals(role, vault.loadCredentials()?.role)

        // 2. User logs out: session coordinator clears, but vault credentials are preserved
        val coordinator = OpenSimeSessionCoordinator(SampleAgendaRepository())
        coordinator.clear()
        assertNull(coordinator.activeSnapshot)

        // Saved credentials still exist in the vault
        assertTrue(vault.hasSavedCredentials())
        assertEquals(username, vault.loadCredentials()?.username)

        // 3. User unchecks Remember me on subsequent login (or clicks "Forget saved login")
        vault.clearCredentials()
        assertFalse(vault.hasSavedCredentials())
        assertNull(vault.loadCredentials())
    }

    @Test
    fun `login account role default is PARENT`() {
        // Enforce the requirement: new manual logins default to Parent
        val defaultRole = AccountRole.PARENT
        assertEquals(AccountRole.PARENT, defaultRole)
        assertNotEquals(AccountRole.STUDENT, defaultRole)
    }

    @Test
    fun `absence record row layout structures short and long subject names predictably`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)

        val shortSubject = AbsenceItem(
            id = "abs_short",
            subject = "Fizica",
            isoDate = "2026-10-15",
            semester = 1,
            status = AbsenceStatus.UNEXCUSED,
            periodNumber = 3,
            periodTime = "10:15 - 11:00",
            room = "Sala 204",
        )

        val longSubject = AbsenceItem(
            id = "abs_long",
            subject = "Limba și literatura română",
            isoDate = "2026-10-16",
            semester = 1,
            status = AbsenceStatus.EXCUSED,
            periodNumber = 1,
            periodTime = "08:30 - 09:15",
            room = "Cab. 102",
        )

        assertEquals("Fizica", shortSubject.subject.ifBlank { ro.discipline })
        assertEquals("Limba și literatura română", longSubject.subject.ifBlank { ro.discipline })

        // Excused vs unexcused labels ("Motivată" / "Nemotivată")
        assertEquals("Nemotivată", if (shortSubject.status == AbsenceStatus.EXCUSED) ro.absStatusExcused else ro.absStatusUnexcused)
        assertEquals("Motivată", if (longSubject.status == AbsenceStatus.EXCUSED) ro.absStatusExcused else ro.absStatusUnexcused)

        // Period formatting ("Ora {period}")
        assertEquals("Ora 3", ro.formatLessonPeriod(shortSubject.periodNumber!!))
        assertEquals("Ora 1", ro.formatLessonPeriod(longSubject.periodNumber!!))
    }

    private class InMemorySharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class Editor(private val prefs: InMemorySharedPreferences) : SharedPreferences.Editor {
            private val modifications = mutableMapOf<String, Any?>()
            private var shouldClear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                modifications[key ?: ""] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                modifications[key ?: ""] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                modifications[key ?: ""] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                shouldClear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (shouldClear) prefs.data.clear()
                modifications.forEach { (k, v) ->
                    if (v == null) prefs.data.remove(k) else prefs.data[k] = v
                }
            }
        }
    }
}
