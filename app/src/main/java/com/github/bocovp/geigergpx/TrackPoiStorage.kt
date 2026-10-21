package com.github.bocovp.geigergpx

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object TrackPoiStorage {
    private val trackLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun addPoi(context: Context, trackId: String, poi: PoiEntry): Boolean {
        if (trackId == TrackCatalog.currentTrackId()) {
            val app = context.applicationContext as GeigerGpxApp
            return app.trackingRepository.addActiveTrackPoi(poi.copy(deviceName = null))
        }
        // Both catalog ID prefixes may refer to the same document.
        val lockKey = trackId.removePrefix("tree:").removePrefix("doc:")
        return trackLocks.getOrPut(lockKey) { Mutex() }.withLock {
            try {
                val loaded = EditableTrackStorage.loadTrack(context, trackId) ?: return@withLock false
                val updatedPois = loaded.pois + poi.copy(deviceName = null)
                EditableTrackStorage.createRcBackupIfNeeded(trackId)
                val saved = EditableTrackStorage.overwriteTrack(
                    context, trackId, loaded.points, edited = true,
                    sensitivityOverride = loaded.sensitivity, deviceNameOverride = loaded.deviceName,
                    pois = updatedPois
                )
                if (!saved) return@withLock false
                TrackCatalog.onTrackPoisUpdated(context, trackId, loaded.points, updatedPois)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }
}
