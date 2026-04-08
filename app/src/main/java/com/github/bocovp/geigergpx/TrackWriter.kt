package com.github.bocovp.geigergpx

import android.location.Location

class TrackWriter {
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

    private var latSum: Double = 0.0

    private var lonSum: Double = 0.0

    private var latLonSum: Int = 0

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

    fun reset() = synchronized(lock) {
        startTimeMillis = 0L
        _totalDistance = 0.0
        writtenPointsInternal.clear()
        lastWrittenLocation = null
        lastWrittenTime = 0L
        latSum = 0.0
        lonSum = 0.0
        latLonSum = 0
        lastPointTotalBeeps = 0
    }

    fun start(now: Long, totalBeeps: Int) = synchronized(lock) {
        reset()
        startTimeMillis = now
        lastPointTotalBeeps = totalBeeps
    }


    private fun initializeAnchor(loc: Location, now: Long, totalBeeps: Int) = synchronized(lock) {
        lastWrittenLocation = Location(loc)
        lastWrittenTime = now
        latSum = loc.latitude
        lonSum = loc.longitude
        latLonSum = 1
        lastPointTotalBeeps = totalBeeps
    }

    private fun movementStatsFor(loc: Location, now: Long): MovementStats = synchronized(lock) {
        val lastLoc = requireNotNull(lastWrittenLocation) { "Anchor location missing" }
        val distance = lastLoc.distanceTo(loc).toDouble()
        val timeDeltaSec = kotlin.math.max(0.1, (now - lastWrittenTime) / 1000.0)
        MovementStats(distance = distance, timeDeltaSec = timeDeltaSec)
    }

    private fun accumulateLocation(loc: Location) = synchronized(lock) {
        latSum += loc.latitude
        lonSum += loc.longitude
        latLonSum += 1
    }

    fun handleGpsLocation(
        loc: Location,
        now: Long,
        totalBeeps: Int,
        spacingM: Double,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double
    ): ProcessLocationResult = synchronized(lock) {
        if (lastWrittenLocation == null) {
            initializeAnchor(loc, now, totalBeeps)
            return ProcessLocationResult()
        }

        val movementStats = movementStatsFor(loc, now)
        accumulateLocation(loc)
        if (movementStats.distance < spacingM) {
            return ProcessLocationResult()
        }

        val currentBeeps = totalBeeps - lastPointTotalBeeps
        val timedOut = maxTimeWithoutCountsS > 0.0 && movementStats.timeDeltaSec >= maxTimeWithoutCountsS

        if (currentBeeps < minCountsPerPoint && !timedOut) {
            return ProcessLocationResult()
        }

        val snapshot = commitPoint(loc, now, movementStats, totalBeeps)
        ProcessLocationResult(snapshot = snapshot)
    }

    private fun commitPoint(loc: Location, now: Long, movementStats: MovementStats, totalBeeps: Int): List<TrackPoint> = synchronized(lock) {
        val finalBeeps = totalBeeps - lastPointTotalBeeps
        val finalCps = finalBeeps.toDouble() / movementStats.timeDeltaSec // TODO: add -1
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

        writtenPointsInternal.add(point)
        val snapshot = writtenPointsInternal.toList()

        _totalDistance += movementStats.distance
        lastWrittenLocation = Location(loc)
        lastWrittenTime = now
        latSum = 0.0
        lonSum = 0.0
        latLonSum = 0
        lastPointTotalBeeps = totalBeeps
        snapshot
    }
}
