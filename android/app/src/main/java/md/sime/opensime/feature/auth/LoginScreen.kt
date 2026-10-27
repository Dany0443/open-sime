package md.sime.opensime.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.i18n.errorMessageFor
import md.sime.opensime.core.model.AccountRole

/**
 * Dedicated full-screen native login screen for Open SIME.
 *
 * Requirements enforced:
 * 1. Full-screen experience (replaces modal dialog).
 * 2. Back navigation returns to the landing screen without clearing credentials or logging out.
 * 3. Account type selector defaults to PARENT for new manual logins and makes the distinction obvious.
 * 4. Remember me is strictly optional and off by default.
 * 5. Supports light/dark themes, accessibility, password autofill, and 48dp touch targets.
 */
@Composable
fun LoginScreen(
    isAuthenticating: Boolean,
    authError: Throwable?,
    onSignIn: (username: String, password: String, role: AccountRole, rememberMe: Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val scrollState = rememberScrollState()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // Requirement: Default to Parent for any new manual login attempt
    var selectedRole by rememberSaveable { mutableStateOf(AccountRole.PARENT) }
    var rememberMe by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var localValidationMessage by remember { mutableStateOf<String?>(null) }

    // Intercept Android hardware/gesture back without discarding saved credentials
    BackHandler(enabled = !isAuthenticating) {
        onBack()
    }

    fun submit() {
        val trimmedUser = username.trim()
        if (trimmedUser.isBlank() || password.isBlank()) {
            localValidationMessage = strings.loginRequired
            return
        }
        localValidationMessage = null
        onSignIn(trimmedUser, password, selectedRole, rememberMe)
    }

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
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Top navigation bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("login_back_btn"),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.ChevronLeft,
                            contentDescription = strings.actionClose,
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("login_settings_btn"),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.Settings,
                            contentDescription = strings.settingsTitle,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // Brand Hero
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CookieAvatar(
                        fullName = "Open SIME",
                        size = 56.dp,
                        fontSize = 20.sp,
                        activeHighlight = true,
                    )
                    Text(
                        text = strings.authTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = strings.authLead,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Account Type Selector: Unmistakable two-option selector
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.studentLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountRoleOptionCard(
                            role = AccountRole.PARENT,
                            title = strings.roleParent,
                            icon = OpenSimeIcons.Profile,
                            selected = selectedRole == AccountRole.PARENT,
                            onSelect = { if (!isAuthenticating) selectedRole = AccountRole.PARENT },
                            highContrast = highContrast,
                            modifier = Modifier.weight(1f),
                        )

                        AccountRoleOptionCard(
                            role = AccountRole.STUDENT,
                            title = strings.roleStudent,
                            icon = OpenSimeIcons.School,
                            selected = selectedRole == AccountRole.STUDENT,
                            onSelect = { if (!isAuthenticating) selectedRole = AccountRole.STUDENT },
                            highContrast = highContrast,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Username input field
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        localValidationMessage = null
                    },
                    label = { Text(strings.usernameLabel) },
                    singleLine = true,
                    enabled = !isAuthenticating,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Username }
                        .testTag("auth_username_input"),
                )

                // Password input field
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localValidationMessage = null
                    },
                    label = { Text(strings.passwordLabel) },
                    singleLine = true,
                    enabled = !isAuthenticating,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("auth_password_toggle"),
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    OpenSimeIcons.VisibilityOff
                                } else {
                                    OpenSimeIcons.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    strings.hidePassword
                                } else {
                                    strings.showPassword
                                },
                                tint = colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.Password }
                        .testTag("auth_password_input"),
                )

                // Remember me checkbox (strictly off-by-default)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Checkbox,
                            enabled = !isAuthenticating,
                            onClick = { rememberMe = !rememberMe },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = rememberMe,
                        onCheckedChange = { if (!isAuthenticating) rememberMe = it },
                        enabled = !isAuthenticating,
                        modifier = Modifier.testTag("auth_remember_me_checkbox"),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = strings.rememberMeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface,
                        )
                        Text(
                            text = strings.rememberMeDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Error banner (validation or server/network error)
                val activeError = localValidationMessage ?: authError?.let { strings.errorMessageFor(it) }
                if (activeError != null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer,
                        border = if (highContrast) BorderStroke(1.5.dp, colorScheme.error) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_error_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = OpenSimeIcons.Error,
                                contentDescription = null,
                                tint = colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = activeError,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Sign-in CTA button
                Button(
                    onClick = { submit() },
                    enabled = !isAuthenticating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 52.dp)
                        .testTag("auth_submit_btn"),
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = colorScheme.onPrimary,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(20.dp),
                        )
                        Text(
                            text = strings.loginChecking,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(
                            text = strings.loginSubmit,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Highly visible, accessible card representing an account role option (Parent or Student).
 * Relies on shape, icon, border, and selection indicator in addition to color.
 */
@Composable
private fun AccountRoleOptionCard(
    role: AccountRole,
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    highContrast: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val testTag = "auth_role_${role.name}"

    val containerColor = if (selected) {
        colorScheme.primaryContainer
    } else {
        colorScheme.surfaceContainerLow
    }
    val contentColor = if (selected) {
        colorScheme.onPrimaryContainer
    } else {
        colorScheme.onSurface
    }
    val borderColor = when {
        selected -> colorScheme.primary
        highContrast -> colorScheme.outline
        else -> colorScheme.outlineVariant
    }
    val borderWidth = if (selected || highContrast) 2.dp else 1.dp

    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(borderWidth, borderColor),
        modifier = modifier
            .sizeIn(minHeight = 56.dp)
            .clickable(
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = OpenSimeIcons.CheckCircle,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

