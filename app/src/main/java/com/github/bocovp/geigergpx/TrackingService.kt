package com.github.bocovp.geigergpx

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.media.ToneGenerator
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
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
        const val EXTRA_FOREGROUND_DISPATCH = "extra_foreground_dispatch"

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

        fun activeKdeTimestampBounds(): Pair<Double, Double>? {
            val service = runningInstance ?: return null
            return service.kde?.timestampBounds()
        }

        fun activeKdeConfidenceIntervals(
            t2s: DoubleArray,
            scaleSeconds: Double
        ): Triple<DoubleArray, DoubleArray, DoubleArray>? {
            val service = runningInstance ?: return null
            val snapshot = service.kde?.copy() ?: return null
            return snapshot.getConfidenceIntervals(t2s, scaleSeconds.coerceAtLeast(KdeScaleSlider.MIN_SECONDS.toDouble()))
        }
    }

    private val repo by lazy { (application as GeigerGpxApp).trackingRepository }

    // Track recording state
    private val trackWriter = TrackWriter()
    private lateinit var gpsManager: GpsManager

    private var audioBeepDetector: com.github.bocovp.geigergpx.AudioInputManager? = null

    // Monitoring state (GPS + audio active, but not recording a track)
    private var isMonitoring: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backupJob: kotlinx.coroutines.Job? = null
    private var gpsFallbackJob: kotlinx.coroutines.Job? = null
    private var uiTickJob: kotlinx.coroutines.Job? = null
    @Volatile private var stopInProgress = false

    private lateinit var prefs: SharedPreferences
    @Volatile private var maxSpeedKmh: Double = 30.0
    @Volatile private var spacingM: Double = 5.0
    @Volatile private var minCountsPerPoint: Int = 0
    @Volatile private var maxTimeWithoutCountsS: Double = 1.0
    @Volatile private var maxTimeWithoutGpsS: Double = 60.0
    @Volatile private var alertDoseRate: Double = 0.0
    @Volatile private var cpsToUsvhCoefficient: Double = 1.0
    private var toneGenerator: ToneGenerator? = null
    private var lastAlertAtMillis: Long = 0L
    private val doseRateMeasurement = DoseRateMeasurement()
    @Volatile private var kde: KernelDensityEstimator? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val notificationManager by lazy { TrackingNotificationManager(this) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "max_speed_kmh",
            "point_spacing_m",
            "min_counts_per_point",
            "max_time_without_counts_s",
            "max_time_without_gps_s",
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
        notificationManager.createChannels()
        gpsManager = GpsManager(
            context = this,
            looper = mainLooper,
            maxSpeedKmhProvider = { maxSpeedKmh },
            onLocationProcessed = { processedLocation ->
                handleLocation(processedLocation)
            }
        )
    }

    override fun onDestroy() {
        runningInstance = null
        stopBackupLoop()
        stopGpsFallbackLoop()
        stopUiTickLoop()
        gpsManager.stopUpdates()
        stopBeepDetector()
        toneGenerator?.release()
        toneGenerator = null
        serviceScope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun loadTrackingPrefs() {
        maxSpeedKmh = prefs.getString("max_speed_kmh", "30.0")?.toDoubleOrNull() ?: 30.0
        spacingM = prefs.getString("point_spacing_m", "5.0")?.toDoubleOrNull() ?: 5.0
        minCountsPerPoint = prefs.getString("min_counts_per_point", "0")?.toIntOrNull() ?: 0
        maxTimeWithoutCountsS = prefs.getString("max_time_without_counts_s", "1")?.toDoubleOrNull() ?: 1.0
        maxTimeWithoutGpsS = prefs.getString("max_time_without_gps_s", "60")?.toDoubleOrNull() ?: 60.0
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
        val action = intent?.action
        val launchedAsForeground = intent?.getBooleanExtra(EXTRA_FOREGROUND_DISPATCH, false) == true
        if (launchedAsForeground) {
            ensureForegroundDispatchContract(action)
        }

        when (action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            ACTION_CANCEL_TRACK -> cancelTracking()
            ACTION_TOGGLE_MEASUREMENT_MODE -> toggleMeasurementMode()
            else -> {
                if (launchedAsForeground) {
                    android.util.Log.w("TrackingService", "Ignoring unknown foreground-dispatched action=$action")
                }
            }
        }
        return START_STICKY
    }

    private fun ensureForegroundDispatchContract(action: String?) {
        val status = when (action) {
            ACTION_START -> "Tracking..."
            ACTION_START_MONITORING -> "Monitoring..."
            ACTION_STOP, ACTION_CANCEL_TRACK -> "Stopping..."
            ACTION_STOP_MONITORING -> "Stopping monitoring..."
            ACTION_TOGGLE_MEASUREMENT_MODE -> "Updating measurement mode..."
            else -> "Processing request..."
        }
        val notification = notificationManager.buildTrackingNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TrackingNotificationManager.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(TrackingNotificationManager.NOTIF_ID, notification)
        }
    }


    // -------------------------------------------------------------------------
    // Monitoring (foreground-only, no track recording)
    // -------------------------------------------------------------------------

    private fun startMonitoring() {
        if (isMonitoring || trackWriter.isTracking()) return  // already monitoring or tracking
        isMonitoring = true

        if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
            gpsManager.startUpdates()
            repo.updateAudioStatus("Working", TrackingRepository.AUDIO_STATUS_WORKING)
            startBeepDetector()
            startUiTickLoop()
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
            gpsManager.stopUpdates()
            stopBeepDetector()
        }

        // Only stop foreground and self-destruct if not tracking
        if (!trackWriter.isTracking()) {
            stopSelf()
            stopUiTickLoop()
        } else {
            // Still tracking: downgrade notification to indicate background tracking
            notificationManager.postTrackingNotification("Tracking (background)...")
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
        gpsManager.reset()

        kde = KernelDensityEstimator(cpsToUsvhCoefficient)
        kde?.clear()

        if (isMonitoring) {
            // Already monitoring: GPS and audio are already running.
            // Just upgrade the notification.
            notificationManager.postTrackingNotification("Tracking...")
        } else {
            // Not monitoring: start GPS and audio now.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    TrackingNotificationManager.NOTIF_ID,
                    notificationManager.buildTrackingNotification("Tracking..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(TrackingNotificationManager.NOTIF_ID, notificationManager.buildTrackingNotification("Tracking..."))
            }
            if (ActivityCompatHelper.hasLocationAndAudioPermissions(this)) {
                gpsManager.startUpdates()
                startBeepDetector()
            } else {
                trackWriter.reset()
                stopSelf()
                return
            }
        }

        startBackupLoop()
        startGpsFallbackLoop()
        startUiTickLoop()

        repo.beginNewTrack()
        repo.setActiveTrackPoints(emptyList())
        repo.updateTrackDuration(0)
        repo.updateTrackGeometry(distance = 0.0, points = 0)
        repo.updateTrackingState(tracking = true, gpsStatus = "Waiting")
        repo.updateAudioStatus("Working", TrackingRepository.AUDIO_STATUS_WORKING)
    }

    private data class TrackStopStats(
        val trackDurationSeconds: Long,
        val distance: Double,
        val points: Int
    )

    private fun stopTrackingSession(stats: TrackStopStats) {
        trackWriter.reset()
        repo.setActiveTrackPoints(emptyList())
        gpsManager.reset()

        if (isMonitoring) {
            notificationManager.postTrackingNotification("Monitoring...")
            repo.updateTrackDuration(stats.trackDurationSeconds)
            repo.updateTrackGeometry(distance = stats.distance, points = stats.points)
            repo.updateTrackingState(tracking = false, gpsStatus = repo.gpsStatus.value ?: "Waiting")
        } else {
            gpsManager.stopUpdates()
            stopBeepDetector()
            repo.updateTrackDuration(stats.trackDurationSeconds)
            repo.updateTrackGeometry(distance = stats.distance, points = stats.points)
            repo.updateTrackingState(tracking = false, gpsStatus = "Waiting")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            stopUiTickLoop()
        }
    }

    private fun cancelTracking() {
        if (!trackWriter.isTracking()) return

        stopBackupLoop()
        stopGpsFallbackLoop()
        GpxWriter.deleteBackupIfExists(this)
        repo.discardTrackCounts()
        stopTrackingSession(TrackStopStats(trackDurationSeconds = 0, distance = 0.0, points = 0))
    }

    private fun stopTracking() {
        if (!trackWriter.isTracking() || stopInProgress) return
        stopInProgress = true

        val finalTrackDurationSeconds = trackWriter.elapsedSeconds(System.currentTimeMillis())
        val finalDistance = trackWriter.totalDistance

        stopBackupLoop()

        val copy = trackWriter.activeTrackPointsSnapshot()
        val finalPointCount = copy.size
        repo.setActiveTrackPoints(copy)
        serviceScope.launch {
            try {
                if (copy.isNotEmpty()) {
                    val saveResult = withContext(NonCancellable + Dispatchers.IO) {
                        GpxWriter.saveTrackWithResult(this@TrackingService, copy)
                    }
                    if (saveResult != null) {
                        // Final save succeeded: remove any leftover backup file
                        withContext(NonCancellable + Dispatchers.IO) {
                            GpxWriter.deleteBackupIfExists(this@TrackingService)
                        }
                        sendBroadcast(
                            Intent(ACTION_TRACK_SAVED).putExtra(EXTRA_TRACK_ID, saveResult.sourceId)
                        )
                        notificationManager.showSaveToast(saveResult.displayPath)
                        saveResult.warning?.let { warning ->
                            notificationManager.showSaveToast(warning)
                        }
                    } else {
                        notificationManager.showSaveToast("File not saved (primary and fallback attempts failed)")
                    }
                } else {
                    notificationManager.showSaveToast("Nothing to save")
                }
            } catch (e: Exception) {
                android.util.Log.e("TrackingService", "Error during final save", e)
            } finally {
                repo.finalizeTrackCounts()
                stopTrackingSession(
                    TrackStopStats(
                        trackDurationSeconds = finalTrackDurationSeconds,
                        distance = finalDistance,
                        points = finalPointCount
                    )
                )
                stopInProgress = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Location / GPS
    // -------------------------------------------------------------------------

    private fun handleLocation(processedLocation: GpsManager.ProcessedLocation) {
        val loc = processedLocation.location
        val now = processedLocation.nowMillis
        val gpsMode = processedLocation.mode
        val tracking = trackWriter.isTracking()

        if (!tracking) {
            updateMonitoringStats(now)
        }

        if (gpsMode == TrackWriter.GpsMode.ACTIVE) {
            doseRateMeasurement.handleGpsLocation(loc)
        }

        if (tracking) {
            repo.updateMonitoringStatus(processedLocation.status)
            val totalBeeps = repo.getTotalCounts()
            val result = if (gpsMode == TrackWriter.GpsMode.ACTIVE) {
                trackWriter.handleGpsLocation(
                    loc = loc,
                    now = now,
                    totalBeeps = totalBeeps,
                    spacingM = spacingM,
                    minCountsPerPoint = minCountsPerPoint,
                    maxTimeWithoutCountsS = maxTimeWithoutCountsS,
                    coefficient = cpsToUsvhCoefficient
                )
            } else {
                maybeCommitNoGpsPoint(
                    now = now,
                    mode = gpsMode,
                    totalBeeps = totalBeeps,
                    spoofedLocation = loc
                )
            }

            result.snapshot?.let { snapshot ->
                repo.setActiveTrackPoints(snapshot)
                repo.updateTrackGeometry(
                    distance = trackWriter.totalDistance,
                    points = snapshot.size
                )
            }
        }
    }


    private fun toggleMeasurementMode() {
        val enabled = doseRateMeasurement.toggleMeasurementMode()
        repo.updateMeasurementMode(enabled)
        repo.updateCpsSnapshot(doseRateMeasurement.currentSnapshot(), onBeep = false)
    }

    private fun updateMonitoringStats(nowMillis: Long) {
        val gpsStatus = gpsManager.currentStatus(nowMillis)
        repo.updateMonitoringStatus(gpsStatus = gpsStatus)
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

                    if (trackWriter.isTracking()) {
                        kde?.addPoint(System.currentTimeMillis() / 1000.0, count)
                    }
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
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            } catch (e: Exception) {
                android.util.Log.e("TrackingService", "Failed to create ToneGenerator", e)
                return
            }
        }

        val tg = toneGenerator ?: return
        serviceScope.launch(Dispatchers.Main) {
            repeat(soundCount) { index ->
                try {
                    tg.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 200)
                } catch (e: Exception) {
                    android.util.Log.e("TrackingService", "Failed to play tone", e)
                    return@launch
                }
                if (index < soundCount - 1) {
                    delay(400L)
                }
            }
        }
    }

    private fun showDoseRateAlertNotification(meanDoseRate: Double) {
        val unit = if (cpsToUsvhCoefficient == 1.0) "cps" else "μSv/h"
        notificationManager.postAlertNotification(meanDoseRate = meanDoseRate, unit = unit)
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

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

    private fun startGpsFallbackLoop() {
        gpsFallbackJob?.cancel()
        gpsFallbackJob = serviceScope.launch {
            while (isActive && trackWriter.isTracking()) {
                delay(1000L)
                if (!trackWriter.isTracking()) break

                val now = System.currentTimeMillis()
                val elapsedSinceLastPoint = trackWriter.secondsSinceLastWritten(now)
                if (maxTimeWithoutGpsS <= 0.0 || elapsedSinceLastPoint < maxTimeWithoutGpsS) {
                    continue
                }

                val mode = gpsManager.currentMode(now)
                if (mode == TrackWriter.GpsMode.ACTIVE) {
                    continue
                }

                val result = maybeCommitNoGpsPoint(
                    now = now,
                    mode = mode,
                    totalBeeps = repo.getTotalCounts(),
                    spoofedLocation = gpsManager.lastLocationCopy()
                )
                result.snapshot?.let { snapshot ->
                    repo.setActiveTrackPoints(snapshot)
                    repo.updateTrackGeometry(
                        distance = trackWriter.totalDistance,
                        points = snapshot.size
                    )
                }
            }
        }
    }

    private fun stopGpsFallbackLoop() {
        gpsFallbackJob?.cancel()
        gpsFallbackJob = null
    }

    private fun startUiTickLoop() {
        uiTickJob?.cancel()
        uiTickJob = serviceScope.launch {
            while (isActive && (isMonitoring || trackWriter.isTracking())) {
                delay(1000L)
                if (!isMonitoring && !trackWriter.isTracking()) break
                val now = System.currentTimeMillis()
                repo.notifyUiTick(now)
                if (trackWriter.isTracking()) {
                    repo.updateTrackDuration(trackWriter.elapsedSeconds(now))
                }
            }
        }
    }

    private fun stopUiTickLoop() {
        uiTickJob?.cancel()
        uiTickJob = null
    }

    private fun maybeCommitNoGpsPoint(
        now: Long,
        mode: TrackWriter.GpsMode,
        totalBeeps: Int,
        spoofedLocation: Location?
    ): TrackWriter.ProcessLocationResult {
        if (mode == TrackWriter.GpsMode.ACTIVE) return TrackWriter.ProcessLocationResult()
        if (maxTimeWithoutGpsS <= 0.0) return TrackWriter.ProcessLocationResult()
        if (trackWriter.secondsSinceLastWritten(now) < maxTimeWithoutGpsS) return TrackWriter.ProcessLocationResult()

        return trackWriter.handleGpsFallback(
            mode = mode,
            spoofedLocation = spoofedLocation,
            now = now,
            totalBeeps = totalBeeps,
            minCountsPerPoint = minCountsPerPoint,
            maxTimeWithoutCountsS = maxTimeWithoutCountsS,
            coefficient = cpsToUsvhCoefficient
        )
    }
}
