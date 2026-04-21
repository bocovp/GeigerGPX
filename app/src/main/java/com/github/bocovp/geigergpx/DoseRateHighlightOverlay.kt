package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

class DoseRateHighlightOverlay(context: android.content.Context) : Overlay() {
    private val density = context.resources.displayMetrics.density

    data class HighlightPoint(
        val latitude: Double,
        val longitude: Double,
        val doseRateForColor: Double,
        val doseLabel: String
    )

    var highlightedPoint: HighlightPoint? = null
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1.088f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 10.88f * density
    }

    private val pointPixel = Point()
    private val geoPoint = GeoPoint(0.0, 0.0)

    override fun draw(canvas: Canvas, projection: Projection) {
        val point = highlightedPoint ?: return

        geoPoint.setCoords(point.latitude, point.longitude)
        projection.toPixels(geoPoint, pointPixel)

        val px = pointPixel.x.toFloat()
        val py = pointPixel.y.toFloat()
        val radius = 6.528f * density

        fillPaint.color = DoseColorScale.colorForDose(point.doseRateForColor, minDose, maxDose)
        canvas.drawCircle(px, py, radius, fillPaint)
        canvas.drawCircle(px, py, radius, strokePaint)

        val textX = px + radius + 3.264f * density
        val textY = py + 3.264f * density
        canvas.drawText(point.doseLabel, textX, textY, textPaint)
    }
}
