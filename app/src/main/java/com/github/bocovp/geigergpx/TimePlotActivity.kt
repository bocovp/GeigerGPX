package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityTimePlotBinding
import com.google.android.material.slider.Slider
import java.util.Locale

class TimePlotActivity : AppCompatActivity() {
    private enum class PlotMode { SLIDING_WINDOW, KERNEL_ESTIMATOR }

    private data class PlotCandidate(
        val id: String,
        val title: String
    )

    private lateinit var binding: ActivityTimePlotBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var cpsToUSvhCoeff: Double = 1.0
    private var currentSamples: List<TrackSample> = emptyList()
    private var activeTrackObserverAttached = false
    private var trackingObserverAttached = false
    /**
     * Tracks that failed to load in this session. Used to avoid repeatedly trying a stale/missing
     * track ID when preferences still contain it.
     */
    private val failedTrackIdsForPlot = mutableSetOf<String>()
    private var selectedTrackIdForPlot: String? = null
    private var plotCandidates: List<PlotCandidate> = emptyList()
    private var plotMode: PlotMode = PlotMode.SLIDING_WINDOW
    private var plotLoadRequestToken: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimePlotBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupGeneralizationSlider()
        setupTrackSelector()
        observeTrackingState()

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

        showLoading(true)
        binding.root.post {
            refreshTrackCandidatesAndPlotAsync(selectedTrackId)
        }
        invalidateOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.time_plot_toolbar_menu, menu)
        updateModeUi(menu.findItem(R.id.action_toggle_plot_mode))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateModeUi(menu.findItem(R.id.action_toggle_plot_mode))
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_toggle_plot_mode -> {
                plotMode = if (plotMode == PlotMode.SLIDING_WINDOW) {
                    PlotMode.KERNEL_ESTIMATOR
                } else {
                    PlotMode.SLIDING_WINDOW
                }
                updatePlot(recalculateVerticalAxis = true)
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun syncBottomNavigationSelection() {
        binding.bottomNavigation.menu.findItem(R.id.navigation_time_plot)?.isChecked = true
    }

    private fun setupTrackSelector() {
        binding.trackNameInputLayout.setStartIconOnClickListener {
            cycleSelectedTrack(-1)
        }
        binding.trackNameInputLayout.setEndIconOnClickListener {
            cycleSelectedTrack(1)
        }
        updateTrackSelectorUi()
    }

    private fun setupGeneralizationSlider() {
        binding.placeholderSlider.valueFrom = 0f
        binding.placeholderSlider.valueTo = GENERALIZATION_SLIDER_INTERNAL_MAX
        binding.placeholderSlider.value = 0f
        binding.placeholderSlider.setLabelFormatter { internalValue ->
            "%.1f".format(Locale.US, internalSliderToDurationMinutes(internalValue))
        }
        updateSliderDescription(internalSliderToDurationMinutes(binding.placeholderSlider.value))
        binding.placeholderSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val durationMinutes = internalSliderToDurationMinutes(value)
            updateSliderDescription(durationMinutes)
            if (fromUser) {
                updatePlot(recalculateVerticalAxis = false)
            }
        }
        binding.placeholderSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                updatePlot(recalculateVerticalAxis = true)
            }
        })
    }

    private fun observeTrackingState() {
        if (trackingObserverAttached) return
        trackingObserverAttached = true
        viewModel.isTracking.observe(this) {
            refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
        }
    }

    private fun observeActiveTrack() {
        if (activeTrackObserverAttached) return
        activeTrackObserverAttached = true
        viewModel.activeTrackPoints.observe(this) { points ->
            // Prevent the live recording stream from overwriting the plot after the user switches
            // to a saved track (or to a no-data state).
            if (selectedTrackIdForPlot != TrackCatalog.currentTrackId()) return@observe
            currentSamples = points.map {
                TrackSample(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    doseRate = it.cps * cpsToUSvhCoeff,
                    counts = it.counts,
                    seconds = it.seconds
                )
            }
            updatePlot(recalculateVerticalAxis = true)
        }
    }

    private fun refreshTrackCandidatesAndPlotAsync(preferredTrackId: String? = null) {
        showLoading(true)
        Thread {
            val candidates = loadPlotCandidates()
            runOnUiThread {
                plotCandidates = candidates
                val resolvedTrackId = resolveSelectedTrackId(candidates, preferredTrackId)
                updateTrackSelectorUi()
                if (resolvedTrackId == null) {
                    selectedTrackIdForPlot = null
                    currentSamples = emptyList()
                    updateTrackTitle(null)
                    binding.timePlotView.setSamples(emptyList(), cpsToUSvhCoeff, recalculateVerticalAxis = true)
                    showPlotMessage(R.string.time_plot_no_track_data)
                } else {
                    loadTrackForPlotAsync(resolvedTrackId)
                }
            }
        }.start()
    }

    private fun loadPlotCandidates(): List<PlotCandidate> {
        val activePoints = viewModel.activeTrackPoints.value.orEmpty()
        val includeCurrentTrack = viewModel.isTracking.value == true
        val selectedTrackIds = TrackSelectionPrefs.selectedTrackIds(this)
        val selectedFolders = TrackSelectionPrefs.selectedFolderIds(this)
        val mapTrackIds = selectedTrackIds.ifEmpty { setOf(TrackCatalog.currentTrackId()) }
        val items = TrackCatalog.loadTrackListItems(
            context = this,
            activePoints = activePoints,
            includeCurrentTrack = includeCurrentTrack,
            includeMapTracks = false,
            mapTrackIds = mapTrackIds,
            includeSubfolderTracks = true
        )
        return items
            .filter { item ->
                when {
                    item.itemType != TrackListItemType.TRACK -> false
                    item.folderName != null -> {
                        selectedTrackIds.contains(item.id) && selectedFolders.contains(item.folderName)
                    }
                    else -> selectedTrackIds.contains(item.id) || (selectedTrackIds.isEmpty() && item.defaultVisible)
                }
            }
            .map { PlotCandidate(id = it.id, title = it.title) }
    }

    private fun loadTrackForPlotAsync(trackId: String?) {
        val normalizedTrackId = trackId?.takeIf { it.isNotBlank() }
        if (normalizedTrackId == null || normalizedTrackId == TrackCatalog.currentTrackId()) {
            loadTrackForPlot(trackId)
            return
        }

        // Switch the "current track for plotting" immediately so the live observer can’t
        // overwrite the plot while the async load is in-flight.
        selectedTrackIdForPlot = normalizedTrackId

        val requestToken = ++plotLoadRequestToken
        Thread {
            val selected = TrackCatalog.loadTrackSamplesById(this, normalizedTrackId)
            runOnUiThread {
                if (requestToken != plotLoadRequestToken || selectedTrackIdForPlot != normalizedTrackId) {
                    return@runOnUiThread
                }
                if (selected != null) {
                    applyLoadedTrack(normalizedTrackId, selected)
                } else {
                    failedTrackIdsForPlot.add(normalizedTrackId)
                    refreshTrackCandidatesAndPlotAsync()
                }
            }
        }.start()
    }

    private fun showLoading(isLoading: Boolean) {
        if (isLoading) {
            showPlotMessage(R.string.time_plot_loading)
        } else {
            showPlotMessage(null)
        }
    }

    private fun showPlotMessage(messageResId: Int?) {
        if (messageResId == null) {
            binding.loadingLabel.visibility = View.GONE
            binding.timePlotView.visibility = View.VISIBLE
            return
        }

        binding.loadingLabel.setText(messageResId)
        binding.loadingLabel.visibility = View.VISIBLE
        binding.timePlotView.visibility = View.INVISIBLE
    }

    private fun loadTrackForPlot(trackId: String?): Boolean {
        selectedTrackIdForPlot = trackId
        val normalizedTrackId = trackId?.takeIf { it.isNotBlank() }
        if (normalizedTrackId == null || normalizedTrackId == TrackCatalog.currentTrackId()) {
            updateTrackTitle(CURRENT_TRACK_TITLE)
            rememberCurrentTrackSelection(this)
            updateTrackSelectorUi()
            observeActiveTrack()
            return true
        }

        val selected = TrackCatalog.loadTrackSamplesById(this, normalizedTrackId) ?: return false
        applyLoadedTrack(normalizedTrackId, selected)
        return true
    }

    private fun applyLoadedTrack(trackId: String, selectedTrack: TrackCatalog.TrackPlotData) {
        selectedTrackIdForPlot = trackId
        failedTrackIdsForPlot.remove(trackId)
        rememberTrackSelection(this, trackId)
        updateTrackTitle(selectedTrack.title)
        updateTrackSelectorUi()
        currentSamples = selectedTrack.samples
        updatePlot()
    }

    private fun updateSliderDescription(valueMinutes: Float) {
        binding.topAppBar.subtitle = "Averaging window: ${"%.1f".format(Locale.US, valueMinutes)} min"
    }

    private fun internalSliderToDurationMinutes(sliderInternalValue: Float): Float {
        // Full formula: minDuration = 10 * (exp(3 * x) - 1) / (exp(3) - 1), where x is in [0, 1].
        val expTerm = kotlin.math.exp(3.0 * sliderInternalValue.toDouble()) - 1.0
        return (EXP_SCALE_FACTOR * expTerm).toFloat()
    }

    private fun updatePlot(recalculateVerticalAxis: Boolean = true) {
        val minDurationMinutes = internalSliderToDurationMinutes(binding.placeholderSlider.value)
        val minDurationSeconds = minDurationMinutes * SECONDS_PER_MINUTE
        if (plotMode == PlotMode.KERNEL_ESTIMATOR) {
            updateKernelEstimatorPlot(minDurationSeconds.toDouble(), recalculateVerticalAxis)
            return
        }
        updatePlotWithGeneralization(minDurationSeconds.toDouble(), recalculateVerticalAxis)
    }

    private fun updatePlotWithGeneralization(
        minDurationSeconds: Double,
        recalculateVerticalAxis: Boolean = true
    ) {
        val track = MapTrack(
            id = CURRENT_TRACK_TITLE,
            title = binding.trackNameField.text?.toString().orEmpty(),
            points = currentSamples
        )
        val generalized = TrackGeneralizer(
            minDistanceMeters = 0.0,
            coeff = cpsToUSvhCoeff,
            minDurationSeconds = minDurationSeconds
        ).generalize(track)
        binding.timePlotView.setSamples(
            samples = generalized.points,
            cpsToUSvh = cpsToUSvhCoeff,
            recalculateVerticalAxis = recalculateVerticalAxis
        )
        if (generalized.points.isEmpty()) {
            showPlotMessage(R.string.time_plot_no_track_data)
        } else {
            showPlotMessage(null)
        }
    }

    private fun updateTrackTitle(title: String?) {
        binding.trackNameField.setText(title.orEmpty())
    }

    private fun updateTrackSelectorUi() {
        val hasMultipleCandidates = plotCandidates.size > 1
        binding.trackNameInputLayout.isStartIconVisible = hasMultipleCandidates
        binding.trackNameInputLayout.isEndIconVisible = hasMultipleCandidates
        if (selectedTrackIdForPlot != null) {
            val candidateTitle = plotCandidates.firstOrNull { it.id == selectedTrackIdForPlot }?.title
            if (candidateTitle != null && binding.trackNameField.text?.toString() != candidateTitle) {
                updateTrackTitle(candidateTitle)
            }
        }
    }

    private fun resolveSelectedTrackId(candidates: List<PlotCandidate>, preferredTrackId: String?): String? {
        if (candidates.isEmpty()) {
            return null
        }
        val preferredIds = listOfNotNull(
            preferredTrackId?.takeIf { it.isNotBlank() },
            preferredTrackSelection(this),
            selectedTrackIdForPlot
        )
        val matchingCandidate = preferredIds.firstNotNullOfOrNull { candidateId ->
            if (failedTrackIdsForPlot.contains(candidateId)) return@firstNotNullOfOrNull null
            candidates.firstOrNull { it.id == candidateId }?.id
        }
        if (matchingCandidate != null) return matchingCandidate

        return candidates.firstOrNull { it.id !in failedTrackIdsForPlot }?.id
    }

    private fun cycleSelectedTrack(delta: Int) {
        if (plotCandidates.size <= 1) return
        val currentIndex = plotCandidates.indexOfFirst { it.id == selectedTrackIdForPlot }
            .takeIf { it >= 0 }
            ?: 0
        val nextIndex = (currentIndex + delta).mod(plotCandidates.size)
        val nextTrackId = plotCandidates[nextIndex].id
        // Allow retrying a previously failed track if the file re-appeared.
        failedTrackIdsForPlot.remove(nextTrackId)
        rememberTrackSelection(this, nextTrackId)
        loadTrackForPlotAsync(nextTrackId)
    }

    private fun updateKernelEstimatorPlot(scaleSeconds: Double, recalculateVerticalAxis: Boolean) {
        val isCurrentTrack = selectedTrackIdForPlot.isNullOrBlank() || selectedTrackIdForPlot == TrackCatalog.currentTrackId()
        if (isCurrentTrack) {
            updateKernelEstimatorPlotForCurrentTrack(scaleSeconds, recalculateVerticalAxis)
        } else {
            updateKernelEstimatorPlotFromSamples(
                samples = currentSamples,
                scaleSeconds = scaleSeconds,
                recalculateVerticalAxis = recalculateVerticalAxis
            )
        }
    }

    private fun updateKernelEstimatorPlotForCurrentTrack(
        scaleSeconds: Double,
        recalculateVerticalAxis: Boolean
    ) {
        val bounds = TrackingService.activeKdeTimestampBounds()
        renderKernelEstimatorPlot(
            bounds = bounds,
            recalculateVerticalAxis = recalculateVerticalAxis
        ) { t2s ->
            TrackingService.activeKdeConfidenceIntervals(
                t2s = t2s,
                scaleSeconds = scaleSeconds
            )
        }
    }

    private fun renderKernelEstimatorPlot(
        bounds: Pair<Double, Double>?,
        recalculateVerticalAxis: Boolean,
        getConfidenceIntervals: (DoubleArray) -> Triple<DoubleArray, DoubleArray, DoubleArray>?
    ) {
        if (bounds == null || bounds.second < bounds.first) {
            binding.timePlotView.setSamples(emptyList(), cpsToUSvhCoeff, recalculateVerticalAxis)
            showPlotMessage(R.string.time_plot_no_track_data)
            return
        }
        val firstTimestamp = bounds.first
        val lastTimestamp = bounds.second
        val sampleCount = KDE_PLOT_SAMPLE_COUNT
        val ts2 = if (kotlin.math.abs(lastTimestamp - firstTimestamp) < 1e-9) {
            doubleArrayOf(firstTimestamp)
        } else {
            val step = (lastTimestamp - firstTimestamp) / (sampleCount - 1).toDouble()
            DoubleArray(sampleCount) { idx -> firstTimestamp + idx * step }
        }
        val ci = getConfidenceIntervals(ts2)
        if (ci == null) {
            binding.timePlotView.setSamples(emptyList(), cpsToUSvhCoeff, recalculateVerticalAxis)
            showPlotMessage(R.string.time_plot_no_track_data)
            return
        }
        val (mean, low, high) = ci
        val relativeSeconds = DoubleArray(ts2.size) { idx -> ts2[idx] - firstTimestamp }
        binding.timePlotView.setKernelSeries(
            relativeSeconds = relativeSeconds,
            mean = mean,
            low = low,
            high = high,
            cpsToUSvh = cpsToUSvhCoeff,
            recalculateVerticalAxis = recalculateVerticalAxis
        )
        if (relativeSeconds.isEmpty()) {
            showPlotMessage(R.string.time_plot_no_track_data)
        } else {
            showPlotMessage(null)
        }
    }

    private fun updateKernelEstimatorPlotFromSamples(
        samples: List<TrackSample>,
        scaleSeconds: Double,
        recalculateVerticalAxis: Boolean
    ) {
        if (samples.isEmpty()) {
            binding.timePlotView.setSamples(emptyList(), cpsToUSvhCoeff, recalculateVerticalAxis)
            showPlotMessage(R.string.time_plot_no_track_data)
            return
        }

        val estimator = KernelDensityEstimator(cpsToUSvhCoeff)
        var accumulatedSeconds = 0.0
        for (sample in samples) {
            val durationSeconds = sample.seconds.coerceAtLeast(0.0)
            addSampleToEstimator(
                estimator = estimator,
                intervalStartSeconds = accumulatedSeconds,
                durationSeconds = durationSeconds,
                counts = sample.counts.coerceAtLeast(0)
            )
            accumulatedSeconds += durationSeconds
        }
        renderKernelEstimatorPlot(
            bounds = estimator.timestampBounds(),
            recalculateVerticalAxis = recalculateVerticalAxis
        ) { t2s ->
            estimator.getConfidenceIntervals(
                t2s = t2s,
                scale = scaleSeconds.coerceAtLeast(1e-3)
            )
        }
    }

    private fun addSampleToEstimator(
        estimator: KernelDensityEstimator,
        intervalStartSeconds: Double,
        durationSeconds: Double,
        counts: Int
    ) {
        if (counts <= 0) return

        val intervalEndSeconds = intervalStartSeconds + durationSeconds
        val groupCount = kotlin.math.ceil(counts / KDE_MAX_COUNTS_PER_POINT.toDouble()).toInt().coerceAtLeast(1)
        val baseGroupSize = counts / groupCount
        val groupsWithExtraCount = counts % groupCount

        for (groupIndex in 0 until groupCount) {
            val groupSize = baseGroupSize + if (groupIndex < groupsWithExtraCount) 1 else 0
            val timestamp = if (durationSeconds <= 0.0) {
                intervalStartSeconds
            } else {
                intervalStartSeconds + ((groupIndex + 0.5) / groupCount) * (intervalEndSeconds - intervalStartSeconds)
            }
            estimator.addPoint(timestamp, groupSize)
        }
    }

    private fun updateModeUi(toggleItem: MenuItem?) {
        toggleItem ?: return
        val (iconRes, titleRes) = when (plotMode) {
            PlotMode.SLIDING_WINDOW -> {
                R.drawable.baseline_planner_review_24 to R.string.time_plot_switch_to_kernel_estimator
            }
            PlotMode.KERNEL_ESTIMATOR -> {
                R.drawable.baseline_bar_chart_24 to R.string.time_plot_switch_to_sliding_window
            }
        }
        toggleItem.setIcon(iconRes)
        toggleItem.title = getString(titleRes)
    }

    companion object {
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val CURRENT_TRACK_TITLE = "Currently recording"
        private const val SECONDS_PER_MINUTE = 60f
        private const val GENERALIZATION_SLIDER_INTERNAL_MAX = 1f
        private const val EXP_SCALE_FACTOR = 0.523957
        private const val KDE_PLOT_SAMPLE_COUNT = 240
        private const val KDE_MAX_COUNTS_PER_POINT = 10

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
