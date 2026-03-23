package com.example.geigergpx

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.example.geigergpx.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var trackMapRenderer: TrackMapRenderer
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }

    private var isHeatmapMode: Boolean = false
    private var latestActivePoints: List<TrackPoint> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Track Map"

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        binding.zoomInButton.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.mapView.controller.zoomOut() }

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
            toggleItem?.setIcon(R.drawable.heatmap_24)
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_heatmap -> {
                // 1. Flip the state
                isHeatmapMode = !isHeatmapMode

                // 2. Refresh the menu to update Icon/Text
                invalidateOptionsMenu()

                // 3. Refresh the map with the new mode
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
        Thread {
            val includeCurrentTrack = viewModel.isTracking.value == true
            val selectedIds = selectedTrackIds()
            val mapTrackIds = selectedIds.ifEmpty { setOf(TrackCatalog.currentTrackId()) }
            val allItems = TrackCatalog.loadTrackListItems(
                context = this,
                activePoints = activePoints,
                includeCurrentTrack = includeCurrentTrack,
                includeMapTracks = true,
                mapTrackIds = mapTrackIds
            )
            val visibleTracks = allItems
                .filter { selectedIds.contains(it.id) || (selectedIds.isEmpty() && it.defaultVisible) }
                .mapNotNull { it.mapTrack }

            runOnUiThread {
                trackMapRenderer.renderTracks(visibleTracks, isHeatmapMode)
            }
        }.start()
    }

    private fun selectedTrackIds(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(TracksActivity.PREF_MAP_VISIBLE_TRACK_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }
}
