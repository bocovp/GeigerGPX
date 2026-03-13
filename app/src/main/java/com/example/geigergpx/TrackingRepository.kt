package com.example.geigergpx

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Shared app state for tracking/monitoring UI.
 *
 * This is intentionally not a ViewModel; it can be used by both the foreground service
 * and UI-layer ViewModels.
 */
class TrackingRepository {
    private val _isTracking = MutableLiveData(false)
    val isTracking: LiveData<Boolean> = _isTracking

    private val _durationText = MutableLiveData("00:00:00")
    val durationText: LiveData<String> = _durationText

    private val _distanceMeters = MutableLiveData(0.0)
    val distanceMeters: LiveData<Double> = _distanceMeters

    private val _pointCount = MutableLiveData(0)
    val pointCount: LiveData<Int> = _pointCount

    private val _currentCps = MutableLiveData(0.0)
    val currentCps: LiveData<Double> = _currentCps

    private val totalCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val _totalCounts = MutableLiveData(0)
    val totalCounts: LiveData<Int> = _totalCounts

    private val _trackCounts = MutableLiveData(0)
    val trackCounts: LiveData<Int> = _trackCounts

    private val _gpsStatus = MutableLiveData("unknown")
    val gpsStatus: LiveData<String> = _gpsStatus

    private val _audioStatus = MutableLiveData("unknown")
    val audioStatus: LiveData<String> = _audioStatus

    fun updateStatus(
        tracking: Boolean,
        durationSeconds: Long,
        distance: Double,
        points: Int,
        cps: Double,
        trackCounts: Int,
        gpsStatus: String
    ) {
        _isTracking.postValue(tracking)
        _durationText.postValue(formatDuration(durationSeconds))
        _distanceMeters.postValue(distance)
        _pointCount.postValue(points)
        _currentCps.postValue(cps)
        _trackCounts.postValue(trackCounts)
        _gpsStatus.postValue(gpsStatus)
    }

    fun updateMonitoringStatus(gpsStatus: String) {
        _gpsStatus.postValue(gpsStatus)
    }

    fun updateAudioStatus(audioStatus: String) {
        _audioStatus.postValue(audioStatus)
    }

    fun incrementTotalCounts() {
        _totalCounts.postValue(totalCounter.incrementAndGet())
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}

