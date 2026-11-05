package md.sime.opensime

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.designsystem.NamedThemePalette
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.feature.settings.SettingsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun legacyTextSizeDoesNotOverrideSystemFontScaleAndControlsRemainUsable() {
        val fixture = UnifiedLogoutTest.Fixture()
        try {
            fixture.context.getSharedPreferences("open_sime_settings", Context.MODE_PRIVATE)
                .edit().putString("text_size", "XL").commit()
            val settings = mutableStateOf(OpenSimeSettingsState.loadFromPreferences(fixture.context))
            var actualScale = 0f
            compose.activityRule.scenario.onActivity { it.setContent {
                val systemDensity = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density = systemDensity.density, fontScale = 2f)) {
                    OpenSimeTheme(settings.value) {
                        actualScale = LocalDensity.current.fontScale
                        SettingsDialog(settings.value, {
                            settings.value = it
                            OpenSimeSettingsState.saveToPreferences(fixture.context, it)
                        }, {}, isLoggedIn = true, onLogout = {})
                    }
                }
            } }
            compose.onNodeWithTag("setting_text_size_XL").assertDoesNotExist()
            compose.runOnIdle { assertEquals(2f, actualScale, 0.001f) }
            compose.onNodeWithTag("setting_theme_DARK").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("setting_contrast").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("setting_reduce_motion").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("settings_action_logout").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("settings_action_forget_credentials").assertDoesNotExist()
            compose.onNodeWithTag("setting_theme_LIGHT").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("setting_lang_RU").performScrollTo().performClick()
            compose.onNodeWithTag("setting_simple_mode").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(2f, actualScale, 0.001f)
                assertEquals(ThemeMode.LIGHT, settings.value.themeMode)
                assertEquals(AppLanguage.RU, settings.value.language)
                assertEquals(settings.value, OpenSimeSettingsState.loadFromPreferences(fixture.context))
                assertEquals("XL", fixture.context.getSharedPreferences("open_sime_settings", Context.MODE_PRIVATE)
                    .getString("text_size", null))
            }
        } finally { fixture.close() }
    }

    @Test fun preferencesSurviveProcessRestartWithLegacyTextSize() {
        val stage = InstrumentationRegistry.getArguments().getString("phase04Stage")
        val fixture = UnifiedLogoutTest.Fixture(if (stage == "verify") "verify" else null,
            if (stage != null) "phase04-preferences-restart" else null)
        val expected = OpenSimeSettingsState(themeMode = ThemeMode.DARK, language = AppLanguage.RU,
            namedPalette = NamedThemePalette.FOREST_CALM, simpleMode = true, highContrast = true,
            reduceMotion = true, onboardingCompleted = true)
        try {
            if (stage != "verify") {
                fixture.context.getSharedPreferences("open_sime_settings", Context.MODE_PRIVATE)
                    .edit().putString("text_size", "XL").commit()
                OpenSimeSettingsState.saveToPreferences(fixture.context, expected)
            }
            assertEquals(expected, OpenSimeSettingsState.loadFromPreferences(fixture.context))
            assertEquals("XL", fixture.context.getSharedPreferences("open_sime_settings", Context.MODE_PRIVATE)
                .getString("text_size", null))
        } finally {
            if (stage != "prepare") fixture.close()
        }
    }
}
