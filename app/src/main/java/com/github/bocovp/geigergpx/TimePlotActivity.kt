package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
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
import kotlinx.coroutines.delay
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
        val sensitivity: Double,
        val isCurrentTrack: Boolean,
        val visibleRange: TimePlotView.VisibleRange,
        val trackTitle: String,
        val recalculateVerticalAxis: Boolean,
        val keepEndVisible: Boolean,
        val generation: Long
    )

    private data class EstimatorCache(
        val points: List<TrackPoint>,
        val estimator: KernelDensityEstimator,
        val sensitivity: Double
    )

    private data class SlidingWindowCache(
        val inputPoints: List<TrackPoint>,
        val scaleSeconds: Double,
        val sensitivity: Double,
        val outputPoints: List<TrackPoint>
    )

    private lateinit var binding: ActivityTimePlotBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var sensitivity: Double = RadiationCalibration.DEFAULT_SENSITIVITY
    private var currentPoints: List<TrackPoint> = emptyList()
   // private var activeTrackObserverAttached = false
 //   private var trackingObserverAttached = false

    @Volatile private var estimatorCache: EstimatorCache? = null
    @Volatile private var slidingWindowCache: SlidingWindowCache? = null

    private var isSliderBeingDragged = false
    private var userSelectedTimeSeconds: Double? = null

    private val failedTrackIdsForPlot = mutableSetOf<String>()
    private var selectedTrackIdForPlot: String? = null
    private var currentPointsTrackIdForPlot: String? = null
    private var plotCandidates: List<PlotCandidate> = emptyList()
    private var refreshCandidatesJob: Job? = null
    private var plotLoadJob: Job? = null
    private var pointMidElapsedSeconds: DoubleArray = DoubleArray(0)
    private var lastAddPoiVisible: Boolean = false
    private var shouldApplyInitialLiveWindow = false
    private var keepScreenOnEnabled: Boolean = false

    // Conflation Strategy Properties
    private val renderRequestFlow = MutableStateFlow<RenderRequest?>(null)
    private var renderCollectorJob: Job? = null
    private var liveKdeRefreshJob: Job? = null
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

        // Listen for view panning/zooming to fetch new KDE fragments
        binding.timePlotView.onVisibleRangeChanged = {
            if (plotMode == PlotMode.KERNEL_ESTIMATOR) {
                updatePlot(recalculateVerticalAxis = false)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {

                    TrackCatalog.allTracks.collectLatest {
                        refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
                    }
                }

                launch {
                    viewModel.activeTrackPoints.collectLatest { points ->
                        if (selectedTrackIdForPlot != TrackCatalog.currentTrackId()) return@collectLatest
                        updateCurrentPoints(points, TrackCatalog.currentTrackId())
                        if (plotMode == PlotMode.SLIDING_WINDOW) {
                            updatePlot(recalculateVerticalAxis = true)
                        }                    }
                }

                launch {
                    viewModel.isTracking.collectLatest {
                        refreshTrackCandidatesAndPlotAsync(selectedTrackIdForPlot)
                    }
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
                    // val shouldShowLoading = binding.timePlotView.visibility != View.VISIBLE
                    // if (shouldShowLoading) showLoading(true)

                    val result = withContext(Dispatchers.Default) {
                        if (request.mode == PlotMode.KERNEL_ESTIMATOR) {
                            calculateKdePlot(
                                isCurrentTrack = request.isCurrentTrack,
                                points = request.points,
                                scaleSeconds = request.scaleSeconds,
                                sensitivity = request.sensitivity,
                                visibleRange = request.visibleRange,
                                keepEndVisible = request.keepEndVisible
                            )
                        } else {
                            calculateSlidingWindowPlot(request.points, request.scaleSeconds, request.sensitivity, request.trackTitle)
                        }
                    }

                    // Apply the result. We don't check generations here because "laggy" 
                    // updates are better than no updates during rapid movement.
                    applyPlotResult(result, request)
                }
            }
        }
    }

    private fun applyPlotResult(result: PlotResult?, request: RenderRequest) {
        val isCurrentTrack = request.isCurrentTrack
        val plotMode = request.mode
        val sensitivity = request.sensitivity
        val recalculateVerticalAxis = request.recalculateVerticalAxis
        binding.timePlotView.setShowLiveMarker(isCurrentTrack && plotMode == PlotMode.KERNEL_ESTIMATOR)
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
                    sensitivity = sensitivity,
                    totalTrackDurationSeconds = result.totalTrackDurationSeconds,
                    recalculateVerticalAxis = recalculateVerticalAxis,
                    isLiveUpdate = isCurrentTrack
                )
                if (shouldApplyInitialLiveWindow && result.totalTrackDurationSeconds > 0.0) {
                    binding.timePlotView.setInitialWindowSeconds(INITIAL_LIVE_WINDOW_SECONDS)
                    shouldApplyInitialLiveWindow = false
                } else if (result.keepEndVisible) {
                    binding.timePlotView.scrollToEnd()
                }
                showPlotMessage(if (relativeSeconds.isEmpty()) R.string.time_plot_no_track_data else null)
            }
            is PlotResult.SlidingWindow -> {
                val wasViewingEnd = binding.timePlotView.isViewingEnd()
                binding.timePlotView.setPoints(
                    points = result.points,
                    sensitivity = sensitivity,
                    recalculateVerticalAxis = recalculateVerticalAxis,
                    isLiveUpdate = isCurrentTrack
                )
                if (shouldApplyInitialLiveWindow && result.points.isNotEmpty()) {
                    binding.timePlotView.setInitialWindowSeconds(INITIAL_LIVE_WINDOW_SECONDS)
                    shouldApplyInitialLiveWindow = false
                } else if (isCurrentTrack && wasViewingEnd) {
                    binding.timePlotView.scrollToEnd()
                }
                showPlotMessage(if (result.points.isEmpty()) R.string.time_plot_no_track_data else null)
            }
            null -> {
                binding.timePlotView.setPoints(emptyList(), sensitivity, recalculateVerticalAxis, isLiveUpdate = isCurrentTrack)
                showPlotMessage(R.string.time_plot_no_track_data)
            }
        }

        // Restore the point selection exactly when the new plot data is applied
        syncPointSelection()
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        applyKeepScreenOnFlag()
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.time_plot_toolbar_menu, menu)
        refreshKeepScreenOnMenuItem(menu.findItem(R.id.action_keep_screen_on))
        updateModeUi(menu.findItem(R.id.action_toggle_plot_mode))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        refreshKeepScreenOnMenuItem(menu.findItem(R.id.action_keep_screen_on))
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
            R.id.action_keep_screen_on -> {
                keepScreenOnEnabled = !keepScreenOnEnabled
                applyKeepScreenOnFlag()
                refreshKeepScreenOnMenuItem(item)
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

    private fun syncPointSelection() {
        val highlighted = appState.highlightedTrackPoint.value
        if (highlighted == null) {
            binding.timePlotView.setSelectedTimeSeconds(null)
            invalidateOptionsMenu()
            return
        }

        val currentId = selectedTrackIdForPlot ?: TrackCatalog.currentTrackId()
        val targetId = highlighted.trackId ?: TrackCatalog.currentTrackId()

        // Only show the point if the rendered plot matches the highlighted point's track
        if (targetId == currentId && currentPointsTrackIdForPlot == currentId && currentPoints.isNotEmpty()) {
            val pointElapsed = elapsedSecondsForHighlight(highlighted)

            // Prevent aggressive snapping to the raw track point by preserving the exact continuous
            // time the user touched, provided it still maps to the currently highlighted point index.
            val userTime = userSelectedTimeSeconds
            val timeToDisplay = if (userTime != null && nearestIndexForElapsedSeconds(userTime) == (highlighted.pointIndex - 1)) {
                userTime
            } else {
                pointElapsed
            }

            binding.timePlotView.setSelectedTimeSeconds(timeToDisplay)
        } else {
            binding.timePlotView.setSelectedTimeSeconds(null)
        }
        invalidateAddPoiMenuIfNeeded()
    }
    private fun setupPointSelectionLinking() {
        binding.timePlotView.onPointSelectionChanged = { selectedSeconds ->
            userSelectedTimeSeconds = selectedSeconds

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
                        if (highlighted != null) {
                            val targetId = highlighted.trackId ?: TrackCatalog.currentTrackId()
                            val currentId = selectedTrackIdForPlot ?: TrackCatalog.currentTrackId()

                            // If the highlighted point belongs to a different track, switch to it
                            if (targetId != currentId) {
                                refreshTrackCandidatesAndPlotAsync(targetId)
                            }
                        }
                        syncPointSelection()
                    }
            }
        }
    }

    private fun refreshKeepScreenOnMenuItem(item: MenuItem?) {
        item ?: return
        val title = if (keepScreenOnEnabled) "Screen stay awake: ON" else "Screen stay awake: OFF"
        val iconRes = if (keepScreenOnEnabled) R.drawable.baseline_lock_24 else R.drawable.baseline_lock_open_24
        item.title = title
        item.setIcon(iconRes)
    }

    private fun applyKeepScreenOnFlag() {
        if (keepScreenOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun elapsedSecondsForHighlight(highlighted: GeigerGpxApp.HighlightedTrackPoint): Double {
        val idxFromSelection = (highlighted.pointIndex - 1).takeIf { it in currentPoints.indices }
        if (idxFromSelection != null) return pointMidElapsedSeconds[idxFromSelection]
        return elapsedSecondsAtPoint(highlighted.point)
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

    private fun updateCurrentPoints(points: List<TrackPoint>, trackId: String?) {
        val trackChanged = currentPointsTrackIdForPlot != trackId

        currentPoints = points
        currentPointsTrackIdForPlot = trackId
        estimatorCache = null
        slidingWindowCache = null
        rebuildPointIndex()

        // Only clear the view's point selection if we loaded a completely different track
        if (trackChanged) {
            binding.timePlotView.setSelectedTimeSeconds(null)
            binding.timePlotView.resetView()
            shouldApplyInitialLiveWindow = trackId == TrackCatalog.currentTrackId()
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
                sensitivity = RadiationCalibration.sensitivityFromPrefs(PreferenceManager.getDefaultSharedPreferences(this@TimePlotActivity))

                val activePoints = viewModel.activeTrackPoints.value.orEmpty()
                val isTracking = viewModel.isTracking.value == true
                val candidates = withContext(Dispatchers.IO) { loadPlotCandidates(activePoints, isTracking) }
                plotCandidates = candidates
                val resolvedTrackId = resolveSelectedTrackId(candidates, preferredTrackId)
                updateTrackSelectorUi()
                if (resolvedTrackId == null) {
                    plotLoadJob?.cancel()
                    renderCollectorJob?.cancel() // Hard stop on track change
                    liveKdeRefreshJob?.cancel()
                    liveKdeRefreshJob = null
                    renderRequestFlow.value = null
                    selectedTrackIdForPlot = null
                    updateCurrentPoints(emptyList(), null)
                    updateTrackTitle(null)
                    binding.timePlotView.setPoints(emptyList(), sensitivity, recalculateVerticalAxis = true)
                    showPlotMessage(R.string.time_plot_no_track_data)
                    setupRenderCollector() // Restart the loop
                } else if (resolvedTrackId != selectedTrackIdForPlot || currentPoints.isEmpty()) {
                    if (resolvedTrackId != TrackCatalog.currentTrackId()) showLoading(true)
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
            binding.loadingLabel.visibility = View.INVISIBLE
            binding.timePlotView.visibility = View.VISIBLE
            binding.trackNameField.visibility = View.VISIBLE
        } else {
            binding.loadingLabel.setText(messageResId)
            binding.loadingLabel.visibility = View.VISIBLE
            binding.timePlotView.visibility = View.INVISIBLE
            binding.trackNameField.visibility = if (binding.trackNameField.text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun loadTrackForPlot(trackId: String?): Boolean {
        selectedTrackIdForPlot = trackId
        val normalizedTrackId = trackId?.takeIf { it.isNotBlank() }
        if (normalizedTrackId == null || normalizedTrackId == TrackCatalog.currentTrackId()) {
            updateTrackTitle(CURRENT_TRACK_TITLE)
            rememberCurrentTrackSelection(this)
            updateTrackSelectorUi()
            // For the currently recorded track, initialize from the points already buffered
            // in TrackWriter (the points that will be saved in the GPX file).
            // This ensures the sliding-window plot can be rendered immediately even before
            // the next activeTrackPoints emission arrives.
            val pointsForPlot = viewModel.activeTrackPoints.value.ifEmpty {
                TrackingService.activeTrackPointsSnapshot()
            }
            updateCurrentPoints(pointsForPlot, TrackCatalog.currentTrackId())
            updatePlot(recalculateVerticalAxis = true)
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
        updateCurrentPoints(selectedTrack.points, trackId)
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

// In TimePlotActivity.kt

    private fun resolveSelectedTrackId(candidates: List<PlotCandidate>, preferredTrackId: String?): String? {
        if (candidates.isEmpty()) return null

        // Determine the track ID of the currently highlighted point, if any.
        val highlightedId = appState.highlightedTrackPoint.value?.let {
            it.trackId ?: TrackCatalog.currentTrackId()
        }

        val preferredIds = listOfNotNull(
            preferredTrackId?.takeIf { it.isNotBlank() },
            highlightedId, // Prioritize the track of the highlighted point
            preferredTrackSelection(this),
            selectedTrackIdForPlot
        )

        val matchingCandidate = preferredIds.firstNotNullOfOrNull { candidateId ->
            if (failedTrackIdsForPlot.contains(candidateId)) return@firstNotNullOfOrNull null
            candidates.firstOrNull { it.id == candidateId }?.id
        }
        return matchingCandidate ?: candidates.firstOrNull { it.id !in failedTrackIdsForPlot }?.id
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

    private fun startOrStopLiveKdeRefresh() {
        val isCurrentTrack = selectedTrackIdForPlot.isNullOrBlank() || selectedTrackIdForPlot == TrackCatalog.currentTrackId()
        val shouldRun = isCurrentTrack && plotMode == PlotMode.KERNEL_ESTIMATOR && viewModel.isTracking.value == true
        if (!shouldRun) {
            liveKdeRefreshJob?.cancel()
            liveKdeRefreshJob = null
            return
        }
        if (liveKdeRefreshJob?.isActive == true) return
        liveKdeRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(LIVE_KDE_UPDATE_INTERVAL_MS)
                    updatePlot(recalculateVerticalAxis = false)
                }
            }
        }
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
        val visibleRange = binding.timePlotView.visibleRangeSeconds()

        val shouldKeepEndVisible = isCurrentTrack && plotMode == PlotMode.KERNEL_ESTIMATOR && binding.timePlotView.isViewingEnd()
        renderRequestFlow.value = RenderRequest(
            mode = plotMode,
            points = currentPoints,
            scaleSeconds = scaleSeconds,
            sensitivity = sensitivity,
            isCurrentTrack = isCurrentTrack,
            visibleRange = visibleRange,
            trackTitle = binding.trackNameField.text?.toString().orEmpty(),
            recalculateVerticalAxis = recalculateVerticalAxis,
            keepEndVisible = shouldKeepEndVisible,
            generation = generation
        )
        startOrStopLiveKdeRefresh()
    }

    private sealed class PlotResult {
        data class Kde(
            val ts2: DoubleArray,
            val firstTimestamp: Double,
            val mean: DoubleArray,
            val low: DoubleArray,
            val high: DoubleArray,
            val totalTrackDurationSeconds: Double,
            val keepEndVisible: Boolean
        ) : PlotResult()
        data class SlidingWindow(val points: List<TrackPoint>) : PlotResult()
    }

    private suspend fun calculateKdePlot(
        isCurrentTrack: Boolean,
        points: List<TrackPoint>,
        scaleSeconds: Double,
        sensitivity: Double,
        visibleRange: TimePlotView.VisibleRange,
        keepEndVisible: Boolean
    ): PlotResult.Kde? {
        val minScale = scaleSeconds.coerceAtLeast(KdeScaleSlider.MIN_SECONDS.toDouble())

        if (isCurrentTrack) {
            val liveBounds = TrackingService.activeKdeTimestampBounds() ?: return null
            if (liveBounds.second < liveBounds.first) return null
            yield() // Check for cancellation before calling service
            val nowSeconds = kotlin.math.max(liveBounds.second, System.currentTimeMillis() / 1000.0)
//            val rangeStart = (liveBounds.first + visibleRange.startSeconds).coerceIn(liveBounds.first, nowSeconds)
//            val rangeEnd = (liveBounds.first + visibleRange.endSeconds).coerceIn(rangeStart, nowSeconds)

            val (rangeStart, rangeEnd) = if (visibleRange.durationSeconds <= 0.0) {
                liveBounds.first to nowSeconds
            } else if (keepEndVisible) {
                val start = (nowSeconds - visibleRange.durationSeconds).coerceAtLeast(liveBounds.first)
                start to nowSeconds
            } else {
                val s = (liveBounds.first + visibleRange.startSeconds).coerceIn(liveBounds.first, nowSeconds)
                val e = (liveBounds.first + visibleRange.endSeconds).coerceIn(s, nowSeconds)
                s to e
            }

            val ts2 = buildTs2(rangeStart, rangeEnd)
            val ci = TrackingService.activeKdeConfidenceIntervals(ts2, minScale, tEndOverride = nowSeconds) ?: return null
            val totalDuration = nowSeconds - liveBounds.first
            return PlotResult.Kde(ts2, liveBounds.first, ci.first, ci.second, ci.third, totalDuration, keepEndVisible)
        }

        val cache = estimatorCache
        val estimator: KernelDensityEstimator
        val firstTimestamp = 0.0

        if (cache != null && cache.points === points && cache.sensitivity == sensitivity) {
            estimator = cache.estimator
        } else {
            var totalDuration = 0.0
            var hasData = false
            for (p in points) {
                yield()
                totalDuration += p.seconds.coerceAtLeast(0.0)
                if (p.counts > 0) hasData = true
            }
            if (!hasData || totalDuration <= 0.0) return null

            estimator = KernelDensityEstimator(sensitivity)
            var accumulatedSeconds = 0.0
            for (p in points) {
                yield()
                val durationSeconds = p.seconds.coerceAtLeast(0.0)
                estimator.addSampleInterval(accumulatedSeconds, durationSeconds, p.counts.coerceAtLeast(0))
                accumulatedSeconds += durationSeconds
            }
            estimatorCache = EstimatorCache(points, estimator, sensitivity)
        }

        val totalDuration = points.sumOf { it.seconds.coerceAtLeast(0.0) }
        val rangeStart = if (visibleRange.durationSeconds <= 0.0) 0.0 else visibleRange.startSeconds.coerceIn(0.0, totalDuration)
        val rangeEnd = if (visibleRange.durationSeconds <= 0.0) totalDuration else visibleRange.endSeconds.coerceIn(rangeStart, totalDuration)

        val ts2 = buildTs2(rangeStart, rangeEnd)
        yield()
        val ci = estimator.getConfidenceIntervals(ts2, minScale, null)
        return PlotResult.Kde(ts2, firstTimestamp, ci.first, ci.second, ci.third, totalDuration, keepEndVisible)
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
        sensitivity: Double,
        trackTitle: String
    ): PlotResult.SlidingWindow {
        val cache = slidingWindowCache
        if (cache != null && cache.inputPoints === points && cache.scaleSeconds == scaleSeconds && cache.sensitivity == sensitivity) {
            return PlotResult.SlidingWindow(cache.outputPoints)
        }

        yield()
        val track = MapTrack(id = CURRENT_TRACK_TITLE, title = trackTitle, points = points)
        val generalized = TrackGeneralizer(
            minDistanceMeters = 0.0,
            sensitivity = sensitivity,
            minDurationSeconds = scaleSeconds
        ).generalize(track)

        slidingWindowCache = SlidingWindowCache(points, scaleSeconds, sensitivity, generalized.points)
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
        private const val LIVE_KDE_UPDATE_INTERVAL_MS = 200L
        private const val INITIAL_LIVE_WINDOW_SECONDS = 120.0
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
