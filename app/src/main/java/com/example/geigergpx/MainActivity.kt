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
import kotlin.math.sqrt
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var latestCpsSnapshot = TrackingRepository.CpsSnapshot()
    private var isHighAccuracyModeEnabled: Boolean = false

    private val cpsRefreshHandler = Handler(Looper.getMainLooper())
    private val cpsRefreshRunnable = object : Runnable {
        override fun run() {
            updateCpsOrDoseLine()
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
            if (ensurePermissions()) {
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

        binding.buttonHighAccuracy.setOnClickListener {
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_TOGGLE_HIGH_ACCURACY_MEASUREMENT
            }
            startService(intent)
        }

        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.buttonMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateCpsOrDoseLine()
        startCpsRefreshLoop()
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopCpsRefreshLoop()
        // Keep monitoring active in background while tracking or while high-accuracy
        // measurement mode is enabled.
        if (viewModel.isTracking.value != true && !isHighAccuracyModeEnabled) {
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
        binding.textTotalCounts.text = if (isTracking || savedTrackCounts != null) {
            val persistedTrackCounts = if (isTracking) trackCounts else (savedTrackCounts ?: trackCounts)
            "Total counts: $persistedTrackCounts / $totalCounts"
        } else {
            "Total counts: $totalCounts"
        }
    }

    private fun observeViewModel() {
        viewModel.isTracking.observe(this) { tracking ->
            binding.buttonStart.isEnabled = !tracking
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

        viewModel.cpsSnapshot.observe(this) { snapshot ->
            latestCpsSnapshot = snapshot
            updateCpsOrDoseLine()
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

        viewModel.highAccuracyModeEnabled.observe(this) { enabled ->
            isHighAccuracyModeEnabled = enabled
            binding.buttonHighAccuracy.text = if (enabled) {
                "Live mode"
            } else {
                "High accuracy measurement"
            }
            updateCpsOrDoseLine()
        }
    }

    private fun updateCpsOrDoseLine() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val decimalDigits = if (isHighAccuracyModeEnabled) 3 else 2

        val t1 = latestCpsSnapshot.oldestTimestampMillis.toDouble() / 1000.0
        val tn = System.currentTimeMillis().toDouble() / 1000.0

        val ci = getConfidenceInterval(t1, tn, latestCpsSnapshot.sampleCount)

        if (coeff == 1.0) {
            if (latestCpsSnapshot.sampleCount <= 9) {
                binding.textCps.text = "CPS: %.${decimalDigits}f … %.${decimalDigits}f".format(ci.lowBound, ci.highBound)
            } else {
                binding.textCps.text = "CPS: %.${decimalDigits}f ± %.${decimalDigits}f".format(ci.mean, ci.delta)
            }
        } else {
            val doseRateMean = ci.mean * coeff
            val doseRateLow = ci.lowBound * coeff
            val doseRateHigh = ci.highBound * coeff
            val doseRateDelta = ci.delta * coeff

            if (latestCpsSnapshot.sampleCount <= 9) {
                binding.textCps.text = "Dose rate: %.${decimalDigits}f … %.${decimalDigits}f μSv/h".format(doseRateLow, doseRateHigh)
            } else {
                binding.textCps.text = "Dose rate: %.${decimalDigits}f ± %.${decimalDigits}f μSv/h".format(doseRateMean, doseRateDelta)
            }
        }
    }

    private data class ConfidenceInterval(
        val mean: Double,
        val delta: Double,
        val lowBound: Double,
        val highBound: Double
    )

    private fun getConfidenceInterval(t1: Double, tn: Double, n: Int): ConfidenceInterval {
        if (n <= 1 || tn <= t1) {
            return ConfidenceInterval(mean = 0.0, delta = 0.0, lowBound = 0.0, highBound = 0.0)
        }
        val deltaTime = tn - t1
        // Using unbiased estimator for low number of points
        val norm = (if (n < 10) n-2 else n-1).toDouble()

        val mean = norm / deltaTime
        val z = 1.95996
        val root =  sqrt((n - 1).toDouble())
        val delta = mean * z / root // This is simply CI for normal distribution
        val gamma = mean * (z*z - 1.0)/(3*(n - 1)).toDouble() // This follows from Cornish–Fisher expansion for Chi^2 distribution

        return ConfidenceInterval(
            mean = mean,
            delta = delta,
            lowBound = max(0.0, mean - delta + gamma),
            highBound = mean + delta + gamma
        )
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
