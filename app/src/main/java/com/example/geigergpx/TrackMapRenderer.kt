package com.example.geigergpx

import android.widget.TextView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class TrackMapRenderer(
    private val mapView: MapView,
    private val tvHalf: TextView?,
    private val tvMax: TextView?
) {

    private val trackOverlays = mutableMapOf<String, GradientTrackOverlay>()
    private var heatmapOverlay: HeatmapOverlay? = null
    private val renderedPointCounts = mutableMapOf<String, Int>()
    private var lastMaxDose = Double.NEGATIVE_INFINITY
    private var lastRenderedTrackIds: Set<String> = emptySet()

    fun renderTracks(tracks: List<MapTrack>,
                     isHeatmapMode: Boolean) {

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(mapView.context)
        val doseCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val activeIds = tracks.map { it.id }.toSet()

        // 1. Calculate current global scale
        var currentMax = Double.NEGATIVE_INFINITY
        tracks.forEach { track ->
            track.points.forEach { sample ->
                if (sample.doseRate > currentMax) currentMax = sample.doseRate
            }
        }

        val currentMin = 0.0
        if (!currentMax.isFinite() || currentMax > 0.5) { // Clipping at 0.5 uSv/h
            currentMax = 0.5
        }

        // Check if the scale itself has changed
        val scaleChanged = currentMax != lastMaxDose
        lastMaxDose = currentMax

        if (scaleChanged) {
            tvHalf?.text = String.format("%.2f µSv/h", currentMax / 2)
            tvMax?.text = String.format("%.2f µSv/h", currentMax)
        }

        var latestPoint: GeoPoint? = null
        var shouldInvalidate = false

        // 2. Mode Branching
        if (isHeatmapMode) {
            // --- HEATMAP MODE ---

            // A. Clean up Line Overlays if they exist
            if (trackOverlays.isNotEmpty()) {
                trackOverlays.values.forEach { mapView.overlays.remove(it) }
                trackOverlays.clear()
                renderedPointCounts.clear()
            }

            // B. Initialize or Update Heatmap Overlay
            // We pass the coefficient here to ensure the overlay uses the latest setting
            val overlay = heatmapOverlay ?: HeatmapOverlay(doseCoefficient).also {
                mapView.overlays.add(it)
                heatmapOverlay = it
            }

            // CRITICAL: Always update the coefficient from settings
            // This ensures that if the user changed settings, the existing overlay updates.
            overlay.doseCoefficient = doseCoefficient

            // C. Update Data
            // We pass ALL tracks to the single overlay
            overlay.tracks = tracks
            overlay.minDose = currentMin
            overlay.maxDose = currentMax

            // Always invalidate in heatmap mode as grid needs recalc if tracks change
            shouldInvalidate = true

            // Capture latest point for auto-center logic
            if (tracks.isNotEmpty() && tracks.last().points.isNotEmpty()) {
                val lastP = tracks.last().points.last()
                latestPoint = GeoPoint(lastP.latitude, lastP.longitude)
            }

        } else {
            // --- LINE MODE ---
            heatmapOverlay?.let {
                mapView.overlays.remove(it)
                heatmapOverlay = null
            }

            // B. Clean up deleted tracks
            removeDeletedTracks(activeIds)

            // C. Draw/Update individual lines

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
        }

        // 3. Auto-center / fit logic
        val trackSetChanged = activeIds != lastRenderedTrackIds
        if (trackSetChanged) {
            fitToTracks(tracks, latestPoint)
            lastRenderedTrackIds = activeIds
            shouldInvalidate = true
        }

        if (shouldInvalidate) {
            mapView.invalidate()
        }
    }

    private fun fitToTracks(tracks: List<MapTrack>, fallbackPoint: GeoPoint?) {
        val geoPoints = tracks
            .flatMap { track -> track.points }
            .map { sample -> GeoPoint(sample.latitude, sample.longitude) }

        when {
            geoPoints.size > 1 -> {
                val boundingBox = BoundingBox.fromGeoPointsSafe(geoPoints)
                mapView.zoomToBoundingBox(boundingBox, true, 64)
            }
            geoPoints.size == 1 -> {
                mapView.controller.setCenter(geoPoints.first())
                mapView.controller.setZoom(18.0)
            }
            fallbackPoint != null -> {
                mapView.controller.setCenter(fallbackPoint)
                mapView.controller.setZoom(18.0)
            }
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