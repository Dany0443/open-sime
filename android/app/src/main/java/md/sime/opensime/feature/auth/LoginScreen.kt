@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package md.sime.opensime.feature.auth

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
 * 4. Remember me is optional and enabled by default; password input stays in memory only.
 * 5. Supports light/dark themes, accessibility, password autofill, and 48dp touch targets.
 *
 * @param reduceMotion optional app-level reduced-motion preference. Motion is also
 * disabled when the system animator duration scale is 0.
 */
@Composable
fun LoginScreen(
    isAuthenticating: Boolean,
    authError: Throwable?,
    onSignIn: (username: String, password: String, role: AccountRole, rememberMe: Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showRememberMe: Boolean = true,
    reduceMotion: Boolean = false,
    /** When false, system Back is not intercepted (Login is the root route and Back should leave the app). */
    interceptSystemBack: Boolean = true,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast
    val scrollState = rememberScrollState()
    val animate = rememberMotionEnabled(reduceMotion)

    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Requirement: Default to Parent for any new manual login attempt
    var selectedRole by rememberSaveable { mutableStateOf(AccountRole.PARENT) }
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    var passwordVisible by remember { mutableStateOf(false) }
    var localValidationMessage by remember { mutableStateOf<String?>(null) }

    // Entrance plays once; rememberSaveable keeps it from replaying on rotation.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // Intercept Android hardware/gesture back without discarding saved credentials
    BackHandler(enabled = interceptSystemBack && !isAuthenticating) {
        onBack()
    }

    fun submit() {
        if (isAuthenticating) return
        val trimmedUser = username.trim()
        if (trimmedUser.isBlank() || password.isBlank()) {
            localValidationMessage = strings.loginRequired
            return
        }
        localValidationMessage = null
        onSignIn(trimmedUser, password, selectedRole, rememberMe)
    }

    val activeError = localValidationMessage ?: authError?.let { strings.errorMessageFor(it) }
    val validationActive = localValidationMessage != null

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
            ) {
                // Top navigation bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("login_back_btn"),
                    ) {
                        Icon(
                            imageVector = OpenSimeIcons.ChevronLeft,
                            contentDescription = strings.actionClose,
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

                Spacer(Modifier.height(16.dp))

                // Brand hero: the cookie settles into place, text follows.
                LoginHero(
                    title = strings.authTitle,
                    lead = strings.authLead,
                    entered = entered,
                    animate = animate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(0, entered, animate),
                )

                Spacer(Modifier.height(28.dp))

                // Account type selector: one track, one sliding indicator.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(1, entered, animate),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.studentLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    AccountRoleSelector(
                        selectedRole = selectedRole,
                        parentTitle = strings.roleParent,
                        studentTitle = strings.roleStudent,
                        enabled = !isAuthenticating,
                        highContrast = highContrast,
                        animate = animate,
                        onSelect = { selectedRole = it },
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Username input field
                LoginTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        localValidationMessage = null
                    },
                    label = strings.usernameLabel,
                    leadingIcon = OpenSimeIcons.Profile,
                    enabled = !isAuthenticating,
                    isError = validationActive && username.isBlank(),
                    highContrast = highContrast,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(2, entered, animate)
                        .semantics { contentType = ContentType.Username }
                        .testTag("auth_username_input"),
                )

                Spacer(Modifier.height(12.dp))

                // Password input field
                LoginTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localValidationMessage = null
                    },
                    label = strings.passwordLabel,
                    leadingIcon = OpenSimeIcons.Lock,
                    enabled = !isAuthenticating,
                    isError = validationActive && password.isBlank(),
                    highContrast = highContrast,
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
                        .entrance(3, entered, animate)
                        .semantics { contentType = ContentType.Password }
                        .testTag("auth_password_input"),
                )

                // Optional encrypted persistence, enabled by default. Label only, no helper text.
                if (showRememberMe) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .entrance(4, entered, animate)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                role = Role.Checkbox,
                                enabled = !isAuthenticating,
                                onClick = { rememberMe = !rememberMe },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = { if (!isAuthenticating) rememberMe = it },
                            enabled = !isAuthenticating,
                            modifier = Modifier.testTag("auth_remember_me_checkbox"),
                        )
                        Text(
                            text = strings.rememberMeLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Error banner (validation or server/network error); slides open and closed.
                AnimatedContent(
                    targetState = activeError,
                    transitionSpec = {
                        if (animate) {
                            (fadeIn(tween(200)) + expandVertically()) togetherWith
                                (fadeOut(tween(120)) + shrinkVertically())
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "errorBanner",
                ) { message ->
                    if (message != null) {
                        ErrorBanner(message = message, highContrast = highContrast, modifier = Modifier.padding(top = 12.dp))
                    } else {
                        Spacer(Modifier.height(0.dp))
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Sign-in CTA button
                Button(
                    onClick = { submit() },
                    enabled = !isAuthenticating,
                    colors = ButtonDefaults.buttonColors(
                        // Keep the brand colour while checking so the progress ring stays visible.
                        disabledContainerColor = colorScheme.primary,
                        disabledContentColor = colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 56.dp)
                        .entrance(5, entered, animate)
                        .testTag("auth_submit_btn"),
                ) {
                    AnimatedContent(
                        targetState = isAuthenticating,
                        transitionSpec = {
                            if (animate) fadeIn(tween(160)) togetherWith fadeOut(tween(90))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "submitContent",
                    ) { checking ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (checking) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = colorScheme.onPrimary,
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(20.dp),
                                )
                            }
                            Text(
                                text = if (checking) strings.loginChecking else strings.loginSubmit,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Left-aligned brand block. The avatar settles in with a small turn and scale. */
@Composable
private fun LoginHero(
    title: String,
    lead: String,
    entered: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val spec: FiniteAnimationSpec<Float> =
        if (animate) MaterialTheme.motionScheme.slowSpatialSpec() else snap()
    val settle by animateFloatAsState(
        targetValue = if (entered || !animate) 1f else 0f,
        animationSpec = spec,
        label = "avatarSettle",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Wrapped in a Box so only the existing CookieAvatar parameters are relied on.
        Box(
            modifier = Modifier.graphicsLayer {
                val scale = 0.75f + 0.25f * settle
                scaleX = scale
                scaleY = scale
                rotationZ = -24f * (1f - settle)
            },
        ) {
            CookieAvatar(
                fullName = "Open SIME",
                size = 72.dp,
                fontSize = 24.sp,
                activeHighlight = true,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = colorScheme.onSurface,
        )
        Text(
            text = lead,
            style = MaterialTheme.typography.bodyLarge,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two-option account selector in a single tonal track. One indicator slides between the
 * options, so the choice reads as a single control rather than two separate cards.
 */
@Composable
private fun AccountRoleSelector(
    selectedRole: AccountRole,
    parentTitle: String,
    studentTitle: String,
    enabled: Boolean,
    highContrast: Boolean,
    animate: Boolean,
    onSelect: (AccountRole) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val spec: FiniteAnimationSpec<Float> =
        if (animate) MaterialTheme.motionScheme.defaultSpatialSpec() else snap()
    val position by animateFloatAsState(
        targetValue = if (selectedRole == AccountRole.STUDENT) 1f else 0f,
        animationSpec = spec,
        label = "roleIndicator",
    )
    val trackShape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(colorScheme.surfaceContainerHigh)
            .then(
                if (highContrast) Modifier.border(2.dp, colorScheme.onSurface, trackShape) else Modifier,
            )
            .padding(4.dp),
    ) {
        // Indicator layer sized to the options row.
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = position * size.width }
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorScheme.primaryContainer)
                    .then(
                        if (highContrast) Modifier.border(2.dp, colorScheme.primary, RoundedCornerShape(24.dp)) else Modifier,
                    ),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            AccountRoleOption(
                role = AccountRole.PARENT,
                title = parentTitle,
                icon = OpenSimeIcons.Profile,
                selected = selectedRole == AccountRole.PARENT,
                enabled = enabled,
                onSelect = { onSelect(AccountRole.PARENT) },
                modifier = Modifier.weight(1f),
            )
            AccountRoleOption(
                role = AccountRole.STUDENT,
                title = studentTitle,
                icon = OpenSimeIcons.School,
                selected = selectedRole == AccountRole.STUDENT,
                enabled = enabled,
                onSelect = { onSelect(AccountRole.STUDENT) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AccountRoleOption(
    role: AccountRole,
    title: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val content by animateColorAsState(
        targetValue = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "roleContent",
    )
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(24.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .testTag("auth_role_${role.name}")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = content,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Tonal filled field with a leading icon and an animated focus ring instead of an outline. */
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector,
    enabled: Boolean,
    isError: Boolean,
    highContrast: Boolean,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val ringWidth by animateDpAsState(
        targetValue = when {
            isError || focused -> 2.dp
            highContrast -> 1.5.dp
            else -> 0.dp
        },
        animationSpec = tween(150),
        label = "fieldRing",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            isError -> colorScheme.error
            focused -> colorScheme.primary
            else -> colorScheme.outline
        },
        animationSpec = tween(150),
        label = "fieldRingColor",
    )
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (focused) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        },
        trailingIcon = trailingIcon,
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = colorScheme.surfaceContainerHigh,
            disabledContainerColor = colorScheme.surfaceContainerHigh,
            errorContainerColor = colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(ringWidth, ringColor, shape),
    )
}

@Composable
private fun ErrorBanner(message: String, highContrast: Boolean, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.errorContainer,
        contentColor = colorScheme.onErrorContainer,
        border = if (highContrast) BorderStroke(1.5.dp, colorScheme.error) else null,
        modifier = modifier
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
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colorScheme.onErrorContainer,
            )
        }
    }
}

/** Fade and rise into place, staggered by [index]. A no-op when motion is reduced. */
@Composable
private fun Modifier.entrance(index: Int, entered: Boolean, animate: Boolean): Modifier {
    if (!animate) return this
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 420, delayMillis = 60 * index, easing = EaseOutCubic),
        label = "entrance$index",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 24.dp.toPx()
    }
}

@Composable
private fun rememberMotionEnabled(reduceMotion: Boolean): Boolean {
    val context = LocalContext.current
    return remember(context, reduceMotion) {
        !reduceMotion && Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}