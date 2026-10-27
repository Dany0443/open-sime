package md.sime.opensime.feature.marks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.ScoreBadge
import md.sime.opensime.core.designsystem.ScoreBadgeSize
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.SubjectMarksSummary
import md.sime.opensime.core.model.SubjectScale

/**
 * Port of `renderMarks()` (`#view-marks`) from `web/public/app.js` and `web/public/style.css`.
 * Displays discipline cards on compact/medium screens and a 3-column table on expanded screens (`>=840dp`),
 * without inventing per-subject averages not provided by the portal.
 */
@Composable
fun MarksScreen(
    snapshot: StudentAgendaSnapshot,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val showWideTable = maxWidth >= 840.dp && density.fontScale < 1.35f

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("marks_screen_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snapshot.subjectSummaries.isEmpty()) {
                item(key = "empty_disciplines") {
                    EmptyStateCard(
                        title = strings.noDisciplinesTitle,
                        subtitle = strings.noDisciplinesSub,
                    )
                }
            } else if (showWideTable) {
                item(key = "marks_table_surface") {
                    MarksTableSurface(
                        subjects = snapshot.subjectSummaries,
                        strings = strings,
                    )
                }
            } else {
                items(
                    items = snapshot.subjectSummaries,
                    key = { it.subject },
                ) { summary ->
                    SubjectMarksCard(
                        summary = summary,
                        strings = strings,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectMarksCard(
    summary: SubjectMarksSummary,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val sem1Marks = summary.marksForSemester(1)
    val sem2Marks = summary.marksForSemester(2)

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colorScheme.surfaceContainerLow,
        border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subject_card_${summary.subject}"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header (`.subject-title-row` + `.subject-teacher`)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = summary.subject,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = colorScheme.onSurface,
                    )
                    ScaleChip(scale = summary.scale, strings = strings)
                }
                if (summary.teacherName.isNotBlank()) {
                    Text(
                        text = summary.teacherName,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            // `.mark-groups`
            if (sem1Marks.isEmpty() && sem2Marks.isEmpty()) {
                Text(
                    text = strings.emptyMarkText(summary.scale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (sem1Marks.isNotEmpty()) {
                        MarkGroupSection(
                            title = strings.semester1,
                            marks = sem1Marks,
                            strings = strings,
                        )
                    }
                    if (sem2Marks.isNotEmpty()) {
                        MarkGroupSection(
                            title = strings.semester2,
                            marks = sem2Marks,
                            strings = strings,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkGroupSection(
    title: String,
    marks: List<MarkItem>,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            marks.forEach { mk ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ScoreBadge(
                        mark = mk,
                        size = ScoreBadgeSize.MEDIUM,
                    )
                    if (mk.isoDate.isNotBlank()) {
                        Text(
                            text = strings.formatShortDate(mk.isoDate),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                            ),
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarksTableSurface(
    subjects: List<SubjectMarksSummary>,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("marks_table_surface"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Table header (`thead`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = strings.discipline,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.2f),
                )
                Text(
                    text = strings.semester1Marks,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.4f),
                )
                Text(
                    text = strings.semester2Marks,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.4f),
                )
            }

            subjects.forEachIndexed { idx, subject ->
                HorizontalDivider(color = colorScheme.outlineVariant)
                val rowBg = if (idx % 2 == 1) colorScheme.surfaceContainer else colorScheme.surfaceContainerLow
                val sem1 = subject.marksForSemester(1)
                val sem2 = subject.marksForSemester(2)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1.2f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = subject.subject,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = colorScheme.onSurface,
                            )
                            ScaleChip(scale = subject.scale, strings = strings)
                        }
                        if (subject.teacherName.isNotBlank()) {
                            Text(
                                text = subject.teacherName,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    BoxMarksCell(
                        marks = sem1,
                        emptyText = strings.emptyMarkText(subject.scale),
                        modifier = Modifier.weight(1.4f),
                    )

                    BoxMarksCell(
                        marks = sem2,
                        emptyText = strings.emptyMarkText(subject.scale),
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxMarksCell(
    marks: List<MarkItem>,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    if (marks.isEmpty()) {
        Text(
            text = "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            marks.forEach { mk ->
                ScoreBadge(mark = mk, size = ScoreBadgeSize.MEDIUM)
            }
        }
    }
}

@Composable
private fun ScaleChip(
    scale: SubjectScale,
    strings: AppStrings,
) {
    val label = when (scale) {
        SubjectScale.QUALIFIER -> strings.scaleQualifiers
        SubjectScale.DESCRIPTOR -> strings.scaleDescriptors
        SubjectScale.NUMERIC -> return
    }
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = colorScheme.surfaceContainerHigh,
        contentColor = colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
