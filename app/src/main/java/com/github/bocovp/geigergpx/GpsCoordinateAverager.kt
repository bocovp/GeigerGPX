package com.github.bocovp.geigergpx

import android.location.Location

class GpsCoordinateAverager {
    private var weightedLatitudeSum: Double = 0.0
    private var weightedLongitudeSum: Double = 0.0
    private var totalWeight: Double = 0.0
    private var pointCount: Int = 0

    val currentPointCount: Int
        get() = pointCount

    fun process(location: Location) {
        val accuracy = location.accuracy.takeIf { it > 0f } ?: 1.0f
        val weight = 1.0 / (accuracy * accuracy).coerceAtLeast(1.0f)
        weightedLatitudeSum += location.latitude * weight
        weightedLongitudeSum += location.longitude * weight
        totalWeight += weight
        pointCount += 1
    }

    fun consumeAverage(): Pair<Double, Double>? {
        if (pointCount == 0 || totalWeight <= 0.0) {
            reset()
            return null
        }
        val average = Pair(
            weightedLatitudeSum / totalWeight,
            weightedLongitudeSum / totalWeight
        )
        reset()
        return average
    }

    fun reset() {
        weightedLatitudeSum = 0.0
        weightedLongitudeSum = 0.0
        totalWeight = 0.0
        pointCount = 0
    }
}
