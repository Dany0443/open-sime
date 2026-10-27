package md.sime.opensime.feature.timetable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.ChipTone
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.designsystem.StatusChip
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.LessonStatus
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.parseHmToMinutes
import md.sime.opensime.feature.home.LessonMetaRow
import kotlin.math.max

/**
 * Port of `#view-timetable` (`buildDaySwitch`, `renderLessons`, `lessonCard`) from `web/public/app.js`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimetableScreen(
    snapshot: StudentAgendaSnapshot,
    simpleMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    var selectedDayIndex by rememberSaveable(snapshot.studentId.value) { mutableIntStateOf(1) }
    val lessonsForDay = snapshot.weekLessonsByDay[selectedDayIndex].orEmpty()
    val selectedIsoDate = snapshot.weekDayIsoDates.getOrElse(selectedDayIndex) { snapshot.referenceIsoDate }
    val isToday = selectedIsoDate == snapshot.referenceIsoDate
    val refMinutes = parseHmToMinutes(snapshot.referenceTimeHm) ?: (9 * 60 + 15)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("timetable_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // `#day-switch`
        item(key = "day_switch") {
            DaySwitchBar(
                weekDayIsoDates = snapshot.weekDayIsoDates,
                referenceIsoDate = snapshot.referenceIsoDate,
                selectedDayIndex = selectedDayIndex,
                onSelectDay = { selectedDayIndex = it },
                strings = strings,
            )
        }

        // `#day-heading`
        item(key = "day_heading") {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = strings.formatLongDate(selectedIsoDate),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isToday) {
                    StatusChip(
                        text = strings.today,
                        tone = ChipTone.INFO,
                    )
                }
                if (lessonsForDay.isNotEmpty()) {
                    StatusChip(
                        text = strings.formatLessonCount(lessonsForDay.size),
                        tone = ChipTone.NEUTRAL,
                    )
                }
            }
        }

        // `#lesson-list`
        if (lessonsForDay.isEmpty()) {
            item(key = "empty_lessons") {
                EmptyStateCard(
                    title = strings.noLessonsTitle,
                    subtitle = strings.noLessonsSub,
                )
            }
        } else {
            item(key = "day_lessons") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lessonsForDay.forEachIndexed { index, lesson ->
                        if (index > 0) {
                            val prevEnd = lessonsForDay[index - 1].endMinutesOfDay
                            val currStart = lesson.startMinutesOfDay
                            if (prevEnd != null && currStart != null && (currStart - prevEnd) >= 5) {
                                Text(
                                    text = strings.formatBreak(currStart - prevEnd),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 84.dp, top = 2.dp, bottom = 2.dp),
                                )
                            }
                        }

                        TimetableLessonCard(
                            lesson = lesson,
                            isToday = isToday,
                            refMinutes = refMinutes,
                            simpleMode = simpleMode,
                            strings = strings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySwitchBar(
    weekDayIsoDates: List<String>,
    referenceIsoDate: String,
    selectedDayIndex: Int,
    onSelectDay: (Int) -> Unit,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilledTonalIconButton(
            onClick = { if (selectedDayIndex > 0) onSelectDay(selectedDayIndex - 1) },
            enabled = selectedDayIndex > 0,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("day_prev_button"),
        ) {
            Icon(
                imageVector = OpenSimeIcons.ChevronLeft,
                contentDescription = strings.prevDay,
            )
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            strings.weekdaysShort.forEachIndexed { dayIndex, shortName ->
                val isoDate = weekDayIsoDates.getOrElse(dayIndex) { referenceIsoDate }
                val isSelected = selectedDayIndex == dayIndex
                val isToday = isoDate == referenceIsoDate
                val dayNumberText = strings.formatDayNumber(isoDate)
                val fullDateA11y = strings.formatLongDate(isoDate)

                val shape = if (isSelected) RoundedCornerShape(16.dp) else RoundedCornerShape(999.dp)
                val bg = if (isSelected) colorScheme.primary else colorScheme.surfaceContainerHigh
                val fg = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant
                val border = if (highContrast) {
                    BorderStroke(2.dp, if (isSelected) colorScheme.onSurface else fg)
                } else {
                    null
                }

                Surface(
                    onClick = { onSelectDay(dayIndex) },
                    shape = shape,
                    color = bg,
                    contentColor = fg,
                    border = border,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 56.dp)
                        .semantics {
                            contentDescription = fullDateA11y
                            selected = isSelected
                        }
                        .testTag("weekday_button_$dayIndex"),
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = shortName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            ),
                            color = fg,
                            maxLines = 1,
                        )
                        Text(
                            text = dayNumberText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                            ),
                            color = fg,
                            maxLines = 1,
                        )
                        if (isToday) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(fg),
                            )
                        } else {
                            Spacer(modifier = Modifier.height(7.dp))
                        }
                    }
                }
            }
        }

        FilledTonalIconButton(
            onClick = { if (selectedDayIndex < 4) onSelectDay(selectedDayIndex + 1) },
            enabled = selectedDayIndex < 4,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("day_next_button"),
        ) {
            Icon(
                imageVector = OpenSimeIcons.ChevronRight,
                contentDescription = strings.nextDay,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimetableLessonCard(
    lesson: LessonItem,
    isToday: Boolean,
    refMinutes: Int,
    simpleMode: Boolean,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    val isActive = isToday && lesson.status == LessonStatus.LIVE_NOW
    val isNext = isToday && lesson.status == LessonStatus.NEXT_UP
    val isPast = isToday && lesson.status == LessonStatus.COMPLETED

    val (cardBg, cardFg, shape) = when {
        isActive -> Triple(colorScheme.primary, colorScheme.onPrimary, MaterialTheme.shapes.extraLarge)
        isNext -> Triple(colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer, MaterialTheme.shapes.large)
        isPast -> Triple(colorScheme.surfaceContainerLow, colorScheme.onSurfaceVariant, MaterialTheme.shapes.large)
        else -> Triple(colorScheme.surfaceContainer, colorScheme.onSurface, MaterialTheme.shapes.large)
    }

    Surface(
        shape = shape,
        color = cardBg,
        contentColor = cardFg,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // `.lesson-time`
            Column(
                modifier = Modifier.width(64.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = lesson.startTime,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                    ),
                    color = cardFg,
                )
                Text(
                    text = lesson.endTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = cardFg.copy(alpha = 0.78f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.formatLessonPeriod(lesson.periodNumber),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = cardFg.copy(alpha = 0.78f),
                )
            }

            // `.lesson-body`
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = lesson.subject,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                        ),
                        color = cardFg,
                    )
                    when {
                        isActive -> StatusChip(text = strings.liveBadge, tone = ChipTone.LIVE)
                        isNext -> StatusChip(text = strings.nextBadge, tone = ChipTone.NEXT)
                        isPast -> StatusChip(text = strings.pastBadge, tone = ChipTone.QUIET)
                    }
                }

                if (!simpleMode) {
                    LessonMetaRow(
                        room = lesson.room,
                        teacher = lesson.teacherName,
                        contentColor = cardFg.copy(alpha = 0.88f),
                    )
                }

                if (!lesson.topic.isNullOrBlank()) {
                    Text(
                        text = lesson.topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = cardFg.copy(alpha = 0.88f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isActive) {
                    val startMin = lesson.startMinutesOfDay ?: refMinutes
                    val endMin = lesson.endMinutesOfDay ?: (refMinutes + 30)
                    val totalMin = max(1, endMin - startMin)
                    val leftMin = max(1, endMin - refMinutes)
                    val progress = ((refMinutes - startMin).toFloat() / totalMin.toFloat()).coerceIn(0f, 1f)

                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            color = colorScheme.onPrimary,
                            trackColor = colorScheme.onPrimary.copy(alpha = 0.25f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = strings.timeLeftTemplate.replace("{time}", strings.formatMinutes(leftMin)),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
