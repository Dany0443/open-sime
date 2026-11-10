package md.sime.opensime

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.compose.ui.unit.Density
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.feature.marks.MarksScreen
import org.junit.Rule
import org.junit.Test

class SubjectDetailsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingDismissalStudentSwitchAndLogoutCloseTheSheet() {
        val students = SampleAgendaRepository.buildDefaultSampleSnapshots().values.toList()
        val snapshot = mutableStateOf(students[0])
        val signedIn = mutableStateOf(true)
        val subject = students[0].subjectSummaries.first().subject
        compose.activityRule.scenario.onActivity { it.setContent {
            OpenSimeTheme {
                if (signedIn.value) MarksScreen(snapshot.value)
            }
        } }

        compose.onNodeWithTag("subject_card_$subject").performClick()
        compose.onNodeWithTag("subject_details_sheet").assertExists()
        compose.onNodeWithTag("subject_details_name").assertExists()
        compose.onNodeWithTag("subject_details_semester2").performScrollTo()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("subject_details_sheet").assertDoesNotExist()

        compose.onNodeWithTag("subject_card_$subject").performClick()
        compose.onNodeWithTag("subject_details_sheet").assertExists()
        compose.onNodeWithTag("subject_details_close").performClick()
        compose.onNodeWithTag("subject_details_sheet").assertDoesNotExist()

        compose.onNodeWithTag("subject_card_$subject").performClick()
        compose.runOnIdle { snapshot.value = students[1] }
        compose.onNodeWithTag("subject_details_sheet").assertDoesNotExist()

        compose.onNodeWithTag("subject_card_${students[1].subjectSummaries.first().subject}").performClick()
        compose.onNodeWithTag("subject_details_sheet").assertExists()
        compose.runOnIdle { signedIn.value = false }
        compose.onNodeWithTag("subject_details_sheet").assertDoesNotExist()
    }

    @Test fun wideGradesTableRowsOpenDetails() {
        val snapshot = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
        val subject = snapshot.subjectSummaries.first().subject
        compose.activityRule.scenario.onActivity { it.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density = density.density * 0.4f)) {
                OpenSimeTheme(settings = OpenSimeSettingsState(themeMode = ThemeMode.DARK)) {
                    MarksScreen(snapshot)
                }
            }
        } }

        compose.onNodeWithTag("subject_table_row_$subject").performClick()
        compose.onNodeWithTag("subject_details_sheet").assertExists()
    }

    @Test fun largeFontScaleShowsLongSubjectAndEmptyGradeState() {
        val base = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
        val longName = "Limba și literatura română — analiză și interpretare avansată"
        val summary = base.subjectSummaries.first().copy(subject = longName, teacherName = "", marks = emptyList())
        val snapshot = base.copy(subjectSummaries = listOf(summary))
        compose.activityRule.scenario.onActivity { it.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density = density.density, fontScale = 1.5f)) {
                OpenSimeTheme(settings = OpenSimeSettingsState(themeMode = ThemeMode.LIGHT)) {
                    MarksScreen(snapshot)
                }
            }
        } }

        compose.onNodeWithTag("subject_card_$longName").performClick()
        compose.onNodeWithTag("subject_details_name").assertExists()
        compose.onNodeWithTag("subject_details_semester1_no_marks").assertExists()
    }

}
