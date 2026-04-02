package com.github.bocovp.geigergpx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TrackingRepository = (app as GeigerGpxApp).trackingRepository

    val isTracking: LiveData<Boolean> = repo.isTracking

    val durationText: LiveData<String> = repo.durationText

    val distanceMeters: LiveData<Double> = repo.distanceMeters

    val pointCount: LiveData<Int> = repo.pointCount

    val activeTrackPoints: LiveData<List<TrackPoint>> = repo.activeTrackPoints

    val cpsUpdate: LiveData<TrackingRepository.CpsUpdate> = repo.cpsUpdate

    val totalCounts: LiveData<Int> = repo.totalCounts

    val savedTrackCounts: LiveData<Int?> = repo.savedTrackCounts

    val trackCounts: LiveData<Int> = MediatorLiveData<Int>().apply {
        value = 0

        val update = {
            val tracking = repo.isTracking.value ?: false
            val savedCounts = repo.savedTrackCounts.value
            val total = repo.totalCounts.value ?: 0
            val offset = repo.countsAtTrackStart.value ?: 0
            value = when {
                tracking -> total - offset
                savedCounts != null -> savedCounts
                else -> 0
            }
        }

        addSource(repo.isTracking) { update() }
        addSource(repo.savedTrackCounts) { update() }
        addSource(repo.totalCounts) { update() }
        addSource(repo.countsAtTrackStart) { update() }
    }

    val gpsStatus: LiveData<String> = repo.gpsStatus

    val audioStatus: LiveData<TrackingRepository.AudioStatus> = repo.audioStatus

    val measurementModeEnabled: LiveData<Boolean> = repo.measurementModeEnabled
}
