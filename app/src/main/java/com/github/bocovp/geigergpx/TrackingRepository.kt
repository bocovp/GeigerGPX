package com.github.bocovp.geigergpx

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Shared app state for tracking/monitoring UI.
 *
 * This is intentionally not a ViewModel; it can be used by both the foreground service
 * and UI-layer ViewModels.
 */
class TrackingRepository {
    data class AudioStatus(
        val status: String = "unknown",
        val errorCode: Int = AUDIO_STATUS_WAITING
    )

    data class CpsSnapshot(
        val sampleCount: Int = 0,
        val oldestTimestampMillis: Long = 0L,
        val measurementStartTimestampMillis: Long = 0L
    )

    data class CpsUpdate(
        val snapshot: CpsSnapshot = CpsSnapshot(),
        val onBeep: Boolean = false
    )

    private val _isTracking = MutableLiveData(false)
    val isTracking: LiveData<Boolean> = _isTracking

    private val _durationText = MutableLiveData("00:00:00")
    val durationText: LiveData<String> = _durationText

    private val _distanceMeters = MutableLiveData(0.0)
    val distanceMeters: LiveData<Double> = _distanceMeters

    private val _pointCount = MutableLiveData(0)
    val pointCount: LiveData<Int> = _pointCount

    private val _activeTrackPoints = MutableLiveData<List<TrackPoint>>(emptyList())
    val activeTrackPoints: LiveData<List<TrackPoint>> = _activeTrackPoints

    private val _cpsUpdate = MutableLiveData(CpsUpdate())
    val cpsUpdate: LiveData<CpsUpdate> = _cpsUpdate

    private val totalCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val _totalCounts = MutableLiveData(0)
    val totalCounts: LiveData<Int> = _totalCounts

    private val _countsAtTrackStart = MutableLiveData(0)
    val countsAtTrackStart: LiveData<Int> = _countsAtTrackStart

    private val _savedTrackCounts = MutableLiveData<Int?>(null)
    val savedTrackCounts: LiveData<Int?> = _savedTrackCounts

    private val _gpsStatus = MutableLiveData("unknown")
    val gpsStatus: LiveData<String> = _gpsStatus

    private val _audioStatus = MutableLiveData(AudioStatus())
    val audioStatus: LiveData<AudioStatus> = _audioStatus

    private val _measurementModeEnabled = MutableLiveData(false)
    val measurementModeEnabled: LiveData<Boolean> = _measurementModeEnabled

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
        _cpsUpdate.postValue(CpsUpdate(snapshot = cpsSnapshot, onBeep = false))
        _gpsStatus.postValue(gpsStatus)
    }

    fun updateCpsSnapshot(cpsSnapshot: CpsSnapshot, onBeep: Boolean = false) {
        _cpsUpdate.postValue(CpsUpdate(snapshot = cpsSnapshot, onBeep = onBeep))
    }

    fun beginNewTrack() {
        _countsAtTrackStart.postValue(totalCounter.get())
        _savedTrackCounts.postValue(null)
        _activeTrackPoints.postValue(emptyList())
    }

    fun setActiveTrackPoints(points: List<TrackPoint>) {
        _activeTrackPoints.postValue(points)
    }

    fun finalizeTrackCounts() {
        val total = totalCounter.get()
        val offset = _countsAtTrackStart.value ?: 0
        _savedTrackCounts.postValue(total - offset)
    }

    fun discardTrackCounts() {
        _savedTrackCounts.postValue(null)
    }

    fun updateMonitoringStatus(gpsStatus: String) {
        _gpsStatus.postValue(gpsStatus)
    }

    fun updateAudioStatus(audioStatus: String, errorCode: Int) {
        _audioStatus.postValue(AudioStatus(status = audioStatus, errorCode = errorCode))
    }

    fun updateMeasurementMode(enabled: Boolean) {
        _measurementModeEnabled.postValue(enabled)
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

    companion object {
        const val AUDIO_STATUS_WAITING = 0
        const val AUDIO_STATUS_WORKING = 1
        const val AUDIO_STATUS_ERROR = 2
    }
}
