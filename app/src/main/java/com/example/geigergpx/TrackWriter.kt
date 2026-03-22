package com.example.geigergpx

import android.location.Location

class TrackWriter {
    data class MovementStats(
        val distance: Double,
        val timeDeltaSec: Double
    ) {
        val speedKmh: Double = if (timeDeltaSec > 0.0) (distance / timeDeltaSec) * 3.6 else 0.0
    }

    var totalDistance: Double = 0.0
        private set
    var lastWrittenLocation: Location? = null
        private set
    private val _writtenPoints = mutableListOf<TrackPoint>()
    val writtenPoints: List<TrackPoint>
        get() = synchronized(_writtenPoints) { _writtenPoints.toList() }
    var lastGpsFixMillis: Long = 0L
        private set
    var lastPointTotalBeeps: Int = 0
        private set
    var latSum: Double = 0.0
        private set
    var lonSum: Double = 0.0
        private set
    var latLonSum: Int = 0
        private set
    val latlLonSum: Int
        get() = latLonSum

    var lastWrittenTime: Long = 0L
        private set

    fun activeTrackPointsSnapshot(): List<TrackPoint> = synchronized(_writtenPoints) {
        _writtenPoints.toList()
    }

    fun pointCount(): Int = synchronized(_writtenPoints) { _writtenPoints.size }

    fun reset() {
        totalDistance = 0.0
        synchronized(_writtenPoints) {
            _writtenPoints.clear()
        }
        lastWrittenLocation = null
        lastWrittenTime = 0L
        latSum = 0.0
        lonSum = 0.0
        latLonSum = 0
        lastPointTotalBeeps = 0
        lastGpsFixMillis = 0L
    }

    fun start(totalBeeps: Int) {
        reset()
        lastPointTotalBeeps = totalBeeps
    }

    fun updateLastGpsFix(now: Long) {
        lastGpsFixMillis = now
    }

    fun hasAnchor(): Boolean = lastWrittenLocation != null

    fun initializeAnchor(loc: Location, now: Long, totalBeeps: Int) {
        lastWrittenLocation = loc
        lastWrittenTime = now
        latSum = loc.latitude
        lonSum = loc.longitude
        latLonSum = 1
        lastPointTotalBeeps = totalBeeps
    }

    fun movementStatsFor(loc: Location, now: Long): MovementStats {
        val lastLoc = requireNotNull(lastWrittenLocation) { "Anchor location missing" }
        val distance = lastLoc.distanceTo(loc).toDouble()
        val timeDeltaSec = kotlin.math.max(0.1, (now - lastWrittenTime) / 1000.0)
        return MovementStats(distance = distance, timeDeltaSec = timeDeltaSec)
    }

    fun accumulateLocation(loc: Location) {
        latSum += loc.latitude
        lonSum += loc.longitude
        latLonSum += 1
    }

    fun currentBeeps(totalBeeps: Int): Int = totalBeeps - lastPointTotalBeeps

    fun shouldWaitForCounts(
        totalBeeps: Int,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double,
        timeDeltaSec: Double
    ): Boolean {
        val currentBeeps = currentBeeps(totalBeeps)
        val timedOut = maxTimeWithoutCountsS > 0.0 && timeDeltaSec >= maxTimeWithoutCountsS
        return minCountsPerPoint > 0 && currentBeeps < minCountsPerPoint && !timedOut
    }

    fun commitPoint(loc: Location, now: Long, movementStats: MovementStats, totalBeeps: Int): List<TrackPoint> {
        val finalBeeps = currentBeeps(totalBeeps)
        val finalCps = finalBeeps.toDouble() / movementStats.timeDeltaSec
        val avgLat = if (latLonSum > 0) latSum / latLonSum.toDouble() else loc.latitude
        val avgLon = if (latLonSum > 0) lonSum / latLonSum.toDouble() else loc.longitude
        val avgTimeMillis = (lastWrittenTime + now) / 2L

        val point = TrackPoint(
            latitude = avgLat,
            longitude = avgLon,
            timeMillis = avgTimeMillis,
            distanceFromLast = movementStats.distance,
            cps = finalCps,
            counts = finalBeeps,
            seconds = movementStats.timeDeltaSec
        )

        val snapshot = synchronized(_writtenPoints) {
            _writtenPoints.add(point)
            _writtenPoints.toList()
        }

        totalDistance += movementStats.distance
        lastWrittenLocation = loc
        lastWrittenTime = now
        latSum = 0.0
        lonSum = 0.0
        latLonSum = 0
        lastPointTotalBeeps = totalBeeps
        return snapshot
    }
}
