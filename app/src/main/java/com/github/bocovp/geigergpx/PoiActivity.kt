package com.github.bocovp.geigergpx

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.bocovp.geigergpx.databinding.ActivityPoiBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PoiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoiBinding
    private val adapter by lazy { PoiAdapter(::onPoiToggled, ::onPoiLongPressed) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        supportActionBar?.title = "POI Library"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }

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
                R.id.navigation_poi -> true
                R.id.navigation_time_plot -> {
                    startActivity(Intent(this, TimePlotActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.poiRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.poiRecyclerView.adapter = adapter

        refreshPoiList()
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        refreshPoiList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }



    private fun syncBottomNavigationSelection() {
        binding.bottomNavigation.menu.findItem(R.id.navigation_poi)?.isChecked = true
    }

    private fun refreshPoiList() {
        binding.loadingLabel.setText(R.string.loading_poi_library)
        binding.loadingLabel.visibility = View.VISIBLE
        binding.poiRecyclerView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val (result, items) = withContext(Dispatchers.IO) {
                    val loadedResult = PoiLibrary.loadPoiLibrary(this@PoiActivity)
                    val mappedItems = loadedResult.entries.map { poi ->
                        PoiUiItem(
                            poi = poi,
                            title = poi.description,
                            subtitle = formatSubtitle(poi)
                        )
                    }
                    loadedResult to mappedItems
                }
                val selectedPoiIds = ensurePoiSelectionInitialized(result.entries.map { it.id }.toSet())
                adapter.submit(items, selectedPoiIds)
                val emptyMessageRes = when (result.state) {
                    PoiLibrary.LoadState.MISSING_FILE -> R.string.no_poi_file_found
                    PoiLibrary.LoadState.EMPTY_LIBRARY -> R.string.no_poi_in_library
                    PoiLibrary.LoadState.HAS_POIS -> null
                }
                binding.loadingLabel.visibility = if (emptyMessageRes == null) View.GONE else View.VISIBLE
                binding.poiRecyclerView.visibility = if (emptyMessageRes == null) View.VISIBLE else View.GONE
                emptyMessageRes?.let(binding.loadingLabel::setText)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                binding.loadingLabel.setText(R.string.no_poi_file_found)
                binding.loadingLabel.visibility = View.VISIBLE
                binding.poiRecyclerView.visibility = View.GONE
            }
        }
    }

    private fun formatSubtitle(poi: PoiEntry): String {
        val dateTime = if (poi.timestampMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(poi.timestampMillis))
        } else {
            "Unknown time"
        }
        return "$dateTime   ${formatDoseRateText(poi)} μSv/h"
    }

    private fun formatDoseRateText(poi: PoiEntry): String {
        val coeff = AppSettings.from(this).getCpsToUsvhCoefficient()

        val ci = ConfidenceInterval(0.0, poi.seconds, poi.counts, false).scale(coeff)
        return ci.toText(decimalDigits = 4)
    }

    private fun onPoiToggled(poiId: String, visible: Boolean) {
        val selected = selectedPoiIds().toMutableSet()
        if (visible) {
            selected.add(poiId)
        } else {
            selected.remove(poiId)
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, true)
            .putStringSet(PREF_MAP_VISIBLE_POI_IDS, selected)
            .apply()
    }

    private fun ensurePoiSelectionInitialized(allPoiIds: Set<String>): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val initialized = prefs.getBoolean(PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, false)
        if (!initialized) {
            prefs.edit()
                .putBoolean(PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, true)
                .putStringSet(PREF_MAP_VISIBLE_POI_IDS, allPoiIds)
                .apply()
            return allPoiIds
        }

        val selected = prefs.getStringSet(PREF_MAP_VISIBLE_POI_IDS, emptySet())?.toSet() ?: emptySet()
        val sanitized = selected.intersect(allPoiIds)
        if (sanitized != selected) {
            prefs.edit().putStringSet(PREF_MAP_VISIBLE_POI_IDS, sanitized).apply()
        }
        return sanitized
    }

    private fun selectedPoiIds(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet(PREF_MAP_VISIBLE_POI_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    private fun onPoiLongPressed(item: PoiUiItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        popup.setForceShowIcon(true)
        menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, "Rename")
            .setIcon(R.drawable.baseline_edit_24)
        menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, "Share")
            .setIcon(R.drawable.baseline_share_24)
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete")
            .setIcon(R.drawable.baseline_delete_24)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RENAME -> showRenameDialog(item)
                MENU_SHARE -> sharePoi(item.poi)
                MENU_DELETE -> confirmDeletePoi(item)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDialog(item: PoiUiItem) {
        val input = EditText(this).apply {
            setText(item.poi.description)
            setSelection(text.length)
            hint = "POI"
        }

        AlertDialog.Builder(this)
            .setTitle("Rename POI")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val renamed = PoiLibrary.renamePoi(this, item.poi, input.text.toString())
                if (renamed) {
                    Toast.makeText(this, "POI renamed", Toast.LENGTH_SHORT).show()
                    refreshPoiList()
                } else {
                    Toast.makeText(this, "Unable to rename POI", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun sharePoi(poi: PoiEntry) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, formatShareText(poi))

        try {
            startActivity(Intent.createChooser(intent, "Share POI"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app available for sharing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatShareText(poi: PoiEntry): String {
        val dateTime = if (poi.timestampMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(poi.timestampMillis))
        } else {
            "Unknown time"
        }
        val lat = String.format(Locale.US, "%.6f", poi.latitude)
        val lon = String.format(Locale.US, "%.6f", poi.longitude)
        return buildString {
            appendLine(poi.description)
            appendLine("Date: $dateTime")
            appendLine("Latitude: $lat")
            appendLine("Longitude: $lon")
            appendLine("Counts: ${poi.counts}")
            appendLine("Seconds: ${String.format(Locale.US, "%.3f", poi.seconds)}")
            append("Dose rate: ${formatDoseRateText(poi)} μSv/h")
        }
    }

    private fun confirmDeletePoi(item: PoiUiItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete POI")
            .setMessage("Delete '${item.title}'?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deleted = PoiLibrary.removePoi(this, item.poi)
                if (deleted) {
                    Toast.makeText(this, "POI removed", Toast.LENGTH_SHORT).show()
                    val selected = selectedPoiIds().toMutableSet().apply { remove(item.poi.id) }
                    PreferenceManager.getDefaultSharedPreferences(this)
                        .edit()
                        .putStringSet(PREF_MAP_VISIBLE_POI_IDS, selected)
                        .apply()
                    refreshPoiList()
                } else {
                    Toast.makeText(this, "Unable to remove POI", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    companion object {
        const val PREF_MAP_VISIBLE_POI_IDS = "map_visible_poi_ids"
        const val PREF_MAP_VISIBLE_POI_IDS_INITIALIZED = "map_visible_poi_ids_initialized"

        private const val MENU_RENAME = 1
        private const val MENU_SHARE = 2
        private const val MENU_DELETE = 3
    }
}
