package com.github.bocovp.geigergpx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
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

        data class ProjectedPoi(
            val poi: PoiMapItem,
            val pixel: Point,
            val boundingRect: RectF,
            val textX: Float,
            val titleY: Float,
            val subtitleY: Float
        )

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

        val projectedPois = projectedPoints.map { (poi, pixel) ->
            val textX = pixel.x + textGap
            val titleY = pixel.y - 2f
            val subtitleY = titleY + subtitleOffset

            val titleMetrics = titlePaint.fontMetrics
            val subtitleMetrics = subtitlePaint.fontMetrics
            val textWidth = maxOf(titlePaint.measureText(poi.name), subtitlePaint.measureText(poi.doseLabel))

            val minX = minOf(pixel.x - radius, textX)
            val maxX = maxOf(pixel.x + radius, textX + textWidth)
            val minY = minOf(
                pixel.y - radius,
                titleY + titleMetrics.ascent,
                subtitleY + subtitleMetrics.ascent
            )
            val maxY = maxOf(
                pixel.y + radius,
                titleY + titleMetrics.descent,
                subtitleY + subtitleMetrics.descent
            )

            ProjectedPoi(
                poi = poi,
                pixel = pixel,
                boundingRect = RectF(minX, minY, maxX, maxY),
                textX = textX,
                titleY = titleY,
                subtitleY = subtitleY
            )
        }

        val overlappingText = BooleanArray(projectedPois.size)
        for (i in projectedPois.indices) {
            for (j in i + 1 until projectedPois.size) {
                if (RectF.intersects(projectedPois[i].boundingRect, projectedPois[j].boundingRect)) {
                    overlappingText[i] = true
                    overlappingText[j] = true
                }
            }
        }

        projectedPois.forEachIndexed { index, projectedPoi ->
            val poi = projectedPoi.poi
            val pixel = projectedPoi.pixel
            val color = DoseColorScale.colorForDose(poi.doseRateForColor, minDose, maxDose)
            fillPaint.color = color

            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), radius, fillPaint)
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), radius, strokePaint)

            if (!overlappingText[index]) {
                canvas.drawText(poi.name, projectedPoi.textX, projectedPoi.titleY, titlePaint)
                canvas.drawText(poi.doseLabel, projectedPoi.textX, projectedPoi.subtitleY, subtitlePaint)
            }
        }
    }
}
