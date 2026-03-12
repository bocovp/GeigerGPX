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
        if (!viewModel.isTracking.value!!) {
            stopMonitoring()
        }
    }

    private fun observeViewModel() {
        viewModel.isTracking.observe(this, Observer { tracking ->
            binding.buttonStart.isEnabled = !tracking
            binding.buttonStop.isEnabled = tracking
        })

        viewModel.durationText.observe(this, Observer {
            binding.textDuration.text = "Duration: $it"
        })

        viewModel.distanceMeters.observe(this, Observer { dist ->
            binding.textDistance.text = "Distance: %.1f m".format(dist)
        })

        viewModel.pointCount.observe(this, Observer { count ->
            binding.textPoints.text = "Points: $count"
        })

        viewModel.currentCps.observe(this, Observer { cps ->
            lastCps = cps
            updateCpsOrDoseLine()
        })

        viewModel.totalCounts.observe(this, Observer { totalCounts ->
            val trackCounts = viewModel.trackCounts.value ?: 0
            val isTracking = viewModel.isTracking.value ?: false
            
            if (isTracking) {
                binding.textTotalCounts.text = "Total counts: $trackCounts / $totalCounts"
            } else {
                binding.textTotalCounts.text = "Total counts: $totalCounts"
            }
        })

        viewModel.gpsStatus.observe(this, Observer { status ->
            binding.textGpsStatus.text = "GPS status: $status"
            val color = when (status) {
                "Waiting" -> R.color.status_waiting
                "Working" -> R.color.status_working
                "Spoofing detected" -> R.color.status_spoofing
                else -> R.color.status_waiting
            }
            binding.textGpsStatus.setTextColor(ContextCompat.getColor(this, color))
        })
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
}

