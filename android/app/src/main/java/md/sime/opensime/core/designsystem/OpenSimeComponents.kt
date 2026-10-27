package md.sime.opensime.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout. defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.sime.opensime.core.i18n.LocalAppStrings
import md.sime.opensime.core.model.GradeBand
import md.sime.opensime.core.model.MarkItem
import md.sime.opensime.core.model.MarkValue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ChipTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    LIVE,
    NEXT,
    QUIET,
}

enum class ScoreBadgeSize {
    SMALL,
    MEDIUM,
    LARGE,
}

@Composable
fun StatusChip(
    text: String,
    tone: ChipTone = ChipTone.NEUTRAL,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val colorScheme = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current

    val (bg, fg) = when (tone) {
        ChipTone.NEUTRAL -> colorScheme.surfaceContainerHighest to colorScheme.onSurfaceVariant
        ChipTone.INFO -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        ChipTone.SUCCESS -> semantic.successContainer to semantic.onSuccessContainer
        ChipTone.WARNING -> semantic.warningContainer to semantic.onWarningContainer
        ChipTone.ERROR -> colorScheme.errorContainer to colorScheme.onErrorContainer
        ChipTone.LIVE -> colorScheme.onPrimary to colorScheme.primary
        ChipTone.NEXT -> colorScheme.tertiary to colorScheme.onTertiary
        ChipTone.QUIET -> Color.Transparent to colorScheme.onSurfaceVariant
    }

    val border = when {
        semantic.highContrast -> BorderStroke(1.5.dp, fg)
        tone == ChipTone.QUIET -> BorderStroke(1.dp, colorScheme.outlineVariant)
        else -> null
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        contentColor = fg,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (tone == ChipTone.LIVE) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(fg),
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = fg,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
                color = fg,
            )
        }
    }
}

@Composable
fun BandChip(
    averageValue: Double?,
    modifier: Modifier = Modifier,
) {
    if (averageValue == null || averageValue.isNaN()) return
    val strings = LocalAppStrings.current
    val band = GradeBand.fromAverage(averageValue)
    val (bg, fg, _) = resolveBandColorsAndRadius(band)
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
        contentColor = fg,
        border = if (highContrast) BorderStroke(1.5.dp, fg) else null,
    ) {
        Text(
            text = strings.bandLabel(band),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            ),
            color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun ScoreBadge(
    mark: MarkItem,
    size: ScoreBadgeSize = ScoreBadgeSize.MEDIUM,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    val band = GradeBand.fromMark(mark.value)
    val (bg, fg, cornerRadius) = resolveBandColorsAndRadius(band)

    val isQualifier = mark.value is MarkValue.Qualifier || mark.value is MarkValue.Other
    val displayWord = when (val v = mark.value) {
        is MarkValue.Numeric -> v.displayText
        is MarkValue.Qualifier -> strings.qualifierFullWord(v.qualifier)
        is MarkValue.Other -> v.label
    }

    val dateDesc = if (mark.isoDate.isNotBlank()) strings.formatLongDate(mark.isoDate) else ""
    val a11yDescription = buildString {
        if (isQualifier) {
            append(displayWord)
            if (dateDesc.isNotBlank()) append(", ").append(dateDesc)
        } else {
            append(if (mark.isThesis) strings.thesisPrefix else strings.gradePrefix)
            append(displayWord)
            if (dateDesc.isNotBlank()) append(" (").append(dateDesc).append(")")
        }
    }

    val minHeight = when (size) {
        ScoreBadgeSize.SMALL -> 34.dp
        ScoreBadgeSize.MEDIUM -> 40.dp
        ScoreBadgeSize.LARGE -> 48.dp
    }
    val minWidth = when (size) {
        ScoreBadgeSize.SMALL -> 34.dp
        ScoreBadgeSize.MEDIUM -> 40.dp
        ScoreBadgeSize.LARGE -> 56.dp
    }
    val textSize = when {
        isQualifier && size == ScoreBadgeSize.SMALL -> 12.sp
        isQualifier && size == ScoreBadgeSize.LARGE -> 16.sp
        isQualifier -> 13.sp
        size == ScoreBadgeSize.SMALL -> 15.sp
        size == ScoreBadgeSize.LARGE -> 22.sp
        else -> 17.sp
    }

    val border = when {
        mark.isThesis -> BorderStroke(2.dp, colorScheme.tertiary)
        highContrast -> BorderStroke(1.5.dp, fg)
        else -> null
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = a11yDescription
            },
        shape = RoundedCornerShape(cornerRadius),
        color = bg,
        contentColor = fg,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isQualifier || mark.isThesis) 10.dp else 8.dp,
                vertical = 4.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (mark.isThesis) {
                Text(
                    text = strings.thesisBadge.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        letterSpacing = 0.4.sp,
                    ),
                    color = fg,
                    modifier = Modifier.padding(end = 5.dp),
                )
            }
            Text(
                text = displayWord,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = textSize,
                ),
                color = fg,
            )
        }
    }
}

@Composable
private fun resolveBandColorsAndRadius(band: GradeBand): Triple<Color, Color, Dp> {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current
    return when (band) {
        GradeBand.TOP -> Triple(semantic.success, semantic.onSuccess, 999.dp)
        GradeBand.GOOD -> Triple(semantic.successContainer, semantic.onSuccessContainer, 16.dp)
        GradeBand.OK -> Triple(semantic.warningContainer, semantic.onWarningContainer, 12.dp)
        GradeBand.LOW -> Triple(colorScheme.error, colorScheme.onError, 8.dp)
        GradeBand.NONE -> Triple(colorScheme.surfaceContainerHighest, colorScheme.onSurfaceVariant, 12.dp)
    }
}

/**
 * 10-lobe cookie avatar matching `COOKIE_PATH` and `.avatar` in `web/public/app.js` & `style.css`.
 */
@Composable
fun CookieAvatar(
    fullName: String,
    toneIndex: Int = 0,
    size: Dp = 44.dp,
    fontSize: TextUnit = 14.sp,
    activeHighlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = LocalOpenSimeSemanticColors.current

    val (bg, fg) = if (activeHighlight) {
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer
    } else {
        when (toneIndex.mod(4)) {
            0 -> colorScheme.primary to colorScheme.onPrimary
            1 -> colorScheme.tertiary to colorScheme.onTertiary
            2 -> colorScheme.secondary to colorScheme.onSecondary
            else -> semantic.success to semantic.onSuccess
        }
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = buildCookiePath(this.size.width, this.size.height)
            drawPath(path = path, color = bg)
        }
        Text(
            text = extractInitials(fullName),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = fontSize,
            ),
            color = fg,
        )
    }
}

@Composable
fun CookieDecorationShape(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val path = buildCookiePath(this.size.width, this.size.height)
        drawPath(path = path, color = tint)
    }
}

private fun buildCookiePath(width: Float, height: Float): Path {
    val path = Path()
    val lobes = 10
    val depth = 0.07
    val steps = 120
    val cx = width / 2f
    val cy = height / 2f
    val scaleX = width / 100f
    val scaleY = height / 100f
    for (i in 0 until steps) {
        val t = (i.toDouble() / steps) * PI * 2.0
        val r = (45.0 * (1.0 + depth * cos(lobes * t))) / (1.0 + depth)
        val x = cx + (r * cos(t)).toFloat() * scaleX
        val y = cy + (r * sin(t)).toFloat() * scaleY
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

fun extractInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter {
        it.isNotBlank() && !it.equals("Prof.", ignoreCase = true) && !it.equals("Elev", ignoreCase = true)
    }
    val chosen = if (words.isNotEmpty()) words else name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val letters = chosen.take(2).mapNotNull { it.firstOrNull { ch -> ch.isLetterOrDigit() } }
    return if (letters.isEmpty()) "?" else letters.joinToString("").uppercase()
}

@Composable
fun EmptyStateCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("empty_state_card"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun ErrorStateCard(
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("error_state_card"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = OpenSimeIcons.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("error_retry_button"),
            ) {
                Text(
                    text = retryLabel,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Segmented button row matching `.seg` and `.seg-btn` in `web/public/style.css`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SegmentedOptionRow(
    options: List<Pair<T, String>>,
    selectedOption: T,
    onSelectOption: (T) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val highContrast = LocalOpenSimeSemanticColors.current.highContrast

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = value == selectedOption
            val shape = if (selected) RoundedCornerShape(16.dp) else RoundedCornerShape(999.dp)
            val bg = if (selected) colorScheme.primary else colorScheme.surfaceContainerHigh
            val fg = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant
            val border = if (highContrast) {
                BorderStroke(2.dp, if (selected) colorScheme.onSurface else fg)
            } else {
                null
            }

            Surface(
                onClick = { onSelectOption(value) },
                shape = shape,
                color = bg,
                contentColor = fg,
                border = border,
                modifier = Modifier
                    .sizeIn(minHeight = 48.dp)
                    .testTag("${testTagPrefix}_$value"),
            ) {
                Box(
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = fg,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

