package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityTimePlotBinding

class TimePlotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimePlotBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimePlotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }

        binding.placeholderSlider.valueFrom = 0f
        binding.placeholderSlider.valueTo = 100f
        binding.placeholderSlider.value = 50f

        syncBottomNavigationSelection()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    true
                }
                R.id.navigation_map -> {
                    startActivity(Intent(this, MapActivity::class.java))
                    true
                }
                R.id.navigation_tracks -> {
                    startActivity(Intent(this, TracksActivity::class.java))
                    true
                }
                R.id.navigation_poi -> {
                    startActivity(Intent(this, PoiActivity::class.java))
                    true
                }
                R.id.navigation_time_plot -> true
                else -> false
            }
        }

        val coeff = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val selectedTrackId = intent.getStringExtra(EXTRA_TRACK_ID)
        if (!selectedTrackId.isNullOrBlank()) {
            val selected = TrackCatalog.loadTrackSamplesById(this, selectedTrackId)
            if (selected != null) {
                binding.trackNameLabel.text = selected.title
                binding.timePlotView.setSamples(selected.samples, coeff)
                return
            }
        }

        binding.trackNameLabel.text = CURRENT_TRACK_TITLE
        viewModel.activeTrackPoints.observe(this) { points ->
            binding.timePlotView.setPoints(points, coeff)
        }
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun syncBottomNavigationSelection() {
        binding.bottomNavigation.menu.findItem(R.id.navigation_time_plot)?.isChecked = true
    }

    companion object {
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val CURRENT_TRACK_TITLE = "Currently recording"
    }
}
