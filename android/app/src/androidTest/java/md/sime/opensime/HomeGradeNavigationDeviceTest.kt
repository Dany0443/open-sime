package md.sime.opensime

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.designsystem.OpenSimeTheme
import md.sime.opensime.feature.home.HomeScreen
import md.sime.opensime.feature.marks.MarksScreen
import md.sime.opensime.feature.subjects.SubjectDetailsKey
import md.sime.opensime.feature.subjects.subjectDetailsKeyForMark
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HomeGradeNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun latestGradeOpensMatchingSheetOnceAndDismissalSurvivesRefresh() {
        val base = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
        val mark = base.subjectSummaries.first().marks.first()
        val snapshot = mutableStateOf(base.copy(recentMarks = listOf(mark)))
        val showGrades = mutableStateOf(false)
        val request = mutableStateOf<SubjectDetailsKey?>(null)
        compose.activityRule.scenario.onActivity { it.setContent {
            OpenSimeTheme {
                if (showGrades.value) MarksScreen(snapshot.value, requestedSubject = request.value,
                    onSubjectRequestHandled = { request.value = null })
                else HomeScreen(snapshot.value, { showGrades.value = true }, {}, {}, {}, simpleMode = false,
                    reduceMotion = true, onOpenGrade = { selected ->
                        request.value = subjectDetailsKeyForMark(snapshot.value, selected)
                        showGrades.value = true
                    })
            }
        } }
        compose.onNodeWithTag("home_screen_list").performScrollToNode(hasTestTag("last_grade_card"))
        compose.onNodeWithTag("last_grade_card").performClick()
        compose.onNodeWithTag("subject_details_name").assertTextEquals(mark.subject)
        compose.runOnIdle { assertNull(request.value) }
        compose.onNodeWithTag("subject_details_close").performClick()
        compose.runOnIdle { snapshot.value = snapshot.value.copy(stale = true) }
        compose.onNodeWithTag("subject_details_sheet").assertDoesNotExist()
    }

    @Test fun requestFromAnotherStudentIsConsumedWithoutOpeningSheet() {
        val students = SampleAgendaRepository.buildDefaultSampleSnapshots().values.toList()
        val request = mutableStateOf<SubjectDetailsKey?>(SubjectDetailsKey(students[0].studentId, students[0].subjectSummaries.first().subject))
        compose.activityRule.scenario.onActivity { it.setContent {
            OpenSimeTheme { MarksScreen(students[1], requestedSubject = request.value,
                onSubjectRequestHandled = { request.value = null }) }
        } }
        compose.onNodeWithTag("subject_details_sheet").assertDoesNotExist()
        compose.runOnIdle { assertNull(request.value) }
    }
}
