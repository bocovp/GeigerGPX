package com.github.bocovp.geigergpx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TrackingViewModel(app: Application) : AndroidViewModel(app) {
    data class CountDisplayState(
        val totalCounts: Int = 0,
        val trackCounts: Int = 0,
        val measurementCounts: Int = 0
    )

    private val repo: TrackingRepository = (app as GeigerGpxApp).trackingRepository

    val isTracking: StateFlow<Boolean> = repo.isTracking

    val trackDurationSeconds: StateFlow<Long> = repo.trackDurationSeconds

    val distanceMeters: StateFlow<Double> = repo.distanceMeters

    val pointCount: StateFlow<Int> = repo.pointCount

    val activeTrackPoints: StateFlow<List<TrackPoint>> = repo.activeTrackPoints

    val cpsUpdate: StateFlow<TrackingRepository.CpsUpdate> = repo.cpsUpdate

    val countDisplayState: StateFlow<CountDisplayState> = combine(
        repo.isTracking,
        repo.measurementModeEnabled,
        repo.savedTrackCounts,
        repo.totalCounts,
        repo.countsAtTrackStart,
        repo.countsAtMeasurementStart
    ) { tracking, measurementModeEnabled, savedTrackCounts, total, trackStart, measurementStart ->
        val calculatedTrackCounts = if (tracking) total - trackStart else (savedTrackCounts ?: 0)
        val calculatedMeasurementCounts = if (measurementModeEnabled) total - measurementStart else 0
            CountDisplayState(
                totalCounts = total,
                trackCounts = calculatedTrackCounts.coerceAtLeast(0),
                measurementCounts = calculatedMeasurementCounts.coerceAtLeast(0)
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CountDisplayState())

    val gpsStatus: StateFlow<String> = repo.gpsStatus

    val audioStatus: StateFlow<TrackingRepository.AudioStatus> = repo.audioStatus

    val measurementModeEnabled: StateFlow<Boolean> = repo.measurementModeEnabled

    val uiTickMillis: StateFlow<Long> = repo.uiTickMillis
}
