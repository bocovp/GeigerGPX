package com.example.geigergpx

import android.location.Location

class TrackWriter {
    data class MovementStats(
        val distance: Double,
        val timeDeltaSec: Double
    ) {
        val speedKmh: Double = if (timeDeltaSec > 0.0) (distance / timeDeltaSec) * 3.6 else 0.0
    }

    data class ProcessLocationResult(
        val snapshot: List<TrackPoint>? = null
    )

    private val lock = Any()

    private var startTimeMillis: Long = 0L
    private var _totalDistance: Double = 0.0
    val totalDistance: Double
        get() = synchronized(lock) { _totalDistance }

    private var _lastWrittenLocation: Location? = null
    private var _lastGpsFixMillis: Long = 0L
    val lastGpsFixMillis: Long
        get() = synchronized(lock) { _lastGpsFixMillis }

    private var _lastPointTotalBeeps: Int = 0
    val lastPointTotalBeeps: Int
        get() = synchronized(lock) { _lastPointTotalBeeps }

    private var _latSum: Double = 0.0
    val latSum: Double
        get() = synchronized(lock) { _latSum }

    private var _lonSum: Double = 0.0
    val lonSum: Double
        get() = synchronized(lock) { _lonSum }

    private var _latLonSum: Int = 0
    val latLonSum: Int
        get() = synchronized(lock) { _latLonSum }

    private var _lastWrittenTime: Long = 0L
    val lastWrittenTime: Long
        get() = synchronized(lock) { _lastWrittenTime }

    private val writtenPointsInternal = mutableListOf<TrackPoint>()
    val writtenPoints: List<TrackPoint>
        get() = activeTrackPointsSnapshot()

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
        _lastWrittenLocation = null
        _lastWrittenTime = 0L
        _latSum = 0.0
        _lonSum = 0.0
        _latLonSum = 0
        _lastPointTotalBeeps = 0
        _lastGpsFixMillis = 0L
    }

    fun start(now: Long, totalBeeps: Int) = synchronized(lock) {
        reset()
        startTimeMillis = now
        _lastPointTotalBeeps = totalBeeps
    }

    fun updateLastGpsFix(now: Long) = synchronized(lock) {
        _lastGpsFixMillis = now
    }

    fun hasAnchor(): Boolean = synchronized(lock) { _lastWrittenLocation != null }

    fun initializeAnchor(loc: Location, now: Long, totalBeeps: Int) = synchronized(lock) {
        _lastWrittenLocation = Location(loc)
        _lastWrittenTime = now
        _latSum = loc.latitude
        _lonSum = loc.longitude
        _latLonSum = 1
        _lastPointTotalBeeps = totalBeeps
    }

    fun movementStatsFor(loc: Location, now: Long): MovementStats = synchronized(lock) {
        val lastLoc = requireNotNull(_lastWrittenLocation) { "Anchor location missing" }
        val distance = lastLoc.distanceTo(loc).toDouble()
        val timeDeltaSec = kotlin.math.max(0.1, (now - _lastWrittenTime) / 1000.0)
        MovementStats(distance = distance, timeDeltaSec = timeDeltaSec)
    }

    fun accumulateLocation(loc: Location) = synchronized(lock) {
        _latSum += loc.latitude
        _lonSum += loc.longitude
        _latLonSum += 1
    }

    fun currentBeeps(totalBeeps: Int): Int = synchronized(lock) { totalBeeps - _lastPointTotalBeeps }

    fun shouldWaitForCounts(
        totalBeeps: Int,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double,
        timeDeltaSec: Double
    ): Boolean = synchronized(lock) {
        val currentBeeps = totalBeeps - _lastPointTotalBeeps
        val timedOut = maxTimeWithoutCountsS > 0.0 && timeDeltaSec >= maxTimeWithoutCountsS
        minCountsPerPoint > 0 && currentBeeps < minCountsPerPoint && !timedOut
    }

    fun processLocation(
        loc: Location,
        now: Long,
        totalBeeps: Int,
        spacingM: Double,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double
    ): ProcessLocationResult = synchronized(lock) {
        if (_lastWrittenLocation == null) {
            initializeAnchor(loc, now, totalBeeps)
            return ProcessLocationResult()
        }

        val movementStats = movementStatsFor(loc, now)
        accumulateLocation(loc)
        if (movementStats.distance < spacingM) {
            return ProcessLocationResult()
        }

        if (shouldWaitForCounts(totalBeeps, minCountsPerPoint, maxTimeWithoutCountsS, movementStats.timeDeltaSec)) {
            return ProcessLocationResult()
        }

        val snapshot = commitPoint(loc, now, movementStats, totalBeeps)
        ProcessLocationResult(snapshot = snapshot)
    }

    fun commitPoint(loc: Location, now: Long, movementStats: MovementStats, totalBeeps: Int): List<TrackPoint> = synchronized(lock) {
        val finalBeeps = totalBeeps - _lastPointTotalBeeps
        val finalCps = finalBeeps.toDouble() / movementStats.timeDeltaSec // TODO: add -1
        val avgLat = if (_latLonSum > 0) _latSum / _latLonSum.toDouble() else loc.latitude
        val avgLon = if (_latLonSum > 0) _lonSum / _latLonSum.toDouble() else loc.longitude
        val avgTimeMillis = (_lastWrittenTime + now) / 2L

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
        _lastWrittenLocation = Location(loc)
        _lastWrittenTime = now
        _latSum = 0.0
        _lonSum = 0.0
        _latLonSum = 0
        _lastPointTotalBeeps = totalBeeps
        snapshot
    }
}
