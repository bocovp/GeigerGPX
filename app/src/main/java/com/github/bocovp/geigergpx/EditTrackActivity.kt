package com.github.bocovp.geigergpx

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.bocovp.geigergpx.databinding.ActivityEditTrackBinding
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController

class EditTrackActivity : AppCompatActivity() {

    private enum class EditMode { NONE, MARK_BAD, CUT_BEFORE, CUT_AFTER, SPLIT }

    private lateinit var binding: ActivityEditTrackBinding
    private lateinit var editOverlay: EditTrackOverlay
    private lateinit var selectionOverlay: RectangleSelectionOverlay

    private var trackId: String = ""
    private var trackTitle: String = ""
    private var trackFolder: String? = null

    private var points: MutableList<TrackPoint> = mutableListOf()
    private var mode: EditMode = EditMode.NONE
    private var selectedIndices: List<Int> = emptyList()
    private var boundaryIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityEditTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        trackId = intent.getStringExtra(EXTRA_TRACK_ID).orEmpty()
        trackTitle = intent.getStringExtra(EXTRA_TRACK_TITLE).orEmpty().ifBlank { "Track" }
        trackFolder = intent.getStringExtra(EXTRA_TRACK_FOLDER)?.takeIf { it.isNotBlank() }

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.title = "Edit Track"
        binding.topAppBar.subtitle = trackTitle
        binding.topAppBar.setNavigationOnClickListener { finish() }

        setupMap()
        setupControls()
        loadTrack()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    private fun setupMap() {
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        editOverlay = EditTrackOverlay()
        selectionOverlay = RectangleSelectionOverlay { rect ->
            applyRectangleSelection(rect)
        }
        binding.mapView.overlays.add(editOverlay)
        binding.mapView.overlays.add(selectionOverlay)
    }

    private fun setupControls() {
        binding.editModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                mode = EditMode.NONE
            } else {
                mode = when (checkedId) {
                    R.id.btnMarkBad -> EditMode.MARK_BAD
                    R.id.btnCutBefore -> EditMode.CUT_BEFORE
                    R.id.btnCutAfter -> EditMode.CUT_AFTER
                    R.id.btnSplit -> EditMode.SPLIT
                    else -> EditMode.NONE
                }
            }
            selectedIndices = emptyList()
            boundaryIndex = null
            refreshUiState()
        }

        binding.btnLeft.setOnClickListener {
            val current = boundaryIndex ?: return@setOnClickListener
            boundaryIndex = (current - 1).coerceAtLeast(0)
            refreshUiState()
        }
        binding.btnRight.setOnClickListener {
            val current = boundaryIndex ?: return@setOnClickListener
            boundaryIndex = (current + 1).coerceAtMost(points.lastIndex)
            refreshUiState()
        }

        binding.btnCancel.setOnClickListener {
            selectedIndices = emptyList()
            boundaryIndex = null
            refreshUiState()
        }

        binding.btnApply.setOnClickListener {
            applyChanges()
        }
    }

    private fun loadTrack() {
        val loaded = EditableTrackStorage.loadTrack(this, trackId)
        if (loaded == null || loaded.points.isEmpty()) {
            Toast.makeText(this, "Unable to load track", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        points = loaded.points.toMutableList()
        fitMapToTrack()
        refreshUiState()
    }

    private fun fitMapToTrack() {
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.size > 1) {
            binding.mapView.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(geoPoints), true, 64)
        } else if (geoPoints.size == 1) {
            binding.mapView.controller.setCenter(geoPoints.first())
            binding.mapView.controller.setZoom(17.0)
        }
    }

    private fun applyRectangleSelection(rect: android.graphics.RectF) {
        if (mode == EditMode.NONE) return

        val p1 = binding.mapView.projection.fromPixels(rect.left.toInt(), rect.top.toInt()) as GeoPoint
        val p2 = binding.mapView.projection.fromPixels(rect.right.toInt(), rect.bottom.toInt()) as GeoPoint

        val minLat = minOf(p1.latitude, p2.latitude)
        val maxLat = maxOf(p1.latitude, p2.latitude)
        val minLon = minOf(p1.longitude, p2.longitude)
        val maxLon = maxOf(p1.longitude, p2.longitude)

        selectedIndices = points.mapIndexedNotNull { index, point ->
            if (point.latitude in minLat..maxLat && point.longitude in minLon..maxLon) index else null
        }

        boundaryIndex = when (mode) {
            EditMode.CUT_BEFORE -> selectedIndices.maxOrNull()
            EditMode.CUT_AFTER -> selectedIndices.minOrNull()
            EditMode.SPLIT -> selectedIndices.sorted().let { sorted ->
                if (sorted.isEmpty()) null else sorted[sorted.size / 2]
            }
            else -> null
        }

        refreshUiState()
    }

    private fun refreshUiState() {
        val samples = points.map { TrackSample(it.latitude, it.longitude, it.cps, it.counts, it.seconds, it.badCoordinates) }
        editOverlay.points = samples
        editOverlay.minDose = 0.0
        editOverlay.maxDose = (samples.maxOfOrNull { it.doseRate } ?: 0.5).coerceIn(0.5, 10.0)
        editOverlay.highlightedIndices = highlightedIndices()

        val adjustEnabled = mode == EditMode.CUT_BEFORE || mode == EditMode.CUT_AFTER || mode == EditMode.SPLIT
        binding.adjustButtonsRow.visibility = if (adjustEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnLeft.isEnabled = adjustEnabled && (boundaryIndex ?: 0) > 0
        binding.btnRight.isEnabled = adjustEnabled && (boundaryIndex != null) && (boundaryIndex!! < points.lastIndex)

        selectionOverlay.enabled = mode != EditMode.NONE
        binding.descriptionText.setText(descriptionText())
        binding.mapView.invalidate()
    }

    private fun highlightedIndices(): Set<Int> {
        return when (mode) {
            EditMode.MARK_BAD -> selectedIndices.toSet()
            EditMode.CUT_BEFORE -> {
                val boundary = boundaryIndex ?: return emptySet()
                (0..boundary).toSet()
            }
            EditMode.CUT_AFTER -> {
                val boundary = boundaryIndex ?: return emptySet()
                (boundary..points.lastIndex).toSet()
            }
            EditMode.SPLIT -> boundaryIndex?.let { setOf(it) } ?: emptySet()
            EditMode.NONE -> emptySet()
        }
    }

    private fun descriptionText(): String {
        return when (mode) {
            EditMode.MARK_BAD -> "${selectedIndices.size} points will be marked as having bad coordinates"
            EditMode.CUT_BEFORE -> {
                val count = (boundaryIndex ?: -1) + 1
                "$count points will be deleted from the start of the track"
            }
            EditMode.CUT_AFTER -> {
                val boundary = boundaryIndex ?: return ""
                "${points.size - boundary} points will be deleted from the end of the track"
            }
            EditMode.SPLIT -> {
                val split = boundaryIndex ?: return ""
                val first = split + 1
                val second = points.size - first
                "Track will be split into two containing $first and $second points"
            }
            EditMode.NONE -> ""
        }
    }

    private fun applyChanges() {
        when (mode) {
            EditMode.NONE -> return
            EditMode.MARK_BAD -> {
                if (selectedIndices.isEmpty()) return
                selectedIndices.forEach { idx ->
                    points[idx] = points[idx].copy(badCoordinates = true)
                }
            }
            EditMode.CUT_BEFORE -> {
                val boundary = boundaryIndex ?: return
                points = points.drop(boundary + 1).toMutableList()
            }
            EditMode.CUT_AFTER -> {
                val boundary = boundaryIndex ?: return
                points = points.take(boundary).toMutableList()
            }
            EditMode.SPLIT -> {
                val split = boundaryIndex ?: return
                val first = points.take(split + 1)
                val second = points.drop(split + 1)
                if (first.isEmpty() || second.isEmpty()) {
                    Toast.makeText(this, "Unable to split at selected point", Toast.LENGTH_SHORT).show()
                    return
                }
                points = first.toMutableList()
                val splitResult = EditableTrackStorage.createSplitTrack(this, trackId, trackTitle, trackFolder, second)
                if (splitResult != null) {
                    TrackCatalog.onTrackSavedById(this, splitResult.newTrackId, splitResult.newTrackTitle, trackFolder, second)
                }
            }
        }

        EditableTrackStorage.overwriteTrack(this, trackId, points)
        TrackCatalog.onTrackSavedById(this, trackId, trackTitle, trackFolder, points)
        selectedIndices = emptyList()
        boundaryIndex = null
        Toast.makeText(this, "Track updated", Toast.LENGTH_SHORT).show()
        refreshUiState()
    }

    companion object {
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_TRACK_FOLDER = "extra_track_folder"
    }
}
