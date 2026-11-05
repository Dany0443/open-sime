package md.sime.opensime.feature.timetable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.portal.ChisinauTime
import md.sime.opensime.feature.home.LessonMetaRow

internal fun timetableSelectedDate(selection: String?, today: LocalDate): LocalDate =
    selection?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today

internal fun timetableWeek(date: LocalDate): List<LocalDate> {
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    return (0L..6L).map(monday::plusDays)
}

/** A missing date is unknown; only an explicit entry establishes an empty day. */
internal fun timetableLessons(snapshot: StudentAgendaSnapshot, date: LocalDate): List<LessonItem>? {
    val iso = date.toString()
    val lessons = snapshot.lessonsByDate[iso]
        ?: snapshot.weekDayIsoDates.indexOf(iso).takeIf { it >= 0 }
            ?.let { snapshot.weekLessonsByDay[it]?.takeIf(List<LessonItem>::isNotEmpty) }
        ?: snapshot.todayLessons.takeIf { iso == snapshot.referenceIsoDate && it.isNotEmpty() }
        ?: return null
    return lessons.sortedWith(compareBy(nullsLast()) { it.startMinutesOfDay })
}

/** Update only at a school-day boundary or when the wall clock changes. */
@Composable
private fun rememberSchoolDate(): LocalDate {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var today by remember { mutableStateOf(LocalDate.now(ChisinauTime.CHISINAU_ZONE)) }
    var clockChanges by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { clockChanges++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(lifecycle, clockChanges) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = Instant.now()
                today = now.atZone(ChisinauTime.CHISINAU_ZONE).toLocalDate()
                val nextMidnight = today.plusDays(1).atStartOfDay(ChisinauTime.CHISINAU_ZONE).toInstant()
                delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L))
            }
        }
    }
    return today
}

@Composable
fun TimetableScreen(snapshot: StudentAgendaSnapshot, simpleMode: Boolean = false, modifier: Modifier = Modifier) {
    TimetableContent(snapshot, rememberSchoolDate(), simpleMode, modifier)
}

@Composable
internal fun TimetableContent(snapshot: StudentAgendaSnapshot, today: LocalDate, simpleMode: Boolean = false, modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    var selection by rememberSaveable(snapshot.studentId.value) { mutableStateOf<String?>(null) }
    val selectedDate = timetableSelectedDate(selection, today)
    val lessons = remember(snapshot, selectedDate) { timetableLessons(snapshot, selectedDate) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("timetable_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "day_switch") {
            DaySwitchBar(timetableWeek(selectedDate), selectedDate, today, { selection = it.toString() }, strings)
        }
        item(key = "day_heading") {
            Text(strings.formatLongDate(selectedDate.toString()), modifier = Modifier.padding(horizontal = 4.dp).testTag("timetable_day_heading"),
                style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.onSurface)
        }
        if (lessons == null) {
            item(key = "unknown_lessons") { EmptyStateCard(title = strings.timetableDayUnavailable, subtitle = strings.timetableDayUnavailableDesc) }
        } else if (lessons.isEmpty()) {
            item(key = "empty_lessons") { EmptyStateCard(title = strings.noLessonsTitle, subtitle = strings.noLessonsSub) }
        } else {
            item(key = "day_lessons") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lessons.forEach { TimetableLessonCard(it, simpleMode, strings) }
                }
            }
        }
    }
}

@Composable
private fun DaySwitchBar(dates: List<LocalDate>, selectedDate: LocalDate, today: LocalDate,
    onSelectDay: (LocalDate) -> Unit, strings: AppStrings) {
    val colors = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val scrollState = rememberScrollState()
    val dayWidth = with(LocalDensity.current) { 54.dp.roundToPx() }
    LaunchedEffect(selectedDate) {
        scrollState.scrollTo(((selectedDate.dayOfWeek.value - 1) * dayWidth - scrollState.viewportSize / 2 + dayWidth / 2).coerceAtLeast(0))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(onClick = { onSelectDay(selectedDate.minusDays(1)) },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("day_prev_button")) {
            Icon(OpenSimeIcons.ChevronLeft, contentDescription = strings.prevDay)
        }
        Row(Modifier.weight(1f).horizontalScroll(scrollState), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dates.forEachIndexed { index, date ->
                val chosen = selectedDate == date
                val fg = if (chosen) colors.onPrimary else colors.onSurfaceVariant
                Surface(onClick = { onSelectDay(date) }, shape = MaterialTheme.shapes.medium,
                    color = if (chosen) colors.primary else colors.surfaceContainerHigh, contentColor = fg,
                    border = if (highContrast) BorderStroke(2.dp, if (chosen) colors.onSurface else fg) else null,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 56.dp).semantics {
                        contentDescription = strings.formatLongDate(date.toString())
                        selected = chosen
                    }.testTag("weekday_button_$index")) {
                    Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(strings.weekdaysLong[index].take(2), style = MaterialTheme.typography.labelSmall)
                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (date == today) FontWeight.ExtraBold else FontWeight.Normal)
                    }
                }
            }
        }
        FilledTonalIconButton(onClick = { onSelectDay(selectedDate.plusDays(1)) },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("day_next_button")) {
            Icon(OpenSimeIcons.ChevronRight, contentDescription = strings.nextDay)
        }
    }
}

@Composable
private fun TimetableLessonCard(lesson: LessonItem, simpleMode: Boolean, strings: AppStrings) {
    val colors = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer, contentColor = colors.onSurface,
        border = if (highContrast) BorderStroke(2.dp, colors.onSurface) else null, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(64.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(lesson.startTime, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(lesson.endTime, style = MaterialTheme.typography.bodySmall)
                Text(strings.formatLessonPeriod(lesson.periodNumber), style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(lesson.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!simpleMode) LessonMetaRow(room = lesson.room, teacher = lesson.teacherName, contentColor = colors.onSurfaceVariant)
                if (!lesson.topic.isNullOrBlank()) Text(lesson.topic, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
