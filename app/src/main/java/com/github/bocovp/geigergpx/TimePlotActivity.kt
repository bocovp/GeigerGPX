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
            rememberTrackSelection(this, selectedTrackId)
        }

        val trackToShow = selectedTrackId ?: preferredTrackSelection(this)
        val loaded = loadTrackForPlot(trackToShow)
        if (!loaded) {
            rememberCurrentTrackSelection(this)
            loadTrackForPlot(TrackCatalog.currentTrackId())
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

    private fun setupGeneralizationSlider() {
        binding.placeholderSlider.valueFrom = 0f
        binding.placeholderSlider.valueTo = GENERALIZATION_SLIDER_INTERNAL_MAX
        binding.placeholderSlider.value = 0f
<<<<<<< codex/make-slider-control-non-linear-jj7yie
        binding.placeholderSlider.setLabelFormatter { internalValue ->
            "%.1f".format(java.util.Locale.US, internalSliderToDurationMinutes(internalValue))
        }
=======
>>>>>>> main
        updateSliderDescription(internalSliderToDurationMinutes(binding.placeholderSlider.value))
        binding.placeholderSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val durationMinutes = internalSliderToDurationMinutes(value)
            updateSliderDescription(durationMinutes)
            if (fromUser) {
                updatePlotWithGeneralization(recalculateVerticalAxis = false)
            }
        }
        binding.placeholderSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                updatePlotWithGeneralization(recalculateVerticalAxis = true)
            }
        })
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
            updatePlotWithGeneralization(recalculateVerticalAxis = true)
        }
    }

    private fun loadTrackForPlot(trackId: String?): Boolean {
        val normalizedTrackId = trackId?.takeIf { it.isNotBlank() }
        if (normalizedTrackId == null || normalizedTrackId == TrackCatalog.currentTrackId()) {
            binding.trackNameLabel.text = CURRENT_TRACK_TITLE
            observeActiveTrack()
            return true
        }

        val selected = TrackCatalog.loadTrackSamplesById(this, normalizedTrackId) ?: return false
        binding.trackNameLabel.text = selected.title
        currentSamples = selected.samples
        updatePlotWithGeneralization()
        return true
    }

    private fun updateSliderDescription(valueMinutes: Float) {
        binding.topAppBar.subtitle = "Min point duration: ${"%.1f".format(java.util.Locale.US, valueMinutes)} min"
    }

    private fun internalSliderToDurationMinutes(sliderInternalValue: Float): Float {
        // Full formula: minDuration = 10 * (exp(3 * x) - 1) / (exp(3) - 1), where x is in [0, 1].
        val expTerm = kotlin.math.exp(3.0 * sliderInternalValue.toDouble()) - 1.0
        return (EXP_SCALE_FACTOR * expTerm).toFloat()
    }

    private fun updatePlotWithGeneralization(recalculateVerticalAxis: Boolean = true) {
        val minDurationMinutes = internalSliderToDurationMinutes(binding.placeholderSlider.value)
        val minDurationSeconds = minDurationMinutes * SECONDS_PER_MINUTE
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
        binding.timePlotView.setSamples(
            samples = generalized.points,
            cpsToUSvh = cpsToUSvhCoeff,
            recalculateVerticalAxis = recalculateVerticalAxis
        )
    }

    companion object {
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val CURRENT_TRACK_TITLE = "Currently recording"
        private const val SECONDS_PER_MINUTE = 60f
        private const val GENERALIZATION_SLIDER_INTERNAL_MAX = 1f
        private const val EXP_SCALE_FACTOR = 0.523957

        fun rememberTrackSelection(context: android.content.Context, trackId: String) {
            val app = context.applicationContext as? GeigerGpxApp ?: return
            app.selectedTimePlotTrackId = trackId
        }

        fun rememberCurrentTrackSelection(context: android.content.Context) {
            rememberTrackSelection(context, TrackCatalog.currentTrackId())
        }

        fun preferredTrackSelection(context: android.content.Context): String? {
            val app = context.applicationContext as? GeigerGpxApp
            return app?.selectedTimePlotTrackId
        }
    }
}
