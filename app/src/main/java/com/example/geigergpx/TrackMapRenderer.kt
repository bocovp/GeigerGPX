package com.example.geigergpx

import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class TrackMapRenderer(private val mapView: MapView) {

    private val trackOverlays = mutableMapOf<String, GradientTrackOverlay>()
    private val renderedPointCounts = mutableMapOf<String, Int>()
    private var lastMinDose = Double.POSITIVE_INFINITY
    private var lastMaxDose = Double.NEGATIVE_INFINITY
    private var hasPositionedToTrack = false

    fun renderTracks(tracks: List<MapTrack>) {
        val activeIds = tracks.map { it.id }.toSet()
        removeDeletedTracks(activeIds)

        // 1. Calculate current global scale
        var currentMin = Double.POSITIVE_INFINITY
        var currentMax = Double.NEGATIVE_INFINITY
        tracks.forEach { track ->
            track.points.forEach { sample ->
                if (sample.doseRate < currentMin) currentMin = sample.doseRate
                if (sample.doseRate > currentMax) currentMax = sample.doseRate
            }
        }

        if (!currentMin.isFinite() || !currentMax.isFinite()) {
            currentMin = 0.0; currentMax = 1.0
        }

        // Check if the scale itself has changed
        val scaleChanged = currentMin != lastMinDose || currentMax != lastMaxDose
        lastMinDose = currentMin
        lastMaxDose = currentMax

        var latestPoint: GeoPoint? = null
        var shouldInvalidate = false

        tracks.forEach { track ->
            val trackPoints = track.points
            if (trackPoints.isEmpty()) return@forEach

            latestPoint = GeoPoint(trackPoints.last().latitude, trackPoints.last().longitude)

            val previousCount = renderedPointCounts[track.id] ?: 0

            // 2. Update if point count changed OR if global scale changed
            if (previousCount != trackPoints.size || scaleChanged) {
                val overlay = trackOverlays.getOrPut(track.id) {
                    GradientTrackOverlay().also { mapView.overlays.add(it) }
                }

                overlay.points = trackPoints
                overlay.minDose = currentMin
                overlay.maxDose = currentMax

                renderedPointCounts[track.id] = trackPoints.size
                shouldInvalidate = true
            }
        }

        // 3. Auto-center logic
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
            trackOverlays.remove(id)?.let {
                mapView.overlays.remove(it)
            }
            renderedPointCounts.remove(id)
        }
    }
}