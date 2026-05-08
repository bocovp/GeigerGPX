package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class CurrentGpsPositionOverlay(context: android.content.Context) : Overlay() {
    private val density = context.resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(25, 118, 210)
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density // Adjust this multiplier for a thicker/thinner outline
    }


    private val screenPoint = Point()
    var location: GeoPoint? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val point = location ?: return
        val projection = mapView.projection
        projection.toPixels(point, screenPoint)
        val radius = 5f * density

        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, fillPaint)
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radius, outlinePaint)
    }
}
