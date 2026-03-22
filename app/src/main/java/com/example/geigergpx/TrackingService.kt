package com.example.geigergpx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max
import java.util.Locale
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class TrackingService : Service() {

    companion object {
        @Volatile
        private var runningInstance: TrackingService? = null

        const val ACTION_START_MONITORING = "com.example.geigergpx.START_MONITORING"
        const val ACTION_STOP_MONITORING  = "com.example.geigergpx.STOP_MONITORING"
        const val ACTION_START = "com.example.geigergpx.START"
        const val ACTION_STOP  = "com.example.geigergpx.STOP"
        const val ACTION_CANCEL_TRACK = "com.example.geigergpx.CANCEL_TRACK"
        const val ACTION_TOGGLE_MEASUREMENT_MODE = "com.example.geigergpx.TOGGLE_MEASUREMENT_MODE"
        const val NOTIF_ID = 1001

        // 10 minutes
        private const val BACKUP_INTERVAL_MS = 10 * 60 * 1000L

        fun activeTrackPointsSnapshot(): List<TrackPoint> {
            val service = runningInstance ?: return emptyList()
            return service.trackWriter.activeTrackPointsSnapshot()
        }

        fun consumeMeasurementAverageCoordinates(): Pair<Double, Double> {
            val service = runningInstance ?: return Pair(0.0, 0.0)
            return service.doseRateMeasurement.consumeMeasurementAverageCoordinates()
        }
    }

    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private val repo by lazy { (application as GeigerGpxApp).trackingRepository }

    // Track recording state
    @Volatile
    private var startTimeMillis: Long = 0L
    private val trackWriter = TrackWriter()


    @Volatile
    private var gpsSpoofingActive: Boolean = false

    @Volatile
    private var spoofingSpeedKmh: Double = 0.0

    private var audioBeepDetector: AudioBeepDetector? = null

    // Monitoring state (GPS + audio active, but not recording a track)
    private var isMonitoring: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backupJob: kotlinx.coroutines.Job? = null

    private lateinit var prefs: SharedPreferences
    @Volatile private var maxSpeedKmh: Double = 30.0
    @Volatile private var spacingM: Double = 5.0
    @Volatile private var minCountsPerPoint: Int = 0
    @Volatile private var maxTimeWithoutCountsS: Double = 1.0

    private val doseRateMeasurement = DoseRateMeasurement()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "max_speed_kmh",
            "point_spacing_m",
            "min_counts_per_point",
            "max_time_without_counts_s",
            "dose_rate_avg_timestamps_n" -> loadTrackingPrefs()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        loadTrackingPrefs()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        createNotificationChannel()
        setupLocationRequest()
        setupLocationCallback()
    }

    override fun onDestroy() {
        runningInstance = null
        stopBackupLoop()
        fusedLocation.removeLocationUpdates(locationCallback)
        stopBeepDetector()
        serviceScope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun loadTrackingPrefs() {
        maxSpeedKmh = prefs.getString("max_speed_kmh", "30.0")?.toDoubleOrNull() ?: 30.0
        spacingM = prefs.getString("point_spacing_m", "5.0")?.toDoubleOrNull() ?: 5.0
        minCountsPerPoint = prefs.getString("min_counts_per_point", "0")?.toIntOrNull() ?: 0
        maxTimeWithoutCountsS = prefs.getString("max_time_without_counts_s", "1")?.toDoubleOrNull() ?: 1.0

        val allowedSizes = setOf(5, 10, 20, 50, 100)
        val requestedWindowSize = prefs.getString("dose_rate_avg_timestamps_n", "10")
            ?.toIntOrNull()
            ?.takeIf { it in allowedSizes }
            ?: 10
        doseRateMeasurement.updateMainCpsWindowSize(requestedWindowSize)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 2. Immediately satisfy the system's foreground requirement
        when (intent?.action) {
            ACTION_START_MONITORING, ACTION_START -> {
                val notification = buildNotification("Initializing...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            }
        }

        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            ACTION_CANCEL_TRACK -> cancelTracking()
            ACTION_TOGGLE_MEASUREMENT_MODE -> toggleMeasurementMode()
        }
        return START_STICKY
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("geigergpx_channel", "Tracking", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    // -------------------------------------------------------------------------
    // Monitoring (foreground-only, no track recording)
    // -------------------------------------------------------------------------

    private fun startMonitoring() {
        if (isMonitoring || startTimeMillis != 0L) return  // already monitoring or tracking
        isMonitoring = true

        if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
            fusedLocation.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
            repo.updateAudioStatus("Working")
            startBeepDetector()
            repo.updateMonitoringStatus(gpsStatus = "Waiting")
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
        val currentTotal = repo.getTotalCounts()
        trackWriter.start(currentTotal)
        trackWriter.updateLastGpsFix(0L)
        gpsSpoofingActive = false

        if (isMonitoring) {
            // Already monitoring: GPS and audio are already running.
            // Just upgrade the notification.
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("Tracking..."))
        } else {
            // Not monitoring: start GPS and audio now.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    buildNotification("Tracking..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("Tracking..."))
            }
            if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
                fusedLocation.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
                startBeepDetector()
            } else {
                startTimeMillis = 0L
                stopSelf()
                return
            }
        }

        startBackupLoop()

        repo.beginNewTrack()
        repo.setActiveTrackPoints(emptyList())
        repo.updateStatus(
            tracking = true,
            durationSeconds = 0,
            distance = 0.0,
            points = 0,
            cpsSnapshot = doseRateMeasurement.currentSnapshot(),
            gpsStatus = "Waiting"
        )
        repo.updateAudioStatus("Working")
    }

    private data class TrackStopStats(
        val durationSeconds: Long,
        val distance: Double,
        val points: Int
    )

    private fun stopTrackingSession(stats: TrackStopStats) {
        startTimeMillis = 0L
        trackWriter.reset()
        repo.setActiveTrackPoints(emptyList())
        trackWriter.updateLastGpsFix(0L)
        gpsSpoofingActive = false
        spoofingSpeedKmh = 0.0

        if (isMonitoring) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification("Monitoring..."))
            repo.updateStatus(
                tracking = false,
                durationSeconds = stats.durationSeconds,
                distance = stats.distance,
                points = stats.points,
                cpsSnapshot = doseRateMeasurement.currentSnapshot(),
                gpsStatus = repo.gpsStatus.value ?: "Waiting"
            )
        } else {
            fusedLocation.removeLocationUpdates(locationCallback)
            stopBeepDetector()
            repo.updateStatus(
                tracking = false,
                durationSeconds = stats.durationSeconds,
                distance = stats.distance,
                points = stats.points,
                cpsSnapshot = doseRateMeasurement.currentSnapshot(),
                gpsStatus = "Waiting"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        }
    }

    private fun cancelTracking() {
        if (startTimeMillis == 0L) return

        stopBackupLoop()
        GpxWriter.deleteBackupIfExists(this)
        repo.discardTrackCounts()
        stopTrackingSession(TrackStopStats(durationSeconds = 0, distance = 0.0, points = 0))
    }

    private fun stopTracking() {
        if (startTimeMillis == 0L) return

        val finalDurationSeconds = max(0L, (System.currentTimeMillis() - startTimeMillis) / 1000L)
        val finalDistance = trackWriter.totalDistance

        stopBackupLoop()

        val copy = trackWriter.activeTrackPointsSnapshot()
        val finalPointCount = copy.size
        repo.setActiveTrackPoints(copy)
        if (copy.isNotEmpty()) {
            val filename = GpxWriter.saveTrack(this, copy)
            if (filename != null) {
                // Final save succeeded: remove any leftover backup file
                GpxWriter.deleteBackupIfExists(this)
                showSaveNotification(filename)
            } else {
                showSaveNotification("File not saved (error)")
            }
        } else {
            showSaveNotification("Nothing to save")
        }
        repo.finalizeTrackCounts()
        stopTrackingSession(
            TrackStopStats(
                durationSeconds = finalDurationSeconds,
                distance = finalDistance,
                points = finalPointCount
            )
        )
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
        trackWriter.updateLastGpsFix(now)

        doseRateMeasurement.handleMeasurementLocation(loc)

        // 1. If not recording a track, just update the "Waiting/Working" UI status and exit
        if (startTimeMillis == 0L) {
            updateMonitoringStats()
            return
        }

        // 2. Handle the very first GPS fix (The Anchor)
        if (!trackWriter.hasAnchor()) {
            trackWriter.initializeAnchor(loc, now, repo.getTotalCounts())
            updateStats(0)
            return
        }

        // 4. Calculate movement statistics
        val elapsedSec = max(0L, (now - startTimeMillis) / 1000L)
        val movementStats = trackWriter.movementStatsFor(loc, now)
        val distance = movementStats.distance
        val timeDeltaSec = movementStats.timeDeltaSec
        val speedKmh = movementStats.speedKmh

        // 5. GPS Quality / Spoofing Filter

        if (speedKmh > maxSpeedKmh) {
            gpsSpoofingActive = true
            spoofingSpeedKmh = speedKmh
            updateStats(elapsedSec)
            return
        }
        gpsSpoofingActive = false
        spoofingSpeedKmh = 0.0

        // Accumulate raw GPS points for averaging (only if not spoofing)
        trackWriter.accumulateLocation(loc)

        // 6. Distance Filter (Wait until we've moved far enough)
        if (distance < spacingM) {
            updateStats(elapsedSec)
            return
        }

        // 7. Geiger Logic: Check if we should "commit" this point to the track
        val totalBeeps = repo.getTotalCounts()
        if (trackWriter.shouldWaitForCounts(totalBeeps, minCountsPerPoint, maxTimeWithoutCountsS, timeDeltaSec)) {
            updateStats(elapsedSec)
            return
        }

        val snapshot = trackWriter.commitPoint(loc, now, movementStats, totalBeeps)
        repo.setActiveTrackPoints(snapshot)

        updateStats(elapsedSec)
    }


    private fun toggleMeasurementMode() {
        val enabled = doseRateMeasurement.toggleMeasurementMode()
        repo.updateMeasurementMode(enabled)
        repo.updateCpsSnapshot(doseRateMeasurement.currentSnapshot(), onBeep = false)
    }

    private fun updateStats(elapsedSec: Long) {
        val gpsOk = (System.currentTimeMillis() - trackWriter.lastGpsFixMillis) <= 5000L
        val gpsStatus = when {
            !gpsOk || trackWriter.lastGpsFixMillis == 0L -> "Waiting"
            gpsSpoofingActive -> "Spoofing detected (${String.format(Locale.US, "%.1f", spoofingSpeedKmh)} km/h)"
            else -> "Working"
        }
        val currentSize = trackWriter.pointCount()
        repo.updateStatus(
            tracking = true,
            durationSeconds = elapsedSec,
            distance = trackWriter.totalDistance,
            points = currentSize,
            cpsSnapshot = doseRateMeasurement.currentSnapshot(),
            gpsStatus = gpsStatus
        )
    }

    private fun updateMonitoringStats() {
        val gpsOk = (System.currentTimeMillis() - trackWriter.lastGpsFixMillis) <= 5000L
        val gpsStatus = when {
            !gpsOk || trackWriter.lastGpsFixMillis == 0L -> "Waiting"
            else -> "Working"
        }
        repo.updateMonitoringStatus(gpsStatus = gpsStatus)
    }

    // -------------------------------------------------------------------------
    // Audio
    // -------------------------------------------------------------------------

    private fun startBeepDetector() {
        if (audioBeepDetector != null) return

        audioBeepDetector = AudioBeepDetector.createWithPrefs(
            context = this,
            onBeep = { _, count ->
                if (count > 0) {
                    repo.incrementTotalCounts(count)
                    doseRateMeasurement.registerBeepsForMainCps(count)
                    repo.updateCpsSnapshot(doseRateMeasurement.currentSnapshot(), onBeep = true)
                }
            },
            onAudioHealth = { healthy ->
                repo.updateAudioStatus(if (healthy) "Working" else "Error")
            }
        ).also { it.start() }
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

    private fun showSaveNotification(message: String?) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.util.Log.e("MYTAG", "Could not show toast: ${e.message}")
            }
        }
    }
    private fun startBackupLoop() {
        backupJob?.cancel()
        backupJob = serviceScope.launch {
            while (isActive && startTimeMillis != 0L) {
                delay(BACKUP_INTERVAL_MS)
                if (startTimeMillis == 0L) break
                val snapshot = trackWriter.activeTrackPointsSnapshot()
                if (snapshot.isEmpty()) continue
                launch(Dispatchers.IO) {
                    try {
                        GpxWriter.saveBackup(this@TrackingService, snapshot)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun stopBackupLoop() {
        backupJob?.cancel()
        backupJob = null
    }
}
