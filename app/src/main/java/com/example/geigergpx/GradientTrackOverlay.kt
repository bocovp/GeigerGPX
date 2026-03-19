package com.example.geigergpx

import android.graphics.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

class GradientTrackOverlay : Overlay() {
    // We store our own copy of points to draw
    var points: List<TrackSample> = emptyList()
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



    private val R1 = 0x00
    private val G1 = 0xC8
    private val B1 = 0x53 // Green
    private val R2 = 0xFF
    private val G2 = 0xEB
    private val B2 = 0x3B // Yellow
    private val R3 = 0xD5
    private val G3 = 0x00
    private val B3 = 0x00 // Red

    private fun colorForDose(value: Double): Int {
        if (abs(value) < 1e-5) // Gray color if no dose info
            return Color.rgb(128, 128, 128)
        val normalized = if (maxDose > minDose) (value - minDose) / (maxDose - minDose) else 0.0
        val t = normalized.coerceIn(0.0, 1.0)
        val r: Int
        val g: Int
        val b: Int

        if (t < 0.5f) {
            val ratio = t * 2f
            r = (R1 + ratio * (R2 - R1)).toInt()
            g = (G1 + ratio * (G2 - G1)).toInt()
            b = (B1 + ratio * (B2 - B1)).toInt()
        } else {
            val ratio = (t - 0.5f) * 2f
            r = (R2 + ratio * (R3 - R2)).toInt()
            g = (G2 + ratio * (G3 - G2)).toInt()
            b = (B2 + ratio * (B3 - B2)).toInt()
        }
        return Color.rgb(r, g, b)
    }
}