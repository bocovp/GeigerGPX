package com.example.geigergpx

import android.graphics.*
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import org.osmdroid.util.PointL

class HeatmapOverlay(
    var doseCoefficient: Double = 1.0
) : Overlay() {

    // Data source
    var tracks: List<MapTrack> = emptyList()
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    // Grid configuration
    private val gridSizePixels = 64 // Size of grid squares in pixels
    private var cachedBitmap: Bitmap? = null
    private var lastMatrix = Matrix()

    // Reusing color logic
    private val R1 = 0x00; private val G1 = 0xC8; private val B1 = 0x53 // Green
    private val R2 = 0xFF; private val G2 = 0xEB; private val B2 = 0x3B // Yellow
    private val R3 = 0xD5; private val G3 = 0x00; private val B3 = 0x00 // Red

    private val paint = Paint().apply {
        isFilterBitmap = true // Enables Bilinear Interpolation
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (tracks.isEmpty()) return

        // 1. Calculate Grid Dimensions based on current view
        val viewWidth = canvas.width
        val viewHeight = canvas.height

        if (viewWidth <= 0 || viewHeight <= 0) return

        val screenRect = projection.intrinsicScreenRect
        val worldTopLeft = PointL()
        projection.toMercatorPixels(screenRect.left, screenRect.top, worldTopLeft)

        // 2. Calculate the "Sub-grid" offset (the phase of the grid)
        // This tells us how many pixels the grid is shifted within the first cell
        val offsetX = Math.floorMod(worldTopLeft.x, gridSizePixels.toLong()).toInt()
        val offsetY = Math.floorMod(worldTopLeft.y, gridSizePixels.toLong()).toInt()

        // 3. Determine grid dimensions (add extra cells to cover edges during shifts)
        val cols = ceil((viewWidth + offsetX).toFloat() / gridSizePixels).toInt() + 1
        val rows = ceil((viewHeight + offsetY).toFloat() / gridSizePixels).toInt() + 1

        // 4. Generate the Bitmap (passing the world offset for consistent binning)
        val gridBitmap = generateHeatmapBitmap(cols, rows, projection, offsetX, offsetY)

        // 5. Draw the bitmap shifted by the offset to "anchor" it to the map surface
        val drawLeft = -offsetX.toFloat()
        val drawTop = -offsetY.toFloat()
        val drawRight = drawLeft + (cols * gridSizePixels)
        val drawBottom = drawTop + (rows * gridSizePixels)

        val destRect = RectF(drawLeft, drawTop, drawRight, drawBottom)
        canvas.drawBitmap(gridBitmap, null, destRect, paint)
    }

    private fun generateHeatmapBitmap(
        cols: Int,
        rows: Int,
        projection: Projection,
        offsetX: Int,
        offsetY: Int
    ): Bitmap {
        // Arrays to hold grid sums
        val sumCounts = IntArray(cols * rows)
        val sumSeconds = DoubleArray(cols * rows)

        val pPixels = Point()
        val geoPoint = org.osmdroid.util.GeoPoint(0.0, 0.0)

        // A. Binning Phase
        // Transform and bucket every point from every track
        for (track in tracks) {
            for (sample in track.points) {
                // Skip if count is 0 (as per requirements)
                if (sample.counts == 0) continue

                // Project Lat/Lon to Screen Pixels
                // Note: reuse GeoPoint to reduce allocation in tight loops if possible
                geoPoint.setCoords(sample.latitude, sample.longitude)
                projection.toPixels(geoPoint, pPixels)

                val col = (pPixels.x + offsetX) / gridSizePixels
                val row = (pPixels.y + offsetY) / gridSizePixels

                // Check bounds
                if (col in 0 until cols && row in 0 until rows) {
                    val index = row * cols + col
                    sumCounts[index] += sample.counts
                    sumSeconds[index] += sample.seconds
                }
            }
        }

        // B. Bitmap Generation Phase
        val pixels = IntArray(cols * rows)

        for (i in 0 until cols * rows) {
            val totalCounts = sumCounts[i]
            val totalSeconds = sumSeconds[i]

            if (totalCounts == 0) {
                // Empty grid cell -> Transparent
                pixels[i] = 0
                continue
            }

            // Formula 1: Calculate CPS
            // Avoid division by zero
            val squareCps = if (totalSeconds > 0.0001) totalCounts / totalSeconds else 0.0

            // Formula 2: Calculate Dose Rate
            val squareDoseRate = squareCps * doseCoefficient

            // Formula 3: Calculate Alpha
            val squareAlpha = min(255, 255*totalCounts / 20)

            // Determine color
            val colorInt = colorForDose(squareDoseRate)

            // Combine color with calculated alpha
            // (Remove original alpha from colorInt and apply our calculated alpha)
            val r = Color.red(colorInt)
            val g = Color.green(colorInt)
            val b = Color.blue(colorInt)

            pixels[i] = Color.argb(squareAlpha, r, g, b)
        }

        // Create immutable bitmap from pixels
        return Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888)
    }

    private fun colorForDose(value: Double): Int {
        if (abs(value) < 1e-5) return Color.GRAY // Fallback

        val normalized = if (maxDose > minDose) (value - minDose) / (maxDose - minDose) else 0.0
        val t = normalized.coerceIn(0.0, 1.0)

        val r: Int; val g: Int; val b: Int

        if (t < 0.5f) {
            val ratio = t * 2f
            r = (R1 + ratio * (R2 - R1)).toInt()
            g = (G1 + ratio * (G2 - G1)).toInt()
            b = (B1 + ratio * (B2 - B1)).toInt()
        } else {
            val ratio = (t - 0.5f) * 2f
            r = (R2 + ratio * (R3 - R2)).toInt()
            g = (G2 + ratio * (G3 - G2)).toInt()
            b = (B2 + ratio * (B3 - B2)).toInt()
        }
        return Color.rgb(r, g, b)
    }
}