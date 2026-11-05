package md.sime.opensime

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.i18n.AppStringCatalog
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.LessonStatus
import md.sime.opensime.feature.timetable.TimetableContent
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class TimetableDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.parse("2026-11-02")
    private val strings = AppStringCatalog.ro
    private val samples = SampleAgendaRepository.buildDefaultSampleSnapshots().values.toList()
    private fun lesson(subject: String, start: String, end: String) = LessonItem(
        id = subject, periodNumber = 1, startTime = start, endTime = end,
        subject = subject, room = "101", teacherName = "Teacher", status = LessonStatus.LIVE_NOW,
    )
    private fun sample(index: Int, subject: String) = samples[index].copy(
        referenceIsoDate = monday.toString(), todayLessons = emptyList(), weekLessonsByDay = emptyMap(),
        lessonsByDate = mapOf(monday.toString() to listOf(lesson("Synthetic later", "11:27", "12:03"),
            lesson(subject, "09:13", "09:51")),
            monday.plusDays(1).toString() to emptyList()), stale = true,
    )

    @Test fun dateRolloverManualSelectionRefreshAndStudentSwitch() {
        val today = mutableStateOf(monday)
        val snapshot = mutableStateOf(sample(0, "Synthetic A"))
        compose.activityRule.scenario.onActivity { it.setContent { TimetableContent(snapshot.value, today.value) } }
        compose.onNodeWithTag("weekday_button_0").assertIsSelected()
        compose.onNodeWithText("Synthetic A").assertExists()
        compose.onNodeWithText("09:13").assertExists()
        compose.onNodeWithText("09:51").assertExists()
        compose.onNodeWithText("11:27").assertExists()
        assertTrue(compose.onNodeWithText("Synthetic A").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Synthetic later").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(strings.formatBreak(96)).assertDoesNotExist()
        compose.onNodeWithText(strings.liveBadge).assertDoesNotExist()
        compose.onNodeWithText(strings.nextBadge).assertDoesNotExist()
        compose.onNodeWithText(strings.pastBadge).assertDoesNotExist()
        compose.runOnIdle { today.value = monday.plusDays(1) }
        compose.onNodeWithTag("weekday_button_1").assertIsSelected()
        compose.onNodeWithText(strings.noLessonsTitle).assertExists()
        compose.onNodeWithTag("day_next_button").performClick()
        compose.onNodeWithText(strings.timetableDayUnavailable).assertExists()
        compose.runOnIdle { today.value = monday.plusDays(7) }
        compose.onNodeWithText(strings.formatLongDate(monday.plusDays(2).toString())).assertExists()
        compose.runOnIdle { snapshot.value = sample(1, "Synthetic B"); today.value = monday }
        compose.onNodeWithTag("weekday_button_0").assertIsSelected()
        compose.onNodeWithText("Synthetic B").assertExists()
        compose.onNodeWithText("Synthetic A").assertDoesNotExist()
        compose.runOnIdle { snapshot.value = sample(1, "Synthetic refreshed").copy(stale = false) }
        compose.onNodeWithText("Synthetic refreshed").assertExists()
        compose.onNodeWithText("Synthetic B").assertDoesNotExist()
    }

    @Test fun intentionalSelectionSurvivesActivityRecreation() {
        val snapshot = sample(0, "Synthetic A")
        fun install(activity: ComponentActivity) = activity.setContent { TimetableContent(snapshot, monday) }
        compose.activityRule.scenario.onActivity(::install)
        compose.onNodeWithTag("day_next_button").performClick()
        compose.onNodeWithTag("weekday_button_1").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::install)
        compose.onNodeWithTag("weekday_button_1").assertIsSelected()
        compose.onNodeWithText(strings.noLessonsTitle).assertExists()
    }

    @Test fun sundaySelectionIsVisibleAndUnknownIsNotAFreeDay() {
        val sunday = monday.plusDays(6)
        compose.activityRule.scenario.onActivity { it.setContent { TimetableContent(sample(0, "Synthetic A"), sunday) } }
        compose.onNodeWithTag("weekday_button_6").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText(strings.timetableDayUnavailable).assertExists()
        compose.onNodeWithText(strings.noLessonsTitle).assertDoesNotExist()
    }
}
