package com.github.bocovp.geigergpx

import org.osmdroid.util.GeoPoint

class TrackGeneralizer(
    private val minDistanceMeters: Double,
    private val coeff: Double,
    private val minDurationSeconds: Double = 0.0
) {

    fun generalize(track: MapTrack): MapTrack {
        if (track.points.size < 2) return track

        val generalized = ArrayList<TrackSample>(track.points.size)
        var lastGeneralizedPoint = track.points.first()
        generalized += lastGeneralizedPoint

        var latSum = 0.0
        var lonSum = 0.0
        var countsSum = 0
        var secondsSum = 0.0
        var averagedPoints = 0

        fun flushAveragedPoint() {
            if (averagedPoints == 0) return

            val averagedPoint = TrackSample(
                latitude = latSum / averagedPoints,
                longitude = lonSum / averagedPoints,
                doseRate = if (secondsSum > 0.0) countsSum * coeff / secondsSum else 0.0,
                counts = countsSum,
                seconds = secondsSum
            )
            generalized += averagedPoint
            lastGeneralizedPoint = averagedPoint

            latSum = 0.0
            lonSum = 0.0
            countsSum = 0
            secondsSum = 0.0
            averagedPoints = 0
        }

        for (i in 1 until track.points.size) {
            val sourcePoint = track.points[i]
            val distanceMeters = GeoPoint(sourcePoint.latitude, sourcePoint.longitude)
                .distanceToAsDouble(GeoPoint(lastGeneralizedPoint.latitude, lastGeneralizedPoint.longitude))

            latSum += sourcePoint.latitude
            lonSum += sourcePoint.longitude
            countsSum += sourcePoint.counts
            secondsSum += sourcePoint.seconds
            averagedPoints += 1

            if (distanceMeters >= minDistanceMeters && secondsSum >= minDurationSeconds) {
                flushAveragedPoint()
            }
        }

        flushAveragedPoint()

        return track.copy(points = generalized)
    }
}
