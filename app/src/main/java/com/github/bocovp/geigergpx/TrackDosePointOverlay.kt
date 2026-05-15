package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

class TrackDosePointOverlay(context: android.content.Context) : Overlay() {
    companion object {
        private const val MIN_ZOOM_FOR_POINTS = 16.0
        private const val MAX_VISIBLE_POINTS = 200
    }

    private val density = context.resources.displayMetrics.density

    var pointsByTrack: List<List<TrackPoint>> = emptyList()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0
    var enabled: Boolean = false

    private val pointFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.088f * density
        color = Color.BLACK
        isAntiAlias = true
    }

    private val reusablePixelPoint = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!enabled || pointsByTrack.isEmpty()) return
        if (projection.zoomLevel < MIN_ZOOM_FOR_POINTS) return

        val visibleBounds = projection.boundingBox ?: return
        val visiblePoints = collectVisiblePoints(visibleBounds)
        if (visiblePoints.isEmpty() || visiblePoints.size >= MAX_VISIBLE_POINTS) return

        visiblePoints.forEach { point ->
            projection.toPixels(GeoPoint(point.latitude, point.longitude), reusablePixelPoint)
            pointFillPaint.color = DoseColorScale.colorForDose(point.doseRate, minDose, maxDose)
            canvas.drawCircle(reusablePixelPoint.x.toFloat(), reusablePixelPoint.y.toFloat(), 4.352f * density, pointFillPaint)
            canvas.drawCircle(reusablePixelPoint.x.toFloat(), reusablePixelPoint.y.toFloat(), 4.352f * density, pointStrokePaint)
        }
    }

    private fun collectVisiblePoints(bounds: BoundingBox): List<TrackPoint> {
        val result = ArrayList<TrackPoint>()
        pointsByTrack.forEach { trackPoints ->
            trackPoints.forEach { point ->
                if (point.badCoordinates) return@forEach
                if (!bounds.contains(point.latitude, point.longitude)) return@forEach
                result.add(point)
                if (result.size >= MAX_VISIBLE_POINTS) return result
            }
        }
        return result
    }
}
