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
    private val density = resources.displayMetrics.density
    private val windowSeconds = 3.0
    private val maxDb = 140f
    private var startNs: Long = 0L
    private var thresholdDb: Float = 0f

    var onThresholdSelected: ((Float) -> Unit)? = null

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = density }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 128, 128, 128); strokeWidth = density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 12f * density }
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 118, 210); strokeWidth = 2f * density; style = Paint.Style.STROKE }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 125, 50); strokeWidth = 1.4f * density; style = Paint.Style.STROKE }
    private val highPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 108, 0); strokeWidth = 1.4f * density; style = Paint.Style.STROKE }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 47, 47); strokeWidth = 1.6f * density; style = Paint.Style.STROKE }

    private val left = 44f * density
    private val right = 12f * density
    private val top = 12f * density
    private val bottom = 30f * density

    fun setThresholdMagnitude(value: Float) {
        thresholdDb = toDb(value).coerceIn(0f, maxDb)
        invalidate()
    }

    fun addSample(main: Float, low: Float, high: Float, timestampNs: Long) {
        if (startNs == 0L) startNs = timestampNs
        val t = (timestampNs - startNs).toDouble() / 1_000_000_000.0
        points.addLast(Point(t, toDb(main), toDb(low), toDb(high)))
        val minT = t - windowSeconds
        while (points.isNotEmpty() && points.first().t < minT) points.removeFirst()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - left - right
        val h = height - top - bottom
        if (w <= 0f || h <= 0f) return
        val now = points.lastOrNull()?.t ?: 0.0
        val minT = (now - windowSeconds).coerceAtLeast(0.0)

        canvas.drawLine(left, top, left, top + h, axisPaint)
        canvas.drawLine(left, top + h, left + w, top + h, axisPaint)
        for (i in 0..4) {
            val y = top + h - h * i / 4f
            val db = maxDb * i / 4f
            canvas.drawLine(left, y, left + w, y, gridPaint)
            canvas.drawText("%.0f".format(db), 4f * density, y + 4f * density, textPaint)
        }
        canvas.drawText("dB", 4f * density, top + 10f * density, textPaint)

        val ty = yFor(thresholdDb, h)
        canvas.drawLine(left, ty, left + w, ty, thresholdPaint)
        canvas.drawText("threshold %.1f dB".format(thresholdDb), left + 6f * density, ty - 4f * density, textPaint)

        drawSeries(canvas, points.map { it.t to it.lowDb }, minT, w, h, lowPaint)
        drawSeries(canvas, points.map { it.t to it.highDb }, minT, w, h, highPaint)
        drawSeries(canvas, points.map { it.t to it.mainDb }, minT, w, h, mainPaint)
    }

    private fun drawSeries(canvas: Canvas, values: List<Pair<Double, Float>>, minT: Double, w: Float, h: Float, paint: Paint) {
        var lastX = 0f; var lastY = 0f; var haveLast = false
        values.forEach { (t, db) ->
            val x = left + (((t - minT) / windowSeconds).toFloat().coerceIn(0f, 1f) * w)
            val y = yFor(db, h)
            if (haveLast) canvas.drawLine(lastX, lastY, x, y, paint)
            lastX = x; lastY = y; haveLast = true
        }
    }

    private fun yFor(db: Float, h: Float) = top + h - (db.coerceIn(0f, maxDb) / maxDb) * h

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val h = height - top - bottom
                if (h > 0f) {
                    val db = ((top + h - event.y) / h * maxDb).coerceIn(0f, maxDb)
                    thresholdDb = db
                    onThresholdSelected?.invoke(fromDb(db))
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    private fun toDb(value: Float): Float = if (value > 0f && value.isFinite()) (10.0 * log10(value.toDouble() / 100.0)).toFloat().coerceAtLeast(0f) else 0f
    private fun fromDb(value: Float): Float = (10.0.pow(value / 10.0) * 100.0).toFloat()
}
