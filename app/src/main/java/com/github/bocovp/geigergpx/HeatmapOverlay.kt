package com.github.bocovp.geigergpx

import android.graphics.*
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.min
import org.osmdroid.util.PointL

class HeatmapOverlay(
    var doseCoefficient: Double = 1.0
) : Overlay() {

    // Data source
    var tracks: List<MapTrack> = emptyList()
        set(value) {
            field = value
            tracksVersion++
            cachedRaster = null
        }
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    // Grid configuration
    private val gridSizePixels = 64 // Size of grid squares in pixels

    data class RasterResult(
        val bitmap: Bitmap,
        val offsetX: Int,
        val offsetY: Int,
        val cols: Int,
        val rows: Int,
        val maxDose: Double
    )

    private var tracksVersion: Long = 0
    private var cachedRaster: RasterResult? = null
    private var cachedKey: String? = null

    private val paint = Paint().apply {
        isFilterBitmap = true // Enables Bilinear Interpolation
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (tracks.isEmpty()) return

        val viewWidth = canvas.width
        val viewHeight = canvas.height

        if (viewWidth <= 0 || viewHeight <= 0) return

        val raster = ensureRaster(projection, viewWidth, viewHeight) ?: return
        val drawLeft = -raster.offsetX.toFloat()
        val drawTop = -raster.offsetY.toFloat()
        val drawRight = drawLeft + (raster.cols * gridSizePixels)
        val drawBottom = drawTop + (raster.rows * gridSizePixels)

        val destRect = RectF(drawLeft, drawTop, drawRight, drawBottom)
        canvas.drawBitmap(raster.bitmap, null, destRect, paint)
    }

    fun refreshRaster(
        projection: Projection,
        viewWidth: Int,
        viewHeight: Int
    ): Double? {
        val raster = ensureRaster(projection, viewWidth, viewHeight) ?: return null
        minDose = 0.0
        maxDose = raster.maxDose
        return raster.maxDose
    }

    private fun ensureRaster(
        projection: Projection,
        viewWidth: Int,
        viewHeight: Int
    ): RasterResult? {
        if (tracks.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return null

        val screenRect = projection.intrinsicScreenRect
        val worldTopLeft = PointL()
        projection.toMercatorPixels(screenRect.left, screenRect.top, worldTopLeft)

        val offsetX = Math.floorMod(worldTopLeft.x, gridSizePixels.toLong()).toInt()
        val offsetY = Math.floorMod(worldTopLeft.y, gridSizePixels.toLong()).toInt()

        val cols = ceil((viewWidth + offsetX).toFloat() / gridSizePixels).toInt() + 1
        val rows = ceil((viewHeight + offsetY).toFloat() / gridSizePixels).toInt() + 1
        val gridX = Math.floorDiv(worldTopLeft.x, gridSizePixels.toLong())
        val gridY = Math.floorDiv(worldTopLeft.y, gridSizePixels.toLong())
        val key = "$tracksVersion|$doseCoefficient|$gridX|$gridY|$viewWidth|$viewHeight|$cols|$rows"
        if (cachedKey == key) {
            return cachedRaster?.copy(offsetX = offsetX, offsetY = offsetY)
        }

        val cellCount = cols * rows

        val sumCounts = IntArray(cellCount)
        val sumSeconds = DoubleArray(cellCount)

        val pPixels = Point()
        val geoPoint = org.osmdroid.util.GeoPoint(0.0, 0.0)

        for (track in tracks) {
            for (pt in track.points) {
                if (pt.counts == 0 || pt.badCoordinates) continue

                geoPoint.setCoords(pt.latitude, pt.longitude)
                projection.toPixels(geoPoint, pPixels)

                val col = (pPixels.x + offsetX) / gridSizePixels
                val row = (pPixels.y + offsetY) / gridSizePixels
                if (col !in 0 until cols || row !in 0 until rows) continue

                val index = row * cols + col
                sumCounts[index] += pt.counts
                sumSeconds[index] += pt.seconds
            }
        }

        var maxBinnedDose = Double.NEGATIVE_INFINITY

        for (i in 0 until cellCount) {
            val totalCounts = sumCounts[i]
            if (totalCounts == 0) continue

            val totalSeconds = sumSeconds[i]
            val squareCps = if (totalSeconds > 0.0001) totalCounts / totalSeconds else 0.0
            val squareDoseRate = squareCps * doseCoefficient

            if (squareDoseRate > maxBinnedDose) maxBinnedDose = squareDoseRate
        }

        if (!maxBinnedDose.isFinite()) return null

        val pixels = IntArray(cellCount)
        val colorMaxDose = if (
            maxBinnedDose < DoseColorScale.MIN_NONZERO_MAX_DOSE ||
            maxBinnedDose > DoseColorScale.DEFAULT_MAX_DOSE
        ) {
            DoseColorScale.DEFAULT_MAX_DOSE
        } else {
            maxBinnedDose
        }
        minDose = 0.0
        maxDose = colorMaxDose

        for (i in 0 until cellCount) {
            val totalCounts = sumCounts[i]
            if (totalCounts == 0) {
                pixels[i] = 0
                continue
            }
            val totalSeconds = sumSeconds[i]
            val squareCps = if (totalSeconds > 0.0001) totalCounts / totalSeconds else 0.0
            val squareDoseRate = squareCps * doseCoefficient
            val squareAlpha = min(255, 255 * totalCounts / 20)
            val colorInt = DoseColorScale.colorForDose(squareDoseRate, minDose, maxDose)
            pixels[i] = Color.argb(squareAlpha, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
        }

        val raster = RasterResult(
            bitmap = Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888),
            offsetX = offsetX,
            offsetY = offsetY,
            cols = cols,
            rows = rows,
            maxDose = colorMaxDose
        )
        cachedRaster = raster
        cachedKey = key
        return raster
    }
}
