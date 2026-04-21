package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

class EditTrackOverlay : Overlay() {
    var points: List<TrackPoint> = emptyList()
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

        points.forEachIndexed { index, pt ->
            projection.toPixels(GeoPoint(pt.latitude, pt.longitude), p1)
            pointFillPaint.color = when {
                highlightedIndices.contains(index) -> Color.parseColor("#1E88E5")
                pt.badCoordinates -> Color.GRAY
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
    var selectionEnabled: Boolean = false
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var selecting = false
    private var longPressPanning = false
    private var longPressTriggered = false
    private var longPressTimeoutMs = 500L
    private var touchSlop = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var currentMapView: org.osmdroid.views.MapView? = null

    private val longPressRunnable = Runnable {
        if (!selectionEnabled || !selecting) return@Runnable
        longPressPanning = true
        longPressTriggered = true
        selecting = false
        currentMapView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        currentMapView?.invalidate()
    }

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
        if (!selectionEnabled || event == null || mapView == null) return false
        currentMapView = mapView

        if (touchSlop == 0f) {
            val viewConfig = ViewConfiguration.get(mapView.context)
            touchSlop = viewConfig.scaledTouchSlop.toFloat()
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
        }

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                mapView.removeCallbacks(longPressRunnable)
                selecting = true
                longPressPanning = false
                longPressTriggered = false
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                mapView.postDelayed(longPressRunnable, longPressTimeoutMs)
                mapView.invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (longPressPanning) {
                    val dx = (lastX - event.x).toInt()
                    val dy = (lastY - event.y).toInt()
                    if (dx != 0 || dy != 0) {
                        mapView.scrollBy(dx, dy)
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }

                val moved = kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop
                if (moved && !longPressTriggered) {
                    mapView.removeCallbacks(longPressRunnable)
                }

                if (!selecting) return true
                endX = event.x
                endY = event.y
                mapView.invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                mapView.removeCallbacks(longPressRunnable)

                if (longPressPanning) {
                    longPressPanning = false
                    selecting = false
                    currentMapView = null
                    return true
                }

                if (!selecting) {
                    currentMapView = null
                    return true
                }
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
                currentMapView = null
                return true
            }
        }
        return true
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!selectionEnabled || !selecting) return
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
