package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityTimePlotBinding
import com.google.android.material.slider.Slider
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimePlotActivity : AppCompatActivity() {
    private enum class PlotMode { SLIDING_WINDOW, KERNEL_ESTIMATOR }

    private data class PlotCandidate(
        val id: String,
        val title: String
    )

    /**
     * Cached result for the KDE mode on a saved (non-live) track.
     *
     * Building the estimator is O(N) and only needs to happen when [currentPoints] or
     * [cpsToUSvhCoeff] change. The sample timestamps [ts2] and [firstTimestamp] depend only on
     * the track's time bounds, which are also stable between slider moves. Invalidated by
     * [updateCurrentPoints].
     *
     * Written on Dispatchers.Default, nulled on Main — [estimatorCache] is @Volatile so the null
     * is immediately visible across threads.
     */
    private data class EstimatorCache(
        val points: List<TrackPoint>,
        val estimator: KernelDensityEstimator,
        val coeff: Double,
        val ts2: DoubleArray,
        val firstTimestamp: Double
    )

    /**
     * Cached result for the sliding-window mode.
     *
     * [TrackGeneralizer.generalize] is O(N) and only needs to re-run when [currentPoints],
     * [cpsToUSvhCoeff], or [scaleSeconds] change. Invalidated by [updateCurrentPoints] when
     * points or coeff change; the scale check is done inline in [calculateSlidingWindowPlot].
     *
     * Same @Volatile requirement as [estimatorCache].
     */
    private data class SlidingWindowCache(
        val inputPoints: List<TrackPoint>,
        val scaleSeconds: Double,
        val coeff: Double,
        val outputPoints: List<TrackPoint>
    )

    private lateinit var binding: ActivityTimePlotBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var cpsToUSvhCoeff: Double = 1.0
    private var currentPoints: List<TrackPoint> = emptyList()
    private var activeTrackObserverAttached = false
    private var trackingObserverAttached = false

    @Volatile private var estimatorCache: EstimatorCache? = null
    @Volatile private var slidingWindowCache: SlidingWindowCache? = null

    /**
     * True while the user's finger is on the slider. Used to suppress the extra
     * [updatePlot] that [onChangeListener] would otherwise fire for the final value
     * immediately before [onStopTrackingTouch], avoiding a redundant cancelled render.
     */
    private var isSliderBeingDragged = false

    /**
     * Tracks that failed to load in this session. Used to avoid repeatedly trying a stale/missing
     * track ID when preferences still contain it.
     */
    private val failedTrackIdsForPlot = mutableSetOf<String>()
    private var selectedTrackIdForPlot: String? = null
    private var plotCandidates: List<PlotCandidate> = emptyList()
    private var refreshCandidatesJob: Job? = null
    private var plotLoadJob: Job? = null
    private var renderJob: Job? = null
    private var renderGeneration: Long = 0L
    private val appState: GeigerGpxApp by lazy { application as GeigerGpxApp }

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

        val selectedTrackId = intent.getStringExtra(EXTRA_TRACK_ID)
        if (!selectedTrackId.isNullOrBlank()) {
            rememberTrackSelection(this, selectedTrackId)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackCatalog.allTracks.collectLatest {
                    refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
                }
            }
        }
        invalidateOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
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
        binding.placeholderSlider.valueFrom = KdeScaleSlider.minutesToInternal(KdeScaleSlider.MIN_MINUTES)
        binding.placeholderSlider.valueTo = KdeScaleSlider.INTERNAL_MAX
        binding.placeholderSlider.value = appState.sharedKdeSliderInternalValue
            .coerceIn(binding.placeholderSlider.valueFrom, binding.placeholderSlider.valueTo)
        appState.sharedKdeSliderInternalValue = binding.placeholderSlider.value
        binding.placeholderSlider.setLabelFormatter { internalValue ->
            "%.1f".format(Locale.US, KdeScaleSlider.internalToMinutes(internalValue))
        }
        updateSliderDescription(KdeScaleSlider.internalToMinutes(binding.placeholderSlider.value))

        binding.placeholderSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val durationMinutes = KdeScaleSlider.internalToMinutes(value)
            updateSliderDescription(durationMinutes)
            if (fromUser) {
                appState.sharedKdeSliderInternalValue = value
                // Only render during active drag. The final value change event fires just before
                // onStopTrackingTouch; letting onStopTrackingTouch handle that render avoids
                // launching a coroutine that is immediately cancelled.
                if (isSliderBeingDragged) {
                    updatePlot(recalculateVerticalAxis = false)
                }
            }
        }

        binding.placeholderSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isSliderBeingDragged = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isSliderBeingDragged = false
                // Recalculate vertical axis now that the user has committed to a scale.
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
            updateCurrentPoints(points)
            updatePlot(recalculateVerticalAxis = true)
        }
    }

    /**
     * Updates [currentPoints] and invalidates both render caches.
     *
     * Must always be called instead of assigning [currentPoints] directly so that stale cached
     * results are never used after a data change.
     */
    private fun updateCurrentPoints(points: List<TrackPoint>) {
        currentPoints = points
        estimatorCache = null
        slidingWindowCache = null
    }

    private fun refreshTrackCandidatesAndPlotAsync(preferredTrackId: String? = null) {
        refreshCandidatesJob?.cancel()
        refreshCandidatesJob = lifecycleScope.launch {
            try {
                showLoading(true)
                cpsToUSvhCoeff = PreferenceManager.getDefaultSharedPreferences(this@TimePlotActivity)
                    .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

                val activePoints = viewModel.activeTrackPoints.value.orEmpty()
                val isTracking = viewModel.isTracking.value == true
                val candidates = withContext(Dispatchers.IO) { loadPlotCandidates(activePoints, isTracking) }
                plotCandidates = candidates
                val resolvedTrackId = resolveSelectedTrackId(candidates, preferredTrackId)
                updateTrackSelectorUi()
                if (resolvedTrackId == null) {
                    plotLoadJob?.cancel()
                    renderJob?.cancel()
                    selectedTrackIdForPlot = null
                    updateCurrentPoints(emptyList())
                    updateTrackTitle(null)
                    binding.timePlotView.setPoints(emptyList(), cpsToUSvhCoeff, recalculateVerticalAxis = true)
                    showPlotMessage(R.string.time_plot_no_track_data)
                } else if (resolvedTrackId != selectedTrackIdForPlot || currentPoints.isEmpty()) {
                    loadTrackForPlotAsync(resolvedTrackId)
                } else {
                    showLoading(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                showPlotMessage(R.string.time_plot_no_track_data)
            }
        }
    }

    private suspend fun loadPlotCandidates(activePoints: List<TrackPoint>, isTracking: Boolean): List<PlotCandidate> {
        val includeCurrentTrack = isTracking
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
            plotLoadJob?.cancel()
            loadTrackForPlot(trackId)
            return
        }

        // Switch the "current track for plotting" immediately so the live observer can't
        // overwrite the plot while the async load is in-flight.
        selectedTrackIdForPlot = normalizedTrackId
        plotLoadJob?.cancel()
        plotLoadJob = lifecycleScope.launch {
            var shouldRefreshTrackCandidates = false
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        TrackCatalog.loadTrackSamplesById(this@TimePlotActivity, normalizedTrackId)
                    }
                }
                result.onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                }

                if (selectedTrackIdForPlot != normalizedTrackId) {
                    return@launch
                }

                result.onSuccess { selected ->
                    if (selected != null) {
                        applyLoadedTrack(normalizedTrackId, selected)
                    } else {
                        failedTrackIdsForPlot.add(normalizedTrackId)
                        shouldRefreshTrackCandidates = true
                    }
                }.onFailure {
                    failedTrackIdsForPlot.add(normalizedTrackId)
                    showPlotMessage(R.string.time_plot_no_track_data)
                }
            } finally {
                if (plotLoadJob == this.coroutineContext[Job]) {
                    if (shouldRefreshTrackCandidates) {
                        refreshTrackCandidatesAndPlotAsync()
                    }
                }
            }
        }
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
            binding.trackNameField.visibility = View.VISIBLE
        } else {
            binding.loadingLabel.setText(messageResId)
            binding.loadingLabel.visibility = View.VISIBLE
            binding.timePlotView.visibility = View.INVISIBLE
            binding.trackNameField.visibility = View.INVISIBLE
        }
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
        return false
    }

    private fun applyLoadedTrack(trackId: String, selectedTrack: TrackCatalog.TrackPlotData) {
        selectedTrackIdForPlot = trackId
        failedTrackIdsForPlot.remove(trackId)
        rememberTrackSelection(this, trackId)
        updateTrackTitle(selectedTrack.title)
        updateTrackSelectorUi()
        updateCurrentPoints(selectedTrack.points)
        updatePlot()
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

    private fun updateSliderDescription(valueMinutes: Float) {
        binding.averagingLabel.text = "Averaging window: ${"%.1f".format(Locale.US, valueMinutes)} min"
    }

    private fun updatePlot(recalculateVerticalAxis: Boolean = true) {
        val minDurationMinutes = KdeScaleSlider.internalToMinutes(binding.placeholderSlider.value)
        val minDurationSeconds = minDurationMinutes * SECONDS_PER_MINUTE
        val scaleSeconds = minDurationSeconds.toDouble()

        renderJob?.cancel()
        val generation = ++renderGeneration
        val isCurrentTrack = selectedTrackIdForPlot.isNullOrBlank() ||
                selectedTrackIdForPlot == TrackCatalog.currentTrackId()

        // Capture on Main before crossing to Dispatchers.Default.
        val mode = plotMode
        val coeff = cpsToUSvhCoeff
        val points = currentPoints
        val trackTitle = binding.trackNameField.text?.toString().orEmpty()

        renderJob = lifecycleScope.launch {
            if (points.isEmpty() && !isCurrentTrack) {
                binding.timePlotView.setPoints(emptyList(), coeff, recalculateVerticalAxis)
                showPlotMessage(R.string.time_plot_no_track_data)
                return@launch
            }

            // Show loading only if the view is currently hidden (first load).
            val shouldShowLoading = binding.timePlotView.visibility != View.VISIBLE
            if (shouldShowLoading) showLoading(true)

            val result = withContext(Dispatchers.Default) {
                if (mode == PlotMode.KERNEL_ESTIMATOR) {
                    calculateKdePlot(isCurrentTrack, points, scaleSeconds, coeff)
                } else {
                    calculateSlidingWindowPlot(points, scaleSeconds, coeff, trackTitle)
                }
            }

            if (generation != renderGeneration || !isActive) return@launch

            when (result) {
                is PlotResult.Kde -> {
                    val relativeSeconds = DoubleArray(result.ts2.size) { idx ->
                        result.ts2[idx] - result.firstTimestamp
                    }
                    binding.timePlotView.setKernelSeries(
                        relativeSeconds = relativeSeconds,
                        mean = result.mean,
                        low = result.low,
                        high = result.high,
                        cpsToUSvh = coeff,
                        recalculateVerticalAxis = recalculateVerticalAxis
                    )
                    showPlotMessage(if (relativeSeconds.isEmpty()) R.string.time_plot_no_track_data else null)
                }
                is PlotResult.SlidingWindow -> {
                    binding.timePlotView.setPoints(
                        points = result.points,
                        cpsToUSvh = coeff,
                        recalculateVerticalAxis = recalculateVerticalAxis
                    )
                    showPlotMessage(if (result.points.isEmpty()) R.string.time_plot_no_track_data else null)
                }
                null -> {
                    binding.timePlotView.setPoints(emptyList(), coeff, recalculateVerticalAxis)
                    showPlotMessage(R.string.time_plot_no_track_data)
                }
            }
        }
    }

    private sealed class PlotResult {
        data class Kde(
            val ts2: DoubleArray,
            val firstTimestamp: Double,
            val mean: DoubleArray,
            val low: DoubleArray,
            val high: DoubleArray
        ) : PlotResult()
        data class SlidingWindow(val points: List<TrackPoint>) : PlotResult()
    }

    /**
     * Calculates the KDE plot result, reusing [estimatorCache] when possible.
     *
     * For a saved (non-live) track, a cache hit skips both the O(N) estimator build and the
     * O(K) ts2 array construction, leaving only [KernelDensityEstimator.getConfidenceIntervals]
     * to run — the cheap, scale-dependent part that must re-execute on every slider move.
     *
     * Runs on Dispatchers.Default.
     */
    private suspend fun calculateKdePlot(
        isCurrentTrack: Boolean,
        points: List<TrackPoint>,
        scaleSeconds: Double,
        coeff: Double
    ): PlotResult.Kde? {
        val minScale = scaleSeconds.coerceAtLeast(KdeScaleSlider.MIN_SECONDS.toDouble())

        if (isCurrentTrack) {
            val liveBounds = TrackingService.activeKdeTimestampBounds() ?: return null
            if (liveBounds.second < liveBounds.first) return null
            val ts2 = buildTs2(liveBounds.first, liveBounds.second)
            val ci = TrackingService.activeKdeConfidenceIntervals(ts2, minScale) ?: return null
            return PlotResult.Kde(ts2, liveBounds.first, ci.first, ci.second, ci.third)
        }

        val cache = estimatorCache
        val estimator: KernelDensityEstimator
        val ts2: DoubleArray
        val firstTimestamp: Double

        if (cache != null && cache.points === points && cache.coeff == coeff) {
            estimator = cache.estimator
            ts2 = cache.ts2
            firstTimestamp = cache.firstTimestamp
        } else {
            var totalDuration = 0.0
            var hasData = false
            for (p in points) {
                kotlinx.coroutines.yield()
                totalDuration += p.seconds.coerceAtLeast(0.0)
                if (p.counts > 0) hasData = true
            }
            if (!hasData || totalDuration <= 0.0) return null

            firstTimestamp = 0.0
            ts2 = buildTs2(firstTimestamp, totalDuration)

            estimator = KernelDensityEstimator(coeff)
            var accumulatedSeconds = 0.0
            for (p in points) {
                kotlinx.coroutines.yield()
                val durationSeconds = p.seconds.coerceAtLeast(0.0)
                estimator.addSampleInterval(accumulatedSeconds, durationSeconds, p.counts.coerceAtLeast(0))
                accumulatedSeconds += durationSeconds
            }
            estimatorCache = EstimatorCache(points, estimator, coeff, ts2, firstTimestamp)
        }

        val ci = estimator.getConfidenceIntervals(ts2, minScale) ?: return null
        return PlotResult.Kde(ts2, firstTimestamp, ci.first, ci.second, ci.third)
    }

    /**
     * Builds the array of [KDE_PLOT_SAMPLE_COUNT] evenly-spaced sample timestamps.
     *
     * Handles the single-point edge case (timestamps within 1e-9 s of each other) by returning
     * a single-element array, avoiding a near-zero step that would produce 240 identical samples.
     */
    private fun buildTs2(firstTimestamp: Double, lastTimestamp: Double): DoubleArray {
        return if (kotlin.math.abs(lastTimestamp - firstTimestamp) < 1e-9) {
            doubleArrayOf(firstTimestamp)
        } else {
            val step = (lastTimestamp - firstTimestamp) / (KDE_PLOT_SAMPLE_COUNT - 1).toDouble()
            DoubleArray(KDE_PLOT_SAMPLE_COUNT) { idx -> firstTimestamp + idx * step }
        }
    }

    /**
     * Calculates the sliding-window plot result, reusing [slidingWindowCache] when possible.
     *
     * [TrackGeneralizer.generalize] is O(N) and only needs to re-run when [scaleSeconds] or
     * [coeff] change (points changes are handled by [updateCurrentPoints] nulling the cache).
     * A cache hit on both values means no computation is done on the background thread at all.
     *
     * Runs on Dispatchers.Default.
     */
    private suspend fun calculateSlidingWindowPlot(
        points: List<TrackPoint>,
        scaleSeconds: Double,
        coeff: Double,
        trackTitle: String
    ): PlotResult.SlidingWindow {
        val cache = slidingWindowCache
        if (cache != null && cache.inputPoints === points && cache.scaleSeconds == scaleSeconds && cache.coeff == coeff) {
            return PlotResult.SlidingWindow(cache.outputPoints)
        }

        val track = MapTrack(
            id = CURRENT_TRACK_TITLE,
            title = trackTitle,
            points = points
        )
        val generalized = TrackGeneralizer(
            minDistanceMeters = 0.0,
            coeff = coeff,
            minDurationSeconds = scaleSeconds
        ).generalize(track)

        slidingWindowCache = SlidingWindowCache(points, scaleSeconds, coeff, generalized.points)
        return PlotResult.SlidingWindow(generalized.points)
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
        private const val KDE_PLOT_SAMPLE_COUNT = 240

        private var plotMode: PlotMode = PlotMode.SLIDING_WINDOW

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
