package com.example.geigergpx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max

class TrackingService : Service() {

    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private val viewModel by lazy { TrackingViewModel.getInstance(application) }

    // Track recording state
    private var startTimeMillis: Long = 0L
    private var totalDistance: Double = 0.0
    private val writtenPoints = mutableListOf<TrackPoint>()

    private var lastWrittenLocation: Location? = null
    private var lastWrittenTime: Long = 0L

    @Volatile
    private var beepCountSinceLastPoint: Int = 0

    @Volatile
    private var trackBeepCount: Int = 0

    @Volatile
    private var lastGpsFixMillis: Long = 0L

    @Volatile
    private var gpsSpoofingActive: Boolean = false

    private var audioBeepDetector: AudioBeepDetector? = null

    // Monitoring state (GPS + audio active, but not recording a track)
    private var isMonitoring: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setupLocationRequest()
        setupLocationCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    // -------------------------------------------------------------------------
    // Monitoring (foreground-only, no track recording)
    // -------------------------------------------------------------------------

    private fun startMonitoring() {
        if (isMonitoring || startTimeMillis != 0L) return  // already monitoring or tracking
        isMonitoring = true

        startForeground(NOTIF_ID, buildNotification("Monitoring..."))

        if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
            fusedLocation.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
            startBeepDetector()
            viewModel.updateMonitoringStatus(gpsStatus = "Waiting")
        } else {
            isMonitoring = false
            stopSelf()
        }
    }

    private fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false

        // Only remove location updates and stop beep detector if not tracking
        if (startTimeMillis == 0L) {
            fusedLocation.removeLocationUpdates(locationCallback)
            stopBeepDetector()
        }

        // Only stop foreground and self-destruct if not tracking
        if (startTimeMillis == 0L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } else {
            // Still tracking: downgrade notification to indicate background tracking
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("Tracking (background)..."))
        }
    }

    // -------------------------------------------------------------------------
    // Track recording
    // -------------------------------------------------------------------------

    private fun startTracking() {
        if (startTimeMillis != 0L) return  // already tracking

        startTimeMillis = System.currentTimeMillis()
        totalDistance = 0.0
        writtenPoints.clear()
        lastWrittenLocation = null
        lastWrittenTime = 0L
        beepCountSinceLastPoint = 0
        trackBeepCount = 0
        lastGpsFixMillis = 0L
        gpsSpoofingActive = false

        if (isMonitoring) {
            // Already monitoring: GPS and audio are already running.
            // Just upgrade the notification.
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("Tracking..."))
        } else {
            // Not monitoring: start GPS and audio now.
            startForeground(NOTIF_ID, buildNotification("Tracking..."))

            if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
                fusedLocation.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
                startBeepDetector()
            } else {
                startTimeMillis = 0L
                stopSelf()
                return
            }
        }

        viewModel.updateStatus(
            tracking = true,
            durationSeconds = 0,
            distance = 0.0,
            points = 0,
            cps = 0.0,
            trackCounts = 0,
            gpsStatus = "Waiting"
        )
    }

    private fun stopTracking() {
        if (startTimeMillis == 0L) return

        val copy = writtenPoints.toList()
        if (copy.isNotEmpty()) {
            val savedFile = GpxWriter.saveTrack(this, copy)
            showSaveNotification(savedFile)
        }
        startTimeMillis = 0L
        lastWrittenLocation = null
        lastWrittenTime = 0L
        beepCountSinceLastPoint = 0
        trackBeepCount = 0

        if (isMonitoring) {
            // Stay in monitoring mode: keep GPS and audio running, downgrade notification.
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("Monitoring..."))
            viewModel.updateStatus(
                tracking = false,
                durationSeconds = 0,
                distance = 0.0,
                points = 0,
                cps = 0.0,
                trackCounts = 0,
                gpsStatus = viewModel.gpsStatus.value ?: "Waiting"
            )
        } else {
            // Not monitoring: stop GPS and audio.
            fusedLocation.removeLocationUpdates(locationCallback)
            stopBeepDetector()
            viewModel.updateStatus(
                tracking = false,
                durationSeconds = 0,
                distance = 0.0,
                points = 0,
                cps = 0.0,
                trackCounts = 0,
                gpsStatus = "Waiting"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    // -------------------------------------------------------------------------
    // Location / GPS
    // -------------------------------------------------------------------------

    private fun setupLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        ).setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                val loc = result.lastLocation ?: return
                handleLocation(loc)
            }
        }
    }

    private fun handleLocation(loc: Location) {
        val now = System.currentTimeMillis()
        lastGpsFixMillis = now

        if (startTimeMillis == 0L) {
            // Monitoring only (not recording): just update GPS status
            updateMonitoringStats()
            return
        }

        // Recording a track
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val maxSpeedKmh = prefs.getString("max_speed_kmh", "30.0")!!.toDoubleOrNull() ?: 30.0
        val spacingM = prefs.getString("point_spacing_m", "5.0")!!.toDoubleOrNull() ?: 5.0
        val minCountsPerPoint = prefs.getString("min_counts_per_point", "0")!!.toIntOrNull() ?: 0
        val maxTimeWithoutCountsS = prefs.getString("max_time_without_counts_s", "1")!!.toDoubleOrNull() ?: 1.0

        val elapsedSec = max(0L, (now - startTimeMillis) / 1000L)

        val lastLoc = lastWrittenLocation
        if (lastLoc != null) {
            val distance = lastLoc.distanceTo(loc).toDouble()
            val timeDeltaSec = max(1.0, (now - lastWrittenTime) / 1000.0)
            val speedMps = distance / timeDeltaSec
            val speedKmh = speedMps * 3.6

            if (speedKmh > maxSpeedKmh) {
                gpsSpoofingActive = true
                updateStats(elapsedSec, lastCps = currentCps())
                return
            }
            gpsSpoofingActive = false

            if (distance < spacingM) {
                updateStats(elapsedSec, lastCps = currentCps())
                return
            }

            val beeps = beepCountSinceLastPoint
            val cps = beeps / timeDeltaSec

            if (lastWrittenTime == 0L) {
                // First candidate point: do NOT write to GPX, just set anchor and reset beeps
                lastWrittenLocation = loc
                lastWrittenTime = now
                beepCountSinceLastPoint = 0
                updateStats(elapsedSec, lastCps = cps)
                return
            }

            val timeSinceLastPointSec = (now - lastWrittenTime) / 1000.0
            val timedOut = maxTimeWithoutCountsS > 0.0 && timeSinceLastPointSec > maxTimeWithoutCountsS
            if (minCountsPerPoint > 0 && beeps <= minCountsPerPoint && !timedOut) {
                updateStats(elapsedSec, lastCps = cps)
                return
            }

            val point = TrackPoint(
                latitude = loc.latitude,
                longitude = loc.longitude,
                timeMillis = now,
                distanceFromLast = distance,
                cps = cps
            )
            writtenPoints += point
            totalDistance += distance
            lastWrittenLocation = loc
            lastWrittenTime = now
            beepCountSinceLastPoint = 0

            updateStats(elapsedSec, lastCps = cps)
        } else {
            // First fix: set anchor without writing
            lastWrittenLocation = loc
            lastWrittenTime = now
            beepCountSinceLastPoint = 0
            updateStats(elapsedSec, lastCps = 0.0)
        }
    }

    private fun currentCps(): Double {
        val now = System.currentTimeMillis()
        val lastTime = lastWrittenTime.takeIf { it != 0L } ?: startTimeMillis
        val dt = max(1.0, (now - lastTime) / 1000.0)
        return beepCountSinceLastPoint / dt
    }

    private fun updateStats(elapsedSec: Long, lastCps: Double) {
        val gpsOk = (System.currentTimeMillis() - lastGpsFixMillis) <= 5000L
        val gpsStatus = when {
            !gpsOk || lastGpsFixMillis == 0L -> "Waiting"
            gpsSpoofingActive -> "Spoofing detected"
            else -> "Working"
        }
        viewModel.updateStatus(
            tracking = true,
            durationSeconds = elapsedSec,
            distance = totalDistance,
            points = writtenPoints.size,
            cps = lastCps,
            trackCounts = trackBeepCount,
            gpsStatus = gpsStatus
        )
    }

    private fun updateMonitoringStats() {
        val gpsOk = (System.currentTimeMillis() - lastGpsFixMillis) <= 5000L
        val gpsStatus = when {
            !gpsOk || lastGpsFixMillis == 0L -> "Waiting"
            else -> "Working"
        }
        viewModel.updateMonitoringStatus(gpsStatus = gpsStatus)
    }

    // -------------------------------------------------------------------------
    // Audio
    // -------------------------------------------------------------------------

    private fun startBeepDetector() {
        audioBeepDetector = AudioBeepDetector {
            if (startTimeMillis != 0L) {
                beepCountSinceLastPoint++
                trackBeepCount++
            }
            viewModel.incrementTotalCounts()
        }.also { it.start() }
    }

    private fun stopBeepDetector() {
        audioBeepDetector?.stop()
        audioBeepDetector = null
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    private fun buildNotification(text: String): Notification {
        val channelId = "geigergpx_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Geiger GPX Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Geiger GPX")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun showSaveNotification(savedFile: java.io.File?) {
        val channelId = "geigergpx_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel if needed (same as main notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Geiger GPX Tracking",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }

        val message = if (savedFile != null) {
            "Track saved to ${savedFile.absolutePath}"
        } else {
            "Track saved (location not available)"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Geiger GPX")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Use system NotificationManager directly for better reliability
        nm.notify(NOTIF_ID + 1, notification)
    }

    companion object {
        const val ACTION_START_MONITORING = "com.example.geigergpx.START_MONITORING"
        const val ACTION_STOP_MONITORING  = "com.example.geigergpx.STOP_MONITORING"
        const val ACTION_START = "com.example.geigergpx.START"
        const val ACTION_STOP  = "com.example.geigergpx.STOP"
        const val NOTIF_ID = 1001
    }
}
