package md.sime.opensime.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.BandChip
import md.sime.opensime.core.designsystem.ChipTone
import md.sime.opensime.core.designsystem.CookieDecorationShape
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.designsystem.ScoreBadge
import md.sime.opensime.core.designsystem.ScoreBadgeSize
import md.sime.opensime.core.designsystem.StatusChip
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.HomeworkItem
import md.sime.opensime.core.model.LessonItem
import md.sime.opensime.core.model.LessonStatus
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.parseHmToMinutes
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

@Composable
fun HomeScreen(
    snapshot: StudentAgendaSnapshot,
    onNavigateToMarks: () -> Unit,
    onNavigateToTimetable: () -> Unit,
    onNavigateToAbsences: () -> Unit,
    onNavigateToHomework: () -> Unit,
    simpleMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideGrid = maxWidth >= 960.dp && density.fontScale < 1.35f

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isWideGrid) {
                item(key = "home_metrics_wide") {
                    HomeMetricsSection(
                        snapshot = snapshot,
                        strings = strings,
                        simpleMode = simpleMode,
                        availableWidthDp = maxWidth.value,
                        fontScale = density.fontScale,
                        onNavigateToMarks = onNavigateToMarks,
                        onNavigateToAbsences = onNavigateToAbsences,
                    )
                }
                item(key = "home_wide_columns") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(modifier = Modifier.weight(7f)) {
                            HomeLiveLessonsCard(
                                snapshot = snapshot,
                                strings = strings,
                                simpleMode = simpleMode,
                                onNavigateToTimetable = onNavigateToTimetable,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(5f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HomeRecentMarksCard(
                                snapshot = snapshot,
                                strings = strings,
                                onNavigateToMarks = onNavigateToMarks,
                            )
                            HomeHomeworkPreviewCard(
                                snapshot = snapshot,
                                strings = strings,
                                onNavigateToHomework = onNavigateToHomework,
                            )
                        }
                    }
                }
            } else {
                item(key = "home_live") {
                    HomeLiveLessonsCard(
                        snapshot = snapshot,
                        strings = strings,
                        simpleMode = simpleMode,
                        onNavigateToTimetable = onNavigateToTimetable,
                    )
                }

                item(key = "home_metrics") {
                    HomeMetricsSection(
                        snapshot = snapshot,
                        strings = strings,
                        simpleMode = simpleMode,
                        availableWidthDp = maxWidth.value,
                        fontScale = density.fontScale,
                        onNavigateToMarks = onNavigateToMarks,
                        onNavigateToAbsences = onNavigateToAbsences,
                    )
                }

                item(key = "home_marks") {
                    HomeRecentMarksCard(
                        snapshot = snapshot,
                        strings = strings,
                        onNavigateToMarks = onNavigateToMarks,
                    )
                }

                item(key = "home_homework") {
                    HomeHomeworkPreviewCard(
                        snapshot = snapshot,
                        strings = strings,
                        onNavigateToHomework = onNavigateToHomework,
                    )
                }
            }
        }
    }
}

/**
 * Port of `renderLive()` (`#home-live` / `.card.live-card`) from `web/public/app.js`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeLiveLessonsCard(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    simpleMode: Boolean,
    onNavigateToTimetable: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val lessons = snapshot.todayLessons
    val activeLesson = lessons.firstOrNull { it.status == LessonStatus.LIVE_NOW }
    val nextLesson = lessons.firstOrNull { it.status == LessonStatus.NEXT_UP }
        ?: lessons.firstOrNull { it.status == LessonStatus.UPCOMING }
    val anyPast = lessons.any { it.status == LessonStatus.COMPLETED }
    val refMinutes = parseHmToMinutes(snapshot.referenceTimeHm) ?: (9 * 60 + 15)

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainerLow,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_live_card"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // `.card-head`
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = strings.todaysLessons,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = strings.formatLongDate(snapshot.referenceIsoDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            // `.live-hero`
            when {
                activeLesson != null -> {
                    val startMin = activeLesson.startMinutesOfDay ?: refMinutes
                    val endMin = activeLesson.endMinutesOfDay ?: (refMinutes + 30)
                    val totalMin = max(1, endMin - startMin)
                    val leftMin = max(1, endMin - refMinutes)
                    val progress = ((refMinutes - startMin).toFloat() / totalMin.toFloat()).coerceIn(0f, 1f)

                    Surface(
                        onClick = onNavigateToTimetable,
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                StatusChip(
                                    text = strings.liveBadge,
                                    tone = ChipTone.LIVE,
                                )
                                Text(
                                    text = "${activeLesson.startTime} - ${activeLesson.endTime}",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = colorScheme.onPrimary,
                                )
                            }

                            Text(
                                text = activeLesson.subject,
                                style = MaterialTheme.typography.headlineMediumEmphasized,
                                color = colorScheme.onPrimary,
                            )

                            if (!simpleMode) {
                                LessonMetaRow(
                                    room = activeLesson.room,
                                    teacher = activeLesson.teacherName,
                                    contentColor = colorScheme.onPrimary.copy(alpha = 0.92f),
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    text = strings.timeLeftTemplate.replace(
                                        "{time}",
                                        strings.formatMinutes(leftMin),
                                    ),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = colorScheme.onPrimary,
                                )
                            }
                        }
                    }

                    if (nextLesson != null) {
                        val untilMin = max(1, (nextLesson.startMinutesOfDay ?: (refMinutes + 40)) - refMinutes)
                        Surface(
                            onClick = onNavigateToTimetable,
                            shape = MaterialTheme.shapes.large,
                            color = colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                StatusChip(
                                    text = strings.nextBadge,
                                    tone = ChipTone.NEXT,
                                )
                                Text(
                                    text = nextLesson.subject,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = strings.startsAtInTemplate
                                        .replace("{start}", nextLesson.startTime)
                                        .replace("{time}", strings.formatMinutes(untilMin)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                nextLesson != null -> {
                    val untilMin = max(1, (nextLesson.startMinutesOfDay ?: (refMinutes + 15)) - refMinutes)
                    Surface(
                        onClick = onNavigateToTimetable,
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colorScheme.tertiaryContainer,
                        contentColor = colorScheme.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                StatusChip(
                                    text = if (anyPast) strings.breakBadge else strings.nextBadge,
                                    tone = ChipTone.NEXT,
                                )
                                Text(
                                    text = "${nextLesson.startTime} - ${nextLesson.endTime}",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                            Text(
                                text = nextLesson.subject,
                                style = MaterialTheme.typography.headlineMediumEmphasized,
                            )
                            if (!simpleMode) {
                                LessonMetaRow(
                                    room = nextLesson.room,
                                    teacher = nextLesson.teacherName,
                                    contentColor = colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                                )
                            }
                            val noteTemplate = if (anyPast) {
                                strings.nextLessonStartsInTemplate
                            } else {
                                strings.firstLessonStartsInTemplate
                            }
                            Text(
                                text = noteTemplate.replace("{time}", strings.formatMinutes(untilMin)),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }

                lessons.isNotEmpty() -> {
                    val last = lessons.last()
                    Surface(
                        onClick = onNavigateToTimetable,
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusChip(text = strings.endedBadge, tone = ChipTone.NEUTRAL)
                            Text(
                                text = strings.lessonsFinishedToday,
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                            )
                            Text(
                                text = strings.lastLessonInfoTemplate
                                    .replace("{subject}", last.subject)
                                    .replace("{end}", last.endTime),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusChip(text = strings.freeDayBadge, tone = ChipTone.NEUTRAL)
                            Text(
                                text = strings.noLessonsTodayTitle,
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                            )
                            Text(
                                text = strings.noLessonsTodaySub,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // `.track` & `.mini-list`
            if (lessons.isNotEmpty()) {
                DaySegmentedTrack(lessons = lessons, refMinutes = refMinutes)

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    lessons.forEach { lesson ->
                        val isPast = lesson.status == LessonStatus.COMPLETED
                        val isActive = lesson.status == LessonStatus.LIVE_NOW
                        val isNext = lesson.status == LessonStatus.NEXT_UP

                        val rowBg = when {
                            isActive -> colorScheme.primaryContainer
                            isNext -> colorScheme.tertiaryContainer
                            else -> androidx.compose.ui.graphics.Color.Transparent
                        }
                        val rowFg = when {
                            isActive -> colorScheme.onPrimaryContainer
                            isNext -> colorScheme.onTertiaryContainer
                            else -> colorScheme.onSurface
                        }
                        val subFg = when {
                            isActive -> colorScheme.onPrimaryContainer
                            isNext -> colorScheme.onTertiaryContainer
                            else -> colorScheme.onSurfaceVariant
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(rowBg)
                                .clickable(onClick = onNavigateToTimetable)
                                .alpha(if (isPast) 0.55f else 1f)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = lesson.startTime,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = subFg,
                            )
                            Text(
                                text = lesson.subject,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = rowFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (lesson.room.isNotBlank()) {
                                Text(
                                    text = lesson.room,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = subFg,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySegmentedTrack(
    lessons: List<LessonItem>,
    refMinutes: Int,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lessons.forEach { lesson ->
            val startMin = lesson.startMinutesOfDay ?: 0
            val endMin = lesson.endMinutesOfDay ?: (startMin + 45)
            val duration = max(1, endMin - startMin)
            val fillFraction = when (lesson.status) {
                LessonStatus.COMPLETED -> 1f
                LessonStatus.LIVE_NOW -> ((refMinutes - startMin).toFloat() / duration.toFloat()).coerceIn(0.15f, 0.95f)
                LessonStatus.NEXT_UP, LessonStatus.UPCOMING -> 0f
            }

            Box(
                modifier = Modifier
                    .weight(duration.toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(colorScheme.primary.copy(alpha = 0.18f)),
            ) {
                if (fillFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .fillMaxHeight()
                            .background(colorScheme.primary),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LessonMetaRow(
    room: String,
    teacher: String,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (room.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = OpenSimeIcons.Room,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = room,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
        if (teacher.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = OpenSimeIcons.Profile,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = teacher,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}

/**
 * Port of `renderMetrics(m)` (`#home-metrics` / `.metrics`) from `web/public/app.js` and `style.css`.
 */
@Composable
private fun HomeMetricsSection(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    simpleMode: Boolean,
    availableWidthDp: Float,
    fontScale: Float,
    onNavigateToMarks: () -> Unit,
    onNavigateToAbsences: () -> Unit,
) {
    val isThreeCol = availableWidthDp >= 600f && fontScale < 1.35f
    val stackAllSingleColumn = availableWidthDp < 380f || fontScale >= 1.35f

    if (isThreeCol) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GpaMetricCard(
                snapshot = snapshot,
                strings = strings,
                simpleMode = simpleMode,
                onClick = onNavigateToMarks,
                modifier = Modifier.weight(1.3f),
            )
            AbsencesMetricCard(
                snapshot = snapshot,
                strings = strings,
                onClick = onNavigateToAbsences,
                modifier = Modifier.weight(1f),
            )
            LastGradeMetricCard(
                snapshot = snapshot,
                strings = strings,
                onClick = onNavigateToMarks,
                modifier = Modifier.weight(1f),
            )
        }
    } else if (stackAllSingleColumn) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GpaMetricCard(
                snapshot = snapshot,
                strings = strings,
                simpleMode = simpleMode,
                onClick = onNavigateToMarks,
                modifier = Modifier.fillMaxWidth(),
            )
            AbsencesMetricCard(
                snapshot = snapshot,
                strings = strings,
                onClick = onNavigateToAbsences,
                modifier = Modifier.fillMaxWidth(),
            )
            LastGradeMetricCard(
                snapshot = snapshot,
                strings = strings,
                onClick = onNavigateToMarks,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GpaMetricCard(
                snapshot = snapshot,
                strings = strings,
                simpleMode = simpleMode,
                onClick = onNavigateToMarks,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AbsencesMetricCard(
                    snapshot = snapshot,
                    strings = strings,
                    onClick = onNavigateToAbsences,
                    modifier = Modifier.weight(1f),
                )
                LastGradeMetricCard(
                    snapshot = snapshot,
                    strings = strings,
                    onClick = onNavigateToMarks,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpaMetricCard(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    simpleMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val gpa = snapshot.resolveGeneralAverage()
    val sem1Avg = snapshot.resolveSemesterAverage(1)
    val sem2Avg = snapshot.resolveSemesterAverage(2)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(
            topStart = 36.dp,
            topEnd = 36.dp,
            bottomStart = 36.dp,
            bottomEnd = 12.dp,
        ),
        color = colorScheme.primaryContainer,
        contentColor = colorScheme.onPrimaryContainer,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = modifier
            .sizeIn(minHeight = 152.dp)
            .testTag("general_average_card"),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (!simpleMode) {
                CookieDecorationShape(
                    tint = colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier
                        .size(160.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 36.dp, y = (-36).dp),
                )
            }

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = strings.generalAverageEstimated,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )

                Text(
                    text = strings.formatAverage(gpa?.value),
                    style = MaterialTheme.typography.displaySmallEmphasized.copy(
                        fontSize = 46.sp,
                        lineHeight = 50.sp,
                    ),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (gpa != null) {
                        BandChip(averageValue = gpa.value)
                    }
                    if (sem1Avg != null) {
                        StatusChip(
                            text = strings.sem1ShortTemplate.replace("{avg}", strings.formatAverage(sem1Avg.value)),
                            tone = ChipTone.NEUTRAL,
                        )
                    }
                    if (sem2Avg != null) {
                        StatusChip(
                            text = strings.sem2ShortTemplate.replace("{avg}", strings.formatAverage(sem2Avg.value)),
                            tone = ChipTone.NEUTRAL,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbsencesMetricCard(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current
    val sum = snapshot.absenceSummary
    val excused = sum.excusedCount
    val unexcused = sum.unexcusedCount
    val total = sum.totalCount

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainerHigh,
        contentColor = colorScheme.onSurface,
        border = if (semantic.highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = modifier
            .sizeIn(minHeight = 152.dp)
            .testTag("attendance_summary_card"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = strings.tabAbsences,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = total.toString(),
                style = MaterialTheme.typography.displaySmallEmphasized.copy(
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                ),
            )
            Text(
                text = if (total == 0) strings.noAbsencesThisYear else strings.inThisSchoolYear,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
            )

            if (excused + unexcused > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (excused > 0) {
                        Box(
                            modifier = Modifier
                                .weight(excused.toFloat())
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(semantic.success),
                        )
                    }
                    if (unexcused > 0) {
                        Box(
                            modifier = Modifier
                                .weight(unexcused.toFloat())
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(colorScheme.error),
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(semantic.success),
                        )
                        Text(
                            text = strings.absExcusedCountTemplate.replace("{count}", excused.toString()),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colorScheme.error),
                        )
                        Text(
                            text = strings.absUnexcusedCountTemplate.replace("{count}", unexcused.toString()),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LastGradeMetricCard(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val lastMark = snapshot.recentMarks.firstOrNull()

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp,
            bottomEnd = 12.dp,
            bottomStart = 28.dp,
        ),
        color = colorScheme.tertiaryContainer,
        contentColor = colorScheme.onTertiaryContainer,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = modifier
            .sizeIn(minHeight = 152.dp)
            .testTag("last_grade_card"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.lastGrade,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (lastMark != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ScoreBadge(
                        mark = lastMark,
                        size = ScoreBadgeSize.LARGE,
                    )
                    Column {
                        Text(
                            text = lastMark.subject,
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (lastMark.isoDate.isNotBlank()) {
                            Text(
                                text = strings.formatShortDate(lastMark.isoDate),
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = strings.noGradesYetShort,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Port of `renderRecent(m)` (`#home-marks` / `.card.marks-card`) from `web/public/app.js`.
 */
@Composable
private fun HomeRecentMarksCard(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    onNavigateToMarks: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val rows = snapshot.recentMarks.take(5)

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainerLow,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_recent_marks_card"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.recentGrades,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onNavigateToMarks,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("home_all_marks_btn"),
                ) {
                    Text(
                        text = strings.allMarks,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (rows.isEmpty()) {
                EmptyStateCard(
                    title = strings.noGradesTitle,
                    subtitle = strings.noGradesSub,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { mark ->
                        RecentMarkRow(
                            mark = mark,
                            strings = strings,
                            onClick = onNavigateToMarks,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentMarkRow(
    mark: MarkItem,
    strings: AppStrings,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScoreBadge(
                mark = mark,
                size = ScoreBadgeSize.MEDIUM,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mark.subject,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colorScheme.onSurface,
                )
                if (mark.isoDate.isNotBlank()) {
                    Text(
                        text = strings.formatLongDate(mark.isoDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Port of `renderHomeworkPreview(m)` (`#home-homework` / `.card.homework-card`) from `web/public/app.js`.
 */
@Composable
private fun HomeHomeworkPreviewCard(
    snapshot: StudentAgendaSnapshot,
    strings: AppStrings,
    onNavigateToHomework: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val items = snapshot.homeworkItems.take(4)

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainerLow,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_homework_card"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.homeworkPreview,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onNavigateToHomework,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("home_all_homework_btn"),
                ) {
                    Text(
                        text = strings.allHomework,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (items.isEmpty()) {
                EmptyStateCard(
                    title = strings.noHomeworkTitle,
                    subtitle = strings.noHomeworkSub,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { task ->
                        HomeworkTaskRow(
                            task = task,
                            referenceIsoDate = snapshot.referenceIsoDate,
                            strings = strings,
                            compact = true,
                            onClick = onNavigateToHomework,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Port of `taskRow(task, today, compact)` in `web/public/app.js:1296-1308`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeworkTaskRow(
    task: HomeworkItem,
    referenceIsoDate: String,
    strings: AppStrings,
    compact: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val dueDate = runCatching { LocalDate.parse(task.dueIsoDate) }.getOrNull()
    val refDate = runCatching { LocalDate.parse(referenceIsoDate) }.getOrNull()
    val diffDays = if (dueDate != null && refDate != null) {
        ChronoUnit.DAYS.between(refDate, dueDate).toInt()
    } else {
        null
    }

    val dueTone = when {
        diffDays == null -> ChipTone.NEUTRAL
        diffDays < 0 -> ChipTone.NEUTRAL
        diffDays <= 1 -> ChipTone.WARNING
        else -> ChipTone.INFO
    }
    val dueLabel = if (task.dueIsoDate.isNotBlank()) {
        "${strings.formatRelativeDay(task.dueIsoDate, referenceIsoDate)}, ${strings.formatShortDate(task.dueIsoDate)}"
    } else {
        ""
    }

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = task.subject.ifBlank { strings.discipline },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = colorScheme.onSurface,
                )
                if (dueLabel.isNotBlank()) {
                    StatusChip(
                        text = dueLabel,
                        tone = dueTone,
                    )
                }
            }
            if (task.description.isNotBlank()) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 2 else Int.MAX_VALUE,
                    overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        }
    }
}
