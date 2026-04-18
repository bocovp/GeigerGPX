package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.atomic.AtomicInteger

class MapActivity : AppCompatActivity() {
    private enum class PlotMode { SLIDING_WINDOW, KERNEL_ESTIMATOR }

    companion object {
        private var rememberedViewportState: TrackMapRenderer.MapViewportState? = null
        private var isAutoZoomDisabledByUser: Boolean = false
        private var isHeatmapMode: Boolean = false
        private var plotMode: PlotMode = PlotMode.SLIDING_WINDOW
    }

    private lateinit var binding: ActivityMapBinding
    private lateinit var trackMapRenderer: TrackMapRenderer
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }

    private var latestActivePoints: List<TrackPoint> = emptyList()
    private val mapLoadRequestSequence = AtomicInteger(0)
    private var hasLoadedMapTracks = false
    private var hasVisibleMapContent = false
    private var ignoreMapMoveEventsUntilMillis: Long = 0L
    private val appState: GeigerGpxApp by lazy { application as GeigerGpxApp }

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
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onUserMapMoved()
                refreshMapTracks(latestActivePoints)
                return false
            }
        })

        val tvHalf = findViewById<TextView>(R.id.tvHalfDose)
        val tvMax = findViewById<TextView>(R.id.tvMaxDose)
        trackMapRenderer = TrackMapRenderer(binding.mapView, tvHalf, tvMax)

        observeTrack()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.map_toolbar_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
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
        modeItem?.isEnabled = !isHeatmapMode

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
                refreshMapTracks(latestActivePoints)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        binding.mapView.onResume()
        rememberedViewportState?.let { trackMapRenderer.restoreViewport(it) }
        refreshMapTracks(latestActivePoints)
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
        val requestId = mapLoadRequestSequence.incrementAndGet()
        val showLoading = !hasLoadedMapTracks || TrackCatalog.isTrackCacheEmpty(this)
        binding.loadingLabel.visibility = if (showLoading) View.VISIBLE else View.GONE
        Thread {
            val includeCurrentTrack = viewModel.isTracking.value == true
            val selectedTrackIds = selectedTrackIds()
            val selectedFolders = selectedFolderIds()
            val mapTrackIds = selectedTrackIds.ifEmpty { setOf(TrackCatalog.currentTrackId()) }
            val allItems = TrackCatalog.loadTrackListItems(
                context = this,
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
                        item.folderName != null -> selectedTrackIds.contains(item.id) && selectedFolders.contains(item.folderName)
                        else -> selectedTrackIds.contains(item.id) || (selectedTrackIds.isEmpty() && item.defaultVisible)
                    }
                }
                .mapNotNull { it.mapTrack }

            val allPois = PoiLibrary.loadPoiLibrary(this).entries
            val selectedPoiIds = ensurePoiSelectionInitialized(allPois.map { it.id }.toSet())
            val visiblePois = allPois.filter { it.id in selectedPoiIds }

            val coeff = PreferenceManager.getDefaultSharedPreferences(this)
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

            runOnUiThread {
                if (requestId != mapLoadRequestSequence.get()) {
                    return@runOnUiThread
                }
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
                binding.loadingLabel.visibility = View.GONE
            }
        }.start()
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
        return (minutes * 60.0).coerceAtLeast(KdeScaleSlider.MIN_SECONDS)
    }

    private fun updateLegendVisibility() {
        val showKernelSlider = false && (!isHeatmapMode && plotMode == PlotMode.KERNEL_ESTIMATOR)
        // slider is disabled for novv
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
