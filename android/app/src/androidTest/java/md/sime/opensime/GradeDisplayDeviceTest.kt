package md.sime.opensime

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.feature.home.HomeScreen
import md.sime.opensime.feature.marks.MarksScreen
import org.junit.Rule
import org.junit.Test

class GradeDisplayDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun provisionalAveragesAreShownForBothStudentsWhileGradesRemainVisible() {
        val students = SampleAgendaRepository.buildDefaultSampleSnapshots().values.toList()
        val snapshot = mutableStateOf(students[0])
        val showMarks = mutableStateOf(false)
        compose.activityRule.scenario.onActivity { it.setContent {
            OpenSimeTheme {
                if (showMarks.value) MarksScreen(snapshot.value)
                else HomeScreen(snapshot.value, {}, {}, {}, {}, simpleMode = false)
            }
        } }
        fun checkEstimated() {
            compose.onNodeWithTag("home_screen_list").performScrollToNode(hasTestTag("general_average_card"))
            compose.onNodeWithText(AppStringCatalog.ro.generalAverageEstimated).assertExists()
        }
        checkEstimated()
        compose.runOnIdle { snapshot.value = students[1] }
        checkEstimated()
        compose.runOnIdle { showMarks.value = true }
        compose.onNodeWithText(students[1].subjectSummaries.first().subject).assertExists()
    }
}
