package app.dogrouter.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

private val DEFAULT_MAP_HEIGHT = 200.dp
private const val BOUNDS_PADDING_PX = 48

/**
 * Read-only mini-map drawing a single cycling leg: the [track] polyline
 * from a start point to an end point, with a marker at each end. Used in
 * Follow plan so the walker can see the route to the next stop at a
 * glance. Non-interactive by design (gloves, glances).
 *
 * [track] is expected to be ordered start-to-end with at least two points.
 */
@Composable
fun RouteLegMap(
    track: List<GeoPoint>,
    modifier: Modifier = Modifier,
    height: Dp = DEFAULT_MAP_HEIGHT,
    lineColor: Int = androidx.compose.material3.MaterialTheme.colorScheme.primary.toArgb(),
) {
    if (track.size < 2) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setOnTouchListener { _, _ -> true } // swallow drags so it stays glanceable
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

    AndroidView(
        factory = { mapView },
        update = { view ->
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
            // Defer the fit until the view has been laid out, otherwise the
            // bounding-box zoom is computed against a zero-size map.
            view.post { view.zoomToBoundingBox(box, false, BOUNDS_PADDING_PX) }
            view.invalidate()
        },
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // osmdroid's async tile drawing can spill outside the view's
            // bounds inside a scrollable Column; clip what it paints.
            .clipToBounds(),
    )
}

private fun endMarker(view: MapView, point: OsmGeoPoint): Marker =
    Marker(view).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }
