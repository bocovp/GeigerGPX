package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityMapBinding
import com.google.android.material.slider.Slider
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapActivity : AppCompatActivity() {
    private enum class PlotMode { SLIDING_WINDOW, KERNEL_ESTIMATOR }

    companion object {
        private var rememberedViewportState: TrackMapRenderer.MapViewportState? = null
        private var isAutoZoomDisabledByUser: Boolean = false
        private var isHeatmapMode: Boolean = false
        private var plotMode: PlotMode = PlotMode.SLIDING_WINDOW
        private const val DOSE_SELECTION_TOUCH_THRESHOLD_DP = 100.0
    }

    private lateinit var binding: ActivityMapBinding
    private lateinit var trackMapRenderer: TrackMapRenderer
    private lateinit var mapDoseLongPressOverlay: MapDoseLongPressOverlay
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }

    private var latestActivePoints: List<TrackPoint> = emptyList()
    private var refreshJob: Job? = null
    private var hasLoadedMapTracks = false
    private var hasVisibleMapContent = false
    private var ignoreMapMoveEventsUntilMillis: Long = 0L
    private var heatmapRefreshPendingFromScroll = false
    private val appState: GeigerGpxApp by lazy { application as GeigerGpxApp }

    private val KDE_SLIDER_ENABLED = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }
        syncBottomNavigationSelection()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    true
                }
                R.id.navigation_map -> true
                R.id.navigation_tracks -> {
                    startActivity(Intent(this, TracksActivity::class.java))
                    true
                }
                R.id.navigation_poi -> {
                    startActivity(Intent(this, PoiActivity::class.java))
                    true
                }
                R.id.navigation_time_plot -> {
                    startActivity(Intent(this, TimePlotActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(5.0)
        binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        binding.zoomInButton.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.mapView.controller.zoomOut() }
        setupKdeScaleSlider()
        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onUserMapMoved()
                requestHeatmapRefreshAfterMapMove()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onUserMapMoved()
                refreshMapTracks(latestActivePoints)
                return false
            }
        })
        binding.mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                triggerHeatmapRefreshAfterMapMove()
            }
            false
        }

        val tvHalf = findViewById<TextView>(R.id.tvHalfDose)
        val tvMax = findViewById<TextView>(R.id.tvMaxDose)
        trackMapRenderer = TrackMapRenderer(binding.mapView, tvHalf, tvMax)

        var lastLongPressX = 0f
        var lastLongPressY = 0f
        var longPressHasTrackPointSelection = false
        mapDoseLongPressOverlay = MapDoseLongPressOverlay(
            onLongPressPositionChanged = { x, y ->
                lastLongPressX = x
                lastLongPressY = y
                val newlySelected = trackMapRenderer.updateHighlightedPointForScreenPosition(
                    screenX = x,
                    screenY = y,
                    useKernelEstimator = !isHeatmapMode && plotMode == PlotMode.KERNEL_ESTIMATOR,
                    maxDistancePx = DOSE_SELECTION_TOUCH_THRESHOLD_DP * resources.displayMetrics.density
                )
                if (longPressHasTrackPointSelection != newlySelected) {
                    longPressHasTrackPointSelection = newlySelected
                    invalidateOptionsMenu()
                }
                val selected = trackMapRenderer.selectedPointInfo()
                appState.setHighlightedTrackPoint(
                    selected?.let {
                        GeigerGpxApp.HighlightedTrackPoint(
                            trackId = it.trackId,
                            trackTitle = it.trackTitle,
                            pointIndex = it.pointIndex,
                            point = it.point
                        )
                    }
                )
            },

            onLongPressFinished = {
                if (!longPressHasTrackPointSelection) {
                    val geo = binding.mapView.projection.fromPixels(lastLongPressX.toInt(), lastLongPressY.toInt())
                    trackMapRenderer.setUnknownHighlightedPoint(geo.latitude, geo.longitude)
                    appState.setHighlightedTrackPoint(null)
                    invalidateOptionsMenu()
                }
            }
        )
        mapDoseLongPressOverlay.longPressEnabled = !isHeatmapMode
        binding.mapView.overlays.add(mapDoseLongPressOverlay)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackCatalog.allTracks.collectLatest {
                    refreshMapTracks(latestActivePoints)
                }
            }
        }
        observeTrack()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.map_toolbar_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val toggleItem = menu?.findItem(R.id.action_toggle_heatmap)
        val modeItem = menu?.findItem(R.id.action_toggle_plot_mode)

        if (isHeatmapMode) {
            toggleItem?.title = "Show Gradient Tracks"
            toggleItem?.setIcon(R.drawable.baseline_line_axis_24)
        } else {
            toggleItem?.title = "Show Heatmap"
            toggleItem?.setIcon(R.drawable.baseline_scatter_plot_24)
        }
        updateModeUi(modeItem)
        modeItem?.isVisible = !isHeatmapMode
        menu.findItem(R.id.action_add_poi)?.isVisible = !isHeatmapMode && trackMapRenderer.hasHighlightedPoint()

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_auto_zoom -> {
                val moved = trackMapRenderer.autoZoomToSelection(animate = true)
                if (moved) {
                    suppressMapMoveEventsTemporarily()
                    rememberViewportAfterProgrammaticAutoZoom()
                }
                true
            }
            R.id.action_toggle_heatmap -> {
                isHeatmapMode = !isHeatmapMode
                updateLegendVisibility()
                invalidateOptionsMenu()
                mapDoseLongPressOverlay.longPressEnabled = !isHeatmapMode
                if (isHeatmapMode) {
                    trackMapRenderer.clearHighlightedPoint()
                    appState.setHighlightedTrackPoint(null)
                }
                invalidateOptionsMenu()
                refreshMapTracks(latestActivePoints)
                true
            }
            R.id.action_toggle_plot_mode -> {
                // Trying to use  kde for heatmap:
                // if (isHeatmapMode) return true
                plotMode = if (plotMode == PlotMode.SLIDING_WINDOW) {
                    PlotMode.KERNEL_ESTIMATOR
                } else {
                    PlotMode.SLIDING_WINDOW
                }
                updateLegendVisibility()
                invalidateOptionsMenu()
                mapDoseLongPressOverlay.longPressEnabled = !isHeatmapMode
                if (isHeatmapMode) {
                    trackMapRenderer.clearHighlightedPoint()
                    appState.setHighlightedTrackPoint(null)
                }
                invalidateOptionsMenu()
                refreshMapTracks(latestActivePoints)
                true
            }
            R.id.action_add_poi -> {
                showAddPoiDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAddPoiDialog() {
        val selected = trackMapRenderer.selectedPointInfo()
        val defaultName = if (selected != null) "${selected.trackTitle} #${selected.pointIndex}" else "Unknown"
        val input = EditText(this).apply {
            setText(defaultName)
            setSelection(text.length)
            hint = "POI"
        }
        AlertDialog.Builder(this)
            .setTitle("Add POI")
            .setMessage("Define POI name:")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val description = input.text.toString()
                val point = selected?.point
                val current = trackMapRenderer.highlightedPoint()
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        if (point != null) {
                            PoiLibrary.addPoi(
                                context = this@MapActivity,
                                description = description,
                                timestampMillis = point.timeMillis,
                                latitude = point.latitude,
                                longitude = point.longitude,
                                doseRate = point.doseRate,
                                counts = point.counts,
                                seconds = point.seconds
                            )
                        } else {
                            PoiLibrary.addPoi(
                                context = this@MapActivity,
                                description = description.ifBlank { "Unknown" },
                                timestampMillis = System.currentTimeMillis(),
                                latitude = current?.latitude ?: 0.0,
                                longitude = current?.longitude ?: 0.0,
                                doseRate = 0.0,
                                counts = 0,
                                seconds = 0.0
                            )
                        }
                    }
                    Toast.makeText(this@MapActivity, if (success) "POI added to Library" else "Unable to add POI", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        binding.mapView.onResume()
        rememberedViewportState?.let { trackMapRenderer.restoreViewport(it) }
        mapDoseLongPressOverlay.longPressEnabled = !isHeatmapMode
    }

    override fun onPause() {
        if (hasVisibleMapContent) {
            rememberedViewportState = trackMapRenderer.currentViewportState()
        }
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun syncBottomNavigationSelection() {
        binding.bottomNavigation.menu.findItem(R.id.navigation_map)?.isChecked = true
    }

    private fun observeTrack() {
        viewModel.activeTrackPoints.observe(this) { points ->
            latestActivePoints = points
            refreshMapTracks(points)
        }
        viewModel.isTracking.observe(this) {
            refreshMapTracks(latestActivePoints)
        }
    }

    private fun refreshMapTracks(activePoints: List<TrackPoint>) {
        // Cancel any in-flight load. Because job.cancel() is cooperative, the
        // withContext(Dispatchers.IO) block will be interrupted at its next
        // suspension point. Any UI-thread code after the withContext block is
        // guaranteed not to run for the cancelled job, so there is no need for
        // the old AtomicInteger sequence guard.
        refreshJob?.cancel()

        val showLoading = !hasLoadedMapTracks || TrackCatalog.isTrackCacheEmpty()
        binding.loadingLabel.visibility = if (showLoading) View.VISIBLE else View.GONE

        // Capture all main-thread state that the IO block needs.
        // LiveData.value is @MainThread; read it here, not inside withContext.
        val includeCurrentTrack = viewModel.isTracking.value == true

        refreshJob = lifecycleScope.launch {
            try {
                // ---- IO-bound work -----------------------------------------------
                val result = withContext(Dispatchers.IO) {
                    val selectedTrackIds = selectedTrackIds()
                    val selectedFolders = selectedFolderIds()
                    val mapTrackIds = selectedTrackIds.ifEmpty { setOf(TrackCatalog.currentTrackId()) }

                    val allItems = TrackCatalog.loadTrackListItems(
                        context = this@MapActivity,
                        activePoints = activePoints,
                        includeCurrentTrack = includeCurrentTrack,
                        includeMapTracks = true,
                        mapTrackIds = mapTrackIds,
                        includeSubfolderTracks = true
                    )

                    val visibleTracks = allItems
                        .filter { item ->
                            when {
                                item.itemType != TrackListItemType.TRACK -> false
                                item.folderName != null ->
                                    selectedTrackIds.contains(item.id) &&
                                            selectedFolders.contains(item.folderName)

                                else ->
                                    selectedTrackIds.contains(item.id) ||
                                            (selectedTrackIds.isEmpty() && item.defaultVisible)
                            }
                        }
                        .mapNotNull { it.mapTrack }

                    val allPois = PoiLibrary.loadPoiLibrary(this@MapActivity).entries
                    val selectedPoiIds = ensurePoiSelectionInitialized(allPois.map { it.id }.toSet())
                    val visiblePois = allPois.filter { it.id in selectedPoiIds }

                    val coeff = PreferenceManager.getDefaultSharedPreferences(this@MapActivity)
                        .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
                    val showCpsUnit = kotlin.math.abs(coeff - 1.0) < 1e-9

                    val poiMapItems = visiblePois.map { poi ->
                        val cps = if (poi.seconds > 0.0001) poi.counts / poi.seconds else 0.0
                        val doseRate = cps * coeff
                        val value = if (showCpsUnit) cps else doseRate
                        val unit = if (showCpsUnit) "cps" else "μSv/h"
                        PoiMapItem(
                            id = poi.id,
                            name = poi.description,
                            latitude = poi.latitude,
                            longitude = poi.longitude,
                            doseRateForColor = doseRate,
                            counts = poi.counts,
                            seconds = poi.seconds,
                            doseLabel = String.format(Locale.US, "%.3f %s", value, unit)
                        )
                    }

                    visibleTracks to poiMapItems
                }
                // ---- Back on Main — only runs if the job was NOT cancelled --------
                // If the user triggered a new refresh while IO was in flight,
                // refreshJob?.cancel() has already been called and this block
                // is skipped automatically by the coroutine runtime.

                val (visibleTracks, poiMapItems) = result

                // Re-read UI-mode flags here, on the main thread, so we always use
                // the latest values. If these flags changed mid-flight the old job
                // was cancelled and replaced, so whatever values we read now are correct.
                hasLoadedMapTracks = true
                val autoFitApplied = trackMapRenderer.renderTracks(
                    tracks = visibleTracks,
                    pois = poiMapItems,
                    isHeatmapMode = isHeatmapMode,
                    shouldAutoFit = !isAutoZoomDisabledByUser,
                    useKernelEstimator = !isHeatmapMode && plotMode == PlotMode.KERNEL_ESTIMATOR,
                    kdeScaleSeconds = currentKdeScaleSeconds()
                )
                if (autoFitApplied) {
                    suppressMapMoveEventsTemporarily()
                    rememberViewportAfterProgrammaticAutoZoom()
                }
                hasVisibleMapContent = visibleTracks.isNotEmpty() || poiMapItems.isNotEmpty()
            } finally {
                if (refreshJob == coroutineContext[Job]) {
                    binding.loadingLabel.visibility = View.GONE
                }
            }
        }
    }

    private fun rememberViewportAfterProgrammaticAutoZoom() {
        binding.mapView.postDelayed({
            if (hasVisibleMapContent) {
                rememberedViewportState = trackMapRenderer.currentViewportState()
            }
        }, 1600L)
    }

    private fun onUserMapMoved() {
        if (System.currentTimeMillis() < ignoreMapMoveEventsUntilMillis) {
            return
        }
        if (!hasVisibleMapContent) {
            return
        }
        isAutoZoomDisabledByUser = true
        rememberedViewportState = trackMapRenderer.currentViewportState()
    }

    private fun suppressMapMoveEventsTemporarily() {
        ignoreMapMoveEventsUntilMillis = System.currentTimeMillis() + 1500L
    }

    private fun requestHeatmapRefreshAfterMapMove() {
        if (!isHeatmapMode) return
        heatmapRefreshPendingFromScroll = true
    }

    private fun triggerHeatmapRefreshAfterMapMove() {
        if (!isHeatmapMode || !heatmapRefreshPendingFromScroll) return
        heatmapRefreshPendingFromScroll = false
        refreshMapTracks(latestActivePoints)
    }

    private fun ensurePoiSelectionInitialized(allPoiIds: Set<String>): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val initialized = prefs.getBoolean(PoiActivity.PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, false)
        if (!initialized) {
            prefs.edit()
                .putBoolean(PoiActivity.PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, true)
                .putStringSet(PoiActivity.PREF_MAP_VISIBLE_POI_IDS, allPoiIds)
                .apply()
            return allPoiIds
        }
        val selected = prefs.getStringSet(PoiActivity.PREF_MAP_VISIBLE_POI_IDS, emptySet())?.toSet() ?: emptySet()
        val sanitized = selected.intersect(allPoiIds)
        if (sanitized != selected) {
            prefs.edit().putStringSet(PoiActivity.PREF_MAP_VISIBLE_POI_IDS, sanitized).apply()
        }
        return sanitized
    }

    private fun selectedTrackIds(): Set<String> {
        return TrackSelectionPrefs.selectedTrackIds(this)
    }

    private fun selectedFolderIds(): Set<String> {
        return TrackSelectionPrefs.selectedFolderIds(this)
    }

    private fun setupKdeScaleSlider() {
        binding.kdeScaleSliderMap.valueFrom = KdeScaleSlider.minutesToInternal(KdeScaleSlider.MIN_MINUTES)
        binding.kdeScaleSliderMap.valueTo = KdeScaleSlider.INTERNAL_MAX
        binding.kdeScaleSliderMap.value = appState.sharedKdeSliderInternalValue
            .coerceIn(binding.kdeScaleSliderMap.valueFrom, binding.kdeScaleSliderMap.valueTo)
        appState.sharedKdeSliderInternalValue = binding.kdeScaleSliderMap.value
        binding.kdeScaleSliderMap.setLabelFormatter { value ->
            "%.1f".format(Locale.US, KdeScaleSlider.internalToMinutes(value))
        }
        binding.kdeScaleSliderMap.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (!fromUser) return@addOnChangeListener
            appState.sharedKdeSliderInternalValue = value
            if (!isHeatmapMode && plotMode == PlotMode.KERNEL_ESTIMATOR) {
                refreshMapTracks(latestActivePoints)
            }
        }
        updateLegendVisibility()
    }

    private fun currentKdeScaleSeconds(): Double {
        val minutes = KdeScaleSlider.internalToMinutes(appState.sharedKdeSliderInternalValue)
        return (minutes * 60.0f).coerceAtLeast(KdeScaleSlider.MIN_SECONDS).toDouble()
    }

    private fun updateLegendVisibility() {
        val showKernelSlider = KDE_SLIDER_ENABLED  && (!isHeatmapMode && plotMode == PlotMode.KERNEL_ESTIMATOR)
        binding.colorLegendContent.visibility = if (showKernelSlider) View.GONE else View.VISIBLE
        binding.kdeScaleSliderMap.visibility = if (showKernelSlider) View.VISIBLE else View.GONE
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
}
