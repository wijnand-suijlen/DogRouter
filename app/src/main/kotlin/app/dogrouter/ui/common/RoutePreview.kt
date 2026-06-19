package app.dogrouter.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.dogrouter.domain.routing.GeoPoint
import kotlin.math.cos

private const val MAX_POINTS = 400

/**
 * Lightweight inline preview of a cycling leg: draws just the route shape
 * on a plain background, with no map tiles. This deliberately avoids an
 * osmdroid MapView so a list of these (e.g. the Today timeline) costs
 * almost nothing — many simultaneous MapViews were causing memory/ANR
 * pressure. The full street map opens on tap via the full-screen view.
 *
 * [track] is the ordered start-to-end polyline (>= 2 points).
 */
@Composable
fun RoutePreview(
    track: List<GeoPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    background: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
) {
    Canvas(modifier = modifier) {
        drawRect(color = background)
        if (track.size < 2) return@Canvas

        val points = track.downsampled()
        // Equirectangular projection: scale longitude by cos(lat) so the
        // shape is not horizontally stretched away from the equator.
        val meanLat = points.sumOf { it.latitude } / points.size
        val k = cos(Math.toRadians(meanLat)).toFloat().coerceAtLeast(0.01f)
        val xs = points.map { (it.longitude * k).toFloat() }
        val ys = points.map { (-it.latitude).toFloat() }

        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val spanX = maxX - minX
        val spanY = maxY - minY

        val pad = 12.dp.toPx()
        val availW = (size.width - 2 * pad).coerceAtLeast(1f)
        val availH = (size.height - 2 * pad).coerceAtLeast(1f)
        val scale = minOf(
            if (spanX > 1e-9f) availW / spanX else Float.MAX_VALUE,
            if (spanY > 1e-9f) availH / spanY else Float.MAX_VALUE,
        ).let { if (it == Float.MAX_VALUE) 1f else it }

        val offX = pad + (availW - spanX * scale) / 2f - minX * scale
        val offY = pad + (availH - spanY * scale) / 2f - minY * scale
        fun mapX(x: Float) = x * scale + offX
        fun mapY(y: Float) = y * scale + offY

        val path = Path().apply {
            moveTo(mapX(xs.first()), mapY(ys.first()))
            for (i in 1 until xs.size) lineTo(mapX(xs[i]), mapY(ys[i]))
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(mapX(xs.first()), mapY(ys.first())))
        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(mapX(xs.last()), mapY(ys.last())))
    }
}

/** Cap the point count so a long route still draws cheaply. */
private fun List<GeoPoint>.downsampled(max: Int = MAX_POINTS): List<GeoPoint> {
    if (size <= max) return this
    val step = size / max
    val out = ArrayList<GeoPoint>(max + 1)
    var i = 0
    while (i < size) {
        out.add(this[i])
        i += step
    }
    if (out.last() != last()) out.add(last())
    return out
}
