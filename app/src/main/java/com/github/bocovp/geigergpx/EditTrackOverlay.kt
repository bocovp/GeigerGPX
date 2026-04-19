package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

class EditTrackOverlay : Overlay() {
    var points: List<TrackSample> = emptyList()
    var highlightedIndices: Set<Int> = emptySet()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    private val linePaint = Paint().apply {
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val pointFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        isAntiAlias = true
    }

    private val p1 = Point()
    private val p2 = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        if (points.size < 2) return

        for (i in 0 until points.size - 1) {
            val first = points[i]
            val second = points[i + 1]
            projection.toPixels(GeoPoint(first.latitude, first.longitude), p1)
            projection.toPixels(GeoPoint(second.latitude, second.longitude), p2)
            linePaint.shader = LinearGradient(
                p1.x.toFloat(), p1.y.toFloat(),
                p2.x.toFloat(), p2.y.toFloat(),
                DoseColorScale.colorForDose(first.doseRate, minDose, maxDose),
                DoseColorScale.colorForDose(second.doseRate, minDose, maxDose),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), linePaint)
        }
        linePaint.shader = null

        points.forEachIndexed { index, sample ->
            projection.toPixels(GeoPoint(sample.latitude, sample.longitude), p1)
            pointFillPaint.color = when {
                highlightedIndices.contains(index) -> Color.parseColor("#1E88E5")
                sample.badCoordinates -> Color.GRAY
                else -> Color.WHITE
            }
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 8f, pointFillPaint)
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 8f, pointStrokePaint)
        }
    }
}

class RectangleSelectionOverlay(
    private val onSelectionComplete: (RectF) -> Unit
) : Overlay() {
    var enabled: Boolean = false
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var selecting = false

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 30, 136, 229)
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1E88E5")
    }

    override fun onTouchEvent(event: android.view.MotionEvent?, mapView: org.osmdroid.views.MapView?): Boolean {
        if (!enabled || event == null || mapView == null) return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                selecting = true
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                mapView.invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!selecting) return true
                endX = event.x
                endY = event.y
                mapView.invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (!selecting) return true
                endX = event.x
                endY = event.y
                selecting = false
                val rect = RectF(
                    kotlin.math.min(startX, endX),
                    kotlin.math.min(startY, endY),
                    kotlin.math.max(startX, endX),
                    kotlin.math.max(startY, endY)
                )
                onSelectionComplete(rect)
                mapView.invalidate()
                return true
            }
        }
        return true
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!enabled || !selecting) return
        val rect = RectF(
            kotlin.math.min(startX, endX),
            kotlin.math.min(startY, endY),
            kotlin.math.max(startX, endX),
            kotlin.math.max(startY, endY)
        )
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, borderPaint)
    }
}
