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
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max
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

    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private val repo by lazy { (application as GeigerGpxApp).trackingRepository }

    // Track recording state
    @Volatile
    private var startTimeMillis: Long = 0L
    private var totalDistance: Double = 0.0
    private val writtenPoints = mutableListOf<TrackPoint>()

    private var lastWrittenLocation: Location? = null
    private var lastWrittenTime: Long = 0L

    // GPS averaging (simple running average between committed points)
    private var latSum: Double = 0.0
    private var lonSum: Double = 0.0
    private var nAv: Int = 0


    // Global beep counter since app start (only updated from audio thread)
    // Snapshot at the moment the current track started
    private var trackStartTotalBeeps: Int = 0
    // Snapshot at the last committed (or anchor) point
    private var lastPointTotalBeeps: Int = 0

    @Volatile
    private var lastGpsFixMillis: Long = 0L

    @Volatile
    private var gpsSpoofingActive: Boolean = false

    private var audioBeepDetector: AudioBeepDetector? = null

    // Monitoring state (GPS + audio active, but not recording a track)
    private var isMonitoring: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backupJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupLocationRequest()
        setupLocationCallback()
    }

    override fun onDestroy() {
        stopBackupLoop()
        serviceScope.cancel()
        super.onDestroy()
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
        totalDistance = 0.0
        synchronized(writtenPoints) {
            writtenPoints.clear()
        }
        lastWrittenLocation = null
        lastWrittenTime = 0L
        latSum = 0.0
        lonSum = 0.0
        nAv = 0
        // Snapshots for derived beep counts
        val currentTotal = repo.getTotalCounts()
        trackStartTotalBeeps = currentTotal
        lastPointTotalBeeps = currentTotal
        lastGpsFixMillis = 0L
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

        repo.updateStatus(
            tracking = true,
            durationSeconds = 0,
            distance = 0.0,
            points = 0,
            cps = 0.0,
            trackCounts = 0,
            gpsStatus = "Waiting"
        )
        repo.updateAudioStatus("Working")
    }

    private fun stopTracking() {
        if (startTimeMillis == 0L) return

        stopBackupLoop()

        val copy = synchronized(writtenPoints) {
            writtenPoints.toList()
        }
        if (copy.isNotEmpty()) {
            val filename = GpxWriter.saveTrack(this, copy)
            if (filename != null) {
                showSaveNotification(filename)
            } else {
                showSaveNotification("File not saved (error)")
            }
        }
        else {
            showSaveNotification("Nothing to save")
        }
        startTimeMillis = 0L
        lastWrittenLocation = null
        lastWrittenTime = 0L
        latSum = 0.0
        lonSum = 0.0
        nAv = 0
        trackStartTotalBeeps = 0
        lastPointTotalBeeps = 0

        if (isMonitoring) {
            // Stay in monitoring mode: keep GPS and audio running, downgrade notification.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            repo.updateStatus(
                tracking = false,
                durationSeconds = 0,
                distance = 0.0,
                points = 0,
                cps = 0.0,
                trackCounts = 0,
                gpsStatus = repo.gpsStatus.value ?: "Waiting"
            )
        } else {
            // Not monitoring: stop GPS and audio.
            fusedLocation.removeLocationUpdates(locationCallback)
            stopBeepDetector()
            repo.updateStatus(
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

        // Ensure any ongoing tracking notification is removed when tracking stops
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
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

        // 1. If not recording a track, just update the "Waiting/Working" UI status and exit
        if (startTimeMillis == 0L) {
            updateMonitoringStats()
            return
        }

        // 2. Handle the very first GPS fix (The Anchor)
        val lastLoc = lastWrittenLocation
        if (lastLoc == null) {
            lastWrittenLocation = loc
            lastWrittenTime = now
            latSum = loc.latitude
            lonSum = loc.longitude
            nAv = 1
            // Start counting from this exact moment/spot
            lastPointTotalBeeps = repo.getTotalCounts()
            updateStats(0, lastCps = 0.0)
            return
        }

        // 3. Load user preferences for filtering
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val maxSpeedKmh = prefs.getString("max_speed_kmh", "30.0")?.toDoubleOrNull() ?: 30.0
        val spacingM = prefs.getString("point_spacing_m", "5.0")?.toDoubleOrNull() ?: 5.0
        val minCountsPerPoint = prefs.getString("min_counts_per_point", "0")?.toIntOrNull() ?: 0
        val maxTimeWithoutCountsS = prefs.getString("max_time_without_counts_s", "1")?.toDoubleOrNull() ?: 1.0


        // 4. Calculate movement statistics
        val elapsedSec = max(0L, (now - startTimeMillis) / 1000L)
        val distance = lastLoc.distanceTo(loc).toDouble()
        val timeDeltaSec = max(0.1, (now - lastWrittenTime) / 1000.0)
        val speedMps = distance / timeDeltaSec
        val speedKmh = speedMps * 3.6

        // 5. GPS Quality / Spoofing Filter

        if (speedKmh > maxSpeedKmh) {
            gpsSpoofingActive = true
            updateStats(elapsedSec, lastCps = currentCps())
            return
        }
        gpsSpoofingActive = false

        // Accumulate raw GPS points for averaging (only if not spoofing)
        latSum += loc.latitude
        lonSum += loc.longitude
        nAv += 1

        // 6. Distance Filter (Wait until we've moved far enough)
        if (distance < spacingM) {
            updateStats(elapsedSec, lastCps = currentCps())
            return
        }

        // 7. Geiger Logic: Check if we should "commit" this point to the track
        val currentBeeps = repo.getTotalCounts() - lastPointTotalBeeps // PEEK: do not reset yet!
        val timedOut = maxTimeWithoutCountsS > 0.0 && timeDeltaSec >= maxTimeWithoutCountsS

        // If we haven't hit the minimum count requirement AND we haven't timed out, wait for more data
        if (minCountsPerPoint > 0 && currentBeeps < minCountsPerPoint && !timedOut) {
            updateStats(elapsedSec, lastCps = currentBeeps.toDouble() / timeDeltaSec)
            return
        }

        // 8. COMMIT: Everything looks good, finalize this point
        // We logically "reset" by taking a new snapshot instead of mutating the counter
        val finalBeeps = repo.getTotalCounts() - lastPointTotalBeeps
        val finalCps = finalBeeps.toDouble() / timeDeltaSec

        val avgLat = if (nAv > 0) latSum / nAv.toDouble() else loc.latitude
        val avgLon = if (nAv > 0) lonSum / nAv.toDouble() else loc.longitude
        val avgTimeMillis = ((lastWrittenTime + now) / 2L)

        val point = TrackPoint(
            latitude = avgLat,
            longitude = avgLon,
            timeMillis = avgTimeMillis,
            distanceFromLast = distance,
            cps = finalCps
        )

        synchronized(writtenPoints) {
            writtenPoints.add(point)
        }
        totalDistance += distance
        lastWrittenLocation = loc
        lastWrittenTime = now
        latSum = 0.0
        lonSum = 0.0
        nAv = 0
        lastPointTotalBeeps = repo.getTotalCounts()

        updateStats(elapsedSec, lastCps = finalCps)
    }

    private fun currentCps(): Double {
        val now = System.currentTimeMillis()
        val lastTime = lastWrittenTime.takeIf { it != 0L } ?: startTimeMillis
        val dt = max(1.0, (now - lastTime) / 1000.0)
        val currentBeeps = repo.getTotalCounts() - lastPointTotalBeeps
        return currentBeeps.toDouble() / dt
    }

    private fun updateStats(elapsedSec: Long, lastCps: Double) {
        val gpsOk = (System.currentTimeMillis() - lastGpsFixMillis) <= 5000L
        val gpsStatus = when {
            !gpsOk || lastGpsFixMillis == 0L -> "Waiting"
            gpsSpoofingActive -> "Spoofing detected"
            else -> "Working"
        }
        val currentSize = synchronized(writtenPoints) { writtenPoints.size }
        val trackBeepCount = repo.getTotalCounts() - trackStartTotalBeeps
        repo.updateStatus(
            tracking = true,
            durationSeconds = elapsedSec,
            distance = totalDistance,
            points = currentSize,
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
        repo.updateMonitoringStatus(gpsStatus = gpsStatus)
    }

    // -------------------------------------------------------------------------
    // Audio
    // -------------------------------------------------------------------------

    private fun startBeepDetector() {
        audioBeepDetector = AudioBeepDetector.createWithPrefs(
            context = this,
            onBeep = { _, count ->
                repeat(count) { repo.incrementTotalCounts() }
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
    companion object {
        const val ACTION_START_MONITORING = "com.example.geigergpx.START_MONITORING"
        const val ACTION_STOP_MONITORING  = "com.example.geigergpx.STOP_MONITORING"
        const val ACTION_START = "com.example.geigergpx.START"
        const val ACTION_STOP  = "com.example.geigergpx.STOP"
        const val NOTIF_ID = 1001

        // 10 minutes
        private const val BACKUP_INTERVAL_MS = 10 * 60 * 1000L
    }

    private fun startBackupLoop() {
        backupJob?.cancel()
        backupJob = serviceScope.launch {
            while (isActive && startTimeMillis != 0L) {
                delay(BACKUP_INTERVAL_MS)
                if (startTimeMillis == 0L) break
                val snapshot = synchronized(writtenPoints) {
                    writtenPoints.toList()
                }
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
