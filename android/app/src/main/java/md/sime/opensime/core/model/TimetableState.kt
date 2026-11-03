package md.sime.opensime.core.model

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** No period-number or duration heuristic: the portal does not identify official breaks. */
enum class TimetablePhase { BEFORE_FIRST, ACTIVE, BETWEEN_LESSONS, AFTER_LAST, NO_LESSONS, UNAVAILABLE }

data class TimetableState(
    val date: LocalDate,
    val phase: TimetablePhase,
    val today: List<LessonItem>,
    val active: LessonItem?,
    val next: LessonItem?,
    val remainingMinutes: Int?,
    val tomorrow: List<LessonItem>?,
    val isStale: Boolean,
) {
    companion object {
        fun derive(
            snapshot: StudentAgendaSnapshot,
            clock: Clock,
            zone: ZoneId = ZoneId.of("Europe/Chisinau"),
        ): TimetableState {
            val now = clock.instant()
            val date = now.atZone(zone).toLocalDate()
            fun day(target: LocalDate): List<LessonItem>? {
                val iso = target.toString()
                val source = snapshot.lessonsByDate[iso]
                    ?: snapshot.weekDayIsoDates.indexOf(iso).takeIf { it >= 0 }
                        ?.let { snapshot.weekLessonsByDay[it]?.takeIf(List<LessonItem>::isNotEmpty) }
                    ?: snapshot.todayLessons.takeIf { iso == snapshot.referenceIsoDate && it.isNotEmpty() }
                    ?: return null
                // Invalid boundaries cannot establish a free day or an end-of-day state.
                if (source.any { it.startMinutesOfDay == null || it.endMinutesOfDay == null ||
                            it.endMinutesOfDay!! <= it.startMinutesOfDay!! }) return null
                return source.sortedBy { it.startMinutesOfDay }
            }
            fun instant(lesson: LessonItem, end: Boolean = false) = date
                .atStartOfDay(zone).toInstant()
                .plusSeconds((if (end) lesson.endMinutesOfDay!! else lesson.startMinutesOfDay!!).toLong() * 60L)
            val raw = day(date)
            val nextId = raw?.firstOrNull { instant(it) > now }?.id
            val lessons = raw.orEmpty().map { lesson ->
                lesson.copy(status = when {
                    now >= instant(lesson, true) -> LessonStatus.COMPLETED
                    now >= instant(lesson) -> LessonStatus.LIVE_NOW
                    lesson.id == nextId -> LessonStatus.NEXT_UP
                    else -> LessonStatus.UPCOMING
                })
            }
            val active = lessons.firstOrNull { it.status == LessonStatus.LIVE_NOW }
            val next = lessons.firstOrNull { it.status == LessonStatus.NEXT_UP }
            val phase = when {
                raw == null -> TimetablePhase.UNAVAILABLE
                lessons.isEmpty() -> TimetablePhase.NO_LESSONS
                active != null -> TimetablePhase.ACTIVE
                next == null -> TimetablePhase.AFTER_LAST
                lessons.any { it.status == LessonStatus.COMPLETED } -> TimetablePhase.BETWEEN_LESSONS
                else -> TimetablePhase.BEFORE_FIRST
            }
            val target = active?.let { instant(it, true) } ?: next?.let { instant(it) }
            val remaining = target?.let { ((Duration.between(now, it).toMillis() + 59_999) / 60_000).toInt() }
            val synced = snapshot.successfulSyncMillis["timetable"]
            val stale = snapshot.stale || synced == null || now.toEpochMilli() < synced ||
                now.toEpochMilli() - synced >= 15 * 60_000L
            return TimetableState(date, phase, lessons, active, next, remaining, day(date.plusDays(1)), stale)
        }
    }
}
