package com.github.bocovp.geigergpx

import android.widget.TextView
import androidx.preference.PreferenceManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import kotlin.math.pow

class TrackMapRenderer(
    private val mapView: MapView,
    private val tvHalf: TextView?,
    private val tvMax: TextView?
) {
    data class MapViewportState(
        val latitude: Double,
        val longitude: Double,
        val zoomLevel: Double
    )

    private val trackOverlays = mutableMapOf<String, GradientTrackOverlay>()
    private var poiOverlay: PoiOverlay? = null
    private var heatmapOverlay: HeatmapOverlay? = null
    private val renderedPointCounts = mutableMapOf<String, Int>()
    private val generalizedTracksById = mutableMapOf<String, List<TrackSample>>()
    private var lastMaxDose = Double.NEGATIVE_INFINITY
    private var lastRenderFingerprint: String = ""
    private var lastGeneralizationZoomLevel: Double? = null
    private var lastRenderedTracks: List<MapTrack> = emptyList()
    private var lastRenderedPois: List<PoiMapItem> = emptyList()
    private var lastFallbackPoint: GeoPoint? = null
    private var lastUseKernelEstimator: Boolean = false
    private var lastKdeScaleSeconds: Double? = null
    private var lastGeneralizationTrackFingerprint: String = ""
    private var highlightOverlay: DoseRateHighlightOverlay? = null
    private var highlightedPoint: DoseRateHighlightOverlay.HighlightPoint? = null

    fun renderTracks(
        tracks: List<MapTrack>,
        pois: List<PoiMapItem>,
        isHeatmapMode: Boolean,
        shouldAutoFit: Boolean,
        useKernelEstimator: Boolean = false,
        kdeScaleSeconds: Double? = null
    ): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(mapView.context)
        val doseCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val activeHighlightOverlay = highlightOverlay ?: DoseRateHighlightOverlay().also {
            mapView.overlays.add(it)
            highlightOverlay = it
        }

        val activeTrackIds = tracks.map { it.id }.toSet()
        val currentZoomLevel = mapView.zoomLevelDouble
        val zoomChangedForGeneralization = lastGeneralizationZoomLevel == null ||
                kotlin.math.abs((lastGeneralizationZoomLevel ?: currentZoomLevel) - currentZoomLevel) > 1e-9
        val modeChangedForGeneralization = lastUseKernelEstimator != useKernelEstimator
        val scaleChangedForGeneralization = kotlin.math.abs((lastKdeScaleSeconds ?: -1.0) - (kdeScaleSeconds ?: -1.0)) > 1e-9
        val currentTrackFingerprint = buildGeneralizationTrackFingerprint(tracks)
        val trackDataChangedForGeneralization = currentTrackFingerprint != lastGeneralizationTrackFingerprint
        val generalizationChanged = zoomChangedForGeneralization ||
                modeChangedForGeneralization ||
                scaleChangedForGeneralization ||
                trackDataChangedForGeneralization

        if (generalizationChanged) {
            recalculateGeneralizedTracks(tracks, currentZoomLevel, useKernelEstimator, kdeScaleSeconds)
            lastGeneralizationZoomLevel = currentZoomLevel
            lastUseKernelEstimator = useKernelEstimator
            lastKdeScaleSeconds = kdeScaleSeconds
            lastGeneralizationTrackFingerprint = currentTrackFingerprint
        }

        var currentMax = Double.NEGATIVE_INFINITY
        tracks.forEach { track ->
            val pointsForColorScale = if (useKernelEstimator) {
                generalizedTracksById[track.id] ?: track.points
            } else {
                track.points
            }
            pointsForColorScale.forEach { sample ->
                if (sample.badCoordinates) return@forEach
                if (sample.doseRate > currentMax) currentMax = sample.doseRate
            }
        }
        pois.forEach { poi ->
            if (poi.doseRateForColor > currentMax) currentMax = poi.doseRateForColor
        }

        val currentMin = 0.0
        if (!currentMax.isFinite() || currentMax > 0.5 || currentMax < 1e-9) {
            currentMax = 0.5
        }

        val scaleChanged = currentMax != lastMaxDose
        lastMaxDose = currentMax

        if (scaleChanged) {
            tvHalf?.text = String.format(java.util.Locale.US, "%.2f µSv/h", currentMax / 2)
            tvMax?.text = String.format(java.util.Locale.US, "%.2f µSv/h", currentMax)
        }
        activeHighlightOverlay.minDose = currentMin
        activeHighlightOverlay.maxDose = currentMax
        activeHighlightOverlay.highlightedPoint = highlightedPoint

        var latestPoint: GeoPoint? = null
        var shouldInvalidate = false
        var autoFitApplied = false

        if (isHeatmapMode) {
            clearHighlightedPoint()
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
            val filteredTracks = tracks.map { track ->
                track.copy(points = track.points.filterNot { it.badCoordinates })
            }
            overlay.tracks = if (pois.isEmpty()) filteredTracks else filteredTracks + poiTrack
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
                val trackPoints = if (useKernelEstimator) {
                    generalizedTracksById[track.id] ?: track.points
                } else {
                    track.points
                }

                if (trackPoints.isEmpty()) return@forEach

                latestPoint = GeoPoint(trackPoints.last().latitude, trackPoints.last().longitude)

                val previousCount = renderedPointCounts[track.id] ?: 0
                if (previousCount != trackPoints.size || scaleChanged || generalizationChanged) {
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
            val poisChanged = pois != lastRenderedPois
            if (poisChanged || scaleChanged) {
                overlay.points = pois
                overlay.minDose = currentMin
                overlay.maxDose = currentMax
                shouldInvalidate = true
            }
            if (pois.isNotEmpty()) {
                latestPoint = GeoPoint(pois.last().latitude, pois.last().longitude)
            }
        }

        val renderFingerprint = buildString {
            append(activeTrackIds.sorted().joinToString(","))
            append('|')
            append(pois.map { it.id }.sorted().joinToString(","))
        }
        val datasetChanged = renderFingerprint != lastRenderFingerprint
        if (datasetChanged && shouldAutoFit) {
            autoFitApplied = fitToSelection(tracks, pois, latestPoint, animate = true)
            lastRenderFingerprint = renderFingerprint
            shouldInvalidate = true
        } else if (datasetChanged) {
            lastRenderFingerprint = renderFingerprint
        }

        lastRenderedTracks = tracks
        lastRenderedPois = pois
        lastFallbackPoint = latestPoint

        if (shouldInvalidate) {
            mapView.invalidate()
        }
        return autoFitApplied
    }

    fun autoZoomToSelection(animate: Boolean): Boolean {
        return fitToSelection(lastRenderedTracks, lastRenderedPois, lastFallbackPoint, animate)
    }

    fun restoreViewport(viewportState: MapViewportState) {
        mapView.controller.setCenter(GeoPoint(viewportState.latitude, viewportState.longitude))
        mapView.controller.setZoom(viewportState.zoomLevel)
    }

    fun currentViewportState(): MapViewportState {
        val center = mapView.mapCenter
        return MapViewportState(
            latitude = center.latitude,
            longitude = center.longitude,
            zoomLevel = mapView.zoomLevelDouble
        )
    }

    private fun fitToSelection(
        tracks: List<MapTrack>,
        pois: List<PoiMapItem>,
        fallbackPoint: GeoPoint?,
        animate: Boolean
    ): Boolean {
        val trackGeoPoints = tracks
            .flatMap { track -> track.points.filterNot { it.badCoordinates } }
            .map { sample -> GeoPoint(sample.latitude, sample.longitude) }

        if (trackGeoPoints.isNotEmpty()) {
            return fitToPoints(trackGeoPoints, fallbackPoint, animate)
        }

        val poiGeoPoints = pois.map { poi -> GeoPoint(poi.latitude, poi.longitude) }
        return fitToPoints(poiGeoPoints, fallbackPoint, animate)
    }

    private fun fitToPoints(geoPoints: List<GeoPoint>, fallbackPoint: GeoPoint?, animate: Boolean): Boolean {
        when {
            geoPoints.size > 1 -> {
                val boundingBox = BoundingBox.fromGeoPointsSafe(geoPoints)
                mapView.zoomToBoundingBox(boundingBox, animate, 64)
                return true
            }
            geoPoints.size == 1 -> {
                mapView.controller.setCenter(geoPoints.first())
                mapView.controller.setZoom(18.0)
                return true
            }
            fallbackPoint != null -> {
                mapView.controller.setCenter(fallbackPoint)
                mapView.controller.setZoom(18.0)
                return true
            }
        }
        return false
    }


    fun clearHighlightedPoint() {
        highlightedPoint = null
        highlightOverlay?.highlightedPoint = null
        mapView.invalidate()
    }

    fun updateHighlightedPointForScreenPosition(
        screenX: Float,
        screenY: Float,
        useKernelEstimator: Boolean,
        maxDistancePx: Double
    ): Boolean {
        val nearest = findNearestTrackSample(screenX, screenY, useKernelEstimator, maxDistancePx)

        highlightedPoint = nearest?.let { sample ->
            val value = if (showCpsUnit()) {
                val seconds = sample.seconds.takeIf { it > 1e-9 } ?: 1.0
                sample.counts / seconds
            } else {
                sample.doseRate
            }
            val unit = if (showCpsUnit()) "cps" else "μSv/h"
            DoseRateHighlightOverlay.HighlightPoint(
                latitude = sample.latitude,
                longitude = sample.longitude,
                doseRateForColor = sample.doseRate,
                doseLabel = String.format(java.util.Locale.US, "%.3f %s", value, unit)
            )
        }

        highlightOverlay?.highlightedPoint = highlightedPoint
        mapView.invalidate()
        return nearest != null
    }

    private fun findNearestTrackSample(
        screenX: Float,
        screenY: Float,
        useKernelEstimator: Boolean,
        maxDistancePx: Double
    ): TrackSample? {
        var nearest: TrackSample? = null
        var nearestDistanceSquared = Double.POSITIVE_INFINITY
        val maxDistanceSquared = maxDistancePx.pow(2)

        val screenPoint = android.graphics.Point()
        val geoPoint = GeoPoint(0.0, 0.0)

        lastRenderedTracks.forEach { track ->
            val points = if (useKernelEstimator) {
                generalizedTracksById[track.id] ?: track.points
            } else {
                generalizedTracksById[track.id] ?: track.points
            }

            points.forEach { sample ->
                if (sample.badCoordinates) return@forEach

                geoPoint.setCoords(sample.latitude, sample.longitude)
                mapView.projection.toPixels(geoPoint, screenPoint)
                val dx = screenPoint.x - screenX
                val dy = screenPoint.y - screenY
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared.toDouble()
                    nearest = sample
                }
            }
        }

        return if (nearestDistanceSquared <= maxDistanceSquared) nearest else null
    }

    private fun showCpsUnit(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(mapView.context)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        return kotlin.math.abs(coeff - 1.0) < 1e-9
    }

    private fun removeDeletedTracks(activeIds: Set<String>) {
        val toRemove = trackOverlays.keys.filterNot { it in activeIds }
        toRemove.forEach { id ->
            trackOverlays.remove(id)?.let {
                mapView.overlays.remove(it)
            }
            renderedPointCounts.remove(id)
            generalizedTracksById.remove(id)
        }
    }

    private fun buildGeneralizationTrackFingerprint(tracks: List<MapTrack>): String {
        if (tracks.isEmpty()) return ""
        return buildString {
            tracks.forEach { track ->
                val lastPoint = track.points.lastOrNull()
                append(track.id)
                append(':')
                append(track.points.size)
                append(':')
                append(lastPoint?.seconds ?: -1.0)
                append(':')
                append(lastPoint?.counts ?: -1)
                append(':')
                append(lastPoint?.latitude ?: 0.0)
                append(':')
                append(lastPoint?.longitude ?: 0.0)
                append(';')
            }
        }
    }

    private fun recalculateGeneralizedTracks(
        tracks: List<MapTrack>,
        zoomLevel: Double,
        useKernelEstimator: Boolean,
        kdeScaleSeconds: Double?
    ) {
        val mapLatitude = mapView.mapCenter.latitude
        val metersPerPixel = TileSystem.GroundResolution(mapLatitude, zoomLevel)
        val minDistanceMeters = metersPerPixel * 10.0

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(mapView.context)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0


        val generalizer = TrackGeneralizer(minDistanceMeters, coeff)
        val kdeScale = if (useKernelEstimator) kdeScaleSeconds?.coerceAtLeast(KdeScaleSlider.MIN_SECONDS.toDouble()) else null

        generalizedTracksById.clear()
        tracks.forEach { track ->
            generalizedTracksById[track.id] = generalizer.generalize(track, kdeScale).points
        }
    }
}
