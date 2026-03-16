package com.example.geigergpx

import android.graphics.Color
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class TrackMapRenderer(private val mapView: MapView) {

    private val trackOverlays = mutableListOf<Polyline>()
    private var hasZoomedToTrack = false

    fun renderTracks(tracks: List<MapTrack>) {
        clearTracks()

        val allPoints = mutableListOf<GeoPoint>()
        val allDoseRates = tracks.flatMap { it.points }.map { it.doseRate }
        val minDose = allDoseRates.minOrNull() ?: 0.0
        val maxDose = allDoseRates.maxOrNull() ?: 1.0

        tracks.forEach { track ->
            if (track.points.size < 2) {
                track.points.forEach { point ->
                    allPoints.add(GeoPoint(point.latitude, point.longitude))
                }
                return@forEach
            }

            val geoPoints = track.points.map { GeoPoint(it.latitude, it.longitude) }
            allPoints.addAll(geoPoints)

            val averageDose = track.points.map { it.doseRate }.average()
            val polyline = Polyline(mapView).apply {
                setPoints(geoPoints)
                outlinePaint.color = colorForDose(averageDose, minDose, maxDose)
                outlinePaint.strokeWidth = 8f
                isGeodesic = true
            }
            mapView.overlays.add(polyline)
            trackOverlays.add(polyline)
        }

        if (allPoints.isNotEmpty() && !hasZoomedToTrack) {
            val box = BoundingBox.fromGeoPointsSafe(allPoints)
            mapView.zoomToBoundingBox(box, false, 64)
            hasZoomedToTrack = true
        }

        mapView.invalidate()
    }

    private fun clearTracks() {
        mapView.overlays.removeAll(trackOverlays)
        trackOverlays.clear()
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
