package md.sime.opensime.feature.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.i18n.LocalAppStrings

/**
 * Compact, single-screen onboarding for Open SIME.
 *
 * Explains 3 key points:
 * 1. School agenda, marks, and absences in one place.
 * 2. Direct connection to the official agenda.sime.md portal.
 * 3. Protected credentials with optional on-device saving.
 *
 * Sized to fit comfortably without scrolling on typical phone screens at 100% font scale,
 * with graceful scrolling support up to 200% font scaling.
 */
@Composable
fun OnboardingScreen(
    onContinueToSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top utility bar (Settings action)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("onboarding_action_settings"),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.Settings,
                            contentDescription = strings.settingsTitle,
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Main body: Brand hero + 3 value points
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Hero branding
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CookieAvatar(
                            fullName = "Open SIME",
                            size = 56.dp,
                            fontSize = 20.sp,
                            activeHighlight = true,
                        )

                        Text(
                            text = strings.onboardingTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )

                        Text(
                            text = strings.onboardingSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }

                    // 3 concise value proposition items
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = if (highContrast) 2.dp else 1.dp,
                            color = if (highContrast) colorScheme.onSurface else colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            OnboardingValueRow(
                                icon = OpenSimeIcons.Marks,
                                text = strings.onboardingPointAgenda,
                            )
                            OnboardingValueRow(
                                icon = OpenSimeIcons.School,
                                text = strings.onboardingPointPortal,
                            )
                            OnboardingValueRow(
                                icon = OpenSimeIcons.Lock,
                                text = strings.onboardingPointSecurity,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Call To Action
                Button(
                    onClick = onContinueToSignIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 52.dp)
                        .testTag("onboarding_action_continue"),
                ) {
                    Text(
                        text = strings.onboardingActionStart,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingValueRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = colorScheme.primaryContainer,
            contentColor = colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
