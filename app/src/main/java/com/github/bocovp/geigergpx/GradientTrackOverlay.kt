package com.github.bocovp.geigergpx

import android.graphics.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

class GradientTrackOverlay(context: android.content.Context) : Overlay() {
    private val density = context.resources.displayMetrics.density

    // We store our own copy of points to draw
    var points: List<TrackPoint> = emptyList()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    val gp1 = GeoPoint(0.0, 0.0)
    val gp2 = GeoPoint(0.0, 0.0)


    private val paint = Paint().apply {
        strokeWidth = 5.44f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val badPointPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val orphanGoodPointPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (points.size < 2) return

        val p1Pixels = Point()
        val p2Pixels = Point()

        for (i in 0 until points.size - 1) {
            val pt1 = points[i]
            val pt2 = points[i + 1]
            if (pt1.badCoordinates || pt2.badCoordinates) continue

            gp1.setCoords(pt1.latitude, pt1.longitude)
            gp2.setCoords(pt2.latitude, pt2.longitude)
            projection.toPixels(gp1, p1Pixels)
            projection.toPixels(gp2, p2Pixels)

            paint.shader = LinearGradient(
                p1Pixels.x.toFloat(), p1Pixels.y.toFloat(),
                p2Pixels.x.toFloat(), p2Pixels.y.toFloat(),
                DoseColorScale.colorForDose(pt1.doseRate, minDose, maxDose),
                DoseColorScale.colorForDose(pt2.doseRate, minDose, maxDose),
                Shader.TileMode.CLAMP
            )

            canvas.drawLine(
                p1Pixels.x.toFloat(), p1Pixels.y.toFloat(),
                p2Pixels.x.toFloat(), p2Pixels.y.toFloat(),
                paint
            )
        }

        paint.shader = null
        var i = 0

        while (i < points.size) {
            val currentPoint = points[i]
            if (!currentPoint.badCoordinates) {
                val hasPreviousGoodNeighbor = points.getOrNull(i - 1)?.badCoordinates == false
                val hasNextGoodNeighbor = points.getOrNull(i + 1)?.badCoordinates == false
                if (!hasPreviousGoodNeighbor && !hasNextGoodNeighbor) {
                    gp1.setCoords(currentPoint.latitude, currentPoint.longitude)
                    projection.toPixels(gp1, p1Pixels)
                    orphanGoodPointPaint.color = DoseColorScale.colorForDose(currentPoint.doseRate, minDose, maxDose)
                    canvas.drawCircle(p1Pixels.x.toFloat(), p1Pixels.y.toFloat(), paint.strokeWidth / 2f, orphanGoodPointPaint)
                }
                i += 1
                continue
            }

            val runStart = i
            while (i < points.size && points[i].badCoordinates) i += 1
            val runEndExclusive = i

            val previousGood = points.getOrNull(runStart - 1)
            val nextGood = points.getOrNull(runEndExclusive)
            if (previousGood == null || nextGood == null ||
                previousGood.badCoordinates || nextGood.badCoordinates
            ) {
                continue
            }

            projection.toPixels(GeoPoint(previousGood.latitude, previousGood.longitude), p1Pixels)
            projection.toPixels(GeoPoint(nextGood.latitude, nextGood.longitude), p2Pixels)

            val badPointsCount = runEndExclusive - runStart
            for (badPointIndex in 0 until badPointsCount) {
                val pt = points[runStart + badPointIndex]
                val ratio = (badPointIndex + 1).toFloat() / (badPointsCount + 1).toFloat()
                val x = p1Pixels.x + (p2Pixels.x - p1Pixels.x) * ratio
                val y = p1Pixels.y + (p2Pixels.y - p1Pixels.y) * ratio

                badPointPaint.color = DoseColorScale.colorForDose(pt.doseRate, minDose, maxDose)
                canvas.drawCircle(x, y, paint.strokeWidth / 2f, badPointPaint)
            }
        }
    }

}
