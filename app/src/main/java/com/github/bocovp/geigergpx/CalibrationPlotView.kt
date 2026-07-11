package com.github.bocovp.geigergpx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.log10
import kotlin.math.pow

class CalibrationPlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Point(val t: Double, val mainDb: Float, val lowDb: Float, val highDb: Float)

    private val points = ArrayDeque<Point>()
    private val pointsLock = Any()
    private val density = resources.displayMetrics.density
    private val windowSeconds = 3.0
    private val maxDb = 140f
    @Volatile private var startNs: Long = 0L
    private var thresholdDb: Float = 0f
    private var thresholdText: String = "threshold 0.0 dB"
    private val dbLabels = arrayOf("0", "35", "70", "105", "140")
    var onThresholdSelected: ((Float, Boolean) -> Unit)? = null

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = density }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 128, 128, 128); strokeWidth = density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 12f * density }
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 118, 210); strokeWidth = 0.75f * density; style = Paint.Style.STROKE }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 125, 50); strokeWidth = 0.5f * density; style = Paint.Style.STROKE }
    private val highPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 108, 0); strokeWidth = 0.5f * density; style = Paint.Style.STROKE }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 47, 47); strokeWidth = 1.25f * density; style = Paint.Style.STROKE }

    private val beeps = ArrayDeque<Long>()
    private val beepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
    private val left = 44f * density
    private val right = 12f * density
    private val top = 12f * density
    private val bottom = 30f * density

    fun setThresholdMagnitude(value: Float) {
        thresholdDb = toDb(value).coerceIn(0f, maxDb)
        thresholdText = "threshold %.1f dB".format(java.util.Locale.US, thresholdDb)
        invalidate()
    }

    fun addBeep(timestampNs: Long) {
        synchronized(pointsLock) { beeps.addLast(timestampNs) }
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(pointsLock) {
            points.clear()
            beeps.clear()
            startNs = 0L
        }
        postInvalidateOnAnimation()
    }

    fun addSamples(mains: FloatArray, lows: FloatArray, highs: FloatArray, timesNs: LongArray, count: Int) {
        if (count == 0) return

        synchronized(pointsLock) {
            if (startNs == 0L) startNs = timesNs[0]

            for (i in 0 until count) {
                val t = (timesNs[i] - startNs).toDouble() / 1_000_000_000.0
                points.addLast(Point(t, toDb(mains[i]), toDb(lows[i]), toDb(highs[i])))
            }

            val minT = points.last().t - windowSeconds
            while (points.isNotEmpty() && points.first().t < minT) {
                points.removeFirst()
            }
            val minNs = startNs + (minT * 1_000_000_000.0).toLong()
            while (beeps.isNotEmpty() && beeps.first() < minNs) {
                beeps.removeFirst()
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - left - right
        val h = height - top - bottom
        if (w <= 0f || h <= 0f) return

        synchronized(pointsLock) {

            // Use the snapshot for all drawing calculations to prevent concurrent modifications
            val now = points.lastOrNull()?.t ?: 0.0
            val minT = (now - windowSeconds).coerceAtLeast(0.0)


            canvas.drawLine(left, top, left, top + h, axisPaint)
            canvas.drawLine(left, top + h, left + w, top + h, axisPaint)
            for (i in 0..4) {
                val y = top + h - h * i / 4f
                canvas.drawLine(left, y, left + w, y, gridPaint)
                canvas.drawText(dbLabels[i], 4f * density, y + 4f * density, textPaint)
            }
            canvas.drawText("dB", 4f * density, top + 10f * density, textPaint)

            val ty = yFor(thresholdDb, h)
            canvas.drawLine(left, ty, left + w, ty, thresholdPaint)
            canvas.drawText(thresholdText, left + 6f * density, ty - 4f * density, textPaint)

            drawSeries(canvas, points, minT, w, h, mainPaint) { it.mainDb }
            //drawSeries(canvas, points, minT, w, h, lowPaint) { it.lowDb }
            //drawSeries(canvas, points, minT, w, h, highPaint) { it.highDb }

            if (startNs != 0L) {
                val beepRadius = 4f * density
                for (i in 0 until beeps.size) {
                    val timestampNs = beeps[i]
                    val t = (timestampNs - startNs).toDouble() / 1_000_000_000.0
                    val x = left + (((t - minT) / windowSeconds).toFloat().coerceIn(0f, 1f) * w)
                    canvas.drawCircle(x, top + beepRadius, beepRadius, beepPaint)
                }
            }
        }
    }

    private val seriesPath = android.graphics.Path()

    private inline fun drawSeries(canvas: Canvas, points: ArrayDeque<Point>, minT: Double, w: Float, h: Float, paint: Paint, selector: (Point) -> Float) {
        if (points.isEmpty()) return
        seriesPath.reset()
        var first = true
        for (i in 0 until points.size) {
            val point = points[i]
            val x = left + (((point.t - minT) / windowSeconds).toFloat().coerceIn(0f, 1f) * w)
            val y = yFor(selector(point), h)
            if (first) {
                seriesPath.moveTo(x, y)
                first = false
            } else {
                seriesPath.lineTo(x, y)
            }
        }
        canvas.drawPath(seriesPath, paint)
    }

    private fun yFor(db: Float, h: Float) = top + h - (db.coerceIn(0f, maxDb) / maxDb) * h

    override fun performClick(): Boolean {
        if (super.performClick()) return true
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val h = height - top - bottom
                if (h > 0f) {
                    val db = ((top + h - event.y) / h * maxDb).coerceIn(0f, maxDb)
                    thresholdDb = db
                    thresholdText = "threshold %.1f dB".format(java.util.Locale.US, db)
                    val isFinished = event.actionMasked == MotionEvent.ACTION_UP
                    if (isFinished) {
                        performClick()
                    }
                    onThresholdSelected?.invoke(fromDb(db), isFinished)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toDb(value: Float): Float = if (value > 0f && value.isFinite()) (10.0 * log10(value.toDouble() / 100.0)).toFloat().coerceAtLeast(0f) else 0f
    private fun fromDb(value: Float): Float = (10.0.pow(value / 10.0) * 100.0).toFloat()
}
