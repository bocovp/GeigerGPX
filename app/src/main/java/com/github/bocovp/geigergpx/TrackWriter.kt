package com.github.bocovp.geigergpx

import android.location.Location
import kotlin.math.roundToLong

class TrackWriter {
    enum class GpsMode {
        ACTIVE,
        INACTIVE,
        SPOOFING
    }

    data class MovementStats(
        val distance: Double,
        val timeDeltaSec: Double
    )

    data class ProcessLocationResult(
        val snapshot: List<TrackPoint>? = null
    )

    private val lock = Any()

    private var startTimeMillis: Long = 0L

    private var _totalDistance: Double = 0.0
    val totalDistance: Double
        get() = synchronized(lock) { _totalDistance }

    private var lastWrittenLocation: Location? = null


    private var lastPointTotalBeeps: Int = 0

    private val coordinateAverager = GpsCoordinateAverager()

    private var lastWrittenTime: Long = 0L

    private val writtenPointsInternal = mutableListOf<TrackPoint>()

    fun activeTrackPointsSnapshot(): List<TrackPoint> = synchronized(lock) {
        writtenPointsInternal.toList()
    }

    fun pointCount(): Int = synchronized(lock) { writtenPointsInternal.size }

    fun isTracking(): Boolean = synchronized(lock) { startTimeMillis != 0L }

    fun elapsedSeconds(now: Long): Long = synchronized(lock) {
        if (startTimeMillis != 0L) {
            kotlin.math.max(0L, (now - startTimeMillis) / 1000L)
        } else {
            0L
        }
    }

    private var lastGoodPointIndex: Int = -1

    fun reset() = synchronized(lock) {
        startTimeMillis = 0L
        _totalDistance = 0.0
        writtenPointsInternal.clear()
        lastWrittenLocation = null
        lastWrittenTime = 0L
        coordinateAverager.reset()
        lastPointTotalBeeps = 0
        lastGoodPointIndex = -1
    }

    fun start(now: Long, totalBeeps: Int) = synchronized(lock) {
        reset()
        startTimeMillis = now
        lastPointTotalBeeps = totalBeeps
    }


    private fun initializeAnchor(loc: Location, now: Long, totalBeeps: Int) = synchronized(lock) {
        lastWrittenLocation = Location(loc)
        lastWrittenTime = now
        coordinateAverager.reset()
        coordinateAverager.process(loc)
        lastPointTotalBeeps = totalBeeps
    }

    private fun movementStatsFor(loc: Location, now: Long): MovementStats = synchronized(lock) {
        val lastLoc = requireNotNull(lastWrittenLocation) { "Anchor location missing" }
        val distance = lastLoc.distanceTo(loc).toDouble()
        val timeDeltaSec = kotlin.math.max(0.1, (now - lastWrittenTime) / 1000.0)
        MovementStats(distance = distance, timeDeltaSec = timeDeltaSec)
    }

    private fun accumulateLocation(loc: Location) = synchronized(lock) {
        coordinateAverager.process(loc)
    }

    fun handleGpsLocation(
        loc: Location,
        now: Long,
        totalBeeps: Int,
        spacingM: Double,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double,
        sensitivity: Double
    ): ProcessLocationResult = synchronized(lock) {
        if (lastWrittenLocation == null && writtenPointsInternal.isEmpty()) {
            initializeAnchor(loc, now, totalBeeps)
            return ProcessLocationResult()
        }

        // 1. Calculate safe distance (ignoring dummy points)
        val distance = if (lastGoodPointIndex == -1 && writtenPointsInternal.isNotEmpty()) {
            0.0 // Do not calculate distance from (0,0)
        } else if (lastGoodPointIndex != -1 && writtenPointsInternal.size - 1 > lastGoodPointIndex) {
            // Calculate distance from the LAST GOOD point, skipping dummy points
            val p = writtenPointsInternal[lastGoodPointIndex]
            val tempLoc = Location("temp").apply { latitude = p.latitude; longitude = p.longitude }
            tempLoc.distanceTo(loc).toDouble()
        } else {
            lastWrittenLocation!!.distanceTo(loc).toDouble()
        }

        val anchorTime = if (lastWrittenTime > 0L) lastWrittenTime else startTimeMillis
        val timeDeltaSec = kotlin.math.max(0.1, (now - anchorTime) / 1000.0)
        val movementStats = MovementStats(distance = distance, timeDeltaSec = timeDeltaSec)

        accumulateLocation(loc)

        // 2. Evaluate commit limits (bypass spacing for the first recovered point)
        val bypassSpacing = (lastGoodPointIndex == -1 && writtenPointsInternal.isNotEmpty())
        if (movementStats.distance < spacingM && !bypassSpacing) {
            return ProcessLocationResult()
        }

        val currentBeeps = totalBeeps - lastPointTotalBeeps
        val timedOut = maxTimeWithoutCountsS > 0.0 && movementStats.timeDeltaSec >= maxTimeWithoutCountsS

        if (currentBeeps < minCountsPerPoint && !timedOut) {
            return ProcessLocationResult()
        }

        // 3. Retroactive Fixes
        if (lastGoodPointIndex == -1 && writtenPointsInternal.isNotEmpty()) {
            // Scenario 2: First good point after dropping out at the very start
            for (i in writtenPointsInternal.indices) {
                val p = writtenPointsInternal[i]
                if (p.badCoordinates) {
                    writtenPointsInternal[i] = p.copy(
                        latitude = loc.latitude,
                        longitude = loc.longitude + p.longitude
                    )
                }
            }
        } else if (lastGoodPointIndex != -1 && writtenPointsInternal.size > lastGoodPointIndex + 1) {
            // Scenario 3.2: Regained GPS after a gap; linearly interpolate
            val idxStart = lastGoodPointIndex
            val idxEnd = writtenPointsInternal.size
            val pStart = writtenPointsInternal[idxStart]
            val steps = idxEnd - idxStart

            for (i in idxStart + 1 until idxEnd) {
                val p = writtenPointsInternal[i]
                if (p.badCoordinates) {
                    val fraction = (i - idxStart).toDouble() / steps.toDouble()
                    val interpLat = pStart.latitude + (loc.latitude - pStart.latitude) * fraction
                    val interpLon = pStart.longitude + (loc.longitude - pStart.longitude) * fraction
                    writtenPointsInternal[i] = p.copy(
                        latitude = interpLat,
                        longitude = interpLon
                    )
                }
            }
        }

        // 4. Safely Commit
        val snapshot = commitPointInternal(
            loc = loc,
            now = now,
            movementStats = movementStats,
            totalBeeps = totalBeeps,
            badCoordinates = false,
            sensitivity = sensitivity
        )

        // Update the anchor to the newly added valid point
        lastGoodPointIndex = writtenPointsInternal.size - 1

        return ProcessLocationResult(snapshot = snapshot)
    }

    private fun commitPoint(loc: Location, now: Long, movementStats: MovementStats, totalBeeps: Int, sensitivity: Double): List<TrackPoint> = synchronized(lock) {
        return commitPointInternal(
            loc = loc,
            now = now,
            movementStats = movementStats,
            totalBeeps = totalBeeps,
            badCoordinates = false,
            sensitivity = sensitivity
        )
    }

    fun handleGpsFallback(
        mode: GpsMode,
        spoofedLocation: Location?,
        now: Long,
        totalBeeps: Int,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double,
        sensitivity: Double
    ): ProcessLocationResult = synchronized(lock) {
        if (mode == GpsMode.ACTIVE) return ProcessLocationResult()

        val fallbackLat: Double
        val fallbackLon: Double

        if (lastGoodPointIndex == -1) {
            // Scenario 1: Beginning of the track without GPS
            fallbackLat = 0.0
            fallbackLon = 0.0001 * writtenPointsInternal.size
        } else {
            // Scenario 3.1: Lost GPS in the middle of the track
            val lastGood = writtenPointsInternal[lastGoodPointIndex]
            val n = writtenPointsInternal.size - lastGoodPointIndex
            fallbackLat = lastGood.latitude
            fallbackLon = lastGood.longitude + (0.0001 * n)
        }

        val candidateLocation = when (mode) {
            GpsMode.SPOOFING -> spoofedLocation?.let { Location(it) } ?: Location("fallback").apply {
                latitude = fallbackLat
                longitude = fallbackLon
            }
            else -> Location("fallback").apply {
                latitude = fallbackLat
                longitude = fallbackLon
            }
        }

        val anchorTime = if (lastWrittenTime > 0L) lastWrittenTime else startTimeMillis
        val timeDeltaSec = kotlin.math.max(0.1, (now - anchorTime) / 1000.0)
        val movementStats = MovementStats(distance = 0.0, timeDeltaSec = timeDeltaSec)

        val currentBeeps = totalBeeps - lastPointTotalBeeps
        if (currentBeeps <= 0) {
            return ProcessLocationResult()
        }

        val timedOut = maxTimeWithoutCountsS > 0.0 && movementStats.timeDeltaSec >= maxTimeWithoutCountsS
        if (currentBeeps < minCountsPerPoint && !timedOut) {
            return ProcessLocationResult()
        }

        val snapshot = commitPointInternal(
            loc = candidateLocation,
            now = now,
            movementStats = movementStats,
            totalBeeps = totalBeeps,
            badCoordinates = true,
            sensitivity = sensitivity
        )
        return ProcessLocationResult(snapshot = snapshot)
    }

    fun secondsSinceLastWritten(now: Long): Double = synchronized(lock) {
        val anchorTime = if (lastWrittenTime > 0L) lastWrittenTime else startTimeMillis
        if (anchorTime <= 0L) 0.0 else kotlin.math.max(0.0, (now - anchorTime) / 1000.0)
    }

    private fun commitPointInternal(
        loc: Location,
        now: Long,
        movementStats: MovementStats,
        totalBeeps: Int,
        badCoordinates: Boolean,
        sensitivity: Double
    ): List<TrackPoint> = synchronized(lock) {
        val finalBeeps = totalBeeps - lastPointTotalBeeps

        // We do not subtract 1 from finalBeeps here since writing track point (choosing end of interval) is assumed to be triggered by timer, not by Geiger counter
        val finalCps = finalBeeps.toDouble() / movementStats.timeDeltaSec

        val finalDoseRate = RadiationCalibration.doseRateFromCps(finalCps, sensitivity)
        val averagedCoordinates = coordinateAverager.consumeAverage()
        val avgLat = averagedCoordinates?.first ?: loc.latitude
        val avgLon = averagedCoordinates?.second ?: loc.longitude

        // GPX point time must represent the midpoint of the measurement window: timestamp = currentTime - duration / 2.
        val halfDurationMillis = (movementStats.timeDeltaSec * 500.0).roundToLong()
        val avgTimeMillis = now - halfDurationMillis  // older version:  (lastWrittenTime + now) / 2L, which is the same

        val point = TrackPoint(
            latitude = avgLat,
            longitude = avgLon,
            timeMillis = avgTimeMillis,
            doseRate = finalDoseRate,
            counts = finalBeeps,
            seconds = movementStats.timeDeltaSec,
            badCoordinates = badCoordinates
        )

        writtenPointsInternal.add(point)
        val snapshot = writtenPointsInternal.toList()

        _totalDistance += movementStats.distance
        lastWrittenLocation = Location(loc)
        lastWrittenTime = now
        coordinateAverager.reset()
        lastPointTotalBeeps = totalBeeps
        snapshot
    }
}
