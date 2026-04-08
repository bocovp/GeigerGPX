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
        var averagedCoordinatePoints = 0
        var averagedDosePoints = 0
        var lastSourcePoint: TrackSample? = null

        fun addToAveraging(sample: TrackSample) {
            if (!sample.badCoordinates) {
                latSum += sample.latitude
                lonSum += sample.longitude
                averagedCoordinatePoints += 1
            }
            countsSum += sample.counts
            secondsSum += sample.seconds
            averagedDosePoints += 1
            lastSourcePoint = sample
        }

        fun flushAveragedPoint() {
            if (averagedDosePoints == 0) return
            val fallbackPoint = lastSourcePoint ?: return

            val averagedPoint = TrackSample(
                latitude = if (averagedCoordinatePoints > 0) latSum / averagedCoordinatePoints else fallbackPoint.latitude,
                longitude = if (averagedCoordinatePoints > 0) lonSum / averagedCoordinatePoints else fallbackPoint.longitude,
                doseRate = if (secondsSum > 0.0) countsSum * coeff / secondsSum else 0.0,
                counts = countsSum,
                seconds = secondsSum,
                badCoordinates = averagedCoordinatePoints == 0
            )
            generalized += averagedPoint
            lastGeneralizedPoint = averagedPoint

            latSum = 0.0
            lonSum = 0.0
            countsSum = 0
            secondsSum = 0.0
            averagedCoordinatePoints = 0
            averagedDosePoints = 0
            lastSourcePoint = null
        }

        for (i in 0 until track.points.size) {
            val sourcePoint = track.points[i]
            addToAveraging(sourcePoint)

            // Capture the var into an immutable val for safe smart-casting
            val currentLastPoint = lastGeneralizedPoint

            if (currentLastPoint == null) {
                if (secondsSum >= minDurationSeconds) {
                    flushAveragedPoint()
                }
            } else {
                val distanceMeters = GeoPoint(sourcePoint.latitude, sourcePoint.longitude)
                    .distanceToAsDouble(GeoPoint(currentLastPoint.latitude, currentLastPoint.longitude))

                if (distanceMeters >= minDistanceMeters && secondsSum >= minDurationSeconds) {
                    flushAveragedPoint()
                }
            }
        }

        flushAveragedPoint()

        return track.copy(points = generalized)
    }
}
