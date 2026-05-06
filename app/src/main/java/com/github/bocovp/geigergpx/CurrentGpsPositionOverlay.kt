package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class CurrentGpsPositionOverlay : Overlay() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2196F3")
    }

    var location: GeoPoint? = null

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val point = location ?: return
        val projection = mapView.projection
        val screenPoint = projection.toPixels(point, null) ?: return
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), 8f, fillPaint)
    }
}
