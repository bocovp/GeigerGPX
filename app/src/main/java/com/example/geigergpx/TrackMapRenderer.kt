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
    private var poiOverlay: PoiOverlay? = null
    private var heatmapOverlay: HeatmapOverlay? = null
    private val renderedPointCounts = mutableMapOf<String, Int>()
    private var lastMaxDose = Double.NEGATIVE_INFINITY
    private var lastRenderFingerprint: String = ""

    fun renderTracks(
        tracks: List<MapTrack>,
        pois: List<PoiMapItem>,
        isHeatmapMode: Boolean
    ) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(mapView.context)
        val doseCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val activeTrackIds = tracks.map { it.id }.toSet()

        var currentMax = Double.NEGATIVE_INFINITY
        tracks.forEach { track ->
            track.points.forEach { sample ->
                if (sample.doseRate > currentMax) currentMax = sample.doseRate
            }
        }
        pois.forEach { poi ->
            if (poi.doseRateForColor > currentMax) currentMax = poi.doseRateForColor
        }

        val currentMin = 0.0
        if (!currentMax.isFinite() || currentMax > 0.5) {
            currentMax = 0.5
        }

        val scaleChanged = currentMax != lastMaxDose
        lastMaxDose = currentMax

        if (scaleChanged) {
            tvHalf?.text = String.format("%.2f µSv/h", currentMax / 2)
            tvMax?.text = String.format("%.2f µSv/h", currentMax)
        }

        var latestPoint: GeoPoint? = null
        var shouldInvalidate = false

        if (isHeatmapMode) {
            if (trackOverlays.isNotEmpty()) {
                trackOverlays.values.forEach { mapView.overlays.remove(it) }
                trackOverlays.clear()
                renderedPointCounts.clear()
            }
            poiOverlay?.let {
                mapView.overlays.remove(it)
                poiOverlay = null
            }

            val overlay = heatmapOverlay ?: HeatmapOverlay(doseCoefficient).also {
                mapView.overlays.add(it)
                heatmapOverlay = it
            }
            overlay.doseCoefficient = doseCoefficient

            val poiTrack = MapTrack(
                id = "__pois__",
                title = "POIs",
                points = pois.map {
                    TrackSample(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        doseRate = it.doseRateForColor,
                        counts = it.counts,
                        seconds = it.seconds
                    )
                }
            )
            overlay.tracks = if (pois.isEmpty()) tracks else tracks + poiTrack
            overlay.minDose = currentMin
            overlay.maxDose = currentMax

            shouldInvalidate = true

            if (tracks.isNotEmpty() && tracks.last().points.isNotEmpty()) {
                val lastP = tracks.last().points.last()
                latestPoint = GeoPoint(lastP.latitude, lastP.longitude)
            } else if (pois.isNotEmpty()) {
                latestPoint = GeoPoint(pois.last().latitude, pois.last().longitude)
            }
        } else {
            heatmapOverlay?.let {
                mapView.overlays.remove(it)
                heatmapOverlay = null
            }

            removeDeletedTracks(activeTrackIds)

            tracks.forEach { track ->
                val trackPoints = track.points
                if (trackPoints.isEmpty()) return@forEach

                latestPoint = GeoPoint(trackPoints.last().latitude, trackPoints.last().longitude)

                val previousCount = renderedPointCounts[track.id] ?: 0
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

            val overlay = poiOverlay ?: PoiOverlay().also {
                mapView.overlays.add(it)
                poiOverlay = it
                shouldInvalidate = true
            }
            overlay.points = pois
            overlay.minDose = currentMin
            overlay.maxDose = currentMax
            if (pois.isNotEmpty()) {
                latestPoint = GeoPoint(pois.last().latitude, pois.last().longitude)
            }
            shouldInvalidate = true
        }

        val renderFingerprint = buildString {
            append(activeTrackIds.sorted().joinToString(","))
            append('|')
            append(pois.map { it.id }.sorted().joinToString(","))
        }
        val datasetChanged = renderFingerprint != lastRenderFingerprint
        if (datasetChanged) {
            fitToSelection(tracks, pois, latestPoint)
            lastRenderFingerprint = renderFingerprint
            shouldInvalidate = true
        }

        if (shouldInvalidate) {
            mapView.invalidate()
        }
    }

    private fun fitToSelection(tracks: List<MapTrack>, pois: List<PoiMapItem>, fallbackPoint: GeoPoint?) {
        val trackGeoPoints = tracks
            .flatMap { track -> track.points }
            .map { sample -> GeoPoint(sample.latitude, sample.longitude) }

        if (trackGeoPoints.isNotEmpty()) {
            fitToPoints(trackGeoPoints, fallbackPoint)
            return
        }

        val poiGeoPoints = pois.map { poi -> GeoPoint(poi.latitude, poi.longitude) }
        fitToPoints(poiGeoPoints, fallbackPoint)
    }

    private fun fitToPoints(geoPoints: List<GeoPoint>, fallbackPoint: GeoPoint?) {
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
