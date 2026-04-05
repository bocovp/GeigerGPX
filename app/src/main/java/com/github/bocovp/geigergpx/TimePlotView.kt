package com.github.bocovp.geigergpx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class TimePlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class PlotSegment(
        val startSeconds: Double,
        val endSeconds: Double,
        val value: Double,
        val ciLow: Double,
        val ciHigh: Double
    )

    private val plotSegments = mutableListOf<PlotSegment>()

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 118, 210)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val ciPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 140, 140, 140)
        style = Paint.Style.FILL
    }

    private val leftPaddingPx = 96f
    private val rightPaddingPx = 22f
    private val topPaddingPx = 24f
    private val bottomPaddingPx = 70f

    private var trackDurationSeconds = 0.0
    private var maxDoseValue = 1.0
    private var yAxisUnit = "μSv/h"

    private var zoomX = 1f
    private var panFraction = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomX = (zoomX * detector.scaleFactor).coerceIn(1f, 30f)
            clampPan()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (plotWidth() <= 0f || zoomX <= 1f) {
                return false
            }
            panFraction += (distanceX / plotWidth()) * (1f / zoomX)
            clampPan()
            invalidate()
            return true
        }
    })

    init {
        refreshAxisColors()
    }

    fun setPoints(points: List<TrackPoint>, cpsToUSvh: Double) {
        val samples = points.map {
            TrackSample(
                latitude = it.latitude,
                longitude = it.longitude,
                doseRate = it.cps * cpsToUSvh,
                counts = it.counts,
                seconds = it.seconds
            )
        }
        setSamples(samples, cpsToUSvh)
    }

    fun setSamples(samples: List<TrackSample>, cpsToUSvh: Double) {
        plotSegments.clear()
        yAxisUnit = if (kotlin.math.abs(cpsToUSvh - 1.0) < 1e-9) "cps" else "μSv/h"

        if (samples.isEmpty()) {
            trackDurationSeconds = 0.0
            maxDoseValue = 1.0
            zoomX = 1f
            panFraction = 0f
            invalidate()
            return
        }

        var elapsedSeconds = 0.0
        for (sample in samples) {
            val seconds = sample.seconds.coerceAtLeast(0.001)
            val ci = ConfidenceInterval(0.0, seconds, sample.counts, false)
            val value = sample.doseRate
            plotSegments += PlotSegment(
                startSeconds = elapsedSeconds,
                endSeconds = elapsedSeconds + seconds,
                value = value,
                ciLow = ci.lowBound.coerceAtLeast(0.0) * cpsToUSvh,
                ciHigh = ci.highBound.coerceAtLeast(0.0) * cpsToUSvh
            )
            elapsedSeconds += seconds
        }

        trackDurationSeconds = elapsedSeconds
        maxDoseValue = (plotSegments.maxOfOrNull { maxOf(it.value, it.ciHigh) } ?: 1.0).coerceAtLeast(0.1)
        clampPan()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        return scaled || gestured || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        refreshAxisColors()

        val plotLeft = leftPaddingPx
        val plotTop = topPaddingPx
        val plotRight = width.toFloat() - rightPaddingPx
        val plotBottom = height.toFloat() - bottomPaddingPx
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop
        if (plotWidth <= 0f || plotHeight <= 0f) return

        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)

        if (plotSegments.isEmpty()) {
            canvas.drawText("No track data", plotLeft + 16f, plotTop + 40f, textPaint)
            return
        }

        drawVerticalTicks(canvas, plotLeft, plotTop, plotBottom, plotRight)
        drawHorizontalTicks(canvas, plotLeft, plotBottom, plotTop, plotRight)
        drawSeries(canvas, plotLeft, plotBottom, plotWidth, plotHeight)
    }

    private fun refreshAxisColors() {
        val baseTextColor = resolveTextPrimaryColor()
        axisPaint.color = ColorUtils.blendARGB(baseTextColor, Color.WHITE, 0.10f)
        textPaint.color = ColorUtils.blendARGB(baseTextColor, Color.WHITE, 0.20f)
        gridPaint.color = ColorUtils.setAlphaComponent(textPaint.color, 110)
    }

    private fun resolveTextPrimaryColor(): Int {
        val typedArray = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            typedArray.getColor(0, Color.DKGRAY)
        } finally {
            typedArray.recycle()
        }
    }

    private fun drawSeries(
        canvas: Canvas,
        plotLeft: Float,
        plotBottom: Float,
        plotWidth: Float,
        plotHeight: Float
    ) {
        if (trackDurationSeconds <= 0.0) return

        val visibleDuration = trackDurationSeconds / zoomX
        val start = (trackDurationSeconds - visibleDuration) * panFraction
        val end = start + visibleDuration

        for (segment in plotSegments) {
            val segmentStart = maxOf(segment.startSeconds, start)
            val segmentEnd = minOf(segment.endSeconds, end)
            if (segmentEnd <= segmentStart) continue

            val leftX = plotLeft + (((segmentStart - start) / visibleDuration).toFloat() * plotWidth)
            val rightX = plotLeft + (((segmentEnd - start) / visibleDuration).toFloat() * plotWidth)

            val lineY = toY(segment.value, plotBottom, plotHeight)
            val ciLowY = toY(segment.ciLow, plotBottom, plotHeight)
            val ciHighY = toY(segment.ciHigh, plotBottom, plotHeight)

            canvas.drawRect(leftX, ciHighY, rightX, ciLowY, ciPaint)
            canvas.drawLine(leftX, lineY, rightX, lineY, linePaint)

            if (segment.endSeconds in start..end) {
                val stepX = plotLeft + (((segment.endSeconds - start) / visibleDuration).toFloat() * plotWidth)
                val nextValue = nextSegmentValue(segment.endSeconds)
                if (nextValue != null) {
                    val nextY = toY(nextValue, plotBottom, plotHeight)
                    canvas.drawLine(stepX, lineY, stepX, nextY, linePaint)
                }
            }
        }
    }

    private fun nextSegmentValue(segmentStart: Double): Double? {
        return plotSegments.firstOrNull { kotlin.math.abs(it.startSeconds - segmentStart) < 1e-9 }?.value
    }

    private fun drawVerticalTicks(
        canvas: Canvas,
        plotLeft: Float,
        plotTop: Float,
        plotBottom: Float,
        plotRight: Float
    ) {
        val tickCount = 5
        for (i in 0..tickCount) {
            val ratio = i.toFloat() / tickCount.toFloat()
            val y = plotBottom - ratio * (plotBottom - plotTop)
            val value = ratio * maxDoseValue
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText(String.format("%.2f", value), 8f, y + 8f, textPaint)
        }
        canvas.drawText(yAxisUnit, plotLeft + 8f, plotTop + 28f, textPaint)
    }

    private fun drawHorizontalTicks(
        canvas: Canvas,
        plotLeft: Float,
        plotBottom: Float,
        plotTop: Float,
        plotRight: Float
    ) {
        if (trackDurationSeconds <= 0.0) return

        val visibleDuration = trackDurationSeconds / zoomX
        val start = (trackDurationSeconds - visibleDuration) * panFraction
        val end = start + visibleDuration
        val stepSeconds = chooseTickStepSeconds(visibleDuration)

        var tick = floor(start / stepSeconds) * stepSeconds
        while (tick <= end + 0.0001) {
            val x = plotLeft + (((tick - start) / visibleDuration).toFloat() * (plotRight - plotLeft))
            if (x in plotLeft..plotRight) {
                canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
                canvas.drawText(formatTickLabel(tick), x - 24f, plotBottom + 34f, textPaint)
            }
            tick += stepSeconds
        }
    }

    private fun chooseTickStepSeconds(duration: Double): Double {
        val rough = (duration / 6.0).coerceAtLeast(1.0)
        val magnitude = 10.0.pow(floor(log10(rough)))
        val normalized = rough / magnitude
        val snapped = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return snapped * magnitude
    }

    private fun formatTickLabel(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).toInt()
        val minutes = totalMinutes % 60
        return if (trackDurationSeconds < 3600.0) {
            String.format("%02d", totalMinutes)
        } else {
            val hours = totalMinutes / 60
            String.format("%d:%02d", hours, minutes)
        }
    }

    private fun toY(value: Double, bottom: Float, height: Float): Float {
        val ratio = (value.coerceAtLeast(0.0) / maxDoseValue).coerceIn(0.0, 1.0)
        return bottom - (ratio.toFloat() * height)
    }

    private fun plotWidth(): Float = (width.toFloat() - leftPaddingPx - rightPaddingPx).coerceAtLeast(0f)

    private fun clampPan() {
        panFraction = panFraction.coerceIn(0f, 1f)
    }
}
