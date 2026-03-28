package com.example.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

data class PoiMapItem(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val doseRateForColor: Double,
    val counts: Int,
    val seconds: Double,
    val doseLabel: String
)

class PoiOverlay : Overlay() {
    var points: List<PoiMapItem> = emptyList()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 20f
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (points.isEmpty()) return

        val screenRect: Rect = projection.intrinsicScreenRect
        val projectedPoints = ArrayList<Pair<PoiMapItem, Point>>(points.size)
        var visibleCount = 0

        points.forEach { poi ->
            val pixel = Point()
            projection.toPixels(GeoPoint(poi.latitude, poi.longitude), pixel)
            projectedPoints += poi to pixel
            if (screenRect.contains(pixel.x, pixel.y)) {
                visibleCount += 1
            }
        }

        val radius = when {
            visibleCount <= 20 -> 12f
            visibleCount <= 60 -> 10f
            visibleCount <= 120 -> 8f
            else -> 6f
        }

        val textGap = radius + 6f
        val subtitleOffset = 18f

        projectedPoints.forEach { (poi, pixel) ->
            val color = DoseColorScale.colorForDose(poi.doseRateForColor, minDose, maxDose)
            fillPaint.color = color

            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), radius, fillPaint)
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), radius, strokePaint)

            val textX = pixel.x + textGap
            val titleY = pixel.y - 2f
            val subtitleY = titleY + subtitleOffset

            canvas.drawText(poi.name, textX, titleY, titlePaint)
            canvas.drawText(poi.doseLabel, textX, subtitleY, subtitlePaint)
        }
    }
}
