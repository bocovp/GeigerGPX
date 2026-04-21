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

// Moved out of draw() — instances are reused across frames via layoutCache.
// Regular class (not data class) because equality/copy semantics are irrelevant
// and mutable fields are needed for in-place update.
private class PoiLayout {
    // Point and RectF are updated in-place each frame via set() — no re-allocation.
    val pixel = Point()
    val boundingRect = RectF()
    var textX = 0f
    var titleY = 0f
    var subtitleY = 0f
    // Text widths depend only on PoiMapItem content, not on the projection.
    // Stored here and refreshed only when pointsDirty is true.
    var titleWidth = 0f
    var subtitleWidth = 0f
    // Replaces the per-frame BooleanArray — reset at the start of each overlap pass.
    var overlapsText = false
}

class PoiOverlay(context: android.content.Context) : Overlay() {
    private val density = context.resources.displayMetrics.density

    // Setter sets the dirty flag so text widths are remeasured on the next draw.
    var points: List<PoiMapItem> = emptyList()
        set(value) {
            field = value
            pointsDirty = true
        }
    var minDose: Double = 0.0
    var maxDose: Double = 1.0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1.088f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 13.056f * density
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 10.88f * density
    }

    // FontMetrics never change for these paints — compute once at initialisation.
    // The original code called titlePaint.fontMetrics inside the per-POI loop,
    // allocating a new FontMetrics object for every POI on every frame.
    private val titleFontMetrics: Paint.FontMetrics = titlePaint.fontMetrics
    private val subtitleFontMetrics: Paint.FontMetrics = subtitlePaint.fontMetrics

    // Single GeoPoint reused for all toPixels() calls.
    // Replaces GeoPoint(poi.latitude, poi.longitude) allocated N times per frame.
    private val scratchGeoPoint = GeoPoint(0.0, 0.0)

    // Layout cache — sized to match points.size, rebuilt only when count changes.
    // Replaces the ArrayList<Pair<PoiMapItem, Point>>, the .map{} list of
    // ProjectedPoi objects, and the BooleanArray, all of which were allocated fresh
    // on every draw call.
    private var layoutCache: Array<PoiLayout> = emptyArray()
    private var pointsDirty = true

    override fun draw(canvas: Canvas, projection: Projection) {
        if (points.isEmpty()) return

        val n = points.size

        // --- Step 1: Resize cache only when point count changes ---------------
        // For a stable POI list this branch is never taken after the first draw.
        if (layoutCache.size != n) {
            layoutCache = Array(n) { PoiLayout() }
            pointsDirty = true
        }

        // --- Step 2: Remeasure text only when point content has changed -------
        // measureText() is moderately expensive; running it every frame for every
        // POI was wasteful because doseLabel and name don't change during panning.
        if (pointsDirty) {
            for (i in 0 until n) {
                val poi = points[i]
                layoutCache[i].titleWidth = titlePaint.measureText(poi.name)
                layoutCache[i].subtitleWidth = subtitlePaint.measureText(poi.doseLabel)
            }
            pointsDirty = false
        }

        // --- Step 3: Project lat/lon → screen pixels --------------------------
        // Must run every frame — the projection changes on every pan and zoom.
        // scratchGeoPoint is updated in-place; layout.pixel is a pre-allocated
        // Point updated in-place by toPixels().
        val screenRect: Rect = projection.intrinsicScreenRect
        var visibleCount = 0

        for (i in 0 until n) {
            val poi = points[i]
            val layout = layoutCache[i]
            scratchGeoPoint.setCoords(poi.latitude, poi.longitude)
            projection.toPixels(scratchGeoPoint, layout.pixel)
            if (screenRect.contains(layout.pixel.x, layout.pixel.y)) visibleCount++
        }

        // --- Step 4: Derive radius from visible count -------------------------
        val radius = when {
            visibleCount <= 20  -> 6.528f * density
            visibleCount <= 60  -> 5.44f * density
            visibleCount <= 120 -> 4.352f * density
            else                -> 3.264f * density
        }
        val textGap = radius + 3.264f * density
        val subtitleOffset = 9.792f * density

        // --- Step 5: Compute per-POI text positions and bounding rects --------
        // RectF.set() updates in-place — no new RectF allocated.
        // titleFontMetrics / subtitleFontMetrics are cached class fields.
        for (i in 0 until n) {
            val layout = layoutCache[i]
            val px = layout.pixel.x.toFloat()
            val py = layout.pixel.y.toFloat()

            layout.textX    = px + textGap
            layout.titleY   = py - 1.088f * density
            layout.subtitleY = layout.titleY + subtitleOffset

            val textWidth = maxOf(layout.titleWidth, layout.subtitleWidth)

            layout.boundingRect.set(
                minOf(px - radius,     layout.textX),
                minOf(
                    py - radius,
                    layout.titleY    + titleFontMetrics.ascent,
                    layout.subtitleY + subtitleFontMetrics.ascent
                ),
                maxOf(px + radius,     layout.textX + textWidth),
                maxOf(
                    py + radius,
                    layout.titleY    + titleFontMetrics.descent,
                    layout.subtitleY + subtitleFontMetrics.descent
                )
            )
        }

        // --- Step 6: Overlap detection ----------------------------------------
        // overlapsText lives in PoiLayout — replaces the per-frame BooleanArray.
        for (i in 0 until n) layoutCache[i].overlapsText = false
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (RectF.intersects(layoutCache[i].boundingRect, layoutCache[j].boundingRect)) {
                    layoutCache[i].overlapsText = true
                    layoutCache[j].overlapsText = true
                }
            }
        }

        // --- Step 7: Draw -----------------------------------------------------
        for (i in 0 until n) {
            val poi    = points[i]
            val layout = layoutCache[i]
            val px     = layout.pixel.x.toFloat()
            val py     = layout.pixel.y.toFloat()

            fillPaint.color = DoseColorScale.colorForDose(poi.doseRateForColor, minDose, maxDose)
            canvas.drawCircle(px, py, radius, fillPaint)
            canvas.drawCircle(px, py, radius, strokePaint)

            if (!layout.overlapsText) {
                canvas.drawText(poi.name,       layout.textX, layout.titleY,    titlePaint)
                canvas.drawText(poi.doseLabel,  layout.textX, layout.subtitleY, subtitlePaint)
            }
        }
    }
}