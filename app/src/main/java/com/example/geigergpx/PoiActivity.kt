package com.example.geigergpx

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.geigergpx.databinding.ActivityPoiBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PoiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPoiBinding
    private val adapter by lazy { PoiAdapter(::onPoiLongPressed) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPoiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "POI Library"

        binding.poiRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.poiRecyclerView.adapter = adapter

        refreshPoiList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshPoiList() {
        binding.loadingLabel.visibility = View.VISIBLE
        binding.poiRecyclerView.visibility = View.GONE

        Thread {
            val entries = PoiLibrary.loadPois(this)
            val items = entries.map { poi ->
                PoiUiItem(
                    poi = poi,
                    title = poi.description,
                    subtitle = formatSubtitle(poi)
                )
            }
            runOnUiThread {
                adapter.submit(items)
                binding.loadingLabel.visibility = View.GONE
                binding.poiRecyclerView.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun formatSubtitle(poi: PoiEntry): String {
        val date = if (poi.timestampMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(poi.timestampMillis))
        } else {
            "Unknown date"
        }
        val lat = String.format(Locale.US, "%.5f", poi.latitude)
        val lon = String.format(Locale.US, "%.5f", poi.longitude)
        return "$date   $lat $lon   ${formatDoseRateText(poi)} μSv/h"
    }

    private fun formatDoseRateText(poi: PoiEntry): String {
        val coeff = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val ci = DoseStatistics.doseRateIntervalFromCountsAndSeconds(poi.counts, poi.seconds, coeff)
        return if (poi.counts <= 9) {
            String.format(Locale.US, "%.4f … %.4f", ci.lowBound, ci.highBound)
        } else {
            String.format(Locale.US, "%.4f ± %.4f", ci.mean, ci.delta)
        }
    }

    private fun onPoiLongPressed(item: PoiUiItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, "Rename")
        menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, "Share")
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "Delete")

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
        val lat = String.format(Locale.US, "%.5f", poi.latitude)
        val lon = String.format(Locale.US, "%.5f", poi.longitude)
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
                    refreshPoiList()
                } else {
                    Toast.makeText(this, "Unable to remove POI", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    companion object {
        private const val MENU_RENAME = 1
        private const val MENU_SHARE = 2
        private const val MENU_DELETE = 3
    }
}
