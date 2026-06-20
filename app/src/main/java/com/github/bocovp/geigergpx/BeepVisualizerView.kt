package com.github.bocovp.geigergpx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class BeepVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DURATION_MS = 3000L
        private const val BIN_COUNT = 3
        private val offsets = intArrayOf(0, 1, -1)
        private const val MAX_BEEPS = 2000 // Circular buffer size
    }

    // Zero-allocation primitive arrays
    private val beepTimes = LongArray(MAX_BEEPS)

    private val binTimes = LongArray(BIN_COUNT)
    private val yOffsets = FloatArray(MAX_BEEPS)

    private var head = 0
    private var tail = 0
    private var isAnimating = false

    private val radius = 3.5f * resources.displayMetrics.density

    private val paint = Paint().apply {
        val typedValue = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        color = if (resolved) typedValue.data else android.graphics.Color.GRAY
        //color =  ContextCompat.getColor(context, R.color.purple_500)
        style = Paint.Style.STROKE
        strokeWidth = radius * 2f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        alpha = 180 // Slight transparency for overlapping points
    }

    private val pointCoords = FloatArray(MAX_BEEPS * 2)

    fun addBeep(timeMillis: Long) {
        val nextHead = (head + 1) % MAX_BEEPS
        if (nextHead == tail) {
            // Buffer full, drop oldest point to prevent overflow crash
            tail = (tail + 1) % MAX_BEEPS
        }

        var maxDist = 0.0f
        var bestInd = 0
        for (i in 0 until BIN_COUNT) {
            if (binTimes[i] == 0L) {
                bestInd = i
                break
            }
            val curDist = intervalToDist((timeMillis-binTimes[i]).coerceAtLeast(0L))
            if (curDist >= 3.0f * radius) {
                bestInd = i
                break
            }
            if (curDist > maxDist) {
                maxDist = curDist
                bestInd = i
            }
        }

        binTimes[bestInd] = timeMillis
        beepTimes[head] = timeMillis
        yOffsets[head] = 3.0f * radius * offsets[bestInd].toFloat()

        head = nextHead

        if (!isAnimating) {
            isAnimating = true
            postInvalidateOnAnimation()
        }
    }

    private fun intervalToDist (interval: Long): Float{
        return (interval.toFloat() / DURATION_MS) * width.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (head == tail) {
            isAnimating = false
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val w = width.toFloat()
        val h = height.toFloat()
        val hHalf = h / 2.0f
        var hasActiveBeeps = false

        // Clean up expired beeps from the tail first
        while (tail != head && (now - beepTimes[tail]) > DURATION_MS) {
            tail = (tail + 1) % MAX_BEEPS
        }
        var i = tail
        var pointCount = 0
        while (i != head) {
            val age = (now - beepTimes[i]).coerceAtLeast(0L)
            val dist = intervalToDist(age)
            hasActiveBeeps = true
            // Calculate X: starts at 'w' (right), ends at 0 (left)
            val x = w - dist
            val y = hHalf + yOffsets[i]
            pointCoords[pointCount * 2] = x
            pointCoords[pointCount * 2 + 1] = y
            pointCount++
            i = (i + 1) % MAX_BEEPS
        }

        if (pointCount > 0) {
            canvas.drawPoints(pointCoords, 0, pointCount * 2, paint)
        }

        // Only keep looping the animation if points are actively moving
        if (hasActiveBeeps) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }
}