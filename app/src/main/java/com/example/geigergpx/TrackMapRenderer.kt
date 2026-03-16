package com.example.geigergpx

import android.graphics.Color
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class TrackMapRenderer(private val mapView: MapView) {

    private val trackOverlays = mutableMapOf<String, MutableList<Polyline>>()
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
            trackPoints.forEach { sample ->
                geoPoints.add(GeoPoint(sample.latitude, sample.longitude))
            }
            latestPoint = geoPoints.lastOrNull() ?: latestPoint

            val previousCount = renderedPointCounts[track.id] ?: 0
            if (previousCount == trackPoints.size) return@forEach

            trackOverlays.remove(track.id)?.forEach { mapView.overlays.remove(it) }
            trackOverlays[track.id] = createSegmentedPolylines(track, geoPoints, minDose, maxDose)

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
            trackOverlays[id]?.forEach { mapView.overlays.remove(it) }
            trackOverlays.remove(id)
            renderedPointCounts.remove(id)
        }
    }

    private fun createSegmentedPolylines(
        track: MapTrack,
        geoPoints: List<GeoPoint>,
        minDose: Double,
        maxDose: Double
    ): MutableList<Polyline> {
        if (geoPoints.size < 2) {
            val fallback = Polyline(mapView).also {
                it.outlinePaint.strokeWidth = 8f
                it.isGeodesic = false
                it.setPoints(geoPoints)
                it.outlinePaint.color = colorForDose(track.points.first().doseRate, minDose, maxDose)
                mapView.overlays.add(it)
            }
            return mutableListOf(fallback)
        }

        val segmentPolylines = mutableListOf<Polyline>()
        for (index in 0 until geoPoints.lastIndex) {
            val start = geoPoints[index]
            val end = geoPoints[index + 1]
            val startDose = track.points[index].doseRate
            val endDose = track.points[index + 1].doseRate
            val segmentDose = (startDose + endDose) / 2.0

            val segmentPolyline = Polyline(mapView).also {
                it.outlinePaint.strokeWidth = 8f
                it.isGeodesic = false
                it.setPoints(listOf(start, end))
                it.outlinePaint.color = colorForDose(segmentDose, minDose, maxDose)
                mapView.overlays.add(it)
            }
            segmentPolylines.add(segmentPolyline)
        }

        return segmentPolylines
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
