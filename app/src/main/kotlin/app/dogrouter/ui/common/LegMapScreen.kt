package app.dogrouter.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.LegGeometryCache
import org.koin.compose.koinInject

/**
 * Full-screen, interactive view of one cycling leg: the route fills the
 * screen and the walker can pinch-zoom and pan. A floating close button
 * (and system back) returns to wherever it was opened from.
 */
@Composable
fun LegMapScreen(
    from: GeoPoint,
    to: GeoPoint,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val cache: LegGeometryCache = koinInject()
    var track by remember(from, to) { mutableStateOf<List<GeoPoint>?>(null) }
    LaunchedEffect(from, to) { track = cache.geometry(from, to) }

    Box(modifier = Modifier.fillMaxSize()) {
        val current = track
        if (current == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            RouteLegMap(
                track = current,
                interactive = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        FilledTonalIconButton(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close map")
        }
    }
}
