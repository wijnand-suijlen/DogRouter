package app.dogrouter.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private const val PREVIEW_ZOOM = 17.0
private val PREVIEW_HEIGHT = 180.dp

/**
 * Read-only mini-map showing the validated pickup/drop-off coordinates so
 * the walker can sanity-check the chosen address at a glance.
 *
 * Multi-touch and the on-map zoom buttons are disabled — interactivity
 * lives in the bigger map picker that lands in a later round.
 */
@Composable
fun AddressMapPreview(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(PREVIEW_ZOOM)
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
            val point = GeoPoint(latitude, longitude)
            view.controller.setCenter(point)
            view.overlays.clear()
            view.overlays.add(
                Marker(view).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                },
            )
            view.invalidate()
        },
        modifier = modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            // osmdroid's async tile drawing can spill outside the
            // AndroidView's bounds inside a scrollable Column; force
            // Compose to clip what the embedded view paints.
            .clipToBounds(),
    )
}
