package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityTimePlotBinding
import com.google.android.material.slider.Slider

class TimePlotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimePlotBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var cpsToUSvhCoeff: Double = 1.0
    private var currentSamples: List<TrackSample> = emptyList()
    private var activeTrackObserverAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimePlotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }

        setupGeneralizationSlider()

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

        cpsToUSvhCoeff = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val selectedTrackId = intent.getStringExtra(EXTRA_TRACK_ID)
        if (!selectedTrackId.isNullOrBlank()) {
            val selected = TrackCatalog.loadTrackSamplesById(this, selectedTrackId)
            if (selected != null) {
                binding.trackNameLabel.text = selected.title
                currentSamples = selected.samples
                updatePlotWithGeneralization()
                return
            }
        }

        binding.trackNameLabel.text = CURRENT_TRACK_TITLE
        observeActiveTrack()
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

    private fun setupGeneralizationSlider() {
        binding.placeholderSlider.valueFrom = 0f
        binding.placeholderSlider.valueTo = MAX_GENERALIZATION_MINUTES
        binding.placeholderSlider.stepSize = GENERALIZATION_SLIDER_STEP_MINUTES
        binding.placeholderSlider.value = 0f
        updateSliderDescription(binding.placeholderSlider.value)
        binding.placeholderSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            updateSliderDescription(value)
            if (fromUser) {
                updatePlotWithGeneralization()
            }
        }
    }

    private fun observeActiveTrack() {
        if (activeTrackObserverAttached) return
        activeTrackObserverAttached = true
        viewModel.activeTrackPoints.observe(this) { points ->
            currentSamples = points.map {
                TrackSample(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    doseRate = it.cps * cpsToUSvhCoeff,
                    counts = it.counts,
                    seconds = it.seconds
                )
            }
            updatePlotWithGeneralization()
        }
    }

    private fun updateSliderDescription(valueMinutes: Float) {
        binding.topAppBar.subtitle = "Min point duration: ${"%.1f".format(valueMinutes)} min"
    }

    private fun updatePlotWithGeneralization() {
        val minDurationSeconds = binding.placeholderSlider.value * SECONDS_PER_MINUTE
        val track = MapTrack(
            id = CURRENT_TRACK_TITLE,
            title = binding.trackNameLabel.text?.toString().orEmpty(),
            points = currentSamples
        )
        val generalized = TrackGeneralizer(
            minDistanceMeters = 0.0,
            coeff = cpsToUSvhCoeff,
            minDurationSeconds = minDurationSeconds.toDouble()
        ).generalize(track)
        binding.timePlotView.setSamples(generalized.points, cpsToUSvhCoeff)
    }

    companion object {
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val CURRENT_TRACK_TITLE = "Currently recording"
        private const val SECONDS_PER_MINUTE = 60f
        private const val MAX_GENERALIZATION_MINUTES = 10f
        private const val GENERALIZATION_SLIDER_STEP_MINUTES = 0.5f
    }
}
