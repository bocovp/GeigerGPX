import android.graphics.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

class GradientTrackOverlay : Overlay() {
    // We store our own copy of points to draw
    var points: List<TrackPoint> = emptyList()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    private val paint = Paint().apply {
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (points.size < 2) return

        val p1Pixels = Point()
        val p2Pixels = Point()

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            // Convert GeoPoints to screen coordinates
            projection.toPixels(GeoPoint(p1.latitude, p1.longitude), p1Pixels)
            projection.toPixels(GeoPoint(p2.latitude, p2.longitude), p2Pixels)

            // Setup the 'honest' gradient for this specific segment
            paint.shader = LinearGradient(
                p1Pixels.x.toFloat(), p1Pixels.y.toFloat(),
                p2Pixels.x.toFloat(), p2Pixels.y.toFloat(),
                colorForDose(p1.doseRate),
                colorForDose(p2.doseRate),
                Shader.TileMode.CLAMP
            )

            canvas.drawLine(
                p1Pixels.x.toFloat(), p1Pixels.y.toFloat(),
                p2Pixels.x.toFloat(), p2Pixels.y.toFloat(),
                paint
            )
        }
    }

    private fun colorForDose(value: Double): Int {
        val normalized = if (maxDose > minDose) (value - minDose) / (maxDose - minDose) else 0.0
        val clamped = normalized.coerceIn(0.0, 1.0)
        return if (clamped < 0.5) {
            val t = clamped / 0.5
            Color.rgb((255 * t).toInt(), 255, 0)
        } else {
            val t = (clamped - 0.5) / 0.5
            Color.rgb(255, (255 * (1.0 - t)).toInt(), 0)
        }
    }
}