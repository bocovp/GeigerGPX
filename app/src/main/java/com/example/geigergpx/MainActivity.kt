package com.example.geigergpx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.example.geigergpx.databinding.ActivityMainBinding
import android.widget.Toast
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var latestCpsSnapshot = TrackingRepository.CpsSnapshot()
    private var isMeasurementModeEnabled: Boolean = false

    private val cpsRefreshHandler = Handler(Looper.getMainLooper())
    private val cpsRefreshRunnable = object : Runnable {
        override fun run() {
            updateCpsOrDoseLine(false)
            cpsRefreshHandler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // User can press Start again after granting permissions
    }

    private val folderPickerForStop = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(SettingsFragment.KEY_GPX_TREE_URI, uri.toString())
                .apply()
        }
        // Stop tracking regardless of whether a folder was chosen or cancelled
        stopTracking()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val restoredName = (application as GeigerGpxApp).consumeRestoredBackupName()
        if (restoredName != null) {
            Toast.makeText(
                this,
                "Backup file was restored and saved as $restoredName",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.buttonStart.setOnClickListener {
            if (viewModel.isTracking.value == true) {
                if ((viewModel.pointCount.value ?: 0) == 0) {
                    cancelTracking()
                } else {
                    showCancelTrackConfirmation()
                }
            } else if (ensurePermissions()) {
                checkBatteryOptimizations()
                startTracking()
            }
        }

        binding.buttonStop.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val treeUri = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)
            if (treeUri.isNullOrBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Choose save folder")
                    .setMessage("No save folder is set. Please choose a folder where the GPX file will be saved.")
                    .setPositiveButton("Choose folder") { _, _ ->
                        folderPickerForStop.launch(null)
                    }
                    .setNegativeButton("Use app folder") { _, _ ->
                        stopTracking()
                    }
                    .show()
            } else {
                stopTracking()
            }
        }

        binding.buttonMeasurementMode.setOnClickListener {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE
            }
            startService(intent)
        }

        binding.buttonSavePoi.setOnClickListener {
            showSavePoiDialog()
        }

        binding.buttonMap.setOnClickListener {
            openMap()
        }

        binding.buttonPoi.setOnClickListener {
            openPoi()
        }

        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_map -> {
                openMap()
                true
            }
            R.id.action_poi -> {
                openPoi()
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openMap() {
        startActivity(Intent(this, MapActivity::class.java))
    }

    private fun openPoi() {
        startActivity(Intent(this, PoiActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        updateCpsOrDoseLine(false)
        startCpsRefreshLoop()
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopCpsRefreshLoop()
        // Keep monitoring active in background while tracking or while
        // measurement mode is enabled.
        if (viewModel.isTracking.value != true && !isMeasurementModeEnabled) {
            stopMonitoring()
        }
    }

    private fun startCpsRefreshLoop() {
        cpsRefreshHandler.removeCallbacks(cpsRefreshRunnable)
        cpsRefreshHandler.postDelayed(cpsRefreshRunnable, 1000L)
    }

    private fun stopCpsRefreshLoop() {
        cpsRefreshHandler.removeCallbacks(cpsRefreshRunnable)
    }

    private fun updateCountDisplay(
        isTracking: Boolean = viewModel.isTracking.value ?: false,
        totalCounts: Int = viewModel.totalCounts.value ?: 0,
        trackCounts: Int = viewModel.trackCounts.value ?: 0,
        savedTrackCounts: Int? = viewModel.savedTrackCounts.value
    ) {
        val displayedTrackCounts = if (isTracking) trackCounts else (savedTrackCounts ?: trackCounts)
        val measurementCount = if (isMeasurementModeEnabled) latestCpsSnapshot.sampleCount else 0
        val measurementDurationSeconds = if (isMeasurementModeEnabled) {
            val measurementStart = latestCpsSnapshot.measurementStartTimestampMillis
            if (measurementStart > 0L) {
                ((System.currentTimeMillis() - measurementStart).toDouble() / 1000.0)
                    .coerceAtLeast(0.0)
            } else {
                0.0
            }
        } else {
            0.0
        }

        binding.textTrackCounts.text = "Track counts: $displayedTrackCounts"
        binding.textMeasurementCounts.text = "Counts: $measurementCount"
        binding.textMeasurementDuration.text = "Duration: ${measurementDurationSeconds.roundToInt()} s"
        binding.textTotalCounts.text = "Total counts: $totalCounts"
    }

    private fun observeViewModel() {
        viewModel.isTracking.observe(this) { tracking ->
            binding.buttonStart.text = if (tracking) "Cancel" else "Start track"
            binding.buttonStop.isEnabled = tracking

            updateCountDisplay(isTracking = tracking)
        }

        viewModel.durationText.observe(this) {
            binding.textDuration.text = "Duration: $it"
        }

        viewModel.distanceMeters.observe(this) { dist ->
            binding.textDistance.text = "Distance: %.1f m".format(dist)
        }

        viewModel.pointCount.observe(this) { count ->
            binding.textPoints.text = "Points: $count"
        }

        viewModel.cpsUpdate.observe(this) { update ->
            latestCpsSnapshot = update.snapshot
            updateCpsOrDoseLine(update.onBeep)
            updateCountDisplay()
        }

        viewModel.totalCounts.observe(this) { totalCounts ->
            updateCountDisplay(totalCounts = totalCounts)
        }

        viewModel.trackCounts.observe(this) { trackCount ->
            updateCountDisplay(trackCounts = trackCount)
        }

        viewModel.savedTrackCounts.observe(this) { savedCounts ->
            updateCountDisplay(savedTrackCounts = savedCounts)
        }

        viewModel.gpsStatus.observe(this) { status ->
            binding.textGpsStatus.text = "GPS status: $status"
            val color = when (status) {
                "Waiting" -> R.color.status_waiting
                "Working" -> R.color.status_working
                else -> if (status.startsWith("Spoofing detected")) {
                    R.color.status_spoofing
                } else {
                    R.color.status_waiting
                }
            }
            binding.textGpsStatus.setTextColor(ContextCompat.getColor(this, color))
        }

        viewModel.audioStatus.observe(this) { status ->
            binding.textAudioStatus.text = "Audio status: $status"
            val color = when (status) {
                "Working" -> R.color.status_working
                "Error" -> R.color.status_spoofing
                else -> R.color.status_waiting
            }
            binding.textAudioStatus.setTextColor(ContextCompat.getColor(this, color))
        }

        viewModel.measurementModeEnabled.observe(this) { enabled ->
            isMeasurementModeEnabled = enabled
            binding.buttonMeasurementMode.text = if (enabled) {
                "Live Mode"
            } else {
                "Measure"
            }
            binding.buttonSavePoi.isEnabled = enabled
            updateCpsOrDoseLine(false)
            updateCountDisplay()
        }
    }

    private fun showSavePoiDialog() {
        if (!isMeasurementModeEnabled) {
            return
        }

        val input = EditText(this).apply {
            hint = "Description"
        }

        AlertDialog.Builder(this)
            .setTitle("Save POI")
            .setView(input)
            .setPositiveButton("Save POI") { _, _ ->
                val description = input.text?.toString()?.trim().orEmpty()
                val (counts, seconds) = getCurrentMeasurementCountsAndSeconds()
                val coeff = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
                val doseRate = ConfidenceInterval(0.0, seconds, counts + 1).scale(coeff).mean
                val (latitude, longitude) = TrackingService.consumeMeasurementAverageCoordinates()

                val ok = PoiLibrary.addPoi(
                    context = this,
                    description = description,
                    timestampMillis = System.currentTimeMillis(),
                    latitude = latitude,
                    longitude = longitude,
                    doseRate = doseRate,
                    counts = counts,
                    seconds = seconds
                )
                if (ok) {
                    Toast.makeText(this, "POI saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Unable to save POI", Toast.LENGTH_SHORT).show()
                }

                if (isMeasurementModeEnabled) {
                    val intent = Intent(this, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE
                    }
                    startService(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCurrentMeasurementCountsAndSeconds(): Pair<Int, Double> {
        val counts = latestCpsSnapshot.sampleCount.coerceAtLeast(0)
        val seconds = if (latestCpsSnapshot.oldestTimestampMillis > 0L) {
            ((System.currentTimeMillis() - latestCpsSnapshot.oldestTimestampMillis).toDouble() / 1000.0)
                .coerceAtLeast(0.0)
        } else {
            0.0
        }
        return Pair(counts, seconds)
    }

    private fun updateCpsOrDoseLine(onBeep: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val t1 = latestCpsSnapshot.oldestTimestampMillis.toDouble() / 1000.0

        val ci = if (onBeep) {
            val tn = System.currentTimeMillis().toDouble() / 1000.0
            // if onBeep we have only n-1 intervals ot analyze (t1->t2) (t2->t1) ... (t{n-1}->tn)
            ConfidenceInterval(t1, tn, latestCpsSnapshot.sampleCount)
        } else {
            val t_now = System.currentTimeMillis().toDouble() / 1000.0
            // if not onBeep we have n intervals: (t1->t2) (t2->t1) ... (t{n-1}->tn) and (tn->now)
            ConfidenceInterval(t1, t_now, latestCpsSnapshot.sampleCount + 1)
        }

        val doseRateMean = ci.mean * coeff
        val doseRateDelta = ci.delta * coeff

        val decimalDigits = if (isMeasurementModeEnabled) {
            if (doseRateDelta < 0.002  * (coeff / 0.1)) 4 else 3
        } else 2

        val doseColor = when {
            doseRateMean < 0.000001 -> R.color.dose_zero
            doseRateMean < 0.15 -> R.color.dose_low
            doseRateMean < 0.3 -> R.color.dose_medium
            else -> R.color.dose_high
        }
        binding.textCps.setTextColor(ContextCompat.getColor(this, doseColor))

        if (coeff == 1.0) {
            val cpsText = ci.toText(decimalDigits)
            binding.textCps.text = "CPS: $cpsText"
        } else {
            val doseRateText = ci.scale(coeff).toText(decimalDigits)
            binding.textCps.text = "Dose rate: $doseRateText μSv/h"
        }
    }

    private fun showCancelTrackConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Cancel track")
            .setMessage("Are you sure you want to discard track?")
            .setPositiveButton("Yes") { _, _ ->
                cancelTracking()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun cancelTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_CANCEL_TRACK
        }
        startService(intent)
    }

    private fun startTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }

        return if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun startMonitoring() {
        if (ensurePermissions()) {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START_MONITORING
            }
            startService(intent)
        }
    }

    private fun stopMonitoring() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP_MONITORING
        }
        startService(intent)
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName

        // Check if the app is already "Unrestricted" (ignored by battery optimizations)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Background Battery Usage")
                .setMessage("To record radiation in the background, battery usage must be set to 'Unrestricted'. Otherwise, Android will stop the app after 10 minutes.")
                .setPositiveButton("Set to Unrestricted") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Ignore") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }
}
