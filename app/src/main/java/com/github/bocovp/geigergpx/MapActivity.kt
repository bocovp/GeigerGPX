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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var trackMapRenderer: TrackMapRenderer
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }

    private var isHeatmapMode: Boolean = false
    private var latestActivePoints: List<TrackPoint> = emptyList()
    private val mapLoadRequestSequence = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        binding.zoomInButton.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.mapView.controller.zoomOut() }
        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                refreshMapTracks(latestActivePoints)
                return false
            }
        })

        val tvHalf = findViewById<TextView>(R.id.tvHalfDose)
        val tvMax = findViewById<TextView>(R.id.tvMaxDose)
        trackMapRenderer = TrackMapRenderer(binding.mapView, tvHalf, tvMax)

        if (savedInstanceState != null) {
            isHeatmapMode = savedInstanceState.getBoolean("heatmap_mode", false)
        }
        observeTrack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("heatmap_mode", isHeatmapMode)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.map_toolbar_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val toggleItem = menu?.findItem(R.id.action_toggle_heatmap)

        if (isHeatmapMode) {
            toggleItem?.title = "Show Gradient Tracks"
            toggleItem?.setIcon(R.drawable.track_24)
        } else {
            toggleItem?.title = "Show Heatmap"
            toggleItem?.setIcon(R.drawable.baseline_heatmap_24)
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_heatmap -> {
                isHeatmapMode = !isHeatmapMode
                invalidateOptionsMenu()
                refreshMapTracks(latestActivePoints)
                true
            } 
            R.id.action_tracks -> {
                startActivity(Intent(this, TracksActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        refreshMapTracks(latestActivePoints)
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
        binding.loadingLabel.visibility = View.VISIBLE
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
                trackMapRenderer.renderTracks(
                    tracks = visibleTracks,
                    pois = poiMapItems,
                    isHeatmapMode = isHeatmapMode
                )
                binding.loadingLabel.visibility = View.GONE
            }
        }.start()
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
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(TracksActivity.PREF_MAP_VISIBLE_TRACK_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    private fun selectedFolderIds(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(TracksActivity.PREF_MAP_VISIBLE_SUBFOLDER_NAMES, emptySet())
            ?.toSet()
            ?: emptySet()
    }
}
