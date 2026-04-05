package com.github.bocovp.geigergpx

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
import android.media.Ringtone
import android.media.RingtoneManager
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
import android.media.AudioFocusRequest
import android.media.AudioManager
class TrackingService : Service() {

    companion object {
        @Volatile
        private var runningInstance: TrackingService? = null

        const val ACTION_START_MONITORING = "com.github.bocovp.geigergpx.START_MONITORING"
        const val ACTION_STOP_MONITORING  = "com.github.bocovp.geigergpx.STOP_MONITORING"
        const val ACTION_START = "com.github.bocovp.geigergpx.START"
        const val ACTION_STOP  = "com.github.bocovp.geigergpx.STOP"
        const val ACTION_CANCEL_TRACK = "com.github.bocovp.geigergpx.CANCEL_TRACK"
        const val ACTION_TOGGLE_MEASUREMENT_MODE = "com.github.bocovp.geigergpx.TOGGLE_MEASUREMENT_MODE"
        const val ACTION_TRACK_SAVED = "com.github.bocovp.geigergpx.TRACK_SAVED"
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val NOTIF_ID = 1001

        // 10 minutes
        private const val BACKUP_INTERVAL_MS = 10 * 60 * 1000L

        private var audioFocusRequest: AudioFocusRequest? = null

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
    private val trackWriter = TrackWriter()
    private val trackLocationLock = Any()

    private var lastTimeMillis: Long = 0L
    private var lastLocation: Location? = null

    @Volatile
    private var gpsSpoofingActive: Boolean = false

    @Volatile
    private var spoofingSpeedKmh: Double = 0.0

    @Volatile
    private var lastGpsFixMillis: Long = 0L

    private var audioBeepDetector: com.github.bocovp.geigergpx.AudioInputManager? = null

    // Monitoring state (GPS + audio active, but not recording a track)
    private var isMonitoring: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backupJob: kotlinx.coroutines.Job? = null

    private lateinit var prefs: SharedPreferences
    @Volatile private var maxSpeedKmh: Double = 30.0
    @Volatile private var spacingM: Double = 5.0
    @Volatile private var minCountsPerPoint: Int = 0
    @Volatile private var maxTimeWithoutCountsS: Double = 1.0
    @Volatile private var alertDoseRate: Double = 0.0
    @Volatile private var cpsToUsvhCoefficient: Double = 1.0
    private var alertRingtone: Ringtone? = null
    private var lastAlertAtMillis: Long = 0L

    private val doseRateMeasurement = DoseRateMeasurement()

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "max_speed_kmh",
            "point_spacing_m",
            "min_counts_per_point",
            "max_time_without_counts_s",
            "dose_rate_avg_timestamps_n",
            "alert_dose_rate",
            "cps_to_usvh" -> loadTrackingPrefs()
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
        alertRingtone?.stop()
        alertRingtone = null
        serviceScope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun loadTrackingPrefs() {
        maxSpeedKmh = prefs.getString("max_speed_kmh", "30.0")?.toDoubleOrNull() ?: 30.0
        spacingM = prefs.getString("point_spacing_m", "5.0")?.toDoubleOrNull() ?: 5.0
        minCountsPerPoint = prefs.getString("min_counts_per_point", "0")?.toIntOrNull() ?: 0
        maxTimeWithoutCountsS = prefs.getString("max_time_without_counts_s", "1")?.toDoubleOrNull() ?: 1.0
        cpsToUsvhCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val configuredAlertDoseRate = prefs.getString("alert_dose_rate", "0")?.toDoubleOrNull() ?: 0.0
        alertDoseRate = if (configuredAlertDoseRate > 0.0) configuredAlertDoseRate else 0.0

        val allowedSizes = setOf(5, 10, 20, 50, 100)
        val requestedWindowSize = prefs.getString("dose_rate_avg_timestamps_n", "10")
            ?.toIntOrNull()
            ?.takeIf { it in allowedSizes }
            ?: 10
        doseRateMeasurement.updateMainCpsWindowSize(requestedWindowSize)
        doseRateMeasurement.updateAlertConfig(
            alertDoseRate = alertDoseRate,
            cpsToUsvhCoefficient = cpsToUsvhCoefficient
        )
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
        if (isMonitoring || trackWriter.isTracking()) return  // already monitoring or tracking
        isMonitoring = true

        if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
            fusedLocation.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
            repo.updateAudioStatus("Working", TrackingRepository.AUDIO_STATUS_WORKING)
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
        if (!trackWriter.isTracking()) {
            fusedLocation.removeLocationUpdates(locationCallback)
            stopBeepDetector()
        }

        // Only stop foreground and self-destruct if not tracking
        if (!trackWriter.isTracking()) {
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
        if (trackWriter.isTracking()) return  // already tracking

        val now = System.currentTimeMillis()
        val currentTotal = repo.getTotalCounts()
        trackWriter.start(now, currentTotal)
        lastGpsFixMillis = 0L
        clearLastTrackLocation()
        gpsSpoofingActive = false
        spoofingSpeedKmh = 0.0

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
                trackWriter.reset()
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
        repo.updateAudioStatus("Working", TrackingRepository.AUDIO_STATUS_WORKING)
    }

    private data class TrackStopStats(
        val durationSeconds: Long,
        val distance: Double,
        val points: Int
    )

    private data class TrackLocationSnapshot(
        val location: Location?,
        val timeMillis: Long
    )

    private fun stopTrackingSession(stats: TrackStopStats) {
        trackWriter.reset()
        repo.setActiveTrackPoints(emptyList())
        lastGpsFixMillis = 0L
        clearLastTrackLocation()
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
        if (!trackWriter.isTracking()) return

        stopBackupLoop()
        GpxWriter.deleteBackupIfExists(this)
        repo.discardTrackCounts()
        stopTrackingSession(TrackStopStats(durationSeconds = 0, distance = 0.0, points = 0))
    }

    private fun stopTracking() {
        if (!trackWriter.isTracking()) return

        val finalDurationSeconds = trackWriter.elapsedSeconds(System.currentTimeMillis())
        val finalDistance = trackWriter.totalDistance

        stopBackupLoop()

        val copy = trackWriter.activeTrackPointsSnapshot()
        val finalPointCount = copy.size
        repo.setActiveTrackPoints(copy)
        if (copy.isNotEmpty()) {
            val saveResult = GpxWriter.saveTrackWithResult(this, copy)
            if (saveResult != null) {
                // Final save succeeded: remove any leftover backup file
                GpxWriter.deleteBackupIfExists(this)
                sendBroadcast(
                    Intent(ACTION_TRACK_SAVED).putExtra(EXTRA_TRACK_ID, saveResult.sourceId)
                )
                showSaveNotification(saveResult.displayPath)
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

    private fun checkSpoofing(loc:Location) {
        val now = System.currentTimeMillis()
        val previousLocationSnapshot = lastTrackLocationSnapshot()
        val previousLocation = previousLocationSnapshot.location
        val previousTimeMillis = previousLocationSnapshot.timeMillis

        if (previousLocation != null && previousTimeMillis > 0L) {
            val timeDeltaSec = max(0.1, (now - previousTimeMillis) / 1000.0)
            val distance = previousLocation.distanceTo(loc).toDouble()
            val speedKmh = (distance / timeDeltaSec) * 3.6

            if (speedKmh > maxSpeedKmh) {
                gpsSpoofingActive = true
                spoofingSpeedKmh = speedKmh
            } else {
                gpsSpoofingActive = false
                spoofingSpeedKmh = 0.0
            }
        }
        updateLastTrackLocation(loc, now)
    }

    private fun handleLocation(loc: Location) {
        checkSpoofing(loc)

        val now = System.currentTimeMillis()
        lastGpsFixMillis = now
        val tracking = trackWriter.isTracking()
        val elapsedSec = if (tracking) trackWriter.elapsedSeconds(now) else 0

        if (!tracking) {
            updateMonitoringStats()
        }

        if (gpsSpoofingActive) {
            if (tracking) {
                updateStats(elapsedSec)
            }
            return
        }

        doseRateMeasurement.handleGpsLocation(loc)

        if (tracking) {
            val result = trackWriter.handleGpsLocation(
                loc = loc,
                now = now,
                totalBeeps = repo.getTotalCounts(),
                spacingM = spacingM,
                minCountsPerPoint = minCountsPerPoint,
                maxTimeWithoutCountsS = maxTimeWithoutCountsS
            )

            result.snapshot?.let { snapshot ->
                repo.setActiveTrackPoints(snapshot)
            }

            updateStats(elapsedSec)
        }
    }


    private fun toggleMeasurementMode() {
        val enabled = doseRateMeasurement.toggleMeasurementMode()
        repo.updateMeasurementMode(enabled)
        repo.updateCpsSnapshot(doseRateMeasurement.currentSnapshot(), onBeep = false)
    }

    private fun updateStats(elapsedSec: Long) {
        val lastGpsFixMillisSnapshot = lastGpsFixMillis
        val gpsOk = (System.currentTimeMillis() - lastGpsFixMillisSnapshot) <= 5000L
        val gpsStatus = when {
            !gpsOk || lastGpsFixMillisSnapshot == 0L -> "Waiting"
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
        val lastGpsFixMillisSnapshot = lastGpsFixMillis
        val gpsOk = (System.currentTimeMillis() - lastGpsFixMillisSnapshot) <= 5000L
        val gpsStatus = when {
            !gpsOk || lastGpsFixMillisSnapshot == 0L -> "Waiting"
            else -> "Working"
        }
        repo.updateMonitoringStatus(gpsStatus = gpsStatus)
    }

    private fun clearLastTrackLocation() = synchronized(trackLocationLock) {
        lastLocation = null
        lastTimeMillis = 0L
    }

    private fun lastTrackLocationSnapshot(): TrackLocationSnapshot = synchronized(trackLocationLock) {
        TrackLocationSnapshot(
            location = lastLocation?.let(::Location),
            timeMillis = lastTimeMillis
        )
    }

    private fun updateLastTrackLocation(location: Location, timeMillis: Long) = synchronized(trackLocationLock) {
        lastLocation = Location(location)
        lastTimeMillis = timeMillis
    }

    // -------------------------------------------------------------------------
    // Audio
    // -------------------------------------------------------------------------

    private fun startBeepDetector() {
        if (audioBeepDetector != null) return

        // Request Audio Focus before starting the detector
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener { focusChange ->
                    // The AudioBeepDetector's inner loop will automatically handle
                    // temporary read failures, but logging focus changes helps debugging.
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> android.util.Log.w("TrackingService", "Audio focus completely lost")
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> android.util.Log.w("TrackingService", "Audio focus transiently lost")
                        AudioManager.AUDIOFOCUS_GAIN -> android.util.Log.i("TrackingService", "Audio focus gained")
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }

        audioBeepDetector = AudioInputManager.createWithPrefs(
            context = this,
            onBeep = { _, count ->
                if (count > 0) {
                    repo.incrementTotalCounts(count)
                    val alertEvent = doseRateMeasurement.processBeep(count)
                    repo.updateCpsSnapshot(doseRateMeasurement.currentSnapshot(), onBeep = true) // ????????????????????
                    alertEvent?.let { dispatchDoseRateAlert(it) }
                }
            },
            onAudioHealth = { _ ->
            },
            onAudioStatus = { status, errorCode ->
                repo.updateAudioStatus(status, errorCode)
            }
        ).also { it.start() }
    }

    private fun stopBeepDetector() {
        audioBeepDetector?.stop()
        audioBeepDetector = null
        // Abandon Audio Focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun dispatchDoseRateAlert(alertEvent: DoseRateMeasurement.AlertEvent) {
        val now = System.currentTimeMillis()
        if (now - lastAlertAtMillis < 500L) return
        lastAlertAtMillis = now

        playAlertSound(alertEvent.soundCount)
        showDoseRateAlertNotification(alertEvent.meanDoseRate)
    }

    private fun playAlertSound(soundCount: Int) {
        if (soundCount <= 0) return
        if (alertRingtone == null) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            alertRingtone = RingtoneManager.getRingtone(this, defaultSoundUri)
        }

        val ringtone = alertRingtone ?: return
        serviceScope.launch(Dispatchers.Main) {
            repeat(soundCount) { index ->
                try {
                    ringtone.play()
                } catch (_: Exception) {
                    return@launch
                }
                if (index < soundCount - 1) {
                    delay(400L)
                }
            }
        }
    }

    private fun showDoseRateAlertNotification(meanDoseRate: Double) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "geigergpx_alert_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Dose rate alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val unit = if (cpsToUsvhCoefficient == 1.0) "cps" else "μSv/h"
        val message = String.format(Locale.US, "Dose rate %.2f %s", meanDoseRate, unit)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Radiation alert")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(2002, notification)
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
            while (isActive && trackWriter.isTracking()) {
                delay(BACKUP_INTERVAL_MS)
                if (!trackWriter.isTracking()) break
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
