package com.example.geigergpx

import android.graphics.Color
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class TrackMapRenderer(private val mapView: MapView) {

    private val trackOverlays = mutableMapOf<String, Polyline>()
    private val renderedPointCounts = mutableMapOf<String, Int>()
    private var hasPositionedToTrack = false

    fun renderTracks(tracks: List<MapTrack>) {
        val activeIds = tracks.map { it.id }.toSet()
        removeDeletedTracks(activeIds)

        var shouldInvalidate = false
        var minDose = Double.POSITIVE_INFINITY
        var maxDose = Double.NEGATIVE_INFINITY
        tracks.forEach { track ->
            track.points.forEach { sample ->
                if (sample.doseRate < minDose) minDose = sample.doseRate
                if (sample.doseRate > maxDose) maxDose = sample.doseRate
            }
        }
        if (!minDose.isFinite() || !maxDose.isFinite()) {
            minDose = 0.0
            maxDose = 1.0
        }

        var latestPoint: GeoPoint? = null

        tracks.forEach { track ->
            val trackPoints = track.points
            if (trackPoints.isEmpty()) return@forEach

            val geoPoints = ArrayList<GeoPoint>(trackPoints.size)
            var doseSum = 0.0
            trackPoints.forEach { sample ->
                geoPoints.add(GeoPoint(sample.latitude, sample.longitude))
                doseSum += sample.doseRate
            }
            latestPoint = geoPoints.lastOrNull() ?: latestPoint

            val previousCount = renderedPointCounts[track.id] ?: 0
            if (previousCount == trackPoints.size) return@forEach

            val polyline = trackOverlays.getOrPut(track.id) {
                Polyline(mapView).also {
                    it.outlinePaint.strokeWidth = 8f
                    it.isGeodesic = false
                    mapView.overlays.add(it)
                }
            }

            polyline.setPoints(geoPoints)
            val averageDose = doseSum / trackPoints.size.toDouble()
            polyline.outlinePaint.color = colorForDose(averageDose, minDose, maxDose)

            renderedPointCounts[track.id] = trackPoints.size
            shouldInvalidate = true
        }

        if (!hasPositionedToTrack && latestPoint != null) {
            mapView.controller.setCenter(latestPoint)
            mapView.controller.setZoom(18.0)
            hasPositionedToTrack = true
            shouldInvalidate = true
        }

        if (shouldInvalidate) {
            mapView.invalidate()
        }
    }

    private fun removeDeletedTracks(activeIds: Set<String>) {
        val toRemove = trackOverlays.keys.filterNot { it in activeIds }
        toRemove.forEach { id ->
            trackOverlays[id]?.let { mapView.overlays.remove(it) }
            trackOverlays.remove(id)
            renderedPointCounts.remove(id)
        }
    }

    private fun colorForDose(value: Double, minDose: Double, maxDose: Double): Int {
        val normalized = if (maxDose > minDose) (value - minDose) / (maxDose - minDose) else 0.0
        val clamped = normalized.coerceIn(0.0, 1.0)
        return if (clamped < 0.5) {
            val t = clamped / 0.5
            Color.rgb((255 * t).toInt(), 255, 0)
        } else {
            val t = (clamped - 0.5) / 0.5
            Color.rgb(255, (255 * (1.0 - t)).toInt(), 0)
        }
    }
}
