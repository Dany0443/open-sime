@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)

package md.sime.opensime.feature.subjects

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import md.sime.opensime.core.designsystem.BandChip
import md.sime.opensime.core.designsystem.CookieDecorationShape
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.designsystem.ScoreBadge
import md.sime.opensime.core.designsystem.ScoreBadgeSize
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.MarkItem

/**
 * Subject details sheet. Cards, top to bottom:
 *  1. Average card (same look as the Home "estimated average" card)
 *  2. Grades card (same wrapped badge + date layout as the Grades screen)
 *  3. Absences card (only when this subject has absences)
 *  4. Homework link (only when this subject has homework; routes to the Homework tab)
 *
 * [onOpenHomework] is optional: without it the homework link is not shown.
 */
@Composable
fun SubjectDetailsSheet(
    details: SubjectDetailsData,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHomework: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val animate = rememberAnimationsEnabled()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val absenceRecords = details.absenceRecords.orEmpty()
    val showHomework = onOpenHomework != null && details.homeworkAvailable && details.homework.isNotEmpty()
    val hasGrades = details.semesterOneGrades.isNotEmpty() ||
        details.semesterTwoGrades.isNotEmpty() ||
        details.unidentifiedPeriodGrades.isNotEmpty()
    val average = details.provisionalAverage
    val qualifierAverage = details.provisionalQualifierAverage

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag("subject_details_sheet"),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(details = details, strings = strings, onDismiss = onDismiss)

            var order = 0
            if (average != null || qualifierAverage != null || hasGrades) {
                Entrance(order++, animate) {
                    AverageCard(
                        average = average?.value,
                        qualifierAverage = qualifierAverage,
                        strings = strings,
                        animate = animate,
                    )
                }
            }
            Entrance(order++, animate) {
                GradesCard(details = details, strings = strings, animate = animate)
            }
            if (absenceRecords.isNotEmpty()) {
                Entrance(order++, animate) {
                    AbsencesCard(records = absenceRecords, strings = strings, animate = animate)
                }
            }
            if (showHomework) {
                Entrance(order++, animate) {
                    HomeworkLinkCard(
                        count = details.homework.size,
                        strings = strings,
                        onClick = { onOpenHomework?.invoke() },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

@Composable
private fun SheetHeader(
    details: SubjectDetailsData,
    strings: AppStrings,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = details.subject,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = colors.onSurface,
                    modifier = Modifier
                        .testTag("subject_details_name")
                        .semantics { heading() },
                )
                details.teacherName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("subject_details_close")) {
                Icon(
                    OpenSimeIcons.Close,
                    contentDescription = strings.actionClose,
                    modifier = Modifier.semantics { contentDescription = strings.actionClose },
                )
            }
        }
        if (details.stale) {
            Surface(
                color = semantic.warningContainer,
                contentColor = semantic.onWarningContainer,
                shape = MaterialTheme.shapes.medium,
                border = contrastBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = strings.subjectSavedDataStale,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 1. Average card (mirrors GpaMetricCard in HomeScreen.kt)
// ---------------------------------------------------------------------------------------------

@Composable
private fun AverageCard(
    average: Double?,
    qualifierAverage: md.sime.opensime.core.model.QualifierCode?,
    strings: AppStrings,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val progress = rememberProgress(animate, 250L, MaterialTheme.motionScheme.slowEffectsSpec())

    Surface(
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomStart = 36.dp, bottomEnd = 12.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        border = contrastBorder(),
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 152.dp)
            .testTag("subject_average_card"),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CookieDecorationShape(
                tint = colors.primary.copy(alpha = 0.16f),
                modifier = Modifier
                    .size(160.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 36.dp, y = (-36).dp)
                    .graphicsLayer { rotationZ = (1f - progress.value.coerceIn(0f, 1f)) * -40f },
            )
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (average == null && qualifierAverage == null) {
                        strings.subjectAverageUnavailable
                    } else {
                        strings.subjectAverageEstimate
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = qualifierAverage?.let(strings::qualifierFullWord) ?: strings.formatAverage(average),
                    style = MaterialTheme.typography.displaySmallEmphasized.copy(
                        fontSize = 46.sp,
                        lineHeight = 50.sp,
                    ),
                )
                if (average != null) BandChip(averageValue = average)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 2. Grades card (mirrors SubjectMarksCard / MarkGroupSection in MarksScreen.kt)
// ---------------------------------------------------------------------------------------------

@Composable
private fun GradesCard(
    details: SubjectDetailsData,
    strings: AppStrings,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val sem1 = details.semesterOneGrades
    val sem2 = details.semesterTwoGrades
    val unknown = details.unidentifiedPeriodGrades

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerLow,
        border = contrastBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (sem1.isEmpty() && sem2.isEmpty() && unknown.isEmpty()) {
                Text(
                    text = strings.noMarksYet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("subject_details_semester1_no_marks"),
                )
            } else {
                if (sem1.isNotEmpty()) {
                    MarkGroup(strings.semester1, sem1, Modifier, strings, animate)
                }
                if (sem2.isNotEmpty()) {
                    MarkGroup(
                        strings.semester2,
                        sem2,
                        Modifier.testTag("subject_details_semester2"),
                        strings,
                        animate,
                    )
                }
                if (unknown.isNotEmpty()) {
                    MarkGroup(strings.subjectUnknownPeriod, unknown, Modifier, strings, animate)
                }
            }
        }
    }
}

/**
 * Same wrapping layout as the Grades screen: badge with its date underneath.
 * Marks that carry an assessment topic are tappable (date is underlined); tapping shows the
 * topic right below the group.
 */
@Composable
private fun MarkGroup(
    title: String,
    marks: List<MarkItem>,
    titleModifier: Modifier,
    strings: AppStrings,
    animate: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    var selected by rememberSaveable(title, marks.size) { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.onSurfaceVariant,
            modifier = titleModifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            marks.forEachIndexed { index, mark ->
                MarkChip(
                    mark = mark,
                    index = index,
                    isSelected = index == selected,
                    strings = strings,
                    animate = animate,
                    onClick = { selected = if (selected == index) -1 else index },
                )
            }
        }

        val detail: @Composable (Int) -> Unit = { index ->
            val mark = marks.getOrNull(index)
            val topic = mark?.topic?.takeIf { it.isNotBlank() }
            if (mark != null && topic != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surfaceContainerHigh,
                    border = contrastBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (mark.isoDate.isNotBlank()) {
                            Text(
                                text = strings.formatLongDate(mark.isoDate),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.primary,
                            )
                        }
                        Text(text = topic, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (animate) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) +
                        scaleIn(motion.defaultSpatialSpec(), initialScale = 0.92f)) togetherWith
                        fadeOut(motion.fastEffectsSpec()) using
                        SizeTransform { _, _ -> motion.defaultSpatialSpec() }
                },
                label = "mark_detail",
            ) { index -> detail(index) }
        } else {
            detail(selected)
        }
    }
}

@Composable
private fun MarkChip(
    mark: MarkItem,
    index: Int,
    isSelected: Boolean,
    strings: AppStrings,
    animate: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val hasTopic = !mark.topic.isNullOrBlank()
    val pop = rememberProgress(animate, 300L + minOf(index, 14) * 35L, motion.fastSpatialSpec())
    val selectScale by animateFloatAsState(
        targetValue = if (isSelected) 1.14f else 1f,
        animationSpec = if (animate) motion.fastSpatialSpec() else snap(),
        label = "mark_select",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .graphicsLayer {
                val p = pop.value
                val s = (0.6f + 0.4f * p) * selectScale
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(16.dp))
            .semantics { this.selected = isSelected }
            .clickable(
                enabled = hasTopic,
                onClickLabel = strings.subjectAssessmentDetails,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(2.dp),
    ) {
        ScoreBadge(mark = mark, size = ScoreBadgeSize.MEDIUM)
        if (mark.isoDate.isNotBlank()) {
            Text(
                text = strings.formatShortDate(mark.isoDate),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (hasTopic) TextDecoration.Underline else TextDecoration.None,
                ),
                color = if (isSelected) colors.primary else colors.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 3. Absences card
// ---------------------------------------------------------------------------------------------

private data class AbsenceGroup(
    val label: String,
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val items: List<AbsenceItem>,
)

/** Green = excused, red = unexcused. Every colour also has a text label. */
@Composable
private fun AbsencesCard(
    records: List<AbsenceItem>,
    strings: AppStrings,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current
    val motion = MaterialTheme.motionScheme
    val sorted = remember(records) { records.sortedBy { it.isoDate } }
    val groups = listOf(
        AbsenceGroup(
            strings.subjectAbsExcused, semantic.success, semantic.successContainer,
            semantic.onSuccessContainer, sorted.filter { it.status == AbsenceStatus.EXCUSED },
        ),
        AbsenceGroup(
            strings.subjectAbsUnexcused, colors.error, colors.errorContainer,
            colors.onErrorContainer, sorted.filter { it.status == AbsenceStatus.UNEXCUSED },
        ),
        AbsenceGroup(
            strings.subjectUnknownStatus, colors.outline, colors.surfaceContainerHighest,
            colors.onSurfaceVariant, sorted.filter { it.status == AbsenceStatus.UNKNOWN },
        ),
    ).filter { it.items.isNotEmpty() }

    val progress = rememberProgress(animate, 350L, motion.slowEffectsSpec())
    val shownTotal = (records.size * progress.value.coerceIn(0f, 1f)).roundToInt()

    Surface(
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 36.dp, bottomEnd = 36.dp, bottomStart = 36.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        border = contrastBorder(),
        modifier = modifier
            .fillMaxWidth()
            .testTag("subject_absences_card"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = strings.subjectAbsencesLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = shownTotal.toString(),
                    style = MaterialTheme.typography.displaySmallEmphasized.copy(
                        fontSize = 46.sp,
                        lineHeight = 50.sp,
                    ),
                )
            }

            // Proportion bar (decorative; the counts below are the accessible info).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .clearAndSetSemantics {},
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value.coerceIn(0f, 1f)),
                ) {
                    groups.forEach { group ->
                        Box(
                            modifier = Modifier
                                .weight(group.items.size.toFloat())
                                .fillMaxHeight()
                                .background(group.accent),
                        )
                    }
                }
            }

            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(group.accent))
                        Text(
                            text = "${group.label} · ${group.items.size}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        group.items.forEachIndexed { index, record ->
                            AbsenceDateChip(record, group, index, strings, animate)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AbsenceDateChip(
    record: AbsenceItem,
    group: AbsenceGroup,
    index: Int,
    strings: AppStrings,
    animate: Boolean,
) {
    val pop = rememberProgress(animate, 450L + minOf(index, 14) * 35L, MaterialTheme.motionScheme.fastSpatialSpec())
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = group.container,
        contentColor = group.onContainer,
        border = if (highContrast) BorderStroke(1.5.dp, group.onContainer) else null,
        modifier = Modifier.graphicsLayer {
            val p = pop.value
            val s = 0.6f + 0.4f * p
            scaleX = s
            scaleY = s
            alpha = p.coerceIn(0f, 1f)
        },
    ) {
        Text(
            text = strings.formatShortDate(record.isoDate),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 4. Homework link (routes to the Homework tab)
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeworkLinkCard(
    count: Int,
    strings: AppStrings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 12.dp, bottomEnd = 36.dp, bottomStart = 36.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        border = contrastBorder(),
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 72.dp)
            .testTag("subject_homework_link"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${strings.subjectHomeworkLabel} · $count",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(text = strings.allHomework, style = MaterialTheme.typography.bodySmall)
            }
            ArrowIcon(tint = colors.onSecondaryContainer)
        }
    }
}

@Composable
private fun ArrowIcon(tint: Color) {
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.38f, h * 0.28f)
            lineTo(w * 0.62f, h * 0.5f)
            lineTo(w * 0.38f, h * 0.72f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Motion + shared helpers
// ---------------------------------------------------------------------------------------------

/** Staggered card entrance: fade, rise and a springy scale. */
@Composable
private fun Entrance(index: Int, animate: Boolean, content: @Composable () -> Unit) {
    val progress = rememberProgress(animate, index * 90L, MaterialTheme.motionScheme.slowSpatialSpec())
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 28.dp.toPx()
            val s = 0.92f + 0.08f * p
            scaleX = s
            scaleY = s
        },
    ) { content() }
}

/** 0 -> 1 over [spec] after [delayMs]; jumps straight to 1 when animations are off. */
@Composable
private fun rememberProgress(
    animate: Boolean,
    delayMs: Long,
    spec: FiniteAnimationSpec<Float>,
): State<Float> {
    val anim = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) {
            delay(delayMs)
            anim.animateTo(1f, spec)
        } else {
            anim.snapTo(1f)
        }
    }
    return anim.asState()
}

@Composable
private fun contrastBorder(): BorderStroke? =
    if (LocalOpenSimeSemanticColors.current.highContrast) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
    } else {
        null
    }

/** False when the system animator scale is 0 ("remove animations"). */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
}
