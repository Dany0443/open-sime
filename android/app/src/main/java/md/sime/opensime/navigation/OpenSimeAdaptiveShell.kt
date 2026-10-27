package md.sime.opensime.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.designsystem.ChipTone
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.CookieDecorationShape
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.designsystem.StatusChip
import md.sime.opensime.core.designsystem.extractInitials
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentProfile

@Composable
fun OpenSimeAdaptiveShell(
    currentDestination: TopLevelDestination,
    isProfileSelected: Boolean,
    onSelectDestination: (TopLevelDestination) -> Unit,
    onSelectProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    availableStudents: List<StudentProfile>,
    selectedStudentId: StudentId,
    onSelectStudent: (StudentId) -> Unit,
    simpleMode: Boolean,
    isSamplePreview: Boolean,
    modifier: Modifier = Modifier,
    onSignInClick: (() -> Unit)? = null,
    onSignOutClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val strings = LocalAppStrings.current
    val semantic = LocalOpenSimeSemanticColors.current
    val pageTitle = if (isProfileSelected) {
        strings.profilePageTitle(simpleMode)
    } else {
        currentDestination.label(strings, simpleMode)
    }

    val activeStudent = availableStudents.find { it.id == selectedStudentId }
    val subtitle = if (activeStudent != null && availableStudents.size > 1) {
        "${activeStudent.fullName} • ${strings.classPrefix}${activeStudent.className}"
    } else {
        ""
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layoutMode = WindowWidthLayout.fromWidthDp(maxWidth.value)

        when (layoutMode) {
            WindowWidthLayout.COMPACT -> {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        OpenSimeHeader(
                            title = pageTitle,
                            subtitle = subtitle,
                            isSamplePreview = isSamplePreview,
                            showCompactActions = true,
                            isProfileSelected = isProfileSelected,
                            activeStudentName = activeStudent?.fullName ?: strings.studentLabel,
                            profileLabel = strings.profilePageTitle(simpleMode),
                            settingsLabel = strings.actionSettings,
                            themeToggleLabel = if (semantic.isDark) strings.themeToLight else strings.themeToDark,
                            isDark = semantic.isDark,
                            onToggleTheme = onToggleTheme,
                            onSelectProfile = onSelectProfile,
                            onOpenSettings = onOpenSettings,
                            availableStudents = availableStudents,
                            selectedStudentId = selectedStudentId,
                            onSelectStudent = onSelectStudent,
                            onSignInClick = onSignInClick,
                        )
                    },
                    bottomBar = {
                        ShortNavigationBar(
                            modifier = Modifier.testTag("compact_bottom_nav"),
                        ) {
                            TopLevelDestination.entries.forEach { destination ->
                                val label = destination.label(strings, simpleMode)
                                val isSelected = !isProfileSelected && currentDestination == destination
                                ShortNavigationBarItem(
                                    selected = isSelected,
                                    onClick = { onSelectDestination(destination) },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_item_${destination.route}"),
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        content()
                    }
                }
            }

            WindowWidthLayout.MEDIUM, WindowWidthLayout.EXPANDED -> {
                val isExpandedDrawer = layoutMode == WindowWidthLayout.EXPANDED

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        OpenSimeSideNavigation(
                            isExpandedDrawer = isExpandedDrawer,
                            currentDestination = currentDestination,
                            isProfileSelected = isProfileSelected,
                            simpleMode = simpleMode,
                            isSamplePreview = isSamplePreview,
                            onSelectDestination = onSelectDestination,
                            onSelectProfile = onSelectProfile,
                            onOpenSettings = onOpenSettings,
                            onSignInClick = onSignInClick,
                            onSignOutClick = onSignOutClick,
                        )

                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            OpenSimeHeader(
                                title = pageTitle,
                                subtitle = subtitle,
                                isSamplePreview = isSamplePreview,
                                showCompactActions = false,
                                isProfileSelected = isProfileSelected,
                                activeStudentName = activeStudent?.fullName ?: strings.studentLabel,
                                profileLabel = strings.profilePageTitle(simpleMode),
                                settingsLabel = strings.actionSettings,
                                themeToggleLabel = if (semantic.isDark) strings.themeToLight else strings.themeToDark,
                                isDark = semantic.isDark,
                                onToggleTheme = onToggleTheme,
                                onSelectProfile = onSelectProfile,
                                onOpenSettings = onOpenSettings,
                                availableStudents = availableStudents,
                                selectedStudentId = selectedStudentId,
                                onSelectStudent = onSelectStudent,
                                applyTopStatusInset = false,
                                onSignInClick = onSignInClick,
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrollable side rail (`600..839dp`) and drawer (`>=840dp`) matching `aside.rail.drawer` in `web/public/style.css`.
 * Vertically scrollable so phone landscape screens (`360dp` height) never clip items.
 */
@Composable
private fun OpenSimeSideNavigation(
    isExpandedDrawer: Boolean,
    currentDestination: TopLevelDestination,
    isProfileSelected: Boolean,
    simpleMode: Boolean,
    isSamplePreview: Boolean,
    onSelectDestination: (TopLevelDestination) -> Unit,
    onSelectProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignInClick: (() -> Unit)? = null,
    onSignOutClick: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val navWidth = if (isExpandedDrawer) 264.dp else 84.dp

    Column(
        modifier = Modifier
            .width(navWidth)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isExpandedDrawer) 12.dp else 8.dp, vertical = 12.dp)
            .testTag("adaptive_wide_rail"),
        horizontalAlignment = if (isExpandedDrawer) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Brand header (`.rail-brand`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (isExpandedDrawer) 12.dp else 4.dp,
                    vertical = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isExpandedDrawer) Arrangement.spacedBy(12.dp) else Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                CookieDecorationShape(
                    tint = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = OpenSimeIcons.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isExpandedDrawer) {
                Column {
                    Text(
                        text = strings.appTitle,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (isSamplePreview) {
                        Text(
                            text = strings.samplePreviewBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        if (isExpandedDrawer) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // 5 Primary navigation items (`#rail-nav`)
        TopLevelDestination.entries.forEach { destination ->
            val label = destination.label(strings, simpleMode)
            val isSelected = !isProfileSelected && currentDestination == destination
            SideNavItem(
                label = label,
                icon = destination.icon,
                selected = isSelected,
                expanded = isExpandedDrawer,
                onClick = { onSelectDestination(destination) },
                modifier = Modifier.testTag("rail_item_${destination.route}"),
            )
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // Secondary rail-pinned destinations (`.drawer-pinned-bottom`: Profil & Setări)
        val profileLabel = strings.profileLabel(simpleMode)
        SideNavItem(
            label = profileLabel,
            icon = OpenSimeIcons.Profile,
            selected = isProfileSelected,
            expanded = isExpandedDrawer,
            onClick = onSelectProfile,
            modifier = Modifier.testTag("rail_action_profile"),
        )

        SideNavItem(
            label = strings.actionSettings,
            icon = OpenSimeIcons.Settings,
            selected = false,
            expanded = isExpandedDrawer,
            onClick = onOpenSettings,
            modifier = Modifier.testTag("rail_action_settings"),
        )

        if (isSamplePreview && onSignInClick != null) {
            SideNavItem(
                label = strings.connectToSime,
                icon = OpenSimeIcons.Login,
                selected = false,
                expanded = isExpandedDrawer,
                onClick = onSignInClick,
                modifier = Modifier.testTag("rail_action_signin"),
            )
        } else if (!isSamplePreview && onSignOutClick != null) {
            SideNavItem(
                label = strings.logoutAction,
                icon = OpenSimeIcons.Logout,
                selected = false,
                expanded = isExpandedDrawer,
                onClick = onSignOutClick,
                modifier = Modifier.testTag("rail_action_signout"),
            )
        }
    }
}

@Composable
private fun SideNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    if (expanded) {
        val bg = if (selected) colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
        val fg = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(999.dp),
            color = bg,
            contentColor = fg,
            border = if (highContrast && selected) BorderStroke(2.dp, colorScheme.onSurface) else null,
            modifier = modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 52.dp)
                .semantics {
                    this.role = Role.Tab
                    this.selected = selected
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        val pillBg = if (selected) colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
        val iconTint = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
        val textTint = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 52.dp)
                .semantics {
                    this.role = Role.Tab
                    this.selected = selected
                },
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = pillBg,
                    border = if (highContrast && selected) BorderStroke(1.5.dp, colorScheme.onSurface) else null,
                    modifier = Modifier.size(width = 52.dp, height = 30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 11.sp,
                    ),
                    color = textTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Header bar + student switcher strip matching `header.topbar` in `web/index.html` and `web/public/style.css`.
 */
@Composable
private fun OpenSimeHeader(
    title: String,
    subtitle: String,
    isSamplePreview: Boolean,
    showCompactActions: Boolean,
    isProfileSelected: Boolean,
    activeStudentName: String,
    profileLabel: String,
    settingsLabel: String,
    themeToggleLabel: String,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onSelectProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    availableStudents: List<StudentProfile>,
    selectedStudentId: StudentId,
    onSelectStudent: (StudentId) -> Unit,
    applyTopStatusInset: Boolean = true,
    onSignInClick: (() -> Unit)? = null,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    Surface(
        color = colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (applyTopStatusInset) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            // `.topbar-main`
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isSamplePreview) {
                            StatusChip(
                                text = strings.samplePreviewBadge,
                                tone = ChipTone.NEUTRAL,
                                modifier = Modifier
                                    .testTag("sample_preview_chip")
                                    .then(
                                        if (onSignInClick != null) {
                                            Modifier.clickable { onSignInClick() }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        }
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // `.topbar-actions`
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("topbar_action_theme"),
                    ) {
                        Icon(
                            imageVector = if (isDark) OpenSimeIcons.LightMode else OpenSimeIcons.DarkMode,
                            contentDescription = themeToggleLabel,
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }

                    if (showCompactActions) {
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("topbar_action_settings"),
                        ) {
                            Icon(
                                imageVector = OpenSimeIcons.Settings,
                                contentDescription = settingsLabel,
                                tint = colorScheme.onSurfaceVariant,
                            )
                        }

                        FilledTonalIconButton(
                            onClick = onSelectProfile,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isProfileSelected) {
                                    colorScheme.primary
                                } else {
                                    colorScheme.secondaryContainer
                                },
                                contentColor = if (isProfileSelected) {
                                    colorScheme.onPrimary
                                } else {
                                    colorScheme.onSecondaryContainer
                                },
                            ),
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("topbar_action_profile"),
                        ) {
                            Text(
                                text = extractInitials(activeStudentName),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                            )
                        }
                    }
                }
            }

            // `.student-strip` (`#student-list` — visible whenever multiple students are available)
            if (availableStudents.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 2.dp)
                        .testTag("student_strip"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    availableStudents.forEachIndexed { index, student ->
                        val isSelected = student.id == selectedStudentId
                        val chipShape = if (isSelected) RoundedCornerShape(16.dp) else RoundedCornerShape(999.dp)
                        val chipBg = if (isSelected) colorScheme.primary else colorScheme.surfaceContainerHigh
                        val chipFg = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant
                        val border = if (highContrast) {
                            BorderStroke(2.dp, if (isSelected) colorScheme.onSurface else chipFg)
                        } else {
                            null
                        }

                        Surface(
                            onClick = { onSelectStudent(student.id) },
                            shape = chipShape,
                            color = chipBg,
                            contentColor = chipFg,
                            border = border,
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .testTag("student_switch_${student.id.value}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CookieAvatar(
                                    fullName = student.fullName,
                                    toneIndex = index,
                                    size = 38.dp,
                                    fontSize = 13.sp,
                                    activeHighlight = isSelected,
                                )
                                Column {
                                    Text(
                                        text = student.fullName,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = chipFg,
                                        maxLines = 1,
                                    )
                                    if (student.className.isNotBlank()) {
                                        Text(
                                            text = student.className,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = chipFg.copy(alpha = 0.85f),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant)
        }
    }
}
