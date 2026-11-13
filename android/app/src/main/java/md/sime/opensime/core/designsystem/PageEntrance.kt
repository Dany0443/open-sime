package md.sime.opensime.core.designsystem

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private data class EntranceSession(val startedAt: Long, val enabled: Boolean)
private val LocalEntranceSession = staticCompositionLocalOf<EntranceSession?> { null }

/** A single entrance timeline per selected page/student, independent of snapshot refreshes. */
@Composable
internal fun PageEntranceScope(studentKey: String, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemAnimations = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
    val startedAt = remember(studentKey) { SystemClock.uptimeMillis() }
    val session = remember(startedAt, reduceMotion, systemAnimations) {
        EntranceSession(startedAt, systemAnimations && !reduceMotion)
    }
    CompositionLocalProvider(LocalEntranceSession provides session, content = content)
}

/** Late/offscreen items appear immediately; only the first few visible cards are staggered. */
internal fun pageEntranceDelay(index: Int, elapsedMillis: Long, detailIndex: Int? = null): Long? {
    if (index !in 0..9 || elapsedMillis >= 360L || (detailIndex != null && detailIndex !in 0..15)) return null
    val detailDelay = detailIndex?.let { 45L + it.coerceAtMost(7) * 18L } ?: 0L
    return (index * 30L + detailDelay - elapsedMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
}

@Composable
internal fun Modifier.pageEntrance(index: Int, detailIndex: Int? = null): Modifier {
    val session = LocalEntranceSession.current ?: return this
    val wait = remember(session) { pageEntranceDelay(index, SystemClock.uptimeMillis() - session.startedAt, detailIndex) }
    if (!session.enabled || wait == null) return this
    val progress = remember(session) { Animatable(0f) }
    val motion = if (detailIndex == null) MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        else MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(session) {
        if (progress.value < 1f) {
            delay(wait)
            progress.animateTo(1f, motion)
        } else progress.snapTo(1f)
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * (if (detailIndex == null) 20.dp else 8.dp).toPx()
        val startingScale = if (detailIndex == null) 0.94f else 0.88f
        scaleX = startingScale + (1f - startingScale) * p
        scaleY = scaleX
    }
}

@Composable
internal fun PageEntranceItem(index: Int, content: @Composable () -> Unit) {
    Box(Modifier.pageEntrance(index)) { content() }
}
