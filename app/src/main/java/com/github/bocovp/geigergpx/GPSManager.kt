package com.github.bocovp.geigergpx

import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class GPSManager(
    context: Context,
    private val looper: Looper,
    private val maxSpeedKmhProvider: () -> Double,
    private val onLocationProcessed: (ProcessedLocation) -> Unit
) {
    data class ProcessedLocation(
        val location: Location,
        val nowMillis: Long,
        val mode: TrackWriter.GpsMode,
        val status: String
    )

    private val fusedLocationProviderClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val gpsSpoofingDetector = GpsSpoofingDetector()
    private val locationRequest: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        1000L
    ).setMinUpdateIntervalMillis(500L)
        .setWaitForAccurateLocation(false)
        .build()

    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            val location = result.lastLocation ?: return
            val now = System.currentTimeMillis()
            val state = gpsSpoofingDetector.process(location, maxSpeedKmhProvider(), now)
            lastObservedLocation = Location(location)
            onLocationProcessed(
                ProcessedLocation(
                    location = location,
                    nowMillis = now,
                    mode = state.mode.toTrackWriterMode(),
                    status = gpsSpoofingDetector.getStatusString(now)
                )
            )
        }
    }

    @Volatile
    private var lastObservedLocation: Location? = null

    fun startUpdates() {
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, looper)
    }

    fun stopUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
    }

    fun reset() {
        gpsSpoofingDetector.reset()
        lastObservedLocation = null
    }

    fun currentMode(nowMillis: Long): TrackWriter.GpsMode {
        return gpsSpoofingDetector.currentState(nowMillis).mode.toTrackWriterMode()
    }

    fun currentStatus(nowMillis: Long): String = gpsSpoofingDetector.getStatusString(nowMillis)

    fun lastLocationCopy(): Location? = lastObservedLocation?.let { Location(it) }

    private fun GpsSpoofingDetector.Mode.toTrackWriterMode(): TrackWriter.GpsMode {
        return when (this) {
            GpsSpoofingDetector.Mode.ACTIVE -> TrackWriter.GpsMode.ACTIVE
            GpsSpoofingDetector.Mode.INACTIVE -> TrackWriter.GpsMode.INACTIVE
            GpsSpoofingDetector.Mode.SPOOFING -> TrackWriter.GpsMode.SPOOFING
        }
    }
}
