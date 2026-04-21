package com.github.bocovp.geigergpx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.floor

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
    private var kernelSeries: List<KernelPoint> = emptyList()
    private var emptyMessage: String = "No track data"

    private data class KernelPoint(
        val t: Double,
        val mean: Double,
        val low: Double,
        val high: Double
    )

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

    private val leftPaddingPx = 80f //96f
    private val rightPaddingPx = 22f
    private val topPaddingPx = 24f
    private val bottomPaddingPx = 70f

    private var trackDurationSeconds = 0.0
    private var maxDoseValue = 1.0
    private var yAxisUnit = "μSv/h"
    private var xAxisUnit = "min"
    private var verticalTickStep = 0.2
    private var verticalTickCount = 5
    private var verticalAxisMaxValue = 1.0

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

    fun setPoints(
        points: List<TrackPoint>,
        cpsToUSvh: Double,
        recalculateVerticalAxis: Boolean = true
    ) {
        kernelSeries = emptyList()
        plotSegments.clear()
        yAxisUnit = if (kotlin.math.abs(cpsToUSvh - 1.0) < 1e-9) "cps" else "μSv/h"

        if (points.isEmpty()) {
            trackDurationSeconds = 0.0
            updateXAxisUnit()
            maxDoseValue = 1.0
            verticalTickStep = 0.2
            verticalTickCount = 5
            verticalAxisMaxValue = 1.0
            zoomX = 1f
            panFraction = 0f
            invalidate()
            return
        }

        var elapsedSeconds = 0.0
        for (p in points) {
            val seconds = p.seconds.coerceAtLeast(0.001)
            val ci = ConfidenceInterval(0.0, seconds, p.counts, false)
            val value = p.doseRate
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
        updateXAxisUnit()
        maxDoseValue = (plotSegments.maxOfOrNull { maxOf(it.value, it.ciHigh) } ?: 1.0).coerceAtLeast(0.1)
        if (recalculateVerticalAxis) {
            verticalTickStep = chooseVerticalTickStep(maxDoseValue)
            verticalTickCount = ceil(maxDoseValue / verticalTickStep).toInt()
            verticalAxisMaxValue = verticalTickStep * verticalTickCount
        }
        clampPan()
        invalidate()
    }

    fun setKernelSeries(
        relativeSeconds: DoubleArray,
        mean: DoubleArray,
        low: DoubleArray,
        high: DoubleArray,
        cpsToUSvh: Double,
        recalculateVerticalAxis: Boolean = true
    ) {
        plotSegments.clear()
        yAxisUnit = if (kotlin.math.abs(cpsToUSvh - 1.0) < 1e-9) "cps" else "μSv/h"
        val size = minOf(relativeSeconds.size, mean.size, low.size, high.size)
        kernelSeries = List(size) { idx ->
            KernelPoint(
                t = relativeSeconds[idx].coerceAtLeast(0.0),
                mean = mean[idx].coerceAtLeast(0.0),
                low = low[idx].coerceAtLeast(0.0),
                high = high[idx].coerceAtLeast(0.0)
            )
        }
        if (kernelSeries.isEmpty()) {
            trackDurationSeconds = 0.0
            updateXAxisUnit()
            maxDoseValue = 1.0
            verticalTickStep = 0.2
            verticalTickCount = 5
            verticalAxisMaxValue = 1.0
            zoomX = 1f
            panFraction = 0f
            invalidate()
            return
        }

        trackDurationSeconds = kernelSeries.last().t.coerceAtLeast(0.0)
        updateXAxisUnit()
        maxDoseValue = kernelSeries.maxOfOrNull { maxOf(it.mean, it.high) }?.coerceAtLeast(0.1) ?: 1.0
        if (recalculateVerticalAxis) {
            verticalTickStep = chooseVerticalTickStep(maxDoseValue)
            verticalTickCount = ceil(maxDoseValue / verticalTickStep).toInt()
            verticalAxisMaxValue = verticalTickStep * verticalTickCount
        }
        clampPan()
        invalidate()
    }

    fun setEmptyMessage(message: String) {
        emptyMessage = message
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

        if (plotSegments.isEmpty() && kernelSeries.isEmpty()) {
            canvas.drawText(emptyMessage, plotLeft + 16f, plotTop + 40f, textPaint)
            return
        }

        drawVerticalTicks(canvas, plotLeft, plotTop, plotBottom, plotRight)
        drawHorizontalTicks(canvas, plotLeft, plotBottom, plotTop, plotRight)
        drawHorizontalAxisLabel(canvas, plotLeft, plotBottom, plotRight)
        if (kernelSeries.isNotEmpty()) {
            drawKernelSeries(canvas, plotLeft, plotBottom, plotWidth, plotHeight)
        } else {
            drawSeries(canvas, plotLeft, plotBottom, plotWidth, plotHeight)
        }
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

    private fun drawKernelSeries(
        canvas: Canvas,
        plotLeft: Float,
        plotBottom: Float,
        plotWidth: Float,
        plotHeight: Float
    ) {
        if (trackDurationSeconds <= 0.0 || kernelSeries.isEmpty()) return
        val visibleDuration = trackDurationSeconds / zoomX
        val start = (trackDurationSeconds - visibleDuration) * panFraction
        val end = start + visibleDuration
        val points = kernelSeries.filter { it.t in start..end }
        if (points.size < 2) return

        val areaPath = Path()
        for (idx in points.indices) {
            val p = points[idx]
            val x = plotLeft + (((p.t - start) / visibleDuration).toFloat() * plotWidth)
            val y = toY(p.high, plotBottom, plotHeight)
            if (idx == 0) areaPath.moveTo(x, y) else areaPath.lineTo(x, y)
        }
        for (idx in points.size - 1 downTo 0) {
            val p = points[idx]
            val x = plotLeft + (((p.t - start) / visibleDuration).toFloat() * plotWidth)
            val y = toY(p.low, plotBottom, plotHeight)
            areaPath.lineTo(x, y)
        }
        areaPath.close()
        canvas.drawPath(areaPath, ciPaint)

        var prev = points.first()
        for (idx in 1 until points.size) {
            val curr = points[idx]
            val x1 = plotLeft + (((prev.t - start) / visibleDuration).toFloat() * plotWidth)
            val x2 = plotLeft + (((curr.t - start) / visibleDuration).toFloat() * plotWidth)
            canvas.drawLine(x1, toY(prev.mean, plotBottom, plotHeight), x2, toY(curr.mean, plotBottom, plotHeight), linePaint)
            prev = curr
        }
    }

    private fun drawVerticalTicks(
        canvas: Canvas,
        plotLeft: Float,
        plotTop: Float,
        plotBottom: Float,
        plotRight: Float
    ) {
        for (i in 0..verticalTickCount) {
            val ratio = i.toFloat() / verticalTickCount.toFloat()
            val y = plotBottom - ratio * (plotBottom - plotTop)
            val value = i * verticalTickStep
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            val dy = if (i == 0) 0f else 8f
            canvas.drawText(String.format(java.util.Locale.US, "%.2f", value), 8f, y + dy, textPaint)
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
        val stepSeconds = chooseHorizontalTickStepSeconds(visibleDuration)

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

    private fun drawHorizontalAxisLabel(
        canvas: Canvas,
        plotLeft: Float,
        plotBottom: Float,
        plotRight: Float
    ) {
        if (trackDurationSeconds <= 0.0) return

        val textWidth = textPaint.measureText(xAxisUnit)
        val x = plotRight - textWidth
        val y = plotBottom + 65f
        canvas.drawText(xAxisUnit, x, y, textPaint)
    }

    private fun chooseHorizontalTickStepSeconds(durationSeconds: Double): Double {
        val allowedStepsMinutes = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0, 120.0, 300.0, 600.0)
        val targetTickCount = 5.0
        val desiredStepMinutes = (durationSeconds / 60.0) / targetTickCount
        val chosenStepMinutes = allowedStepsMinutes.minByOrNull { kotlin.math.abs(it - desiredStepMinutes) }
            ?: allowedStepsMinutes.first()
        return chosenStepMinutes * 60.0
    }

    private fun chooseVerticalTickStep(maxValue: Double): Double {
        val allowedSteps = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
        val candidatesInRange = allowedSteps.filter { step ->
            val positiveTickCount = ceil(maxValue / step).toInt()
            positiveTickCount in 5..7
        }
        if (candidatesInRange.isNotEmpty()) {
            return candidatesInRange.minByOrNull { step ->
                val positiveTickCount = ceil(maxValue / step).toInt()
                kotlin.math.abs(positiveTickCount - 6)
            } ?: candidatesInRange.first()
        }

        return allowedSteps.minByOrNull { step ->
            val positiveTickCount = ceil(maxValue / step).toInt()
            val distanceToRange = when {
                positiveTickCount < 5 -> 5 - positiveTickCount
                positiveTickCount > 7 -> positiveTickCount - 7
                else -> 0
            }
            distanceToRange.toDouble()
        } ?: allowedSteps.first()
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

    private fun updateXAxisUnit() {
        xAxisUnit = if (trackDurationSeconds < 3600.0) "min" else "h:mm"
    }

    private fun toY(value: Double, bottom: Float, height: Float): Float {
        val ratio = (value.coerceAtLeast(0.0) / verticalAxisMaxValue).coerceIn(0.0, 1.0)
        return bottom - (ratio.toFloat() * height)
    }

    private fun plotWidth(): Float = (width.toFloat() - leftPaddingPx - rightPaddingPx).coerceAtLeast(0f)

    private fun clampPan() {
        panFraction = panFraction.coerceIn(0f, 1f)
    }
}
