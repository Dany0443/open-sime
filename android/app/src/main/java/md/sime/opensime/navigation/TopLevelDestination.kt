package md.sime.opensime.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.i18n.AppStrings

/**
 * The five primary navigation destinations of Open SIME Android.
 * Profil and Setări remain accessible via top app bar actions (on compact widths)
 * and rail/drawer actions (on medium/expanded widths) without overcrowding bottom navigation.
 */
enum class TopLevelDestination(val route: String) {
    HOME("home"),
    MARKS("marks"),
    TIMETABLE("timetable"),
    ABSENCES("absences"),
    HOMEWORK("homework");

    val icon: ImageVector
        get() = when (this) {
            HOME -> OpenSimeIcons.Home
            MARKS -> OpenSimeIcons.Marks
            TIMETABLE -> OpenSimeIcons.Timetable
            ABSENCES -> OpenSimeIcons.Absences
            HOMEWORK -> OpenSimeIcons.Homework
        }

    fun label(strings: AppStrings, simpleMode: Boolean): String = when (this) {
        HOME -> strings.tabHome
        MARKS -> strings.marksLabel(simpleMode)
        TIMETABLE -> strings.timetableLabel(simpleMode)
        ABSENCES -> strings.absencesLabel(simpleMode)
        HOMEWORK -> strings.homeworkLabel(simpleMode)
    }
}

/**
 * Window width size classes aligned with Material 3 adaptive breakpoints and AGENTS.md:
 * - Compact: < 600dp (bottom navigation via ShortNavigationBar)
 * - Medium: 600dp .. 839dp (collapsed navigation rail via WideNavigationRail)
 * - Expanded: >= 840dp (expanded navigation rail / drawer via WideNavigationRail)
 */
enum class WindowWidthLayout {
    COMPACT,
    MEDIUM,
    EXPANDED;

    companion object {
        fun fromWidthDp(widthDp: Float): WindowWidthLayout = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

