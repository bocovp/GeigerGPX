package com.example.geigergpx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class TrackingViewModel(app: Application) : AndroidViewModel(app) {

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

    private val _totalCounts = MutableLiveData(0)
    val totalCounts: LiveData<Int> = _totalCounts

    private val _trackCounts = MutableLiveData(0)
    val trackCounts: LiveData<Int> = _trackCounts

    private val _gpsStatus = MutableLiveData("unknown")
    val gpsStatus: LiveData<String> = _gpsStatus

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

    fun incrementTotalCounts() {
        _totalCounts.postValue(_totalCounts.value?.plus(1) ?: 1)
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    companion object {
        @Volatile
        private var instance: TrackingViewModel? = null

        fun getInstance(app: Application): TrackingViewModel {
            return instance ?: synchronized(this) {
                instance ?: TrackingViewModel(app).also { instance = it }
            }
        }
    }
}

