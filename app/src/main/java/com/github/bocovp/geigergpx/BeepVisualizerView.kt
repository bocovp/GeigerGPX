package com.github.bocovp.geigergpx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class BeepVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DURATION_MS = 2000L
        private const val MAX_BEEPS = 2000 // Circular buffer size
    }

    // Zero-allocation primitive arrays
    private val beepTimes = LongArray(MAX_BEEPS)
    private val beepY = FloatArray(MAX_BEEPS)

    private var head = 0
    private var tail = 0
    private var isAnimating = false

    private val paint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 180 // Slight transparency for overlapping points
    }

    fun addBeep(timeMillis: Long) {
        val nextHead = (head + 1) % MAX_BEEPS
        if (nextHead == tail) {
            // Buffer full, drop oldest point to prevent overflow crash
            tail = (tail + 1) % MAX_BEEPS
        }

        beepTimes[head] = timeMillis
        // Assign a random Y-coordinate (0.0 to 1.0) to create a scintillator/waterfall effect
        beepY[head] = 0.5f //Math.random().toFloat()
        head = nextHead

        if (!isAnimating) {
            isAnimating = true
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (head == tail) {
            isAnimating = false
            return
        }

        val now = System.currentTimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 4f * resources.displayMetrics.density
        var hasActiveBeeps = false

        var i = tail
        while (i != head) {
            val age = now - beepTimes[i]
            if (age > DURATION_MS) {
                // Point expired, advance tail
                tail = (i + 1) % MAX_BEEPS
            } else {
                hasActiveBeeps = true
                // Calculate X: starts at 'w' (right), ends at 0 (left)
                val x = w - (age.toFloat() / DURATION_MS) * w
                val y = beepY[i] * h
                canvas.drawCircle(x, y, radius, paint)
            }
            i = (i + 1) % MAX_BEEPS
        }

        // Only keep looping the animation if points are actively moving
        if (hasActiveBeeps) {
            postInvalidateOnAnimation()
        } else {
            isAnimating = false
        }
    }
}