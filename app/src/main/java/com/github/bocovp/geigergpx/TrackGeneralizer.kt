package com.github.bocovp.geigergpx

import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

class TrackGeneralizer(
    private val minDistanceMeters: Double,
    private val coeff: Double,
    private val minDurationSeconds: Double = 0.0
) {

    fun generalize(track: MapTrack, kdeScale: Double? = null): MapTrack {
        if (track.points.size < 2) return track

        val geoPointSource = GeoPoint(0.0, 0.0)
        val geoPointLast = GeoPoint(0.0, 0.0)

        val generalized = ArrayList<TrackPoint>(track.points.size)

        var lastGeneralizedPoint: TrackPoint? = null

        var latSum = 0.0
        var lonSum = 0.0
        var countsSum = 0
        var secondsSum = 0.0
        var averagedCoordinatePoints = 0
        var averagedDosePoints = 0
        var lastSourcePoint: TrackPoint? = null

        fun addToAveraging(p: TrackPoint) {
            if (!p.badCoordinates) {
                latSum += p.latitude
                lonSum += p.longitude
                averagedCoordinatePoints += 1
            }
            countsSum += p.counts
            secondsSum += p.seconds
            averagedDosePoints += 1
            lastSourcePoint = p
        }

        fun flushAveragedPoint() {
            if (averagedDosePoints == 0) return
            val fallbackPoint = lastSourcePoint ?: return

            val averagedPoint = TrackPoint(
                latitude = if (averagedCoordinatePoints > 0) latSum / averagedCoordinatePoints else fallbackPoint.latitude,
                longitude = if (averagedCoordinatePoints > 0) lonSum / averagedCoordinatePoints else fallbackPoint.longitude,
                timeMillis = fallbackPoint.timeMillis,
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
            } else if (!sourcePoint.badCoordinates && !currentLastPoint.badCoordinates) {
                geoPointSource.setCoords(sourcePoint.latitude, sourcePoint.longitude)
                geoPointLast.setCoords(currentLastPoint.latitude, currentLastPoint.longitude)
                val distanceMeters = geoPointSource.distanceToAsDouble(geoPointLast)

                if (distanceMeters >= minDistanceMeters && secondsSum >= minDurationSeconds) {
                    flushAveragedPoint()
                }
            } else {
                // At least one endpoint has bad coordinates — distance is meaningless,
                // fall back to duration-only flushing (same logic as the first-point case)
                if (secondsSum >= minDurationSeconds) {
                    flushAveragedPoint()
                }
            }
        }

        flushAveragedPoint()

        if (kdeScale != null) {
            return track.copy(points = applyKdeToGeneralizedTrack(track, generalized, kdeScale))
        }
        return track.copy(points = generalized)
    }

    private fun applyKdeToGeneralizedTrack(
        sourceTrack: MapTrack,
        generalizedTrackPoints: List<TrackPoint>,
        kdeScale: Double
    ): List<TrackPoint> {
        if (generalizedTrackPoints.isEmpty() || kdeScale <= 0.0) return generalizedTrackPoints

        val kernelEstimator = KernelDensityEstimator(coeff)
        var intervalStartSeconds = 0.0
        for (sample in sourceTrack.points) {
            kernelEstimator.addSampleInterval(
                intervalStartSeconds = intervalStartSeconds,
                durationSeconds = sample.seconds,
                counts = sample.counts
            )
            intervalStartSeconds += sample.seconds.coerceAtLeast(0.0)
        }

        val midpointSeconds = DoubleArray(generalizedTrackPoints.size)
        var elapsedSeconds = 0.0
        generalizedTrackPoints.forEachIndexed { idx, sample ->
            val duration = sample.seconds.coerceAtLeast(0.0)
            midpointSeconds[idx] = elapsedSeconds + duration * 0.5
            elapsedSeconds += duration
        }
        val estimatedDoseRate = kernelEstimator.estimateDoseRate(midpointSeconds, kdeScale)
        return generalizedTrackPoints.mapIndexed { idx, sample ->
            val doseRate = estimatedDoseRate.getOrElse(idx) { sample.doseRate }.coerceAtLeast(0.0)
            val counts = if (coeff > 0.0) {
                ((doseRate / coeff) * sample.seconds).roundToInt().coerceAtLeast(0)
            } else {
                sample.counts
            }
            sample.copy(doseRate = doseRate, counts = counts)
        }
    }
}
