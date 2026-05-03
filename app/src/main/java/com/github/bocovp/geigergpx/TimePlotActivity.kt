package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class TimePlotActivity : AppCompatActivity() {
    private enum class PlotMode { SLIDING_WINDOW, KERNEL_ESTIMATOR }

    private data class PlotCandidate(
        val id: String,
        val title: String
    )

    /**
     * Captures the full state required for a render pass.
     */
    private data class RenderRequest(
        val mode: PlotMode,
        val points: List<TrackPoint>,
        val scaleSeconds: Double,
        val coeff: Double,
        val isCurrentTrack: Boolean,
        val trackTitle: String,
        val recalculateVerticalAxis: Boolean,
        val generation: Long
    )

    private data class EstimatorCache(
        val points: List<TrackPoint>,
        val estimator: KernelDensityEstimator,
        val coeff: Double,
        val ts2: DoubleArray,
        val firstTimestamp: Double
    )

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
   // private var activeTrackObserverAttached = false
 //   private var trackingObserverAttached = false

    @Volatile private var estimatorCache: EstimatorCache? = null
    @Volatile private var slidingWindowCache: SlidingWindowCache? = null

    private var isSliderBeingDragged = false

    private val failedTrackIdsForPlot = mutableSetOf<String>()
    private var selectedTrackIdForPlot: String? = null
    private var plotCandidates: List<PlotCandidate> = emptyList()
    private var refreshCandidatesJob: Job? = null
    private var plotLoadJob: Job? = null
    private var pendingHighlightedPoint: GeigerGpxApp.HighlightedTrackPoint? = null
    private var pointMidElapsedSeconds: DoubleArray = DoubleArray(0)
    private var lastAddPoiVisible: Boolean = false

    // Conflation Strategy Properties
    private val renderRequestFlow = MutableStateFlow<RenderRequest?>(null)
    private var renderCollectorJob: Job? = null
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackCatalog.allTracks.collectLatest {
                    refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
                }

                viewModel.activeTrackPoints.collectLatest { points ->
                    if (selectedTrackIdForPlot != TrackCatalog.currentTrackId()) return@collectLatest
                    updateCurrentPoints(points)
                    updatePlot(recalculateVerticalAxis = true)
                }

                viewModel.isTracking.collectLatest {
                    refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
                }
            }
        }
        setupRenderCollector() // Initialize the confluent collector
        setupPointSelectionLinking()

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

        invalidateOptionsMenu()
    }

    /**
     * Sets up the conflated rendering loop.
     *
     * Using [renderRequestFlow].collect ensures that once a render starts, it finishes.
     * If multiple slider moves occur while the CPU is busy, only the LATEST one is
     * processed next, preventing UI freeze and "starvation".
     */
    private fun setupRenderCollector() {
        renderCollectorJob?.cancel()
        renderCollectorJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                renderRequestFlow.collect { request ->
                    request ?: return@collect

                    // Only show loading if the view is hidden (initial load)
                    val shouldShowLoading = binding.timePlotView.visibility != View.VISIBLE
                    if (shouldShowLoading) showLoading(true)

                    val result = withContext(Dispatchers.Default) {
                        if (request.mode == PlotMode.KERNEL_ESTIMATOR) {
                            calculateKdePlot(request.isCurrentTrack, request.points, request.scaleSeconds, request.coeff)
                        } else {
                            calculateSlidingWindowPlot(request.points, request.scaleSeconds, request.coeff, request.trackTitle)
                        }
                    }

                    // Apply the result. We don't check generations here because "laggy" 
                    // updates are better than no updates during rapid movement.
                    applyPlotResult(result, request.coeff, request.recalculateVerticalAxis)
                }
            }
        }
    }

    private fun applyPlotResult(result: PlotResult?, coeff: Double, recalculateVerticalAxis: Boolean) {
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
        val isVisible = shouldShowAddPoiAction()
        menu.findItem(R.id.action_add_poi)?.isVisible = isVisible
        lastAddPoiVisible = isVisible
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
            R.id.action_add_poi -> {
                saveSelectedPointAsPoi()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveSelectedPointAsPoi() {
        val selected = appState.highlightedTrackPoint.value ?: return
        val defaultName = "${selected.trackTitle ?: "Track"} #${selected.pointIndex}"
        val input = EditText(this).apply {
            setText(defaultName)
            setSelection(text.length)
            hint = "POI"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add POI")
            .setMessage("Define POI name:")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val description = input.text?.toString().orEmpty()
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        PoiLibrary.addPoi(
                            context = applicationContext,
                            description = description,
                            timestampMillis = selected.point.timeMillis,
                            latitude = selected.point.latitude,
                            longitude = selected.point.longitude,
                            doseRate = selected.point.doseRate,
                            counts = selected.point.counts,
                            seconds = selected.point.seconds
                        )
                    }
                    android.widget.Toast.makeText(this@TimePlotActivity, if (success) "POI saved" else "Unable to save POI", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun setupPointSelectionLinking() {
        binding.timePlotView.onPointSelectionChanged = { selectedSeconds ->
            val selected = nearestPointForElapsedSeconds(selectedSeconds)
            if (selected == null) {
                appState.setHighlightedTrackPoint(null)
            } else {
                val trackId = selectedTrackIdForPlot ?: TrackCatalog.currentTrackId()
                appState.setHighlightedTrackPoint(
                    GeigerGpxApp.HighlightedTrackPoint(
                        trackId = trackId,
                        trackTitle = binding.trackNameField.text?.toString(),
                        pointIndex = selected.first + 1,
                        point = selected.second
                    )
                )
            }
            invalidateAddPoiMenuIfNeeded()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appState.highlightedTrackPoint
                    .collectLatest { highlighted ->
                        val selected = highlighted ?: run {
                            binding.timePlotView.setSelectedTimeSeconds(null)
                            invalidateOptionsMenu()
                            return@collectLatest
                        }
                        val currentId = selectedTrackIdForPlot ?: TrackCatalog.currentTrackId()
                        val targetId = selected.trackId ?: TrackCatalog.currentTrackId()
                        if (targetId != currentId) {
                            pendingHighlightedPoint = selected
                            loadTrackForPlotAsync(targetId)
                        } else if (targetId == currentId) {
                            if (currentPoints.isEmpty()) {
                                pendingHighlightedPoint = selected
                            } else {
                                val elapsed = elapsedSecondsAtPoint(selected.point)
                                binding.timePlotView.setSelectedTimeSeconds(elapsed)
                            }
                        }
                        invalidateAddPoiMenuIfNeeded()
                    }
            }
        }
    }

    private fun nearestPointForElapsedSeconds(seconds: Double?): Pair<Int, TrackPoint>? {
        if (seconds == null || currentPoints.isEmpty()) return null
        val nearestIndex = nearestIndexForElapsedSeconds(seconds)
        return if (nearestIndex >= 0) nearestIndex to currentPoints[nearestIndex] else null
    }

    private fun elapsedSecondsAtPoint(target: TrackPoint): Double {
        if (currentPoints.isEmpty()) return 0.0
        val idx = nearestIndexForTimeMillis(target.timeMillis)
        return pointMidElapsedSeconds.getOrElse(idx) { 0.0 }
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
                updatePlot(recalculateVerticalAxis = true)
            }
        })
    }

    private fun updateCurrentPoints(points: List<TrackPoint>) {
        currentPoints = points
        estimatorCache = null
        slidingWindowCache = null
        rebuildPointIndex()

        val pending = pendingHighlightedPoint
        if (pending != null && points.isNotEmpty()) {
            val currentId = selectedTrackIdForPlot ?: TrackCatalog.currentTrackId()
            val targetId = pending.trackId ?: TrackCatalog.currentTrackId()
            if (targetId == currentId) {
                binding.timePlotView.setSelectedTimeSeconds(elapsedSecondsAtPoint(pending.point))
                pendingHighlightedPoint = null
            }
        }
    }

    private fun rebuildPointIndex() {
        if (currentPoints.isEmpty()) {
            pointMidElapsedSeconds = DoubleArray(0)
            return
        }
        var elapsed = 0.0
        pointMidElapsedSeconds = DoubleArray(currentPoints.size)
        currentPoints.forEachIndexed { index, point ->
            val duration = point.seconds.coerceAtLeast(0.001)
            pointMidElapsedSeconds[index] = elapsed + duration / 2.0
            elapsed += duration
        }
    }

    private fun nearestIndexForElapsedSeconds(seconds: Double): Int {
        val arr = pointMidElapsedSeconds
        if (arr.isEmpty()) return -1
        var lo = 0
        var hi = arr.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < seconds) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return 0
        val prev = lo - 1
        return if (kotlin.math.abs(arr[lo] - seconds) < kotlin.math.abs(arr[prev] - seconds)) lo else prev
    }

    private fun nearestIndexForTimeMillis(targetMillis: Long): Int {
        if (currentPoints.isEmpty()) return -1
        var lo = 0
        var hi = currentPoints.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (currentPoints[mid].timeMillis < targetMillis) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return 0
        val prev = lo - 1
        val a = kotlin.math.abs(currentPoints[lo].timeMillis - targetMillis)
        val b = kotlin.math.abs(currentPoints[prev].timeMillis - targetMillis)
        return if (a < b) lo else prev
    }

    private fun invalidateAddPoiMenuIfNeeded() {
        val isVisible = shouldShowAddPoiAction()
        if (isVisible != lastAddPoiVisible) {
            invalidateOptionsMenu()
            lastAddPoiVisible = isVisible
        }
    }

    private fun shouldShowAddPoiAction(): Boolean {
        val highlighted = appState.highlightedTrackPoint.value ?: return false
        return (selectedTrackIdForPlot ?: TrackCatalog.currentTrackId()) ==
            (highlighted.trackId ?: TrackCatalog.currentTrackId())
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
                    renderCollectorJob?.cancel() // Hard stop on track change
                    renderRequestFlow.value = null
                    selectedTrackIdForPlot = null
                    updateCurrentPoints(emptyList())
                    updateTrackTitle(null)
                    binding.timePlotView.setPoints(emptyList(), cpsToUSvhCoeff, recalculateVerticalAxis = true)
                    showPlotMessage(R.string.time_plot_no_track_data)
                    setupRenderCollector() // Restart the loop
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
                result.onFailure { if (it is CancellationException) throw it }

                if (selectedTrackIdForPlot != normalizedTrackId) return@launch

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
                    if (shouldRefreshTrackCandidates) refreshTrackCandidatesAndPlotAsync()
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (isLoading) showPlotMessage(R.string.time_plot_loading) else showPlotMessage(null)
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
  //          observeActiveTrack()
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
        if (candidates.isEmpty()) return null
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
            .takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta).mod(plotCandidates.size)
        val nextTrackId = plotCandidates[nextIndex].id
        failedTrackIdsForPlot.remove(nextTrackId)
        rememberTrackSelection(this, nextTrackId)
        loadTrackForPlotAsync(nextTrackId)
    }

    private fun updateSliderDescription(valueMinutes: Float) {
        binding.averagingLabel.text = "Averaging window: ${"%.1f".format(Locale.US, valueMinutes)} min"
    }

    /**
     * Now simply captures the current state and emits it to the rendering flow.
     */
    private fun updatePlot(recalculateVerticalAxis: Boolean = true) {
        val minDurationMinutes = KdeScaleSlider.internalToMinutes(binding.placeholderSlider.value)
        val scaleSeconds = minDurationMinutes.toDouble() * SECONDS_PER_MINUTE
        val generation = ++renderGeneration
        val isCurrentTrack = selectedTrackIdForPlot.isNullOrBlank() ||
                selectedTrackIdForPlot == TrackCatalog.currentTrackId()

        renderRequestFlow.value = RenderRequest(
            mode = plotMode,
            points = currentPoints,
            scaleSeconds = scaleSeconds,
            coeff = cpsToUSvhCoeff,
            isCurrentTrack = isCurrentTrack,
            trackTitle = binding.trackNameField.text?.toString().orEmpty(),
            recalculateVerticalAxis = recalculateVerticalAxis,
            generation = generation
        )
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
            yield() // Check for cancellation before calling service
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
                yield()
                totalDuration += p.seconds.coerceAtLeast(0.0)
                if (p.counts > 0) hasData = true
            }
            if (!hasData || totalDuration <= 0.0) return null

            firstTimestamp = 0.0
            ts2 = buildTs2(firstTimestamp, totalDuration)

            estimator = KernelDensityEstimator(coeff)
            var accumulatedSeconds = 0.0
            for (p in points) {
                yield()
                val durationSeconds = p.seconds.coerceAtLeast(0.0)
                estimator.addSampleInterval(accumulatedSeconds, durationSeconds, p.counts.coerceAtLeast(0))
                accumulatedSeconds += durationSeconds
            }
            estimatorCache = EstimatorCache(points, estimator, coeff, ts2, firstTimestamp)
        }

        yield()
        val ci = estimator.getConfidenceIntervals(ts2, minScale) ?: return null
        return PlotResult.Kde(ts2, firstTimestamp, ci.first, ci.second, ci.third)
    }

    private fun buildTs2(firstTimestamp: Double, lastTimestamp: Double): DoubleArray {
        return if (kotlin.math.abs(lastTimestamp - firstTimestamp) < 1e-9) {
            doubleArrayOf(firstTimestamp)
        } else {
            val step = (lastTimestamp - firstTimestamp) / (KDE_PLOT_SAMPLE_COUNT - 1).toDouble()
            DoubleArray(KDE_PLOT_SAMPLE_COUNT) { idx -> firstTimestamp + idx * step }
        }
    }

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

        yield()
        val track = MapTrack(id = CURRENT_TRACK_TITLE, title = trackTitle, points = points)
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
            PlotMode.SLIDING_WINDOW -> R.drawable.baseline_planner_review_24 to R.string.time_plot_switch_to_kernel_estimator
            PlotMode.KERNEL_ESTIMATOR -> R.drawable.baseline_bar_chart_24 to R.string.time_plot_switch_to_sliding_window
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
            (context.applicationContext as? GeigerGpxApp)?.selectedTimePlotTrackId = trackId
        }
        fun rememberCurrentTrackSelection(context: android.content.Context) {
            rememberTrackSelection(context, TrackCatalog.currentTrackId())
        }
        fun preferredTrackSelection(context: android.content.Context): String? {
            return (context.applicationContext as? GeigerGpxApp)?.selectedTimePlotTrackId
        }
    }
}
