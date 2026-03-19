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
import androidx.core.app.NotificationManagerCompat
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
            return synchronized(service.writtenPoints) {
                service.writtenPoints.toList()
            }
        }

        fun consumeMeasurementAverageCoordinates(): Pair<Double, Double> {
            val service = runningInstance ?: return Pair(0.0, 0.0)
            return service.consumeMeasurementAverageCoordinatesInternal()
        }
    }

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

    // GPS averaging for measurement mode (POI placement)
    private var measLatSum: Double = 0.0
    private var measLonSum: Double = 0.0
    private var measLatLonCount: Int = 0


    // Global beep counter since app start (only updated from audio thread)
    // Snapshot at the last committed (or anchor) point
    private var lastPointTotalBeeps: Int = 0

    @Volatile
    private var lastGpsFixMillis: Long = 0L

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

    @Volatile private var mainCpsBeepWindowSize = 10
    private var mainCpsBeepTimes = LongArray(mainCpsBeepWindowSize)
    private var mainCpsBeepCount: Int = 0
    private var mainCpsBeepNextIndex: Int = 0
    private var measurementModeEnabled: Boolean = false
    private var measurementOldestTimestamp: Long = 0L
    private var measurementTimestampCount: Long = 0L
    private val mainCpsLock = Any()

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
        updateMainCpsWindowSize(requestedWindowSize)
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

        repo.beginNewTrack()
        repo.setActiveTrackPoints(emptyList())
        repo.updateStatus(
            tracking = true,
            durationSeconds = 0,
            distance = 0.0,
            points = 0,
            cpsSnapshot = currentCpsSnapshot(),
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
        totalDistance = 0.0
        synchronized(writtenPoints) {
            writtenPoints.clear()
        }
        repo.setActiveTrackPoints(emptyList())
        lastWrittenLocation = null
        lastWrittenTime = 0L
        latSum = 0.0
        lonSum = 0.0
        nAv = 0
        lastPointTotalBeeps = 0
        lastGpsFixMillis = 0L
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
                cpsSnapshot = currentCpsSnapshot(),
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
                cpsSnapshot = currentCpsSnapshot(),
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
        val finalDistance = totalDistance

        stopBackupLoop()

        val copy = synchronized(writtenPoints) {
            writtenPoints.toList()
        }
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
        lastGpsFixMillis = now

        handleMeasurementLocation(loc)

        // 1. If not recording a track, just update the "Waiting/Working" UI status and exit
        if (startTimeMillis == 0L) {
            updateMonitoringStats()
            return
        }

        handleTrackLocation(loc, now)
    }

    private fun handleTrackLocation(loc: Location, now: Long) {

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
            updateStats(0)
            return
        }

        // 4. Calculate movement statistics
        val elapsedSec = max(0L, (now - startTimeMillis) / 1000L)
        val distance = lastLoc.distanceTo(loc).toDouble()
        val timeDeltaSec = max(0.1, (now - lastWrittenTime) / 1000.0)
        val speedMps = distance / timeDeltaSec
        val speedKmh = speedMps * 3.6

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
        latSum += loc.latitude
        lonSum += loc.longitude
        nAv += 1

        // 6. Distance Filter (Wait until we've moved far enough)
        if (distance < spacingM) {
            updateStats(elapsedSec)
            return
        }

        // 7. Geiger Logic: Check if we should "commit" this point to the track
        val currentBeeps = repo.getTotalCounts() - lastPointTotalBeeps // PEEK: do not reset yet!
        val timedOut = maxTimeWithoutCountsS > 0.0 && timeDeltaSec >= maxTimeWithoutCountsS

        // If we haven't hit the minimum count requirement AND we haven't timed out, wait for more data
        if (minCountsPerPoint > 0 && currentBeeps < minCountsPerPoint && !timedOut) {
            updateStats(elapsedSec)
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

        val snapshot = synchronized(writtenPoints) {
            writtenPoints.add(point)
            writtenPoints.toList()
        }
        repo.setActiveTrackPoints(snapshot)
        totalDistance += distance
        lastWrittenLocation = loc
        lastWrittenTime = now
        latSum = 0.0
        lonSum = 0.0
        nAv = 0
        lastPointTotalBeeps = repo.getTotalCounts()

        updateStats(elapsedSec)
    }

    private fun handleMeasurementLocation(loc: Location) {
        if (!measurementModeEnabled) return
        measLatSum += loc.latitude
        measLonSum += loc.longitude
        measLatLonCount += 1
    }

    private fun consumeMeasurementAverageCoordinatesInternal(): Pair<Double, Double> {
        val latitude = if (measLatLonCount > 0) measLatSum / measLatLonCount.toDouble() else 0.0
        val longitude = if (measLatLonCount > 0) measLonSum / measLatLonCount.toDouble() else 0.0
        measLatSum = 0.0
        measLonSum = 0.0
        measLatLonCount = 0
        return Pair(latitude, longitude)
    }

    private fun currentMainCpsSampleCount(): Int = synchronized(mainCpsLock) {
        if (measurementModeEnabled) measurementTimestampCount.toInt() else mainCpsBeepCount
    }
    private fun currentMainCpsOldestTimestampMillis(): Long = synchronized(mainCpsLock) {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 1L) return@synchronized 0L
            return@synchronized measurementOldestTimestamp
        }

        if (mainCpsBeepCount < 1) return@synchronized 0L

        val oldestIndex = if (mainCpsBeepCount == mainCpsBeepWindowSize) {
            mainCpsBeepNextIndex
        } else {
            0
        }
        return@synchronized mainCpsBeepTimes[oldestIndex]
    }

    private fun currentCpsSnapshot() = TrackingRepository.CpsSnapshot(
        cps = calculateMainScreenCps(),
        sampleCount = currentMainCpsSampleCount(),
        oldestTimestampMillis = currentMainCpsOldestTimestampMillis()
    )

    private fun toggleMeasurementMode() {
        synchronized(mainCpsLock) {
            measurementModeEnabled = !measurementModeEnabled
            if (measurementModeEnabled) {
                measurementTimestampCount = mainCpsBeepCount.toLong()
                measurementOldestTimestamp = if (mainCpsBeepCount >= 1) {
                    val oldestIndex = if (mainCpsBeepCount == mainCpsBeepWindowSize) {
                        mainCpsBeepNextIndex
                    } else {
                        0
                    }
                    mainCpsBeepTimes[oldestIndex]
                } else {
                    0L
                }
                measLatSum = 0.0
                measLonSum = 0.0
                measLatLonCount = 0
            }
        }
        repo.updateMeasurementMode(measurementModeEnabled)
        repo.updateCpsSnapshot(currentCpsSnapshot(), onBeep = false)
    }


    private fun updateMainCpsWindowSize(newSize: Int) {
        synchronized(mainCpsLock) {
            if (newSize == mainCpsBeepWindowSize) return

            val preservedCount = minOf(mainCpsBeepCount, newSize)
            val newTimes = LongArray(newSize)

            if (preservedCount > 0) {
                val start = (mainCpsBeepNextIndex - preservedCount + mainCpsBeepWindowSize) % mainCpsBeepWindowSize
                for (i in 0 until preservedCount) {
                    val srcIndex = (start + i) % mainCpsBeepWindowSize
                    newTimes[i] = mainCpsBeepTimes[srcIndex]
                }
            }

            mainCpsBeepTimes = newTimes
            mainCpsBeepWindowSize = newSize
            mainCpsBeepCount = preservedCount
            mainCpsBeepNextIndex = preservedCount % newSize
        }
    }

    private fun registerBeepsForMainCps(beepCount: Int) {
        if (beepCount <= 0) return
        synchronized(mainCpsLock) {
            repeat(beepCount) {
                mainCpsBeepTimes[mainCpsBeepNextIndex] = System.currentTimeMillis()
                val beepTime = mainCpsBeepTimes[mainCpsBeepNextIndex]
                mainCpsBeepNextIndex = (mainCpsBeepNextIndex + 1) % mainCpsBeepWindowSize
                if (mainCpsBeepCount < mainCpsBeepWindowSize) {
                    mainCpsBeepCount += 1
                }
                if (measurementModeEnabled) {
                    if (measurementTimestampCount == 0L) {
                        measurementOldestTimestamp = beepTime
                    }
                    measurementTimestampCount += 1L
                }
            }
        }
    }

    private fun calculateMainScreenCps(): Double = synchronized(mainCpsLock) {
        if (measurementModeEnabled) {
            if (measurementTimestampCount < 2L || measurementOldestTimestamp == 0L) {
                return@synchronized 0.0
            }
            val newestIndex = (mainCpsBeepNextIndex - 1 + mainCpsBeepWindowSize) % mainCpsBeepWindowSize
            val newest = mainCpsBeepTimes[newestIndex]
            val deltaSeconds = (newest - measurementOldestTimestamp) / 1000.0
            if (deltaSeconds <= 0.0) return@synchronized 0.0

            return@synchronized (measurementTimestampCount - 1).toDouble() / deltaSeconds
        }

        if (mainCpsBeepCount < 2) return@synchronized 0.0

        val newestIndex = (mainCpsBeepNextIndex - 1 + mainCpsBeepWindowSize) % mainCpsBeepWindowSize
        val oldestIndex = if (mainCpsBeepCount == mainCpsBeepWindowSize) {
            mainCpsBeepNextIndex
        } else {
            0
        }

        val newest = mainCpsBeepTimes[newestIndex]
        val oldest = mainCpsBeepTimes[oldestIndex]
        val deltaSeconds = (newest - oldest) / 1000.0
        if (deltaSeconds <= 0.0) return@synchronized 0.0

        (mainCpsBeepCount - 1).toDouble() / deltaSeconds
    }

    private fun updateStats(elapsedSec: Long) {
        val gpsOk = (System.currentTimeMillis() - lastGpsFixMillis) <= 5000L
        val gpsStatus = when {
            !gpsOk || lastGpsFixMillis == 0L -> "Waiting"
            gpsSpoofingActive -> "Spoofing detected (${String.format(Locale.US, "%.1f", spoofingSpeedKmh)} km/h)"
            else -> "Working"
        }
        val currentSize = synchronized(writtenPoints) { writtenPoints.size }
        repo.updateStatus(
            tracking = true,
            durationSeconds = elapsedSec,
            distance = totalDistance,
            points = currentSize,
            cpsSnapshot = currentCpsSnapshot(),
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
        if (audioBeepDetector != null) return

        audioBeepDetector = AudioBeepDetector.createWithPrefs(
            context = this,
            onBeep = { _, count ->
                repo.incrementTotalCounts(count)
                registerBeepsForMainCps(count)
                repo.updateCpsSnapshot(currentCpsSnapshot(), onBeep = true)
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
