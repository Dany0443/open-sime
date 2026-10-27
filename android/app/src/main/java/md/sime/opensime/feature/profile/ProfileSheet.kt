package md.sime.opensime.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import md.sime.opensime.core.designsystem.ChipTone
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.designsystem.StatusChip
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.core.i18n.AppStrings
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.core.model.TeacherItem

/**
 * Port of `renderProfile()` (`#view-profile`: `#profile-card` + `#teacher-grid`)
 * from `web/public/app.js` and `web/public/style.css`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    snapshot: StudentAgendaSnapshot,
    studentToneIndex: Int = 0,
    modifier: Modifier = Modifier,
    hasSavedCredentials: Boolean = false,
    onSignInClick: (() -> Unit)? = null,
    onSignOutClick: (() -> Unit)? = null,
    onForgetCredentialsClick: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val density = LocalDensity.current
    val student = snapshot.student

    val infoRows = buildList {
        if (student.schoolName.isNotBlank()) add(strings.schoolLabel to student.schoolName)
        if (student.classMasterName.isNotBlank()) add(strings.classMasterLabel to student.classMasterName)
        if (student.academicYear.isNotBlank()) add(strings.studyYearLabel to student.academicYear)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useTwoColumnTeachers = maxWidth >= 640.dp && density.fontScale < 1.35f

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("profile_screen_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // `.profile-card`
            item(key = "profile_hero") {
                Surface(
                    shape = RoundedCornerShape(36.dp),
                    color = colorScheme.primaryContainer,
                    contentColor = colorScheme.onPrimaryContainer,
                    border = if (highContrast) BorderStroke(2.dp, colorScheme.onSurface) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_hero_card"),
                ) {
                    FlowRow(
                        modifier = Modifier.padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CookieAvatar(
                            fullName = student.fullName,
                            toneIndex = studentToneIndex,
                            size = 88.dp,
                            fontSize = 26.sp,
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = student.fullName,
                                style = MaterialTheme.typography.headlineMediumEmphasized,
                                color = colorScheme.onPrimaryContainer,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (student.className.isNotBlank()) {
                                    StatusChip(
                                        text = "${strings.classPrefix}${student.className}",
                                        tone = ChipTone.INFO,
                                        icon = OpenSimeIcons.School,
                                    )
                                }
                                if (!student.enrollmentStatus.isNullOrBlank()) {
                                    StatusChip(
                                        text = student.enrollmentStatus,
                                        tone = ChipTone.SUCCESS,
                                        icon = OpenSimeIcons.CheckCircle,
                                    )
                                }
                            }
                            val formattedBirthDate = formatBirthDate(student.birthDate, strings)
                            if (formattedBirthDate != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.testTag("profile_birth_date"),
                                ) {
                                    Text(
                                        text = "${strings.birthDateLabel}:",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                    )
                                    Text(
                                        text = formattedBirthDate,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // `.info-grid`
            if (infoRows.isNotEmpty()) {
                item(key = "profile_info_grid") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        infoRows.forEach { (label, value) ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = colorScheme.surfaceContainerLow,
                                border = if (highContrast) BorderStroke(1.5.dp, colorScheme.onSurface) else null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Saved credentials management
            if (hasSavedCredentials && onForgetCredentialsClick != null) {
                item(key = "profile_saved_credentials_card") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = colorScheme.surfaceContainerLow,
                        border = if (highContrast) BorderStroke(1.5.dp, colorScheme.onSurface) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_saved_credentials_card"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = OpenSimeIcons.Lock,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column {
                                    Text(
                                        text = strings.rememberMeLabel,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.rememberMeDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onForgetCredentialsClick,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .sizeIn(minHeight = 48.dp)
                                    .testTag("profile_action_forget_credentials"),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = OpenSimeIcons.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(strings.forgetSavedLogin)
                                }
                            }
                        }
                    }
                }
            }

            // `#teacher-grid` heading
            item(key = "teachers_heading") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.teachersSection,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (snapshot.teachers.isEmpty()) {
                item(key = "teachers_empty") {
                    EmptyStateCard(
                        title = strings.noTeachersTitle,
                        subtitle = strings.noTeachersSub,
                    )
                }
            } else if (useTwoColumnTeachers) {
                val pairs = snapshot.teachers.chunked(2)
                items(pairs.size, key = { "teacher_row_$it" }) { rowIdx ->
                    val rowTeachers = pairs[rowIdx]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowTeachers.forEachIndexed { colIdx, teacher ->
                            TeacherCard(
                                teacher = teacher,
                                toneIndex = rowIdx * 2 + colIdx + 1,
                                classMasterLabel = strings.classMasterBadge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowTeachers.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(snapshot.teachers.size, key = { "teacher_${snapshot.teachers[it].name}" }) { idx ->
                    TeacherCard(
                        teacher = snapshot.teachers[idx],
                        toneIndex = idx + 1,
                        classMasterLabel = strings.classMasterBadge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeacherCard(
    teacher: TeacherItem,
    toneIndex: Int,
    classMasterLabel: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    Surface(
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainerLow,
        border = if (highContrast) BorderStroke(1.5.dp, colorScheme.onSurface) else null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CookieAvatar(
                    fullName = teacher.name,
                    toneIndex = toneIndex,
                    size = 48.dp,
                    fontSize = 15.sp,
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = teacher.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colorScheme.onSurface,
                    )
                    if (teacher.isClassMaster) {
                        StatusChip(
                            text = classMasterLabel,
                            tone = ChipTone.INFO,
                        )
                    }
                }
            }

            if (teacher.subjects.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    teacher.subjects.forEach { subj ->
                        StatusChip(
                            text = subj,
                            tone = ChipTone.NEUTRAL,
                        )
                    }
                }
            }
        }
    }
}

internal fun formatBirthDate(rawDate: String?, strings: AppStrings): String? {
    if (rawDate == null) return null
    val trimmed = rawDate.trim()
    if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed.equals("undefined", ignoreCase = true)) {
        return null
    }
    // Expected portal format: DD.MM.YYYY (e.g., "15.05.2012" or "01.01.2010")
    val parts = trimmed.split('.')
    if (parts.size == 3) {
        val day = parts[0].toIntOrNull()
        val month = parts[1].toIntOrNull()
        val year = parts[2].toIntOrNull()
        if (day != null && month != null && year != null && day in 1..31 && month in 1..12 && year in 1900..2100) {
            val monthName = strings.monthsLong.getOrNull(month - 1)
            if (monthName != null) {
                return when (strings.language) {
                    AppLanguage.RO -> "$day $monthName $year"
                    AppLanguage.RU -> "$day $monthName $year г."
                    AppLanguage.EN -> "$monthName $day, $year"
                }
            }
        }
    }
    // Safe fallback if clean non-empty string without raw placeholder
    return if (trimmed.length in 4..30 && !trimmed.contains("?")) trimmed else null
}
