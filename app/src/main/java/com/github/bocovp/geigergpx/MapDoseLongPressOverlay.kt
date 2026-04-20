package com.github.bocovp.geigergpx

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

class MapDoseLongPressOverlay(
    private val onLongPressPositionChanged: (x: Float, y: Float) -> Unit,
    private val onLongPressFinished: () -> Unit
) : Overlay() {
    var longPressEnabled: Boolean = true

    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var touchSlop = 0f
    private var longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var currentMapView: MapView? = null

    private val longPressRunnable = Runnable {
        val mapView = currentMapView ?: return@Runnable
        if (!longPressEnabled) return@Runnable
        longPressTriggered = true
        mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPressPositionChanged(downX, downY)
    }

    override fun onTouchEvent(event: MotionEvent?, mapView: MapView?): Boolean {
        if (!longPressEnabled || event == null || mapView == null) return false
        currentMapView = mapView

        if (touchSlop == 0f) {
            val config = ViewConfiguration.get(mapView.context)
            touchSlop = config.scaledTouchSlop.toFloat()
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
        }

        var consumeEvent = false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mapView.removeCallbacks(longPressRunnable)
                longPressTriggered = false
                downX = event.x
                downY = event.y
                mapView.postDelayed(longPressRunnable, longPressTimeoutMs)
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressTriggered) {
                    onLongPressPositionChanged(event.x, event.y)
                    consumeEvent = true
                } else {
                    val moved = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                    if (moved) {
                        mapView.removeCallbacks(longPressRunnable)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mapView.removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    onLongPressFinished()
                    consumeEvent = true
                }
                longPressTriggered = false
                currentMapView = null
            }
        }
        return consumeEvent
    }
}
