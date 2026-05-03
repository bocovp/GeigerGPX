package com.github.bocovp.geigergpx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _trackDurationSeconds = MutableStateFlow(0L)
    val trackDurationSeconds: StateFlow<Long> = _trackDurationSeconds.asStateFlow()

    private val _distanceMeters = MutableStateFlow(0.0)
    val distanceMeters: StateFlow<Double> = _distanceMeters.asStateFlow()

    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    private val _activeTrackPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val activeTrackPoints: StateFlow<List<TrackPoint>> = _activeTrackPoints.asStateFlow()

    private val _cpsUpdate = MutableStateFlow(CpsUpdate())
    val cpsUpdate: StateFlow<CpsUpdate> = _cpsUpdate.asStateFlow()

    private val totalCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val _totalCounts = MutableStateFlow(0)
    val totalCounts: StateFlow<Int> = _totalCounts.asStateFlow()

    private val _countsAtTrackStart = MutableStateFlow(0)
    val countsAtTrackStart: StateFlow<Int> = _countsAtTrackStart.asStateFlow()

    private val _countsAtMeasurementStart = MutableStateFlow(0)
    val countsAtMeasurementStart: StateFlow<Int> = _countsAtMeasurementStart.asStateFlow()

    private val _savedTrackCounts = MutableStateFlow<Int?>(null)
    val savedTrackCounts: StateFlow<Int?> = _savedTrackCounts.asStateFlow()

    private val _gpsStatus = MutableStateFlow("unknown")
    val gpsStatus: StateFlow<String> = _gpsStatus.asStateFlow()

    private val _audioStatus = MutableStateFlow(AudioStatus())
    val audioStatus: StateFlow<AudioStatus> = _audioStatus.asStateFlow()

    private val _measurementModeEnabled = MutableStateFlow(false)
    val measurementModeEnabled: StateFlow<Boolean> = _measurementModeEnabled.asStateFlow()
    private val _uiTickMillis = MutableStateFlow(0L)
    val uiTickMillis: StateFlow<Long> = _uiTickMillis.asStateFlow()

    fun updateTrackGeometry(distance: Double, points: Int) {
        _distanceMeters.value = distance
        _pointCount.value = points
    }

    fun updateTrackDuration(trackDurationSeconds: Long) {
        _trackDurationSeconds.value = trackDurationSeconds
    }

    fun updateTrackingState(tracking: Boolean, gpsStatus: String) {
        _isTracking.value = tracking
        _gpsStatus.value = gpsStatus
    }

    fun updateCpsSnapshot(cpsSnapshot: CpsSnapshot, onBeep: Boolean = false) {
        _cpsUpdate.value = CpsUpdate(snapshot = cpsSnapshot, onBeep = onBeep)
    }

    fun beginNewTrack() {
        _countsAtTrackStart.value = totalCounter.get()
        _savedTrackCounts.value = null
        _activeTrackPoints.value = emptyList()
    }

    fun setActiveTrackPoints(points: List<TrackPoint>) {
        _activeTrackPoints.value = points
    }

    fun finalizeTrackCounts() {
        val total = totalCounter.get()
        val offset = _countsAtTrackStart.value
        _savedTrackCounts.value = total - offset
    }

    fun discardTrackCounts() {
        _savedTrackCounts.value = null
    }

    fun updateMonitoringStatus(gpsStatus: String) {
        _gpsStatus.value = gpsStatus
    }

    fun updateAudioStatus(audioStatus: String, errorCode: Int) {
        _audioStatus.value = AudioStatus(status = audioStatus, errorCode = errorCode)
    }

    fun updateMeasurementMode(enabled: Boolean) {
        if (enabled) {
            _countsAtMeasurementStart.value = totalCounter.get()
        }
        _measurementModeEnabled.value = enabled
    }

    fun notifyUiTick(nowMillis: Long) {
        _uiTickMillis.value = nowMillis
    }

    /** Increment and return the global total beep count in a thread-safe way. */
    fun incrementTotalCounts(amount: Int): Int {
        val newValue = totalCounter.addAndGet(amount)
        _totalCounts.value = newValue
        return newValue
    }

    /** Get the latest global total beep count. */
    fun getTotalCounts(): Int = totalCounter.get()

    companion object {
        const val AUDIO_STATUS_WAITING = 0
        const val AUDIO_STATUS_WORKING = 1
        const val AUDIO_STATUS_ERROR = 2

        fun formatDuration(sec: Long): String {
            val safe = sec.coerceAtLeast(0L)
            val h = safe / 3600
            val m = (safe % 3600) / 60
            val s = safe % 60
            return String.format("%02d:%02d:%02d", h, m, s)
        }
    }
}
