package app.dogrouter.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.dogrouter.domain.routing.GeoPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint as OsmGeoPoint

private const val BOUNDS_PADDING_PX = 48

/**
 * osmdroid map of a single cycling leg: the [track] polyline with a marker
 * at each end, fitted to the leg's bounds. The fit runs once per [track]
 * so it never fights the user's gestures.
 *
 * When [interactive] is false it is a static overview (used inline in
 * Follow plan, under a tap overlay); when true the user can pinch-zoom and
 * pan (the full-screen [LegMapScreen]). Use at most a couple of these at
 * once — the Today timeline shows a tap-to-open icon instead of inline
 * maps, because many simultaneous MapViews caused memory/ANR pressure.
 *
 * [track] is expected to be ordered start-to-end with at least two points.
 */
@Composable
fun RouteLegMap(
    track: List<GeoPoint>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    lineColor: Int = androidx.compose.material3.MaterialTheme.colorScheme.primary.toArgb(),
) {
    if (track.size < 2) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(context, interactive) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(
                if (interactive) {
                    CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                } else {
                    CustomZoomButtonsController.Visibility.NEVER
                },
            )
            if (!interactive) {
                // Swallow touches so a static overview never pans under the
                // finger; the caller's tap overlay opens the full map.
                setOnTouchListener { _, _ -> true }
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDetach()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Draw the leg once per track value. Re-running on every recomposition
    // would reset the camera and undo the user's pan/zoom.
    val rendered = remember(track) { booleanArrayOf(false) }
    AndroidView(
        factory = { mapView },
        update = { view ->
            if (rendered[0]) return@AndroidView
            val points = track.map { OsmGeoPoint(it.latitude, it.longitude) }
            view.overlays.clear()
            view.overlays.add(
                Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = lineColor
                    outlinePaint.strokeWidth = 10f
                },
            )
            view.overlays.add(endMarker(view, points.first()))
            view.overlays.add(endMarker(view, points.last()))
            val box = BoundingBox.fromGeoPoints(points)
            // Defer the fit until the view has a size, otherwise the
            // bounding-box zoom is computed against a zero-size map.
            view.post {
                view.zoomToBoundingBox(box, false, BOUNDS_PADDING_PX)
                view.invalidate()
            }
            rendered[0] = true
        },
        modifier = modifier.clipToBounds(),
    )
}

private fun endMarker(view: MapView, point: OsmGeoPoint): Marker =
    Marker(view).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }
