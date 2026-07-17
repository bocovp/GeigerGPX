package com.github.bocovp.geigergpx

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.bocovp.geigergpx.databinding.ActivityPoiBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

class PoiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoiBinding
    private val adapter by lazy { PoiAdapter(::onPoiToggled, ::onPoiLongPressed) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        supportActionBar?.title = getString(R.string.poi_library)
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
            getString(R.string.unknown_time)
        }
        return "$dateTime   ${formatDoseRateText(poi)}"
    }

    private fun formatDoseRateText(poi: PoiEntry): String {
        if (poi.seconds <= 0.0) {
            return getString(R.string.unknown_dose_rate)
        }
        val ci = ConfidenceInterval(0.0, poi.seconds, poi.counts, false).scale(1.0 / poi.sensitivity)
        return "${ci.toText(decimalDigits = 4)} μSv/h"
    }

    private fun onPoiToggled(poiId: String, visible: Boolean) {
        PoiLibrary.setPoiSelected(this, poiId, visible)
    }

    private fun ensurePoiSelectionInitialized(allPoiIds: Set<String>): Set<String> {
        return PoiLibrary.ensurePoiSelectionInitialized(this, allPoiIds)
    }

    private fun selectedPoiIds(): Set<String> {
        return PoiLibrary.selectedPoiIds(this)
    }

    private fun onPoiLongPressed(item: PoiUiItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        popup.setForceShowIcon(true)
        menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, getString(R.string.rename))
            .setIcon(R.drawable.baseline_edit_24)
        menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, getString(R.string.share))
            .setIcon(R.drawable.baseline_share_24)
        menu.add(Menu.NONE, MENU_DETAILS, Menu.NONE, getString(R.string.details))
            .setIcon(R.drawable.baseline_info_24)
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, getString(R.string.delete))
            .setIcon(R.drawable.baseline_delete_24)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RENAME -> showRenameDialog(item)
                MENU_SHARE -> sharePoi(item.poi)
                MENU_DETAILS -> showPoiDetails(item.poi)
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
            hint = getString(R.string.poi)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_poi)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val renamed = PoiLibrary.renamePoi(this, item.poi, input.text.toString())
                if (renamed) {
                    Toast.makeText(this, getString(R.string.poi_renamed), Toast.LENGTH_SHORT).show()
                    refreshPoiList()
                } else {
                    Toast.makeText(this, getString(R.string.unable_to_rename_poi), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showPoiDetails(poi: PoiEntry) {
        val details = formatShareText(poi)
        AlertDialog.Builder(this)
            .setTitle(R.string.poi_details)
            .setView(buildDetailsView(poiDetailsItems(poi)))
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.copy) { _, _ ->
                copyTextToClipboard(getString(R.string.poi_details_clip_label), details)
            }
            .show()
    }

    private fun poiDetailsItems(poi: PoiEntry): List<Pair<String, String>> {
        val dateTime = if (poi.timestampMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(poi.timestampMillis))
        } else {
            getString(R.string.unknown_time)
        }
        val items = mutableListOf(
            getString(R.string.details_name) to poi.description,
            getString(R.string.details_date) to dateTime,
            getString(R.string.details_latitude) to String.format(Locale.US, "%.6f", poi.latitude),
            getString(R.string.details_longitude) to String.format(Locale.US, "%.6f", poi.longitude),
            getString(R.string.details_counts) to poi.counts.toString(),
            getString(R.string.details_seconds) to String.format(Locale.US, "%.3f", poi.seconds),
            getString(R.string.details_dose_rate) to formatDoseRateText(poi)
        )
        poi.deviceName?.takeIf { it.isNotBlank() }?.let { items.add(getString(R.string.details_device) to it) }
        return items
    }

    private fun buildDetailsView(items: List<Pair<String, String>>): ScrollView {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (24 * density).toInt()
            val verticalPadding = (8 * density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
        }

        items.forEach { (name, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                alpha = 0.72f
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 16f
            })
            container.addView(row)
        }

        return ScrollView(this).apply { addView(container) }
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun sharePoi(poi: PoiEntry) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, formatShareText(poi))

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_poi)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_available_sharing), Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatShareText(poi: PoiEntry): String {
        val dateTime = if (poi.timestampMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(poi.timestampMillis))
        } else {
            getString(R.string.unknown_time)
        }
        val lat = String.format(Locale.US, "%.6f", poi.latitude)
        val lon = String.format(Locale.US, "%.6f", poi.longitude)
        return buildString {
            appendLine(poi.description)
            appendLine(getString(R.string.share_date_format, dateTime))
            appendLine(getString(R.string.share_latitude_format, lat))
            appendLine(getString(R.string.share_longitude_format, lon))
            appendLine(getString(R.string.share_counts_format, poi.counts))
            appendLine(getString(R.string.share_seconds_format, String.format(Locale.US, "%.3f", poi.seconds)))
            append(getString(R.string.share_dose_rate_format, formatDoseRateText(poi)))

            poi.deviceName?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(getString(R.string.share_device_format, it))
            }
        }
    }

    private fun confirmDeletePoi(item: PoiUiItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_poi)
            .setMessage(getString(R.string.delete_item_format, item.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deleted = PoiLibrary.removePoi(this, item.poi)
                if (deleted) {
                    Toast.makeText(this, getString(R.string.poi_removed), Toast.LENGTH_SHORT).show()
                    PoiLibrary.setPoiSelected(this, item.poi.id, selected = false)
                    refreshPoiList()
                } else {
                    Toast.makeText(this, getString(R.string.unable_to_remove_poi), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    companion object {

        private const val MENU_RENAME = 1
        private const val MENU_SHARE = 2
        private const val MENU_DETAILS = 3
        private const val MENU_DELETE = 4
    }
}
