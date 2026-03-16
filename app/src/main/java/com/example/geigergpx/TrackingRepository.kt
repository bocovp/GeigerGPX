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
    data class CpsSnapshot(
        val cps: Double = 0.0,
        val sampleCount: Int = 0,
        val oldestTimestampMillis: Long = 0L
    )

    private val _isTracking = MutableLiveData(false)
    val isTracking: LiveData<Boolean> = _isTracking

    private val _durationText = MutableLiveData("00:00:00")
    val durationText: LiveData<String> = _durationText

    private val _distanceMeters = MutableLiveData(0.0)
    val distanceMeters: LiveData<Double> = _distanceMeters

    private val _pointCount = MutableLiveData(0)
    val pointCount: LiveData<Int> = _pointCount

    private val _cpsSnapshot = MutableLiveData(CpsSnapshot())
    val cpsSnapshot: LiveData<CpsSnapshot> = _cpsSnapshot

    private val totalCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val _totalCounts = MutableLiveData(0)
    val totalCounts: LiveData<Int> = _totalCounts

    private val _countsAtTrackStart = MutableLiveData(0)
    val countsAtTrackStart: LiveData<Int> = _countsAtTrackStart

    private val _savedTrackCounts = MutableLiveData<Int?>(null)
    val savedTrackCounts: LiveData<Int?> = _savedTrackCounts

    private val _gpsStatus = MutableLiveData("unknown")
    val gpsStatus: LiveData<String> = _gpsStatus

    private val _audioStatus = MutableLiveData("unknown")
    val audioStatus: LiveData<String> = _audioStatus

    private val _highAccuracyModeEnabled = MutableLiveData(false)
    val highAccuracyModeEnabled: LiveData<Boolean> = _highAccuracyModeEnabled

    fun updateStatus(
        tracking: Boolean,
        durationSeconds: Long,
        distance: Double,
        points: Int,
        cpsSnapshot: CpsSnapshot,
        gpsStatus: String
    ) {
        _isTracking.postValue(tracking)
        _durationText.postValue(formatDuration(durationSeconds))
        _distanceMeters.postValue(distance)
        _pointCount.postValue(points)
        _cpsSnapshot.postValue(cpsSnapshot)
        _gpsStatus.postValue(gpsStatus)
    }

    fun updateCpsSnapshot(cpsSnapshot: CpsSnapshot) {
        _cpsSnapshot.postValue(cpsSnapshot)
    }

    fun beginNewTrack() {
        _countsAtTrackStart.postValue(totalCounter.get())
        _savedTrackCounts.postValue(null)
    }

    fun finalizeTrackCounts() {
        val total = totalCounter.get()
        val offset = _countsAtTrackStart.value ?: 0
        _savedTrackCounts.postValue(total - offset)
    }

    fun updateMonitoringStatus(gpsStatus: String) {
        _gpsStatus.postValue(gpsStatus)
    }

    fun updateAudioStatus(audioStatus: String) {
        _audioStatus.postValue(audioStatus)
    }

    fun updateHighAccuracyMode(enabled: Boolean) {
        _highAccuracyModeEnabled.postValue(enabled)
    }

    /** Increment and return the global total beep count in a thread-safe way. */
    fun incrementTotalCounts(amount: Int): Int {
        val newValue = totalCounter.addAndGet(amount)
        _totalCounts.postValue(newValue)
        return newValue
    }

    /** Get the latest global total beep count. */
    fun getTotalCounts(): Int = totalCounter.get()

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
