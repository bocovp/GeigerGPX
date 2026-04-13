package com.github.bocovp.geigergpx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData

class TrackingViewModel(app: Application) : AndroidViewModel(app) {
    data class CountDisplayState(
        val totalCounts: Int = 0,
        val trackCounts: Int = 0,
        val measurementCounts: Int = 0
    )

    private val repo: TrackingRepository = (app as GeigerGpxApp).trackingRepository

    val isTracking: LiveData<Boolean> = repo.isTracking

    val trackDurationText: LiveData<String> = repo.trackDurationText

    val trackDurationSeconds: LiveData<Long> = repo.trackDurationSeconds

    val distanceMeters: LiveData<Double> = repo.distanceMeters

    val pointCount: LiveData<Int> = repo.pointCount

    val activeTrackPoints: LiveData<List<TrackPoint>> = repo.activeTrackPoints

    val cpsUpdate: LiveData<TrackingRepository.CpsUpdate> = repo.cpsUpdate

    val countDisplayState: LiveData<CountDisplayState> = MediatorLiveData<CountDisplayState>().apply {
        value = CountDisplayState()

        val update = {
            val tracking = repo.isTracking.value ?: false
            val measurementModeEnabled = repo.measurementModeEnabled.value ?: false
            val savedTrackCounts = repo.savedTrackCounts.value
            val total = repo.totalCounts.value ?: 0
            val trackStart = repo.countsAtTrackStart.value ?: 0
            val measurementStart = repo.countsAtMeasurementStart.value ?: 0
            val calculatedTrackCounts = if (tracking) total - trackStart else (savedTrackCounts ?: 0)
            val calculatedMeasurementCounts = if (measurementModeEnabled) total - measurementStart else 0
            value = CountDisplayState(
                totalCounts = total,
                trackCounts = calculatedTrackCounts.coerceAtLeast(0),
                measurementCounts = calculatedMeasurementCounts.coerceAtLeast(0)
            )
        }

        addSource(repo.isTracking) { update() }
        addSource(repo.measurementModeEnabled) { update() }
        addSource(repo.savedTrackCounts) { update() }
        addSource(repo.totalCounts) { update() }
        addSource(repo.countsAtTrackStart) { update() }
        addSource(repo.countsAtMeasurementStart) { update() }
    }

    val gpsStatus: LiveData<String> = repo.gpsStatus

    val audioStatus: LiveData<TrackingRepository.AudioStatus> = repo.audioStatus

    val measurementModeEnabled: LiveData<Boolean> = repo.measurementModeEnabled

    val uiTickMillis: LiveData<Long> = repo.uiTickMillis
}
