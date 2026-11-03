package md.sime.opensime.core.model

import md.sime.opensime.core.data.SampleAgendaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimetableStateTest {
    private val zone = ZoneId.of("Europe/Chisinau")
    private val date = "2026-11-02"
    private val physics = lesson("physics", "09:30", "10:15", 7)
    private val next = lesson("next", "10:30", "11:10", 12)
    private val base = SampleAgendaRepository.buildDefaultSampleSnapshots()
        .getValue(SampleAgendaRepository.STUDENT_SEVENTH_GRADE)
        .copy(referenceIsoDate = date, referenceTimeHm = "09:15", todayLessons = emptyList(), weekLessonsByDay = emptyMap())

    private fun lesson(id: String, start: String, end: String, period: Int = 1) = LessonItem(
        id = id, periodNumber = period, startTime = start, endTime = end,
        subject = id, room = "", teacherName = "",
        // Intentionally wrong stored status: the clock must be authoritative.
        status = LessonStatus.COMPLETED,
    )

    private fun snapshot(vararg days: Pair<String, List<LessonItem>>) = base.copy(lessonsByDate = mapOf(*days))

    private fun clock(local: String) = Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

    private fun at(time: String, data: StudentAgendaSnapshot = snapshot(date to listOf(physics, next))) =
        TimetableState.derive(data, clock("${date}T$time"), zone)

    @Test
    fun `09 29 is before the 09 30 lesson regardless of persisted status`() {
        val state = at("09:29:00")
        assertEquals(TimetablePhase.BEFORE_FIRST, state.phase)
        assertEquals(physics.id, state.next?.id)
        assertEquals(1, state.remainingMinutes)
        assertNull(state.active)
    }

    @Test
    fun `09 30 is inclusive and 09 38 has 37 minutes left`() {
        assertEquals(TimetablePhase.ACTIVE, at("09:30:00").phase)
        assertEquals(45, at("09:30:00").remainingMinutes)
        val state = at("09:38:00")
        assertEquals(TimetablePhase.ACTIVE, state.phase)
        assertEquals(physics.id, state.active?.id)
        assertEquals(37, state.remainingMinutes)
        assertEquals(LessonStatus.LIVE_NOW, state.today.first().status)
        assertEquals(LessonStatus.NEXT_UP, state.today.last().status)
    }

    @Test
    fun `end is exclusive and gap ends exactly at next start`() {
        for (time in listOf("10:15:00", "10:16:00")) {
            val state = at(time)
            assertEquals(TimetablePhase.BETWEEN_LESSONS, state.phase)
            assertNull(state.active)
            assertEquals(next.id, state.next?.id)
            assertEquals(LessonStatus.COMPLETED, state.today.first().status)
        }
        assertEquals(15, at("10:15:00").remainingMinutes)
        assertEquals(14, at("10:16:00").remainingMinutes)
        assertEquals(next.id, at("10:30:00").active?.id)
    }

    @Test
    fun `final lesson ends exactly at its end and not a minute sooner`() {
        assertEquals(TimetablePhase.ACTIVE, at("11:09:59").phase)
        assertEquals(1, at("11:09:59").remainingMinutes)
        val state = at("11:10:00")
        assertEquals(TimetablePhase.AFTER_LAST, state.phase)
        assertNull(state.active)
        assertNull(state.next)
        assertNull(state.remainingMinutes)
        assertTrue(state.today.all { it.status == LessonStatus.COMPLETED })
        assertEquals(LocalDate.parse(date), state.date)
    }

    @Test
    fun `tomorrow preview uses tomorrow actual schedule`() {
        val tomorrowLesson = lesson("tomorrow", "11:45", "12:20")
        val state = at("11:10:00", snapshot(date to listOf(physics, next), "2026-11-03" to listOf(tomorrowLesson)))
        assertEquals(TimetablePhase.AFTER_LAST, state.phase)
        assertEquals(listOf("tomorrow"), state.tomorrow?.map { it.id })
        assertEquals("11:45", state.tomorrow?.first()?.startTime)
    }

    @Test
    fun `explicit empty tomorrow is a day off while absent tomorrow is unknown`() {
        val known = at("11:10:00", snapshot(date to listOf(physics, next), "2026-11-03" to emptyList()))
        assertEquals(emptyList<LessonItem>(), known.tomorrow)
        assertNull(at("11:10:00").tomorrow)
    }

    @Test
    fun `explicit empty today differs from unavailable timetable`() {
        assertEquals(TimetablePhase.NO_LESSONS, at("09:38:00", snapshot(date to emptyList())).phase)
        assertEquals(TimetablePhase.UNAVAILABLE, at("09:38:00", snapshot()).phase)
    }

    @Test
    fun `irregular unordered lessons and long gaps use times not period numbers`() {
        val early = lesson("early", "07:12", "07:27", 99)
        val late = lesson("late", "14:02", "14:19", 1)
        val data = snapshot(date to listOf(late, early))
        assertEquals(early.id, at("07:12:00", data).active?.id)
        assertEquals(TimetablePhase.BETWEEN_LESSONS, at("12:00:00", data).phase)
        assertEquals(late.id, at("14:02:00", data).active?.id)
        assertEquals(listOf(early.id, late.id), at("12:00:00", data).today.map { it.id })
    }

    @Test
    fun `consecutive lessons transition directly without inventing a break`() {
        val consecutive = next.copy(startTime = physics.endTime)
        val state = at("10:15:00", snapshot(date to listOf(physics, consecutive)))
        assertEquals(TimetablePhase.ACTIVE, state.phase)
        assertEquals(consecutive.id, state.active?.id)
    }

    @Test
    fun `midnight switches date rather than reusing previous day`() {
        val tomorrowLesson = lesson("tomorrow", "08:00", "08:30")
        val data = snapshot(date to listOf(physics), "2026-11-03" to listOf(tomorrowLesson))
        val before = TimetableState.derive(data, clock("2026-11-02T23:59:59"), zone)
        val after = TimetableState.derive(data, clock("2026-11-03T00:00:00"), zone)
        assertEquals(TimetablePhase.AFTER_LAST, before.phase)
        assertEquals(LocalDate.parse("2026-11-03"), after.date)
        assertEquals(TimetablePhase.BEFORE_FIRST, after.phase)
        assertEquals(tomorrowLesson.id, after.next?.id)
        assertEquals(listOf(tomorrowLesson.id), after.today.map { it.id })
    }

    @Test
    fun `zone is injectable and clock moves both forward and backward`() {
        val data = snapshot(date to listOf(physics, next))
        val now = clock("2026-11-02T09:38:00")
        assertEquals(TimetablePhase.ACTIVE, TimetableState.derive(data, now, zone).phase)
        assertEquals(TimetablePhase.BEFORE_FIRST, TimetableState.derive(data, now, ZoneId.of("UTC")).phase)
        assertEquals(TimetablePhase.AFTER_LAST, at("12:00:00", data).phase)
        assertEquals(TimetablePhase.BEFORE_FIRST, at("09:29:00", data).phase)
        assertEquals(37, at("09:38:00", data).remainingMinutes)
    }

    @Test
    fun `cached stale schedule keeps advancing after resume or engine recreation`() {
        val data = snapshot(date to listOf(physics, next)).copy(stale = true)
        assertEquals(TimetablePhase.BEFORE_FIRST, at("09:15:00", data).phase)
        val resumed = TimetableState.derive(data, clock("2026-11-02T09:38:00"), zone)
        assertTrue(resumed.isStale)
        assertEquals(37, resumed.remainingMinutes)
        assertEquals(TimetablePhase.AFTER_LAST, at("11:10:00", data).phase)
    }

    @Test
    fun `weekend lessons are kept and unknown future weeks never repeat`() {
        val saturday = "2026-11-07"
        val data = snapshot(saturday to listOf(physics))
        val state = TimetableState.derive(data, clock("${saturday}T09:38:00"), zone)
        assertEquals(TimetablePhase.ACTIVE, state.phase)
        assertEquals(37, state.remainingMinutes)
        assertEquals(TimetablePhase.UNAVAILABLE, TimetableState.derive(data, clock("2026-11-14T09:38:00"), zone).phase)
    }

    @Test
    fun `two student snapshots with different schedules have independent results`() {
        val a = snapshot(date to listOf(physics))
        val b = a.copy(student = a.student.copy(id = SampleAgendaRepository.STUDENT_ELEVENTH_GRADE),
            lessonsByDate = mapOf(date to listOf(next)))
        assertEquals(TimetablePhase.ACTIVE, at("09:38:00", a).phase)
        assertEquals(TimetablePhase.BEFORE_FIRST, at("09:38:00", b).phase)
        assertEquals(physics.id, at("09:38:00", a).active?.id)
        assertEquals(next.id, at("09:38:00", b).next?.id)
        assertFalse(a.studentId == b.studentId)
    }
}
