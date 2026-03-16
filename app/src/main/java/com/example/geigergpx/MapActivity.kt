package com.example.geigergpx

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.example.geigergpx.databinding.ActivityMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var trackMapRenderer: TrackMapRenderer
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }

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
        trackMapRenderer = TrackMapRenderer(binding.mapView)

        observeTrack()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
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
            val coeff = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("cps_to_usvh", "1.0")
                ?.toDoubleOrNull()
                ?: 1.0

            val mappedPoints = points.map {
                TrackSample(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    doseRate = it.cps * coeff
                )
            }

            val tracks = listOf(
                MapTrack(
                    id = "active-track",
                    title = "Current recording",
                    points = mappedPoints
                )
            )
            trackMapRenderer.renderTracks(tracks)
        }
    }
}
