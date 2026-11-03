@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package md.sime.opensime.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import md.sime.opensime.core.i18n.LocalAppStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val strings = LocalAppStrings.current
    val star = MaterialShapes.SoftBurst.toShape()
    val loadingShapes = remember {
        listOf(MaterialShapes.SoftBurst, MaterialShapes.Sunny, MaterialShapes.Cookie9Sided)
    }
    var releaseRotation by remember { mutableFloatStateOf(0f) }
    // Follow native nested-scroll distance directly, including reversing and pulling past threshold.
    val dragRotation = state.distanceFraction * 360f
    SideEffect {
        if (!refreshing) releaseRotation = dragRotation
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (!refreshing) onRefresh() },
        state = state,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.IndicatorBox(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                val description = if (refreshing) strings.refreshing else strings.refreshAction
                Box(
                    modifier = Modifier.size(48.dp).semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    if (refreshing && !reduceMotion) {
                        // Use the library's rotating, spring-morphing Expressive animation.
                        LoadingIndicator(
                            modifier = Modifier.size(38.dp).rotate(releaseRotation),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            polygons = loadingShapes,
                        )
                    } else {
                        Box(
                            Modifier
                                .size(38.dp)
                                .padding(4.dp)
                                .graphicsLayer {
                                    rotationZ = if (reduceMotion) 0f else dragRotation
                                    val reveal = if (refreshing || reduceMotion) 1f
                                        else state.distanceFraction.coerceIn(0f, 1f)
                                    scaleX = reveal
                                    scaleY = reveal
                                    alpha = reveal
                                }
                                .background(MaterialTheme.colorScheme.onPrimaryContainer, star),
                        )
                    }
                }
            }
        },
        content = content,
    )
}
