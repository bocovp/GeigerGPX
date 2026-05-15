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

    var tracks: List<MapTrack> = emptyList()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0
    var enabledQ: Boolean = false

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
    private val reusableGeoPoint = GeoPoint(0.0, 0.0)
    private val circleRadius = 4.352f * density
    private val visiblePointsBuffer = ArrayList<TrackPoint>(MAX_VISIBLE_POINTS + 1)

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!enabledQ || tracks.isEmpty()) return
        if (projection.zoomLevel < MIN_ZOOM_FOR_POINTS) return

        val visibleBounds = projection.boundingBox ?: return
        val visiblePoints = collectVisiblePoints(visibleBounds)
        if (visiblePoints.isEmpty() || visiblePoints.size > MAX_VISIBLE_POINTS) return

        visiblePoints.forEach { point ->
            reusableGeoPoint.setCoords(point.latitude, point.longitude)
            projection.toPixels(reusableGeoPoint, reusablePixelPoint)
            pointFillPaint.color = DoseColorScale.colorForDose(point.doseRate, minDose, maxDose)
            canvas.drawCircle(reusablePixelPoint.x.toFloat(), reusablePixelPoint.y.toFloat(), circleRadius, pointFillPaint)
            canvas.drawCircle(reusablePixelPoint.x.toFloat(), reusablePixelPoint.y.toFloat(), circleRadius, pointStrokePaint)
        }
    }

    private fun collectVisiblePoints(bounds: BoundingBox): List<TrackPoint> {
        visiblePointsBuffer.clear()
        tracks.forEach { track ->
            track.points.forEach { point ->
                if (point.badCoordinates) return@forEach
                if (!bounds.contains(point.latitude, point.longitude)) return@forEach
                visiblePointsBuffer.add(point)
                if (visiblePointsBuffer.size > MAX_VISIBLE_POINTS) return visiblePointsBuffer
            }
        }
        return visiblePointsBuffer
    }
}
