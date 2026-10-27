package md.sime.opensime.feature.absences

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.ChipTone
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.SegmentedOptionRow
import md.sime.opensime.core.designsystem.StatusChip
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.AbsenceItem
import md.sime.opensime.core.model.AbsenceStatus
import md.sime.opensime.core.model.StudentAgendaSnapshot

/**
 * Port of `#view-absences` (`buildAbsToggle`, `renderAbsences`, `absenceRow`) from `web/public/app.js`.
 */
@Composable
fun AbsencesScreen(
    snapshot: StudentAgendaSnapshot,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val density = LocalDensity.current
    var selectedSemester by rememberSaveable(snapshot.studentId.value) {
        mutableIntStateOf(snapshot.student.currentSemester.coerceIn(1, 2))
    }
    val expandedSubjects = remember(snapshot.studentId.value, selectedSemester) {
        mutableStateMapOf<String, Boolean>()
    }

    val semBlock = snapshot.absencesForSemester(selectedSemester)
    val bySubject = remember(semBlock.records) {
        semBlock.records
            .groupBy { it.subject.ifBlank { strings.discipline } }
            .entries
            .sortedByDescending { it.value.size }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val stackStats = maxWidth < 360.dp || density.fontScale >= 1.4f

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("absences_screen_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // `#abs-toggle`
            item(key = "abs_toggle") {
                SegmentedOptionRow(
                    options = listOf(
                        1 to strings.semester1,
                        2 to strings.semester2,
                    ),
                    selectedOption = selectedSemester,
                    onSelectOption = { selectedSemester = it },
                    testTagPrefix = "abs_sem_toggle",
                )
            }

            // `#abs-stats` (`.stat-row`)
            item(key = "abs_stats") {
                AbsenceStatRow(
                    total = semBlock.totalCount,
                    excused = semBlock.excusedCount,
                    unexcused = semBlock.unexcusedCount,
                    stackVertically = stackStats,
                    strings = strings,
                )
            }

            // `#abs-list`
            if (semBlock.records.isEmpty()) {
                item(key = "abs_empty") {
                    EmptyStateCard(
                        title = strings.noAbsencesTitle,
                        subtitle = strings.noAbsencesSubTemplate.replace("{sem}", selectedSemester.toString()),
                    )
                }
            } else {
                // Section 1: `Pe discipline` (`.abs-subject-grid`)
                if (bySubject.isNotEmpty()) {
                    item(key = "abs_by_subject_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = strings.absBySubject,
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )

                            bySubject.forEach { (subjectName, records) ->
                                val isExpanded = expandedSubjects[subjectName] ?: false
                                AbsenceSubjectCollapsibleCard(
                                    subjectName = subjectName,
                                    records = records,
                                    expanded = isExpanded,
                                    onToggleExpand = {
                                        expandedSubjects[subjectName] = !isExpanded
                                    },
                                    strings = strings,
                                )
                            }
                        }
                    }
                }

                // Section 2: `Cronologic`
                item(key = "abs_chronological_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.absChronological,
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )

                        semBlock.records.forEach { record ->
                            AbsenceRecordRow(
                                record = record,
                                strings = strings,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AbsenceStatRow(
    total: Int,
    excused: Int,
    unexcused: Int,
    stackVertically: Boolean,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current

    if (stackVertically) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile(
                label = strings.absTotal,
                value = total.toString(),
                bg = colorScheme.primaryContainer,
                fg = colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            StatTile(
                label = strings.absExcused,
                value = excused.toString(),
                bg = semantic.successContainer,
                fg = semantic.onSuccessContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            StatTile(
                label = strings.absUnexcused,
                value = unexcused.toString(),
                bg = colorScheme.errorContainer,
                fg = colorScheme.onErrorContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatTile(
                label = strings.absTotal,
                value = total.toString(),
                bg = colorScheme.primaryContainer,
                fg = colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(
                    topStart = 28.dp,
                    bottomStart = 28.dp,
                    topEnd = 12.dp,
                    bottomEnd = 12.dp,
                ),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = strings.absExcused,
                value = excused.toString(),
                bg = semantic.successContainer,
                fg = semantic.onSuccessContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = strings.absUnexcused,
                value = unexcused.toString(),
                bg = colorScheme.errorContainer,
                fg = colorScheme.onErrorContainer,
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    bottomStart = 12.dp,
                    topEnd = 28.dp,
                    bottomEnd = 28.dp,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    bg: Color,
    fg: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    Surface(
        shape = shape,
        color = bg,
        contentColor = fg,
        border = if (highContrast) BorderStroke(1.5.dp, fg) else null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMediumEmphasized.copy(
                    fontSize = 30.sp,
                ),
                color = fg,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbsenceSubjectCollapsibleCard(
    subjectName: String,
    records: List<AbsenceItem>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    strings: AppStrings,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val excCount = records.count { it.status == AbsenceStatus.EXCUSED }
    val unexcCount = records.count { it.status == AbsenceStatus.UNEXCUSED }

    Surface(
        onClick = onToggleExpand,
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainer,
        border = if (highContrast) BorderStroke(1.5.dp, colorScheme.onSurface) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("abs_subject_card_$subjectName"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = subjectName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusChip(
                        text = strings.formatAbsenceCount(records.size),
                        tone = ChipTone.NEUTRAL,
                    )
                    if (unexcCount > 0) {
                        StatusChip(
                            text = strings.absUnexcusedBadgeTemplate.replace("{count}", unexcCount.toString()),
                            tone = ChipTone.ERROR,
                        )
                    }
                    if (excCount > 0) {
                        StatusChip(
                            text = strings.absExcusedBadgeTemplate.replace("{count}", excCount.toString()),
                            tone = ChipTone.SUCCESS,
                        )
                    }
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    records.forEach { record ->
                        AbsenceRecordRow(
                            record = record,
                            strings = strings,
                            containerColor = colorScheme.surfaceContainerLow,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbsenceRecordRow(
    record: AbsenceItem,
    strings: AppStrings,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isExcused = record.status == AbsenceStatus.EXCUSED

    val metaParts = buildList {
        record.periodNumber?.let { add(strings.formatLessonPeriod(it)) }
        record.periodTime?.takeIf { it.isNotBlank() }?.let { add(it) }
        record.room?.takeIf { it.isNotBlank() }?.let { rm ->
            if (
                rm.startsWith("Sala", ignoreCase = true) ||
                rm.startsWith("Cab", ignoreCase = true) ||
                rm.startsWith("Lab", ignoreCase = true) ||
                rm.startsWith("Atelier", ignoreCase = true) ||
                rm.startsWith("Каб", ignoreCase = true) ||
                rm.startsWith("Room", ignoreCase = true)
            ) {
                add(rm)
            } else {
                add(strings.roomPrefixTemplate.replace("{room}", rm))
            }
        }
        record.teacherName?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // `.abs-date` (`56x56dp` date badge)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(56.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = strings.formatDayNumber(record.isoDate),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            lineHeight = 20.sp,
                        ),
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = strings.formatShortMonth(record.isoDate),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            // `.abs-body`
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = record.subject.ifBlank { strings.discipline },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    StatusChip(
                        text = if (isExcused) strings.absStatusExcused else strings.absStatusUnexcused,
                        tone = if (isExcused) ChipTone.SUCCESS else ChipTone.ERROR,
                    )
                }

                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }

                if (!record.note.isNullOrBlank()) {
                    Text(
                        text = record.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
