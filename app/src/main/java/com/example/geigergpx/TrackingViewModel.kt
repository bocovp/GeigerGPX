package com.example.geigergpx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData

class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: TrackingRepository =
        (app as GeigerGpxApp).trackingRepository

    val isTracking: LiveData<Boolean> = repo.isTracking

    val durationText: LiveData<String> = repo.durationText

    val distanceMeters: LiveData<Double> = repo.distanceMeters

    val pointCount: LiveData<Int> = repo.pointCount

    val currentCps: LiveData<Double> = repo.currentCps

    val totalCounts: LiveData<Int> = repo.totalCounts

    val trackCounts: LiveData<Int> = repo.trackCounts

    val gpsStatus: LiveData<String> = repo.gpsStatus
}

