package md.sime.opensime.feature.timetable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * Week paging. Pages are weeks counted from a fixed Monday, so a page index always means the
 * same calendar week: the rail never rebuilds or resets when the selection or "today" changes.
 */
private val WeekAnchor: LocalDate = LocalDate.of(2020, 1, 6) // a Monday
private const val WeekCount = 1100 // roughly 21 years

internal fun timetableWeekPage(date: LocalDate): Int =
    ChronoUnit.WEEKS.between(WeekAnchor, timetableWeek(date).first()).toInt().coerceIn(0, WeekCount - 1)

internal fun timetableWeekStart(page: Int): LocalDate = WeekAnchor.plusWeeks(page.toLong())

private val RailHorizontalPadding = 12.dp
private val RailCellMinHeight = 72.dp
private val RailIndicatorShape = RoundedCornerShape(22.dp)
/** Fixed-size cells: cap font scaling here only, the full date stays available to TalkBack. */
private const val RailMaxFontScale = 1.3f

@Composable
internal fun TimetableContent(snapshot: StudentAgendaSnapshot, today: LocalDate, simpleMode: Boolean = false, modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    var selection by rememberSaveable(snapshot.studentId.value) { mutableStateOf<String?>(null) }
    val selectedDate = timetableSelectedDate(selection, today)
    val lessons = remember(snapshot, selectedDate) { timetableLessons(snapshot, selectedDate) }

    // Swiping the lesson area horizontally steps one day, crossing week boundaries.
    // The day rail consumes its own horizontal drags first, so the two never conflict.
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val swipeState = rememberDraggableState { dragTotal += it }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .draggable(
                state = swipeState,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragTotal = 0f },
                onDragStopped = {
                    when {
                        dragTotal <= -swipeThresholdPx -> selection = selectedDate.plusDays(1).toString()
                        dragTotal >= swipeThresholdPx -> selection = selectedDate.minusDays(1).toString()
                    }
                    dragTotal = 0f
                },
            )
            .testTag("timetable_screen_list"),
        // Horizontal padding is applied per item so the day rail can use the full width.
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "day_switch") {
            DayRail(selectedDate, today, { selection = it.toString() }, strings)
        }
        item(key = "day_heading") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.formatLongDate(selectedDate.toString()),
                    modifier = Modifier.weight(1f).testTag("timetable_day_heading"),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Following "today" again is one tap, however many weeks away the user has paged.
                AnimatedVisibility(visible = selectedDate != today) {
                    TextButton(onClick = { selection = null }, modifier = Modifier.testTag("timetable_today_button")) {
                        Text(strings.today)
                    }
                }
            }
        }
        if (lessons == null) {
            item(key = "unknown_lessons") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    EmptyStateCard(title = strings.timetableDayUnavailable, subtitle = strings.timetableDayUnavailableDesc)
                }
            }
        } else if (lessons.isEmpty()) {
            item(key = "empty_lessons") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    EmptyStateCard(title = strings.noLessonsTitle, subtitle = strings.noLessonsSub)
                }
            }
        } else {
            item(key = "day_lessons") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lessons.forEach { TimetableLessonCard(it, simpleMode, strings) }
                }
            }
        }
    }
}

/**
 * Week rail: one page per week, seven equal cells per page, no partially visible days at rest.
 *
 * State ownership: the selected date lives in [TimetableContent]; this composable only mirrors it.
 *  - selection -> pager: when the selected date moves to another week, the pager follows.
 *  - pager -> selection: when the user swipes and the pager SETTLES on another week, the same
 *    weekday of that week is selected. Mid-swipe nothing changes, so there is no flicker.
 * Both directions compare week pages, so neither can re-trigger the other.
 */
@Composable
private fun DayRail(selectedDate: LocalDate, today: LocalDate, onSelectDay: (LocalDate) -> Unit, strings: AppStrings) {
    val selectedPage = timetableWeekPage(selectedDate)
    val pagerState = rememberPagerState(initialPage = selectedPage) { WeekCount }
    val currentSelected by rememberUpdatedState(selectedDate)
    val currentOnSelect by rememberUpdatedState(onSelectDay)

    LaunchedEffect(selectedPage) {
        when (abs(pagerState.currentPage - selectedPage)) {
            0 -> Unit
            1, 2 -> pagerState.animateScrollToPage(selectedPage)
            else -> pagerState.scrollToPage(selectedPage) // far jumps (e.g. Today) should not scroll through weeks
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val selected = currentSelected
            if (page != timetableWeekPage(selected)) {
                currentOnSelect(timetableWeekStart(page).plusDays(selected.dayOfWeek.value - 1L))
            }
        }
    }

    val density = LocalDensity.current
    val railDensity = remember(density) { Density(density.density, min(density.fontScale, RailMaxFontScale)) }

    HorizontalPager(
        state = pagerState,
        // Padding on the pager itself (not contentPadding) so neighbouring weeks never peek in at rest.
        modifier = Modifier.fillMaxWidth().padding(horizontal = RailHorizontalPadding).testTag("timetable_day_selector"),
        key = { it },
    ) { page ->
        val dates = remember(page) { timetableWeek(timetableWeekStart(page)) }
        CompositionLocalProvider(LocalDensity provides railDensity) {
            WeekPage(
                dates = dates,
                selectedIndex = if (page == selectedPage) selectedDate.dayOfWeek.value - 1 else null,
                today = today,
                onSelectDay = onSelectDay,
                strings = strings,
            )
        }
    }
}

/**
 * One week. A single selection container slides between days (it is not drawn per day), so the
 * selected day is the only filled shape; unselected days are plain text.
 */
@Composable
private fun WeekPage(dates: List<LocalDate>, selectedIndex: Int?, today: LocalDate,
    onSelectDay: (LocalDate) -> Unit, strings: AppStrings) {
    val colors = MaterialTheme.colorScheme
    val position = remember { Animatable((selectedIndex ?: 0).toFloat()) }
    val presence = remember { Animatable(if (selectedIndex != null) 1f else 0f) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex == null) {
            presence.animateTo(0f, tween(120))
        } else if (presence.value == 0f) {
            // Arriving from another week: appear in place instead of sliding in from Monday.
            position.snapTo(selectedIndex.toFloat())
            presence.animateTo(1f, tween(160))
        } else {
            coroutineScope {
                launch {
                    position.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                }
                launch { presence.animateTo(1f, tween(120)) }
            }
        }
    }
    val slide = position.value
    val visible = presence.value

    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxWidth(1f / dates.size)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = position.value * size.width
                        alpha = presence.value
                    }
                    .padding(2.dp)
                    .clip(RailIndicatorShape)
                    .background(colors.primary),
            )
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            dates.forEachIndexed { index, date ->
                DayCell(
                    date = date,
                    index = index,
                    selected = index == selectedIndex,
                    isToday = date == today,
                    // Text colour follows the container as it passes, so it is never onPrimary on a bare surface.
                    emphasis = (1f - abs(slide - index)).coerceIn(0f, 1f) * visible,
                    onSelect = { onSelectDay(date) },
                    strings = strings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, index: Int, selected: Boolean, isToday: Boolean, emphasis: Float,
    onSelect: () -> Unit, strings: AppStrings, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val labelColor = lerpColor(colors.onSurfaceVariant, colors.onPrimary.copy(alpha = 0.85f), emphasis)
    val numberColor = lerpColor(if (isToday) colors.primary else colors.onSurface, colors.onPrimary, emphasis)
    val dotColor = if (isToday) lerpColor(colors.primary, colors.onPrimary, emphasis) else Color.Transparent
    val description = if (isToday) {
        "${strings.today}, ${strings.formatLongDate(date.toString())}"
    } else {
        strings.formatLongDate(date.toString())
    }

    Column(
        modifier = modifier
            .heightIn(min = RailCellMinHeight)
            .clip(RailIndicatorShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = description }
            .padding(vertical = 10.dp)
            .testTag("weekday_button_$index"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = strings.weekdaysLong[index].take(3).uppercase(),
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
        Text(
            text = date.dayOfMonth.toString().padStart(2, '0'),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = numberColor,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
        // Today marker: one quiet dot. The slot is always reserved so heights never jump.
        Box(Modifier.size(4.dp).background(dotColor, CircleShape))
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