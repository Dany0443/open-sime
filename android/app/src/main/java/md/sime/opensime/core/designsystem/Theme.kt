package md.sime.opensime.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.core.i18n.LocalAppStrings

/**
 * User-selectable theme mode.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * In-app text size scale matching `html[data-text-size]` in `web/public/style.css`.
 */
enum class AppTextSize(val code: String, val scaleFactor: Float) {
    S("S", 0.90f),
    M("M", 1.00f),
    L("L", 1.125f),
    XL("XL", 1.25f),
}

/**
 * Named color palettes. Supports the default Open SIME brand identity (`SIME_INDIGO`)
 * and keeps the theme registry extensible.
 */
enum class NamedThemePalette(val id: String, val displayNameRo: String, val displayNameRu: String, val displayNameEn: String) {
    SIME_INDIGO("sime_indigo", "Indigo SIME", "Индиго SIME", "SIME Indigo"),
    FOREST_CALM("forest_calm", "Verde Codru", "Лесной зелёный", "Forest Calm"),
    WARM_AMBER("warm_amber", "Chihlimbar", "Тёплый янтарь", "Warm Amber");

    fun labelFor(language: AppLanguage): String = when (language) {
        AppLanguage.RO -> displayNameRo
        AppLanguage.RU -> displayNameRu
        AppLanguage.EN -> displayNameEn
    }
}

@Immutable
data class OpenSimeSettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val textSize: AppTextSize = AppTextSize.M,
    val namedPalette: NamedThemePalette = NamedThemePalette.SIME_INDIGO,
    val highContrast: Boolean = false,
    val simpleMode: Boolean = false,
    val reduceMotion: Boolean = false,
    val language: AppLanguage = AppLanguage.RO,
) {
    companion object {
        private const val PREFS_NAME = "open_sime_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_PALETTE = "named_palette"
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        private const val KEY_SIMPLE_MODE = "simple_mode"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_LANGUAGE = "language"

        val Saver: Saver<OpenSimeSettingsState, List<String>> = Saver(
            save = { state ->
                listOf(
                    state.themeMode.name,
                    state.textSize.name,
                    state.namedPalette.name,
                    state.highContrast.toString(),
                    state.simpleMode.toString(),
                    state.reduceMotion.toString(),
                    state.language.name,
                )
            },
            restore = { saved ->
                OpenSimeSettingsState(
                    themeMode = saved.getOrNull(0)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                    textSize = saved.getOrNull(1)?.let { runCatching { AppTextSize.valueOf(it) }.getOrNull() } ?: AppTextSize.M,
                    namedPalette = saved.getOrNull(2)?.let { runCatching { NamedThemePalette.valueOf(it) }.getOrNull() } ?: NamedThemePalette.SIME_INDIGO,
                    highContrast = saved.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
                    simpleMode = saved.getOrNull(4)?.toBooleanStrictOrNull() ?: false,
                    reduceMotion = saved.getOrNull(5)?.toBooleanStrictOrNull() ?: false,
                    language = saved.getOrNull(6)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.RO,
                )
            },
        )

        fun loadFromPreferences(context: Context): OpenSimeSettingsState {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return OpenSimeSettingsState(
                themeMode = prefs.getString(KEY_THEME_MODE, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                textSize = prefs.getString(KEY_TEXT_SIZE, null)?.let { runCatching { AppTextSize.valueOf(it) }.getOrNull() } ?: AppTextSize.M,
                namedPalette = prefs.getString(KEY_PALETTE, null)?.let { runCatching { NamedThemePalette.valueOf(it) }.getOrNull() } ?: NamedThemePalette.SIME_INDIGO,
                highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, false),
                simpleMode = prefs.getBoolean(KEY_SIMPLE_MODE, false),
                reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
                language = prefs.getString(KEY_LANGUAGE, null)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: AppLanguage.RO,
            )
        }

        fun saveToPreferences(context: Context, state: OpenSimeSettingsState) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME_MODE, state.themeMode.name)
                .putString(KEY_TEXT_SIZE, state.textSize.name)
                .putString(KEY_PALETTE, state.namedPalette.name)
                .putBoolean(KEY_HIGH_CONTRAST, state.highContrast)
                .putBoolean(KEY_SIMPLE_MODE, state.simpleMode)
                .putBoolean(KEY_REDUCE_MOTION, state.reduceMotion)
                .putString(KEY_LANGUAGE, state.language.name)
                .apply()
        }
    }
}

/**
 * Extended semantic colors matching `--success*` and `--warning*` in `web/public/style.css`.
 */
@Immutable
data class OpenSimeSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val highContrast: Boolean,
    val isDark: Boolean,
)

private val LightSemanticColors = OpenSimeSemanticColors(
    success = Color(0xFF1B6B3A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFB4F1C3),
    onSuccessContainer = Color(0xFF002110),
    warning = Color(0xFF8A5100),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB5),
    onWarningContainer = Color(0xFF2C1600),
    highContrast = false,
    isDark = false,
)

private val DarkSemanticColors = OpenSimeSemanticColors(
    success = Color(0xFF8FD9A5),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF00522A),
    onSuccessContainer = Color(0xFFAEF2C0),
    warning = Color(0xFFFFB95F),
    onWarning = Color(0xFF482900),
    warningContainer = Color(0xFF693C00),
    onWarningContainer = Color(0xFFFFDDB5),
    highContrast = false,
    isDark = true,
)

val LocalOpenSimeSemanticColors = staticCompositionLocalOf { LightSemanticColors }

// Open SIME brand palette tokens (aligned with web/public/style.css)
private val SimeLightColorScheme: ColorScheme = expressiveLightColorScheme().copy(
    primary = Color(0xFF4355B9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF9A4521),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCD),
    onTertiaryContainer = Color(0xFF380D00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1E9),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
)

private val SimeDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC2C5DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFFFFB596),
    onTertiary = Color(0xFF581E00),
    tertiaryContainer = Color(0xFF7B2F0A),
    onTertiaryContainer = Color(0xFFFFDBCD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
)

private val ForestLightColorScheme: ColorScheme = SimeLightColorScheme.copy(
    primary = Color(0xFF256A4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFACF2C8),
    onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF4D6356),
    secondaryContainer = Color(0xFFD0E8D8),
    onSecondaryContainer = Color(0xFF0A1F15),
)

private val ForestDarkColorScheme: ColorScheme = SimeDarkColorScheme.copy(
    primary = Color(0xFF90D5AD),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF035234),
    onPrimaryContainer = Color(0xFFACF2C8),
    secondary = Color(0xFFB4CCBC),
    secondaryContainer = Color(0xFF364B3F),
    onSecondaryContainer = Color(0xFFD0E8D8),
)

private val WarmAmberLightColorScheme: ColorScheme = SimeLightColorScheme.copy(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF725A42),
    secondaryContainer = Color(0xFFFEDDBE),
    onSecondaryContainer = Color(0xFF291806),
)

private val WarmAmberDarkColorScheme: ColorScheme = SimeDarkColorScheme.copy(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFE1C1A4),
    secondaryContainer = Color(0xFF59422C),
    onSecondaryContainer = Color(0xFFFEDDBE),
)

/**
 * Resolves the effective [ColorScheme] from [ThemeMode], [NamedThemePalette], and high-contrast preference.
 */
fun resolveOpenSimeColorScheme(
    darkTheme: Boolean,
    palette: NamedThemePalette = NamedThemePalette.SIME_INDIGO,
    highContrast: Boolean = false,
): ColorScheme {
    val base = when (palette) {
        NamedThemePalette.SIME_INDIGO -> if (darkTheme) SimeDarkColorScheme else SimeLightColorScheme
        NamedThemePalette.FOREST_CALM -> if (darkTheme) ForestDarkColorScheme else ForestLightColorScheme
        NamedThemePalette.WARM_AMBER -> if (darkTheme) WarmAmberDarkColorScheme else WarmAmberLightColorScheme
    }
    if (!highContrast) return base
    return if (darkTheme) {
        base.copy(
            surface = Color(0xFF000000),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF0A0A0E),
            surfaceContainer = Color(0xFF121218),
            surfaceContainerHigh = Color(0xFF1C1C24),
            surfaceContainerHighest = Color(0xFF282832),
            onSurface = Color(0xFFFFFFFF),
            onSurfaceVariant = Color(0xFFFFFFFF),
            outline = Color(0xFFFFFFFF),
            outlineVariant = Color(0xFFD0D0DC),
        )
    } else {
        base.copy(
            surface = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF4F4F8),
            surfaceContainer = Color(0xFFEAEAF2),
            surfaceContainerHigh = Color(0xFFDFDFE8),
            surfaceContainerHighest = Color(0xFFD2D2DE),
            onSurface = Color(0xFF000000),
            onSurfaceVariant = Color(0xFF000000),
            outline = Color(0xFF000000),
            outlineVariant = Color(0xFF2C2C38),
        )
    }
}

private val BaseTypography = Typography()

val OpenSimeTypography = Typography(
    displaySmall = BaseTypography.displaySmall,
    headlineMedium = BaseTypography.headlineMedium,
    headlineSmall = BaseTypography.headlineSmall,
    titleLarge = BaseTypography.titleLarge,
    titleMedium = BaseTypography.titleMedium,
    titleSmall = BaseTypography.titleSmall,
    bodyLarge = BaseTypography.bodyLarge,
    bodyMedium = BaseTypography.bodyMedium,
    bodySmall = BaseTypography.bodySmall,
    labelLarge = BaseTypography.labelLarge,
    labelMedium = BaseTypography.labelMedium,
    labelSmall = BaseTypography.labelSmall,
    displaySmallEmphasized = TextStyle(
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.ExtraBold,
    ),
    headlineMediumEmphasized = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.ExtraBold,
    ),
    headlineSmallEmphasized = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.ExtraBold,
    ),
    titleLargeEmphasized = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleMediumEmphasized = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleSmallEmphasized = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
    ),
    labelLargeEmphasized = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
    ),
)

val OpenSimeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
    extraLargeIncreased = RoundedCornerShape(36.dp),
    extraExtraLarge = RoundedCornerShape(44.dp),
)

@Composable
fun OpenSimeTheme(
    settings: OpenSimeSettingsState = OpenSimeSettingsState(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = resolveOpenSimeColorScheme(
        darkTheme = isDark,
        palette = settings.namedPalette,
        highContrast = settings.highContrast,
    )

    val semanticColors = (if (isDark) DarkSemanticColors else LightSemanticColors).copy(
        highContrast = settings.highContrast,
        isDark = isDark,
    )

    val motionScheme = if (settings.reduceMotion) {
        MotionScheme.standard()
    } else {
        MotionScheme.expressive()
    }

    val strings = AppStringCatalog.forLanguage(settings.language)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    val baseDensity = LocalDensity.current
    val simpleModeFactor = if (settings.simpleMode && settings.textSize == AppTextSize.M) 1.10f else 1.0f
    val targetFontScale = (baseDensity.fontScale * settings.textSize.scaleFactor * simpleModeFactor)
        .coerceIn(0.75f, 2.25f)
    val effectiveDensity = Density(
        density = baseDensity.density,
        fontScale = targetFontScale,
    )

    CompositionLocalProvider(
        LocalAppStrings provides strings,
        LocalOpenSimeSemanticColors provides semanticColors,
        LocalDensity provides effectiveDensity,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            shapes = OpenSimeShapes,
            typography = OpenSimeTypography,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
