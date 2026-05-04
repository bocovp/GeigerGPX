package com.github.bocovp.geigergpx

import android.location.Location

class DoseRateMeasurement(
    initialWindowSize: Int = 10
) {
    private var measurementModeEnabled: Boolean = false
    private var measurementStartTimestampMillis: Long = 0L
    private var measurementOldestTimestamp: Long = 0L
    private var measurementTimestampCount: Long = 0L
    private var windowSize: Int = initialWindowSize

    private var beepTimes = LongArray(windowSize)
    private var mainCpsBeepTimes = beepTimes
    private var mainCpsBeepCount: Int = 0
    private var mainCpsBeepNextIndex: Int = 0

    private val measurementCoordinateAverager = GpsCoordinateAverager()
    private var alertDoseRate: Double = 0.0
    private var cpsToUsvhCoefficient: Double = 1.0

    data class AlertEvent(
        val soundCount: Int,
        val meanDoseRate: Double
    )

    @Synchronized
    fun toggleMeasurementMode(nowMillis: Long = System.currentTimeMillis()): Boolean {
        measurementModeEnabled = !measurementModeEnabled
        if (measurementModeEnabled) {
            measurementStartTimestampMillis = nowMillis
            measurementTimestampCount = 0L
            measurementOldestTimestamp = 0L
            measurementCoordinateAverager.reset()
        } else {
            measurementStartTimestampMillis = 0L
            measurementCoordinateAverager.reset()
        }
        measurementModeEnabled
    }

    @Synchronized
    fun handleGpsLocation(loc: Location) {
        if (!measurementModeEnabled) return
        measurementCoordinateAverager.process(loc)
    }

    @Synchronized
    fun consumeMeasurementAverageCoordinates(): Pair<Double, Double> {
        return measurementCoordinateAverager.consumeAverage() ?: Pair(0.0, 0.0)
    }

    @Synchronized
    fun updateMainCpsWindowSize(newSize: Int) {
            if (newSize == windowSize) return

            val preservedCount = minOf(mainCpsBeepCount, newSize)
            val newTimes = LongArray(newSize)

            if (preservedCount > 0) {
                val start = (mainCpsBeepNextIndex - preservedCount + windowSize) % windowSize
                for (i in 0 until preservedCount) {
                    val srcIndex = (start + i) % windowSize
                    newTimes[i] = mainCpsBeepTimes[srcIndex]
                }
            }

            beepTimes = newTimes
            mainCpsBeepTimes = newTimes
            windowSize = newSize
            mainCpsBeepCount = preservedCount
            mainCpsBeepNextIndex = preservedCount % newSize
    }

    @Synchronized
    fun updateAlertConfig(alertDoseRate: Double, cpsToUsvhCoefficient: Double) {
        this.alertDoseRate = if (alertDoseRate > 0.0) alertDoseRate else 0.0
        this.cpsToUsvhCoefficient = cpsToUsvhCoefficient
    }

    @Synchronized
    fun processBeep(beepCount: Int, nowProvider: () -> Long = System::currentTimeMillis): AlertEvent? {
        if (beepCount <= 0) return null
        repeat(beepCount) {
            val beepTime = nowProvider()
            mainCpsBeepTimes[mainCpsBeepNextIndex] = beepTime
            mainCpsBeepNextIndex = (mainCpsBeepNextIndex + 1) % windowSize
            if (mainCpsBeepCount < windowSize) {
                mainCpsBeepCount += 1
            }
            if (measurementModeEnabled) {
                if (measurementTimestampCount == 0L) {
                    measurementOldestTimestamp = beepTime
                }
                measurementTimestampCount += 1L
            }
        }
        return evaluateAlertLocked()
    }

    @Synchronized
    fun calculateMainScreenCps(): Double = calculateMainScreenCpsInternal()

    @Synchronized
    fun currentSampleCount(): Int {
        return if (measurementModeEnabled) measurementTimestampCount.toInt() else mainCpsBeepCount
    }

    @Synchronized
    fun currentOldestTimestampMillis(): Long {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 1L) return 0L
            return measurementOldestTimestamp
        }

        if (mainCpsBeepCount < 1) return 0L
        return mainCpsBeepTimes[oldestMainCpsIndex()]
    }

    @Synchronized
    fun currentMeasurementStartTimestampMillis(): Long {
        return if (measurementModeEnabled) measurementStartTimestampMillis else 0L
    }

    @Synchronized
    fun currentSnapshot(): TrackingRepository.CpsSnapshot {
        return TrackingRepository.CpsSnapshot(
            sampleCount = currentSampleCountLocked(),
            oldestTimestampMillis = currentOldestTimestampMillisLocked(),
            measurementStartTimestampMillis = if (measurementModeEnabled) {
                measurementStartTimestampMillis
            } else {
                0L
            }
        )
    }

    private fun newestMainCpsTimestamp(): Long? {
        if (mainCpsBeepCount < 1) return null
        val newestIndex = (mainCpsBeepNextIndex - 1 + windowSize) % windowSize
        return mainCpsBeepTimes[newestIndex]
    }

    private fun currentSampleCountLocked(): Int {
        return if (measurementModeEnabled) measurementTimestampCount.toInt() else mainCpsBeepCount
    }

    private fun currentOldestTimestampMillisLocked(): Long {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 1L) return 0L
            return measurementOldestTimestamp
        }

        if (mainCpsBeepCount < 1) return 0L
        return mainCpsBeepTimes[oldestMainCpsIndex()]
    }

    private fun oldestMainCpsIndex(): Int {
        return if (mainCpsBeepCount == windowSize) mainCpsBeepNextIndex else 0
    }

    private fun evaluateAlertLocked(): AlertEvent? {
        if (measurementModeEnabled) return null
        if (alertDoseRate <= 0.0) return null
        if (mainCpsBeepCount < windowSize) return null

        val meanDoseRate = calculateMainScreenCpsInternal() * cpsToUsvhCoefficient
        if (meanDoseRate < alertDoseRate) return null

        val ratio = meanDoseRate / alertDoseRate
        val soundCount = when {
            ratio >= 3.0 -> 3
            ratio >= 2.0 -> 2
            else -> 1
        }
        return AlertEvent(soundCount = soundCount, meanDoseRate = meanDoseRate)
    }

    private fun calculateMainScreenCpsInternal(): Double {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 2L || measurementOldestTimestamp == 0L) {
                return 0.0
            }
            val newest = newestMainCpsTimestamp() ?: return 0.0
            val deltaSeconds = (newest - measurementOldestTimestamp) / 1000.0
            if (deltaSeconds <= 0.0) return 0.0
            return (measurementTimestampCount - 1).toDouble() / deltaSeconds
        }

        if (mainCpsBeepCount < 2) return 0.0
        val newest = newestMainCpsTimestamp() ?: return 0.0
        val oldest = mainCpsBeepTimes[oldestMainCpsIndex()]
        val deltaSeconds = (newest - oldest) / 1000.0
        if (deltaSeconds <= 0.0) return 0.0
        return (mainCpsBeepCount - 1).toDouble() / deltaSeconds
    }

}
