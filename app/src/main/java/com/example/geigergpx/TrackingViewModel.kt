package com.example.geigergpx

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

    val cpsSnapshot: LiveData<TrackingRepository.CpsSnapshot> = repo.cpsSnapshot

    val totalCounts: LiveData<Int> = repo.totalCounts

    val trackCounts: LiveData<Int> = MediatorLiveData<Int>().apply {
        // Persist last completed track counts after tracking stops.
        value = 0

        val update = {
            val isTrackingNow = repo.isTracking.value == true
            value = if (isTrackingNow) {
                val total = repo.totalCounts.value ?: 0
                val offset = repo.countsAtTrackStart.value ?: 0
                (total - offset).coerceAtLeast(0)
            } else {
                repo.lastTrackCounts.value ?: 0
            }
        }

        addSource(repo.isTracking) { update() }
        addSource(repo.totalCounts) { update() }
        addSource(repo.countsAtTrackStart) { update() }
        addSource(repo.lastTrackCounts) { update() }
    }

    val gpsStatus: LiveData<String> = repo.gpsStatus

    val audioStatus: LiveData<String> = repo.audioStatus

    val highAccuracyModeEnabled: LiveData<Boolean> = repo.highAccuracyModeEnabled
}
