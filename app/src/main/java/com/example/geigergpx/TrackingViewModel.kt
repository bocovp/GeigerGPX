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

    val currentCps: LiveData<Double> = repo.currentCps

    val totalCounts: LiveData<Int> = repo.totalCounts

    val trackCounts: LiveData<Int> = MediatorLiveData<Int>().apply {
        // 1. Give it a starting value so it's never null
        value = 0

        val update = {
            val total = repo.totalCounts.value ?: 0
            val offset = repo.countsAtTrackStart.value ?: 0
            value = total - offset
        }

        // 2. Observe changes
        addSource(repo.totalCounts) { update() }
        addSource(repo.countsAtTrackStart) { update() }
    }

    val gpsStatus: LiveData<String> = repo.gpsStatus

    val audioStatus: LiveData<String> = repo.audioStatus

    val highAccuracyModeEnabled: LiveData<Boolean> = repo.highAccuracyModeEnabled
}
