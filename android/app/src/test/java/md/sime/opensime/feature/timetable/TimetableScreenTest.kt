package md.sime.opensime.feature.timetable

import java.time.LocalDate
import md.sime.opensime.core.data.SampleAgendaRepository
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.LessonStatus
import org.junit.Assert.*
import org.junit.Test

class TimetableScreenTest {
    private val monday = LocalDate.parse("2026-11-02")
    private val base = SampleAgendaRepository.buildDefaultSampleSnapshots().values.first()
        .copy(todayLessons = emptyList(), weekLessonsByDay = emptyMap(), lessonsByDate = emptyMap())
    private fun lesson(id: String, start: String, end: String) = LessonItem(
        id = id, periodNumber = 1, startTime = start, endTime = end,
        subject = id, room = "", teacherName = "", status = LessonStatus.LIVE_NOW,
    )

    @Test fun `automatic selection follows today at midnight and week rollover`() {
        assertEquals(monday, timetableSelectedDate(null, monday))
        assertEquals(monday.plusDays(1), timetableSelectedDate(null, monday.plusDays(1)))
        assertEquals(monday.plusDays(7), timetableSelectedDate(null, monday.plusDays(7)))
        assertEquals(monday.plusDays(7), timetableWeek(monday.plusDays(7)).first())
        assertEquals(monday.plusDays(6), timetableWeek(monday.plusDays(6)).last())
    }

    @Test fun `intentional selection survives date changes`() {
        val selected = monday.plusDays(3)
        assertEquals(selected, timetableSelectedDate(selected.toString(), monday.plusDays(7)))
    }

    @Test fun `known days preserve irregular times and order while missing coverage remains unknown`() {
        val early = lesson("early", "09:13", "09:51")
        val late = lesson("late", "11:27", "12:03")
        val snapshot = base.copy(lessonsByDate = mapOf(monday.toString() to listOf(late, early), monday.plusDays(1).toString() to emptyList()))
        assertEquals(listOf(early, late), timetableLessons(snapshot, monday))
        assertEquals(emptyList<LessonItem>(), timetableLessons(snapshot, monday.plusDays(1)))
        assertNull(timetableLessons(snapshot, monday.plusDays(2)))
    }

    @Test fun `new snapshots and separate students never reuse old lessons`() {
        val a = lesson("student-a", "08:00", "08:45")
        val b = lesson("student-b", "10:00", "10:45")
        val first = base.copy(lessonsByDate = mapOf(monday.toString() to listOf(a)), stale = true)
        val refreshed = first.copy(lessonsByDate = mapOf(monday.toString() to listOf(b)), stale = false)
        assertEquals(listOf(a), timetableLessons(first, monday))
        assertEquals(listOf(b), timetableLessons(refreshed, monday))
        assertNull(timetableLessons(base, monday))
    }
}
