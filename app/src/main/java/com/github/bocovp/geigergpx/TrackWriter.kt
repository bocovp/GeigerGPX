package com.github.bocovp.geigergpx

import android.location.Location

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

    fun reset() = synchronized(lock) {
        startTimeMillis = 0L
        _totalDistance = 0.0
        writtenPointsInternal.clear()
        lastWrittenLocation = null
        lastWrittenTime = 0L
        coordinateAverager.reset()
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
        coefficient: Double
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

        val snapshot = commitPoint(loc, now, movementStats, totalBeeps, coefficient)
        ProcessLocationResult(snapshot = snapshot)
    }

    private fun commitPoint(loc: Location, now: Long, movementStats: MovementStats, totalBeeps: Int, coefficient: Double): List<TrackPoint> = synchronized(lock) {
        return commitPointInternal(
            loc = loc,
            now = now,
            movementStats = movementStats,
            totalBeeps = totalBeeps,
            badCoordinates = false,
            coefficient = coefficient
        )
    }

    fun handleGpsFallback(
        mode: GpsMode,
        spoofedLocation: Location?,
        now: Long,
        totalBeeps: Int,
        minCountsPerPoint: Int,
        maxTimeWithoutCountsS: Double,
        coefficient: Double
    ): ProcessLocationResult = synchronized(lock) {
        if (mode == GpsMode.ACTIVE) return ProcessLocationResult()
        val lastLoc = lastWrittenLocation ?: return ProcessLocationResult()

        val candidateLocation = when (mode) {
            GpsMode.INACTIVE -> Location(lastLoc)
            GpsMode.SPOOFING -> spoofedLocation?.let { Location(it) } ?: return ProcessLocationResult()
            GpsMode.ACTIVE -> return ProcessLocationResult()
        }

        val movementStats = movementStatsFor(candidateLocation, now)
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
            coefficient = coefficient
        )
        ProcessLocationResult(snapshot = snapshot)
    }

    fun secondsSinceLastWritten(now: Long): Double = synchronized(lock) {
        if (lastWrittenTime <= 0L) 0.0 else kotlin.math.max(0.0, (now - lastWrittenTime) / 1000.0)
    }

    private fun commitPointInternal(
        loc: Location,
        now: Long,
        movementStats: MovementStats,
        totalBeeps: Int,
        badCoordinates: Boolean,
        coefficient: Double
    ): List<TrackPoint> = synchronized(lock) {
        val finalBeeps = totalBeeps - lastPointTotalBeeps
        val finalCps = finalBeeps.toDouble() / movementStats.timeDeltaSec // TODO: add -1
        val averagedCoordinates = coordinateAverager.consumeAverage()
        val avgLat = averagedCoordinates?.first ?: loc.latitude
        val avgLon = averagedCoordinates?.second ?: loc.longitude
        val avgTimeMillis = (lastWrittenTime + now) / 2L

        val point = TrackPoint(
            latitude = avgLat,
            longitude = avgLon,
            timeMillis = avgTimeMillis,
            doseRate = finalCps * coefficient,
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
