package app.dogrouter.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.LegGeometryCache
import org.koin.compose.koinInject

private val DEFAULT_LEG_MAP_HEIGHT = 150.dp

/**
 * Inline cycling-leg mini-map: loads the route geometry between [from] and
 * [to] (cached), draws a static map, and opens the full-screen interactive
 * view via [onOpenFullscreen] when tapped. Shows a placeholder while the
 * geometry is being computed.
 */
@Composable
fun CyclingLegMap(
    from: GeoPoint,
    to: GeoPoint,
    onOpenFullscreen: (from: GeoPoint, to: GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = DEFAULT_LEG_MAP_HEIGHT,
) {
    val cache: LegGeometryCache = koinInject()
    var track by remember(from, to) { mutableStateOf<List<GeoPoint>?>(null) }
    LaunchedEffect(from, to) { track = cache.geometry(from, to) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.medium),
    ) {
        val current = track
        if (current == null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else {
            RouteLegMap(
                track = current,
                interactive = false,
                modifier = Modifier.matchParentSize(),
            )
            // Transparent layer on top so a tap opens the full-screen map
            // instead of being swallowed by the embedded MapView.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { onOpenFullscreen(from, to) },
            )
        }
    }
}
