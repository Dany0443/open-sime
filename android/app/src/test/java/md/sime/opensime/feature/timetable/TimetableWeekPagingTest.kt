package md.sime.opensime.feature.timetable

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the week paging that backs the Orar day rail. */
class TimetableWeekPagingTest {

    @Test fun everyDayOfAWeekMapsToTheSamePage() {
        // Mon 2 Nov 2026 .. Sun 8 Nov 2026, Sunday belongs to the week that started on Monday.
        val pages = (2L..8L).map { timetableWeekPage(LocalDate.of(2026, 11, it.toInt())) }
        assertEquals(setOf(356), pages.toSet())
    }

    @Test fun mondayStartsTheNextPage() {
        assertEquals(356, timetableWeekPage(LocalDate.of(2026, 11, 8)))
        assertEquals(357, timetableWeekPage(LocalDate.of(2026, 11, 9)))
    }

    @Test fun pageStartIsAlwaysAMonday() {
        listOf(0, 1, 356, 357, 358, 1099).forEach {
            assertEquals(DayOfWeek.MONDAY, timetableWeekStart(it).dayOfWeek)
        }
        assertEquals(LocalDate.of(2026, 11, 2), timetableWeekStart(356))
        assertEquals(LocalDate.of(2026, 11, 16), timetableWeekStart(358))
    }

    @Test fun pageAndStartRoundTripForEveryDayOfASchoolYear() {
        var date = LocalDate.of(2026, 9, 1)
        while (date.isBefore(LocalDate.of(2027, 6, 1))) {
            assertEquals(timetableWeek(date).first(), timetableWeekStart(timetableWeekPage(date)))
            date = date.plusDays(1)
        }
    }

    /** Swiping the rail keeps the weekday: the same weekday of the neighbouring week is selected. */
    @Test fun swipingKeepsTheWeekday() {
        val wednesday = LocalDate.of(2026, 11, 4)
        val page = timetableWeekPage(wednesday)
        val next = timetableWeekStart(page + 1).plusDays(wednesday.dayOfWeek.value - 1L)
        val previous = timetableWeekStart(page - 1).plusDays(wednesday.dayOfWeek.value - 1L)
        assertEquals(wednesday.plusWeeks(1), next)
        assertEquals(wednesday.minusWeeks(1), previous)
        assertEquals(DayOfWeek.WEDNESDAY, next.dayOfWeek)
    }

    @Test fun datesOutsideTheSupportedRangeAreClampedNotRejected() {
        assertEquals(0, timetableWeekPage(LocalDate.of(2019, 6, 1)))
        assertEquals(1099, timetableWeekPage(LocalDate.of(2100, 1, 1)))
    }

    @Test fun weekHasSevenConsecutiveDaysStartingOnMonday() {
        val week = timetableWeek(timetableWeekStart(356))
        assertEquals(7, week.size)
        assertEquals(DayOfWeek.MONDAY, week.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, week.last().dayOfWeek)
        assertTrue(week.zipWithNext().all { (a, b) -> a.plusDays(1) == b })
    }

    @Test fun selectionFallsBackToTodayWhenMissingOrInvalid() {
        val today = LocalDate.of(2026, 11, 7)
        assertEquals(today, timetableSelectedDate(null, today))
        assertEquals(today, timetableSelectedDate("not-a-date", today))
        assertEquals(LocalDate.of(2026, 11, 12), timetableSelectedDate("2026-11-12", today))
    }
}
