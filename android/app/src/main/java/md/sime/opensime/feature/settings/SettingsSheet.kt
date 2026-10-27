package md.sime.opensime.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import md.sime.opensime.core.designsystem.AppTextSize
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.designsystem.OpenSimeSettingsState
import md.sime.opensime.core.designsystem.SegmentedOptionRow
import md.sime.opensime.core.designsystem.ThemeMode
import md.sime.opensime.core.i18n.AppLanguage
import md.sime.opensime.core.i18n.LocalAppStrings

/**
 * Port of `#settings-dialog` from `web/index.html` and `web/public/style.css:3230-3318`.
 * Contains only genuinely implemented settings (Language, Text size, Theme, Accessibility toggles).
 */
@Composable
fun SettingsDialog(
    settings: OpenSimeSettingsState,
    onUpdateSettings: (OpenSimeSettingsState) -> Unit,
    onDismiss: () -> Unit,
    hasSavedCredentials: Boolean = false,
    savedLoginUsername: String? = null,
    onForgetSavedCredentials: (() -> Unit)? = null,
    isLoggedIn: Boolean = false,
    onLogout: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            contentColor = colorScheme.onSurface,
            border = BorderStroke(
                width = if (highContrast) 2.dp else 1.dp,
                color = if (highContrast) colorScheme.onSurface else colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .testTag("settings_dialog"),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // `.settings-dialog-header`
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = strings.settingsTitle,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("settings_close_btn"),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.Close,
                            contentDescription = strings.actionClose,
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = colorScheme.outlineVariant)

                // `.settings-dialog-body`
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Section 1: Language (`Limbă`)
                    SettingsSection(title = strings.settingsLangTitle) {
                        SegmentedOptionRow(
                            options = AppLanguage.entries.map { it to it.nativeLabel },
                            selectedOption = settings.language,
                            onSelectOption = { onUpdateSettings(settings.copy(language = it)) },
                            testTagPrefix = "setting_lang",
                        )
                    }

                    // Section 2: Text size (`Mărime text`)
                    SettingsSection(title = strings.settingsTextSizeTitle) {
                        SegmentedOptionRow(
                            options = listOf(
                                AppTextSize.S to strings.textSizeS,
                                AppTextSize.M to strings.textSizeM,
                                AppTextSize.L to strings.textSizeL,
                                AppTextSize.XL to strings.textSizeXl,
                            ),
                            selectedOption = settings.textSize,
                            onSelectOption = { onUpdateSettings(settings.copy(textSize = it)) },
                            testTagPrefix = "setting_text_size",
                        )
                    }

                    // Section 3: Theme (`Temă` - Light and Dark only)
                    SettingsSection(title = strings.settingsThemeTitle) {
                        SegmentedOptionRow(
                            options = listOf(
                                ThemeMode.LIGHT to strings.settingsThemeLight,
                                ThemeMode.DARK to strings.settingsThemeDark,
                            ),
                            selectedOption = if (settings.themeMode == ThemeMode.DARK) ThemeMode.DARK else ThemeMode.LIGHT,
                            onSelectOption = { onUpdateSettings(settings.copy(themeMode = it)) },
                            testTagPrefix = "setting_theme",
                        )
                    }

                    // Section 4: Accessibility options (`Opțiuni accesibilitate`)
                    SettingsSection(title = strings.settingsA11yTitle) {
                        Column {
                            SettingToggleRow(
                                title = strings.simpleModeTitle,
                                description = strings.simpleModeDesc,
                                checked = settings.simpleMode,
                                onCheckedChange = { onUpdateSettings(settings.copy(simpleMode = it)) },
                                testTag = "setting_simple_mode",
                            )
                            HorizontalDivider(color = colorScheme.surfaceContainerHighest)
                            SettingToggleRow(
                                title = strings.contrastTitle,
                                description = strings.contrastDesc,
                                checked = settings.highContrast,
                                onCheckedChange = { onUpdateSettings(settings.copy(highContrast = it)) },
                                testTag = "setting_contrast",
                            )
                            HorizontalDivider(color = colorScheme.surfaceContainerHighest)
                            SettingToggleRow(
                                title = strings.reduceMotionTitle,
                                description = strings.reduceMotionDesc,
                                checked = settings.reduceMotion,
                                onCheckedChange = { onUpdateSettings(settings.copy(reduceMotion = it)) },
                                testTag = "setting_reduce_motion",
                            )
                        }
                    }

                    // Section 5: Saved Login management (only if credentials exist in vault)
                    if (hasSavedCredentials && onForgetSavedCredentials != null) {
                        SettingsSection(title = strings.rememberMeLabel) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = colorScheme.surfaceContainerLow,
                                border = if (highContrast) BorderStroke(1.5.dp, colorScheme.onSurface) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_saved_credentials_card"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = OpenSimeIcons.Lock,
                                            contentDescription = null,
                                            tint = colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = if (!savedLoginUsername.isNullOrBlank()) {
                                                "${strings.studentLabel}: ${maskUsername(savedLoginUsername)}"
                                            } else {
                                                strings.rememberMeDesc
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.onSurface,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = onForgetSavedCredentials,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .sizeIn(minHeight = 48.dp)
                                            .testTag("settings_action_forget_credentials"),
                                    ) {
                                        Icon(
                                            imageVector = OpenSimeIcons.Delete,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .size(18.dp),
                                        )
                                        Text(strings.forgetSavedLogin)
                                    }
                                }
                            }
                        }
                    }

                    // Section 6: Log out (only when active session exists)
                    if (isLoggedIn && onLogout != null) {
                        SettingsSection(title = strings.logoutAction) {
                            OutlinedButton(
                                onClick = onLogout,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                                    .testTag("settings_action_logout"),
                            ) {
                                Icon(
                                    imageVector = OpenSimeIcons.Logout,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(18.dp),
                                )
                                Text(strings.logoutAction)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun maskUsername(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.length <= 2 -> "***"
        trimmed.length <= 4 -> "${trimmed.first()}***"
        else -> "${trimmed.take(2)}***${trimmed.takeLast(1)}"
    }
}
