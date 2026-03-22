package com.example.geigergpx

import android.location.Location

class DoseRateMeasurement(
    initialWindowSize: Int = 10
) {
    private val mainCpsLock = Any()

    private var measurementModeEnabled: Boolean = false
    private var measurementStartTimestampMillis: Long = 0L
    private var measurementOldestTimestamp: Long = 0L
    private var measurementTimestampCount: Long = 0L
    private var windowSize: Int = initialWindowSize

    private var beepTimes = LongArray(windowSize)
    private var mainCpsBeepTimes = beepTimes
    private var mainCpsBeepCount: Int = 0
    private var mainCpsBeepNextIndex: Int = 0

    private var measLatSum: Double = 0.0
    private var measLonSum: Double = 0.0
    private var measLatLonCount: Int = 0

    fun toggleMeasurementMode(nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(mainCpsLock) {
        measurementModeEnabled = !measurementModeEnabled
        if (measurementModeEnabled) {
            measurementStartTimestampMillis = nowMillis
            measurementTimestampCount = mainCpsBeepCount.toLong()
            measurementOldestTimestamp = if (mainCpsBeepCount >= 1) {
                mainCpsBeepTimes[oldestMainCpsIndex()]
            } else {
                0L
            }
            resetMeasurementCoordinates()
        } else {
            measurementStartTimestampMillis = 0L
            resetMeasurementCoordinates()
        }
        measurementModeEnabled
    }

    fun handleGpsLocation(loc: Location) {
        synchronized(mainCpsLock) {
            if (!measurementModeEnabled) return
            measLatSum += loc.latitude
            measLonSum += loc.longitude
            measLatLonCount += 1
        }
    }

    fun consumeMeasurementAverageCoordinates(): Pair<Double, Double> = synchronized(mainCpsLock) {
        val latitude = if (measLatLonCount > 0) measLatSum / measLatLonCount.toDouble() else 0.0
        val longitude = if (measLatLonCount > 0) measLonSum / measLatLonCount.toDouble() else 0.0
        resetMeasurementCoordinates()
        Pair(latitude, longitude)
    }

    fun updateMainCpsWindowSize(newSize: Int) {
        synchronized(mainCpsLock) {
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
    }

    fun processBeep(beepCount: Int, nowProvider: () -> Long = System::currentTimeMillis) {
        if (beepCount <= 0) return
        synchronized(mainCpsLock) {
            repeat(beepCount) {
                mainCpsBeepTimes[mainCpsBeepNextIndex] = nowProvider()
                val beepTime = mainCpsBeepTimes[mainCpsBeepNextIndex]
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
        }
    }

    fun calculateMainScreenCps(): Double = synchronized(mainCpsLock) {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 2L || measurementOldestTimestamp == 0L) {
                return@synchronized 0.0
            }
            val newest = newestMainCpsTimestamp() ?: return@synchronized 0.0
            val deltaSeconds = (newest - measurementOldestTimestamp) / 1000.0
            if (deltaSeconds <= 0.0) return@synchronized 0.0
            return@synchronized (measurementTimestampCount - 1).toDouble() / deltaSeconds
        }

        if (mainCpsBeepCount < 2) return@synchronized 0.0
        val newest = newestMainCpsTimestamp() ?: return@synchronized 0.0
        val oldest = mainCpsBeepTimes[oldestMainCpsIndex()]
        val deltaSeconds = (newest - oldest) / 1000.0
        if (deltaSeconds <= 0.0) return@synchronized 0.0
        (mainCpsBeepCount - 1).toDouble() / deltaSeconds
    }

    fun currentSampleCount(): Int = synchronized(mainCpsLock) {
        if (measurementModeEnabled) measurementTimestampCount.toInt() else mainCpsBeepCount
    }

    fun currentOldestTimestampMillis(): Long = synchronized(mainCpsLock) {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 1L) return@synchronized 0L
            return@synchronized measurementOldestTimestamp
        }

        if (mainCpsBeepCount < 1) return@synchronized 0L
        mainCpsBeepTimes[oldestMainCpsIndex()]
    }

    fun currentMeasurementStartTimestampMillis(): Long = synchronized(mainCpsLock) {
        if (measurementModeEnabled) measurementStartTimestampMillis else 0L
    }

    fun currentSnapshot(): TrackingRepository.CpsSnapshot = synchronized(mainCpsLock) {
        TrackingRepository.CpsSnapshot(
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

    private fun resetMeasurementCoordinates() {
        measLatSum = 0.0
        measLonSum = 0.0
        measLatLonCount = 0
    }
}
