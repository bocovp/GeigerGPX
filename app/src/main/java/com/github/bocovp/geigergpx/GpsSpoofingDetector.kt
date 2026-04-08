package com.github.bocovp.geigergpx

import android.location.Location
import java.util.Locale
import kotlin.math.max

class GpsSpoofingDetector {
    enum class Mode {
        INACTIVE,
        ACTIVE,
        SPOOFING
    }

    data class State(val mode: Mode, val speedKmh: Double = 0.0) {
        val isSpoofing: Boolean
            get() = mode == Mode.SPOOFING
    }

    companion object {
        const val DEFAULT_GPS_FIX_TIMEOUT_MS: Long = 5000L
    }

    private val lock = Any()
    private var lastLocation: Location? = null
    private var lastTimeMillis: Long = 0L
    private var state: State = State(mode = Mode.INACTIVE)

    var lastGpsFixMillis: Long = 0L
        private set

    fun process(loc: Location, maxSpeedKmh: Double, nowMillis: Long): State = synchronized(lock) {
        val previousLocation = lastLocation
        val previousTimeMillis = lastTimeMillis

        if (previousLocation != null && previousTimeMillis > 0L) {
            val timeDeltaSec = max(0.1, (nowMillis - previousTimeMillis) / 1000.0)
            val distance = previousLocation.distanceTo(loc).toDouble()
            val speedKmh = (distance / timeDeltaSec) * 3.6
            state = if (speedKmh > maxSpeedKmh) {
                State(mode = Mode.SPOOFING, speedKmh = speedKmh)
            } else {
                State(mode = Mode.ACTIVE)
            }
        } else {
            state = State(mode = Mode.ACTIVE)
        }

        lastLocation = Location(loc)
        lastTimeMillis = nowMillis
        lastGpsFixMillis = nowMillis
        state
    }

    fun currentState(
        nowMillis: Long,
        gpsFixTimeoutMillis: Long = DEFAULT_GPS_FIX_TIMEOUT_MS
    ): State = synchronized(lock) {
        if (lastGpsFixMillis == 0L || (nowMillis - lastGpsFixMillis) > gpsFixTimeoutMillis) {
            State(mode = Mode.INACTIVE)
        } else {
            state
        }
    }

    fun getStatusString(nowMillis: Long, locale: Locale = Locale.US): String {
        val current = currentState(nowMillis)
        return when (current.mode) {
            Mode.INACTIVE -> "Waiting"
            Mode.ACTIVE -> "Working"
            Mode.SPOOFING -> "Spoofing detected (${String.format(locale, "%.1f", current.speedKmh)} km/h)"
        }
    }

    fun reset() = synchronized(lock) {
        lastLocation = null
        lastTimeMillis = 0L
        lastGpsFixMillis = 0L
        state = State(mode = Mode.INACTIVE)
    }

}
