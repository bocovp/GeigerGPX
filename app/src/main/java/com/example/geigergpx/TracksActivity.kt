package com.example.geigergpx

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.geigergpx.databinding.ActivityTracksBinding
import java.io.File

class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private val adapter by lazy { TracksAdapter(::onTrackToggled, ::onTrackLongPressed) }
    private var hasLoadedTrackList = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Tracks"

        binding.tracksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tracksRecyclerView.adapter = adapter

        // Trigger an initial load immediately
        refreshTrackList()

        viewModel.activeTrackPoints.observe(this) {
            refreshTrackList()
        }

        viewModel.isTracking.observe(this) {
            refreshTrackList()
        }
    }

    private fun refreshTrackList() {
        val showLoading = !hasLoadedTrackList || TrackCatalog.isTrackCacheEmpty(this)
        binding.loadingLabel.visibility = if (showLoading) View.VISIBLE else View.GONE
        binding.tracksRecyclerView.visibility = if (showLoading) View.GONE else View.VISIBLE
        Thread {
            val points = viewModel.activeTrackPoints.value.orEmpty()
            val includeCurrentTrack = viewModel.isTracking.value == true
            val items = TrackCatalog.loadTrackListItems(this, points, includeCurrentTrack)
            val selected = selectedTrackIds().ifEmpty { setOf(TrackCatalog.currentTrackId()) }
            runOnUiThread {
                hasLoadedTrackList = true
                adapter.submit(items, selected)
                binding.loadingLabel.visibility = View.GONE
                binding.tracksRecyclerView.visibility = View.VISIBLE
            }
        }.start()
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tracks_toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh_tracks -> {
                TrackCatalog.clearTrackCache(this)
                refreshTrackList()
                true
            }
            else -> super.onOptionsItemSelected(item)
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

    private fun onTrackLongPressed(item: TrackListItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu

        if (!item.isCurrentTrack) {
            menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete")
        }
        menu.add(Menu.NONE, MENU_OPEN_DEFAULT, Menu.NONE, "Open in default app")
        menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, "Share")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_DELETE -> confirmDeleteTrack(item)
                MENU_OPEN_DEFAULT -> openInDefaultApp(item)
                MENU_SHARE -> shareTrack(item)
            }
            true
        }
        popup.show()
    }

    private fun confirmDeleteTrack(item: TrackListItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete track")
            .setMessage("Delete '${item.title}'?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deleted = deleteTrack(item)
                if (deleted) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    refreshTrackList()
                } else {
                    Toast.makeText(this, "Unable to delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun deleteTrack(item: TrackListItem): Boolean {
        if (item.isCurrentTrack) return false
        return when {
            item.id.startsWith("doc:") -> {
                val uri = Uri.parse(item.id.removePrefix("doc:"))
                DocumentFile.fromSingleUri(this, uri)?.delete() == true
            }
            item.id.startsWith("file:") -> {
                val path = item.id.removePrefix("file:")
                File(path).delete()
            }
            else -> false
        }
    }

    private fun openInDefaultApp(item: TrackListItem) {
        val uri = trackUriForOpenOrShare(item) ?: run {
            Toast.makeText(this, "Track file is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, GPX_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open GPX files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTrack(item: TrackListItem) {
        val uri = trackUriForOpenOrShare(item) ?: run {
            Toast.makeText(this, "Track file is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND)
            .setType(GPX_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            startActivity(Intent.createChooser(intent, "Share track"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app available for sharing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trackUriForOpenOrShare(item: TrackListItem): Uri? {
        return if (item.isCurrentTrack) {
            val points = viewModel.activeTrackPoints.value.orEmpty().ifEmpty {
                TrackingService.activeTrackPointsSnapshot()
            }
            GpxWriter.saveBackup(this, points)
            val backupUri = GpxWriter.backupUri(this) ?: return null
            if (backupUri.scheme == "file") {
                val file = File(backupUri.path ?: return null)
                FileProvider.getUriForFile(this, "${this.packageName}.fileprovider", file)
            } else {
                backupUri
            }
        } else {
            when {
                item.id.startsWith("doc:") -> Uri.parse(item.id.removePrefix("doc:"))
                item.id.startsWith("file:") -> {
                    val file = File(item.id.removePrefix("file:"))
                    if (!file.exists()) return null
                    FileProvider.getUriForFile(this, "${this.packageName}.fileprovider", file)
                }
                else -> null
            }
        }
    }

    private fun selectedTrackIds(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(PREF_MAP_VISIBLE_TRACK_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    companion object {
        const val PREF_MAP_VISIBLE_TRACK_IDS = "map_visible_track_ids"

        private const val MENU_DELETE = 1
        private const val MENU_OPEN_DEFAULT = 2
        private const val MENU_SHARE = 3
        private const val GPX_MIME = "application/gpx+xml"
    }
}
