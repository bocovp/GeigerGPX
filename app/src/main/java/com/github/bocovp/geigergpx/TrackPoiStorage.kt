package com.github.bocovp.geigergpx

import android.content.Context

object TrackPoiStorage {
    suspend fun addPoi(context: Context, trackId: String, poi: PoiEntry): Boolean {
        if (trackId == TrackCatalog.currentTrackId()) {
            val app = context.applicationContext as GeigerGpxApp
            if (!app.trackingRepository.isTracking.value) return false
            app.trackingRepository.addActiveTrackPoi(poi.copy(deviceName = null))
            return true
        }
        val loaded = EditableTrackStorage.loadTrack(context, trackId) ?: return false
        val updatedPois = loaded.pois + poi.copy(deviceName = null)
        return runCatching {
            EditableTrackStorage.createRcBackupIfNeeded(trackId)
            EditableTrackStorage.overwriteTrack(
                context, trackId, loaded.points, edited = true,
                sensitivityOverride = loaded.sensitivity, deviceNameOverride = loaded.deviceName,
                pois = updatedPois
            )
            TrackCatalog.onTrackPoisUpdated(trackId, loaded.points, updatedPois)
            true
        }.getOrDefault(false)
    }
}
