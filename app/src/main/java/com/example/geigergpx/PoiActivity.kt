package com.example.geigergpx

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        return "$date   $lat $lon   ${poi.doseRateText} μSv/h"
    }

    private fun onPoiLongPressed(item: PoiUiItem) {
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
}
