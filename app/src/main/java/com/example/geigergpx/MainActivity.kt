package com.example.geigergpx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.example.geigergpx.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { TrackingViewModel.getInstance(application) }
    private var lastCps: Double = 0.0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // User can press Start again after granting permissions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonStart.setOnClickListener {
            if (ensurePermissions()) {
                startTracking()
            }
        }

        binding.buttonStop.setOnClickListener {
            stopTracking()
        }

        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateCpsOrDoseLine()
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

        viewModel.totalCounts.observe(this, Observer { counts ->
            binding.textTotalCounts.text = "Total counts: $counts"
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
}

