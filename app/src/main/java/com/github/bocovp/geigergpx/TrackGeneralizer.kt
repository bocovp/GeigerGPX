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

        var lastGeneralizedPoint: TrackSample? = null

        var latSum = 0.0
        var lonSum = 0.0
        var countsSum = 0
        var secondsSum = 0.0
        var averagedPoints = 0

        fun addToAveraging(sample: TrackSample) {
            latSum += sample.latitude
            lonSum += sample.longitude
            countsSum += sample.counts
            secondsSum += sample.seconds
            averagedPoints += 1
        }

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

        addToAveraging(track.points.first())
        if (secondsSum >= minDurationSeconds) {
            flushAveragedPoint()
        }

        for (i in 1 until track.points.size) {
            val sourcePoint = track.points[i]
            addToAveraging(sourcePoint)

            if (lastGeneralizedPoint == null) {
                if (secondsSum >= minDurationSeconds) {
                    flushAveragedPoint()
                }
                continue
            }

            val generalizedPoint = lastGeneralizedPoint ?: continue
            val distanceMeters = GeoPoint(sourcePoint.latitude, sourcePoint.longitude)
                .distanceToAsDouble(GeoPoint(generalizedPoint.latitude, generalizedPoint.longitude))

            if (distanceMeters >= minDistanceMeters && secondsSum >= minDurationSeconds) {
                flushAveragedPoint()
            }
        }

        flushAveragedPoint()

        return track.copy(points = generalized)
    }
}
