package com.example.geigergpx

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.geigergpx.databinding.ActivityTracksBinding

class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private val adapter by lazy { TracksAdapter(::onTrackToggled) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Tracks"

        binding.tracksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tracksRecyclerView.adapter = adapter

        viewModel.activeTrackPoints.observe(this) { points ->
            Thread {
                val items = TrackCatalog.loadTrackListItems(this, points)
                val selectedIds = selectedTrackIds()
                val selected = if (selectedIds.isEmpty()) {
                    setOf(TrackCatalog.currentTrackId())
                } else {
                    selectedIds
                }
                runOnUiThread {
                    adapter.submit(items, selected)
                }
            }.start()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun onTrackToggled(trackId: String, visible: Boolean) {
        val selected = selectedTrackIds().toMutableSet()
        if (visible) {
            selected.add(trackId)
        } else {
            selected.remove(trackId)
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putStringSet(PREF_MAP_VISIBLE_TRACK_IDS, selected)
            .apply()
    }

    private fun selectedTrackIds(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(PREF_MAP_VISIBLE_TRACK_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    companion object {
        const val PREF_MAP_VISIBLE_TRACK_IDS = "map_visible_track_ids"
    }
}
