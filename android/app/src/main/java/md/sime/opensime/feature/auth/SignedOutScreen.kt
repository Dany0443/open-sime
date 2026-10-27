package md.sime.opensime.feature.auth

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Standard signed-out screen when no live session is active.
 *
 * Ensures the app does not substitute synthetic demo data on startup or after sign-out.
 */
@Composable
fun SignedOutScreen(
    onSignInClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onViewOnboarding: () -> Unit,
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
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Top utility row: Info/Onboarding & Settings
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onViewOnboarding,
                        modifier = Modifier.sizeIn(minHeight = 48.dp, minWidth = 48.dp),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(20.dp),
                        )
                        Text(
                            text = strings.onboardingBadge,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("signed_out_action_settings"),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.Settings,
                            contentDescription = strings.actionSettings,
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hero branding
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CookieAvatar(
                        fullName = "Open SIME",
                        size = 88.dp,
                        fontSize = 28.sp,
                        activeHighlight = true,
                    )

                    Text(
                        text = strings.signedOutHeroTitle,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = strings.signedOutHeroSubtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Primary sign-in call to action
                Button(
                    onClick = onSignInClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 52.dp)
                        .testTag("signed_out_action_signin"),
                ) {
                    Icon(
                        imageVector = OpenSimeIcons.Login,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(22.dp),
                    )
                    Text(
                        text = strings.signedOutActionSignIn,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Security & Privacy explanation card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceContainerLow,
                        contentColor = colorScheme.onSurface,
                    ),
                    border = BorderStroke(
                        width = if (highContrast) 2.dp else 1.dp,
                        color = if (highContrast) colorScheme.onSurface else colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.Lock,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(24.dp),
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = strings.signedOutNoticeTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onSurface,
                            )
                            Text(
                                text = strings.signedOutNoticeDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
