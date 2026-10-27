package md.sime.opensime.feature.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import md.sime.opensime.core.designsystem.EmptyStateCard
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.StudentAgendaSnapshot
import md.sime.opensime.feature.home.HomeworkTaskRow

/**
 * Port of `renderHomework()` (`#view-homework`) from `web/public/app.js` and `web/public/style.css`.
 */
@Composable
fun HomeworkScreen(
    snapshot: StudentAgendaSnapshot,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val groupedByDue = remember(snapshot.homeworkItems) {
        snapshot.homeworkItems
            .sortedBy { it.dueIsoDate.ifBlank { "9999-99-99" } }
            .groupBy { it.dueIsoDate }
            .entries
            .toList()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("homework_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (groupedByDue.isEmpty()) {
            item(key = "empty_homework") {
                EmptyStateCard(
                    title = strings.noHomeworkTitle,
                    subtitle = strings.noHomeworkListSub,
                )
            }
        } else {
            items(
                items = groupedByDue,
                key = { it.key.ifBlank { "no_due" } },
            ) { (dueIso, tasks) ->
                val groupTitle = if (dueIso.isNotBlank()) {
                    "${strings.formatRelativeDay(dueIso, snapshot.referenceIsoDate)}, ${strings.formatLongDate(dueIso)}"
                } else {
                    strings.noDueDate
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = groupTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    tasks.forEach { task ->
                        HomeworkTaskRow(
                            task = task,
                            referenceIsoDate = snapshot.referenceIsoDate,
                            strings = strings,
                            compact = false,
                        )
                    }
                }
            }
        }
    }
}
