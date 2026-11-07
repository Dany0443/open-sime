@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package md.sime.opensime.feature.onboarding

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import md.sime.opensime.core.designsystem.CookieAvatar
import md.sime.opensime.core.designsystem.LocalOpenSimeSemanticColors
import md.sime.opensime.core.designsystem.OpenSimeIcons
import md.sime.opensime.core.i18n.LocalAppStrings

private const val PAGE_COUNT = 2
private const val REPOSITORY_URL = "https://github.com/Dany0443/open-sime"
private val ContentMaxWidth = 520.dp

/**
 * Two-page welcome experience.
 *
 * Page 1 introduces the app, page 2 names the three essentials (timetable, grades,
 * absences). A single persistent action advances from page 1 and finishes on page 2.
 *
 * Completion is reported only through [onContinueToSignIn]; the GitHub and settings
 * actions never complete onboarding.
 *
 * @param reduceMotion optional app-level reduced-motion preference. Motion is also
 * disabled when the system animator duration scale is 0.
 */
@Composable
fun OnboardingScreen(
    onContinueToSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val animate = rememberMotionEnabled(reduceMotion)
    val pagerState = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == PAGE_COUNT - 1

    // System Back steps from page 2 to page 1; on page 1 it falls through to the activity.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch {
            if (animate) pagerState.animateScrollToPage(0) else pagerState.scrollToPage(0)
        }
    }

    // Hero entrance plays once; rememberSaveable keeps it from replaying on rotation.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val heroSpec: FiniteAnimationSpec<Float> =
        if (animate) MaterialTheme.motionScheme.slowSpatialSpec() else snap()
    val heroScale by animateFloatAsState(
        targetValue = if (entered || !animate) 1f else 0.78f,
        animationSpec = heroSpec,
        label = "heroScale",
    )

    Surface(modifier = modifier.fillMaxSize(), color = colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Secondary actions: repository on the left, settings on the right.
            Row(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { runCatching { uriHandler.openUri(REPOSITORY_URL) } },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("onboarding_action_github"),
                ) {
                    Icon(
                        imageVector = GitHubMark,
                        contentDescription = strings.onboardingGithubLabel,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("onboarding_pager"),
            ) { page ->
                OnboardingPage(pagerState = pagerState, page = page, animate = animate) { visibility ->
                    if (page == 0) {
                        WelcomePage(heroScale = heroScale)
                    } else {
                        EssentialsPage(visibility = visibility)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PageIndicator(pagerState)
                Button(
                    onClick = {
                        if (isLastPage) {
                            onContinueToSignIn()
                        } else {
                            scope.launch {
                                val next = pagerState.currentPage + 1
                                if (animate) pagerState.animateScrollToPage(next) else pagerState.scrollToPage(next)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 56.dp)
                        .testTag(if (isLastPage) "onboarding_action_continue" else "onboarding_action_next"),
                ) {
                    AnimatedContent(
                        targetState = isLastPage,
                        transitionSpec = {
                            if (animate) fadeIn(tween(160)) togetherWith fadeOut(tween(90))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "primaryActionLabel",
                    ) { last ->
                        Text(
                            text = if (last) strings.onboardingActionStart else strings.onboardingActionNext,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** Scrollable, vertically centered page frame with a light scroll-driven parallax and fade. */
@Composable
private fun OnboardingPage(
    pagerState: PagerState,
    page: Int,
    animate: Boolean,
    content: @Composable ColumnScope.(visibility: () -> Float) -> Unit,
) {
    // 1f when this page is fully in view, 0f when fully off screen.
    val visibility: () -> Float = {
        if (!animate) 1f
        else (1f - abs(page - (pagerState.currentPage + pagerState.currentPageOffsetFraction))).coerceIn(0f, 1f)
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .graphicsLayer {
                    if (animate) {
                        val signed = page - (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        alpha = (1f - abs(signed) * 0.9f).coerceIn(0f, 1f)
                        translationX = -signed * size.width * 0.2f
                    }
                }
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .heightIn(min = minHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            content(visibility)
        }
    }
}

@Composable
private fun WelcomePage(heroScale: Float) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    // Wrapped in a Box so only the existing CookieAvatar parameters are relied on.
    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = heroScale
            scaleY = heroScale
        },
    ) {
        CookieAvatar(
            fullName = "Open SIME",
            size = 168.dp,
            fontSize = 56.sp,
            activeHighlight = true,
        )
    }
    Spacer(Modifier.height(32.dp))
    Text(
        text = strings.onboardingBadge,
        style = MaterialTheme.typography.labelLarge,
        color = colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = strings.onboardingTitle,
        style = MaterialTheme.typography.headlineMediumEmphasized,
        color = colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = strings.onboardingSubtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * One composition instead of three cards: three tonal shapes of different families,
 * with the middle one larger and raised, each rising into place as the page arrives.
 */
@Composable
private fun EssentialsPage(visibility: () -> Float) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    val items = listOf(
        Essential(ScheduleIcon, strings.tabTimetable, colorScheme.primaryContainer, colorScheme.onPrimaryContainer,
            CircleShape, 88.dp, 36.dp),
        Essential(OpenSimeIcons.Marks, strings.tabMarks, colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer,
            MaterialTheme.shapes.extraLarge, 112.dp, 0.dp),
        Essential(EventBusyIcon, strings.tabAbsences, colorScheme.secondaryContainer, colorScheme.onSecondaryContainer,
            RoundedCornerShape(topStart = 36.dp, topEnd = 12.dp, bottomEnd = 36.dp, bottomStart = 12.dp), 88.dp, 36.dp),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = item.topOffset)
                    .graphicsLayer {
                        // Staggered: each item starts a little after the previous one.
                        val p = (visibility() * 1.4f - index * 0.2f).coerceIn(0f, 1f)
                        val scale = 0.8f + 0.2f * p
                        scaleX = scale
                        scaleY = scale
                        alpha = p
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(item.size)
                        .clip(item.shape)
                        .background(item.container)
                        .then(
                            if (highContrast) Modifier.border(2.dp, colorScheme.onSurface, item.shape) else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = item.content,
                        modifier = Modifier.size(item.size * 0.42f),
                    )
                }
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    Spacer(Modifier.height(40.dp))
    Text(
        text = strings.onboardingPage2Title,
        style = MaterialTheme.typography.headlineMediumEmphasized,
        color = colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = strings.onboardingPage2Subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private class Essential(
    val icon: ImageVector,
    val label: String,
    val container: Color,
    val content: Color,
    val shape: Shape,
    val size: Dp,
    val topOffset: Dp,
)

/** The selected dot stretches into a bar and the transition follows the swipe continuously. */
@Composable
private fun PageIndicator(pagerState: PagerState) {
    val colorScheme = MaterialTheme.colorScheme
    val strings = LocalAppStrings.current
    val description = strings.onboardingPageIndicator
        .replace("{page}", (pagerState.currentPage + 1).toString())
        .replace("{total}", PAGE_COUNT.toString())
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAGE_COUNT) { index ->
            val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val selection = (1f - abs(position - index)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .height(8.dp)
                    .width(lerpDp(8.dp, 28.dp, selection))
                    .clip(CircleShape)
                    .background(lerpColor(colorScheme.outlineVariant, colorScheme.primary, selection)),
            )
        }
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

private fun vectorIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

/** Official GitHub mark (24x24 viewport). */
private val GitHubMark: ImageVector by lazy {
    vectorIcon(
        "GitHubMark",
        "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12",
    )
}

/** Material "schedule" glyph. */
private val ScheduleIcon: ImageVector by lazy {
    vectorIcon(
        "Schedule",
        "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
    )
}

/** Material "event_busy" glyph. */
private val EventBusyIcon: ImageVector by lazy {
    vectorIcon(
        "EventBusy",
        "M9.31 17l2.44-2.44L14.19 17l1.06-1.06-2.44-2.44 2.44-2.44L14.19 10l-2.44 2.44L9.31 10l-1.06 1.06 2.44 2.44-2.44 2.44L9.31 17zM19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11z",
    )
}