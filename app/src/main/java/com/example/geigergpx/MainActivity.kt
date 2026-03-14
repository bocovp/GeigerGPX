package com.example.geigergpx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.example.geigergpx.databinding.ActivityMainBinding
import android.widget.Toast
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.content.Context

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var lastCps: Double = 0.0

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

        val restoredName = GpxWriter.restoreBackupIfPresent(this)
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

        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateCpsOrDoseLine()
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        // Only stop monitoring if tracking is not active
        // If tracking is active, keep GPS and audio running in background
        if (viewModel.isTracking.value != true) {
            stopMonitoring()
        }
    }

    private fun updateCountDisplay(
        isTracking: Boolean = viewModel.isTracking.value ?: false,
        totalCounts: Int = viewModel.totalCounts.value ?: 0,
        trackCounts: Int = viewModel.trackCounts.value ?: 0) {
        binding.textTotalCounts.text = if (isTracking) {
            "Total counts: $trackCounts / $totalCounts"
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

        viewModel.currentCps.observe(this) { cps ->
            lastCps = cps
            updateCpsOrDoseLine()
        }

        viewModel.totalCounts.observe(this) { totalCounts ->
            updateCountDisplay(totalCounts = totalCounts)
        }

        viewModel.trackCounts.observe(this) { trackCount ->
            updateCountDisplay(trackCounts = trackCount)
        }

        viewModel.gpsStatus.observe(this) { status ->
            binding.textGpsStatus.text = "GPS status: $status"
            val color = when (status) {
                "Waiting" -> R.color.status_waiting
                "Working" -> R.color.status_working
                "Spoofing detected" -> R.color.status_spoofing
                else -> R.color.status_waiting
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
    }

    private fun updateCpsOrDoseLine() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        if (coeff == 1.0) {
            binding.textCps.text = "CPS: %.2f".format(lastCps)
        } else {
            binding.textCps.text = "Dose rate: %.2f μSv/h".format(lastCps * coeff)
        }
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

