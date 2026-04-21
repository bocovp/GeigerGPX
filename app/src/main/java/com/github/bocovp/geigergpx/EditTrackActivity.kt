package com.github.bocovp.geigergpx

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityEditTrackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        editOverlay = EditTrackOverlay(this)
        selectionOverlay = RectangleSelectionOverlay(this) { rect ->
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
            finish()
        }

        binding.btnApply.setOnClickListener {
            applyChanges()
        }
    }

    private fun loadTrack() {
        lifecycleScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    EditableTrackStorage.loadTrack(this@EditTrackActivity, trackId)
                }
            }.getOrNull()
            if (loaded == null || loaded.points.isEmpty()) {
                Toast.makeText(this@EditTrackActivity, "Unable to load track", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            points = loaded.points.toMutableList()
            fitMapToTrack()
            refreshUiState()
        }
    }

    private fun fitMapToTrack() {
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.isEmpty()) return
        binding.mapView.post {
            // Must set a valid zoom first so osmdroid can compute a non-NaN map size
            binding.mapView.controller.setZoom(15.0)
            if (geoPoints.size > 1) {
                binding.mapView.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(geoPoints), false, 64)
            } else {
                binding.mapView.controller.setCenter(geoPoints.first())
                binding.mapView.controller.setZoom(17.0)
            }
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
        editOverlay.points = points
        editOverlay.minDose = 0.0
        editOverlay.maxDose = (points.maxOfOrNull { it.doseRate } ?: 0.5).coerceIn(0.5, 10.0)
        editOverlay.highlightedIndices = highlightedIndices()

        val adjustEnabled = mode == EditMode.CUT_BEFORE || mode == EditMode.CUT_AFTER || mode == EditMode.SPLIT
        binding.adjustButtonsRow.visibility = if (adjustEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnLeft.isEnabled = adjustEnabled && (boundaryIndex ?: 0) > 0
        binding.btnRight.isEnabled = adjustEnabled && (boundaryIndex != null) && (boundaryIndex!! < points.lastIndex)

        selectionOverlay.selectionEnabled = mode != EditMode.NONE
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
            EditMode.MARK_BAD -> "Mark ${selectedIndices.size} points as 'bad coordinates'"
            EditMode.CUT_BEFORE -> {
                val count = (boundaryIndex ?: -1) + 1
                "Remove first $count points"
            }
            EditMode.CUT_AFTER -> {
                val boundary = boundaryIndex ?: return ""
                "Remove last ${points.size - boundary} points"
            }
            EditMode.SPLIT -> {
                val split = boundaryIndex ?: return ""
                val first = split + 1
                val second = points.size - first
                "Split into $first and $second points"
            }
            EditMode.NONE -> ""
        }
    }

    private fun applyChanges() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val updatedPoints = points.toMutableList()
        var splitPoints: List<TrackPoint>? = null

        when (mode) {
            EditMode.NONE -> return
            EditMode.MARK_BAD -> {
                if (selectedIndices.isEmpty()) return
                selectedIndices.forEach { idx ->
                    updatedPoints[idx] = updatedPoints[idx].copy(badCoordinates = true)
                }
            }
            EditMode.CUT_BEFORE -> {
                val boundary = boundaryIndex ?: return
                updatedPoints.clear()
                updatedPoints.addAll(points.drop(boundary + 1))
            }
            EditMode.CUT_AFTER -> {
                val boundary = boundaryIndex ?: return
                updatedPoints.clear()
                updatedPoints.addAll(points.take(boundary))
            }
            EditMode.SPLIT -> {
                val split = boundaryIndex ?: return
                val first = updatedPoints.take(split + 1)
                val second = points.drop(split + 1)
                if (first.isEmpty() || second.isEmpty()) {
                    Toast.makeText(this, "Unable to split at selected point", Toast.LENGTH_SHORT).show()
                    return
                }
                updatedPoints.clear()
                updatedPoints.addAll(first)
                splitPoints = second
            }
        }

        binding.btnApply.isEnabled = false
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    val splitResult = splitPoints?.let {
                        EditableTrackStorage.createSplitTrack(this@EditTrackActivity, trackId, trackTitle, trackFolder, it)
                            ?: return@withContext false
                    }
                    splitPoints?.let { second ->
                        splitResult?.let { result ->
                            TrackCatalog.onTrackSavedById(this@EditTrackActivity, result.newTrackId, result.newTrackTitle, trackFolder, second, coeff)
                        }
                    }
                    EditableTrackStorage.overwriteTrack(this@EditTrackActivity, trackId, updatedPoints)
                    TrackCatalog.onTrackSavedById(this@EditTrackActivity, trackId, trackTitle, trackFolder, updatedPoints, coeff)
                    true
                }

                if (success) {
                    points = updatedPoints
                    selectedIndices = emptyList()
                    boundaryIndex = null
                    binding.btnCancel.text = "Cancel (Finish)"
                    Toast.makeText(this@EditTrackActivity, "Track updated", Toast.LENGTH_SHORT).show()
                    refreshUiState()
                } else {
                    Toast.makeText(this@EditTrackActivity, "Failed to split track", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@EditTrackActivity, "Error saving changes", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnApply.isEnabled = true
            }
        }
    }

    companion object {
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_TRACK_TITLE = "extra_track_title"
        const val EXTRA_TRACK_FOLDER = "extra_track_folder"
    }
}
