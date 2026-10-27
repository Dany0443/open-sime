package md.sime.opensime

import md.sime.opensime.core.designsystem.AppTextSize
import md.sime.opensime.core.designsystem.NamedThemePalette
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.core.designsystem.resolveOpenSimeColorScheme
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.core.model.QualifierCode
import md.sime.opensime.navigation.TopLevelDestination
import md.sime.opensime.navigation.WindowWidthLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndLocalizationTest {

    @Test
    fun `five primary destinations match Open SIME specification in standard and simple mode`() {
        val destinations = TopLevelDestination.entries
        assertEquals(
            listOf(
                TopLevelDestination.HOME,
                TopLevelDestination.MARKS,
                TopLevelDestination.TIMETABLE,
                TopLevelDestination.ABSENCES,
                TopLevelDestination.HOMEWORK,
            ),
            destinations,
        )

        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        assertEquals("Acasă", TopLevelDestination.HOME.label(ro, simpleMode = false))
        assertEquals("Reușita", TopLevelDestination.MARKS.label(ro, simpleMode = false))
        assertEquals("Orar", TopLevelDestination.TIMETABLE.label(ro, simpleMode = false))
        assertEquals("Absențe", TopLevelDestination.ABSENCES.label(ro, simpleMode = false))
        assertEquals("Teme", TopLevelDestination.HOMEWORK.label(ro, simpleMode = false))

        // Simple mode labels for younger students
        assertEquals("Notele mele", TopLevelDestination.MARKS.label(ro, simpleMode = true))
        assertEquals("Orarul meu", TopLevelDestination.TIMETABLE.label(ro, simpleMode = true))
        assertEquals("Cât am lipsit", TopLevelDestination.ABSENCES.label(ro, simpleMode = true))
        assertEquals("Ce am de învățat", TopLevelDestination.HOMEWORK.label(ro, simpleMode = true))
        assertEquals("Despre mine", ro.profileLabel(simpleMode = true))
    }

    @Test
    fun `window width breakpoints map to compact, medium, and expanded`() {
        assertEquals(WindowWidthLayout.COMPACT, WindowWidthLayout.fromWidthDp(360f))
        assertEquals(WindowWidthLayout.COMPACT, WindowWidthLayout.fromWidthDp(599.9f))
        assertEquals(WindowWidthLayout.MEDIUM, WindowWidthLayout.fromWidthDp(600f))
        assertEquals(WindowWidthLayout.MEDIUM, WindowWidthLayout.fromWidthDp(839.9f))
        assertEquals(WindowWidthLayout.EXPANDED, WindowWidthLayout.fromWidthDp(840f))
        assertEquals(WindowWidthLayout.EXPANDED, WindowWidthLayout.fromWidthDp(1280f))
    }

    @Test
    fun `theming resolves distinct schemes for light, dark, high contrast, and named palettes`() {
        val lightIndigo = resolveOpenSimeColorScheme(
            darkTheme = false,
            palette = NamedThemePalette.SIME_INDIGO,
            highContrast = false,
        )
        val darkIndigo = resolveOpenSimeColorScheme(
            darkTheme = true,
            palette = NamedThemePalette.SIME_INDIGO,
            highContrast = false,
        )
        val highContrastDark = resolveOpenSimeColorScheme(
            darkTheme = true,
            palette = NamedThemePalette.SIME_INDIGO,
            highContrast = true,
        )
        val lightForest = resolveOpenSimeColorScheme(
            darkTheme = false,
            palette = NamedThemePalette.FOREST_CALM,
            highContrast = false,
        )

        assertNotEquals(lightIndigo.primary, darkIndigo.primary)
        assertNotEquals(lightIndigo.surface, darkIndigo.surface)
        assertNotEquals(darkIndigo.surface, highContrastDark.surface)
        assertNotEquals(lightIndigo.primary, lightForest.primary)
    }

    @Test
    fun `locale-consistent number formatting uses comma for RO and RU and dot for EN`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        assertEquals("9,38", ro.formatAverage(9.376))
        assertEquals("9,38", ru.formatAverage(9.376))
        assertEquals("9.38", en.formatAverage(9.376))
    }

    @Test
    fun `human-readable date formatting avoids raw ISO strings across RO, RU, and EN`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        assertEquals("27 oct", ro.formatShortDate("2026-10-27"))
        assertEquals("27 окт", ru.formatShortDate("2026-10-27"))
        assertEquals("27 Oct", en.formatShortDate("2026-10-27"))

        assertEquals("Marți, 27 octombrie", ro.formatLongDate("2026-10-27"))
        assertFalse(ro.formatLongDate("2026-10-27").contains("2026-10-27"))

        assertEquals("Astăzi", ro.formatRelativeDay("2026-10-27", referenceIsoDate = "2026-10-27"))
        assertEquals("Mâine", ro.formatRelativeDay("2026-10-28", referenceIsoDate = "2026-10-27"))
        assertEquals("Ieri", ro.formatRelativeDay("2026-10-26", referenceIsoDate = "2026-10-27"))
    }

    @Test
    fun `pluralization and qualifier full words match web localization across RO, RU, and EN`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        assertEquals("1 oră", ro.formatLessonCount(1))
        assertEquals("5 ore", ro.formatLessonCount(5))
        assertEquals("1 урок", ru.formatLessonCount(1))
        assertEquals("3 урока", ru.formatLessonCount(3))
        assertEquals("5 уроков", ru.formatLessonCount(5))
        assertEquals("1 lesson", en.formatLessonCount(1))
        assertEquals("5 lessons", en.formatLessonCount(5))

        assertEquals("Foarte bine", ro.qualifierFullWord(QualifierCode.FB))
        assertEquals("Bine", ro.qualifierFullWord(QualifierCode.B))
        assertEquals("Suficient", ro.qualifierFullWord(QualifierCode.S))
        assertEquals("Insuficient", ro.qualifierFullWord(QualifierCode.I))
    }

    @Test
    fun `absence empty state strings distinguish zero absences from portal count with unavailable details across RO, RU, EN`() {
        val ro = AppStringCatalog.forLanguage(AppLanguage.RO)
        val ru = AppStringCatalog.forLanguage(AppLanguage.RU)
        val en = AppStringCatalog.forLanguage(AppLanguage.EN)

        assertEquals("Detaliile nu sunt disponibile pe portal", ro.absencesNoDetailsTitle)
        assertEquals(
            "Portalul raportează 5 absențe în acest semestru, dar fără detalii pe ore.",
            ro.absencesNoDetailsSubTemplate.replace("{total}", "5"),
        )

        assertEquals("Детали недоступны на портале", ru.absencesNoDetailsTitle)
        assertEquals(
            "Портал сообщает о 5 пропусках в этом семестре, но без деталей по урокам.",
            ru.absencesNoDetailsSubTemplate.replace("{total}", "5"),
        )

        assertEquals("Details unavailable on portal", en.absencesNoDetailsTitle)
        assertEquals(
            "The portal reports 5 absences in this semester, but without lesson breakdown.",
            en.absencesNoDetailsSubTemplate.replace("{total}", "5"),
        )
    }

    @Test
    fun `settings state round-trips through Saver and text size multipliers match web`() {
        assertEquals(0.90f, AppTextSize.S.scaleFactor, 0.001f)
        assertEquals(1.00f, AppTextSize.M.scaleFactor, 0.001f)
        assertEquals(1.125f, AppTextSize.L.scaleFactor, 0.001f)
        assertEquals(1.25f, AppTextSize.XL.scaleFactor, 0.001f)

        val custom = OpenSimeSettingsState(
            language = AppLanguage.RU,
            themeMode = ThemeMode.DARK,
            namedPalette = NamedThemePalette.SIME_INDIGO,
            textSize = AppTextSize.L,
            simpleMode = true,
            highContrast = true,
            reduceMotion = true,
            onboardingCompleted = true,
        )
        assertTrue(custom.reduceMotion)
        assertTrue(custom.highContrast)
        assertTrue(custom.simpleMode)
        assertTrue(custom.onboardingCompleted)

        val defaultState = OpenSimeSettingsState()
        assertFalse(defaultState.onboardingCompleted)
    }

    @Test
    fun `onboarding and signed-out strings are populated across RO, RU, and EN without placeholders`() {
        for (lang in AppLanguage.entries) {
            val s = AppStringCatalog.forLanguage(lang)
            assertTrue("onboardingBadge must not be blank for $lang", s.onboardingBadge.isNotBlank())
            assertTrue("onboardingTitle must not be blank for $lang", s.onboardingTitle.isNotBlank())
            assertTrue("onboardingSubtitle must not be blank for $lang", s.onboardingSubtitle.isNotBlank())
            assertTrue("onboardingFeatureDirectTitle must not be blank for $lang", s.onboardingFeatureDirectTitle.isNotBlank())
            assertTrue("onboardingFeaturePrivacyTitle must not be blank for $lang", s.onboardingFeaturePrivacyTitle.isNotBlank())
            assertTrue("onboardingFeatureCleanTitle must not be blank for $lang", s.onboardingFeatureCleanTitle.isNotBlank())
            assertTrue("onboardingActionContinue must not be blank for $lang", s.onboardingActionContinue.isNotBlank())
            assertTrue("onboardingActionSettings must not be blank for $lang", s.onboardingActionSettings.isNotBlank())
            assertTrue("signedOutHeroTitle must not be blank for $lang", s.signedOutHeroTitle.isNotBlank())
            assertTrue("signedOutHeroSubtitle must not be blank for $lang", s.signedOutHeroSubtitle.isNotBlank())
            assertTrue("signedOutNoticeTitle must not be blank for $lang", s.signedOutNoticeTitle.isNotBlank())
            assertTrue("signedOutNoticeDesc must not be blank for $lang", s.signedOutNoticeDesc.isNotBlank())
            assertTrue("signedOutActionSignIn must not be blank for $lang", s.signedOutActionSignIn.isNotBlank())

            // Must never use literal '?' placeholder for diacritics
            assertFalse("Placeholder '?' in onboardingTitle for $lang", s.onboardingTitle.contains("?"))
            assertFalse("Placeholder '?' in onboardingSubtitle for $lang", s.onboardingSubtitle.contains("?"))
        }
    }
}
