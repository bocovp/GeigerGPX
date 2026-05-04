package com.github.bocovp.geigergpx

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.bocovp.geigergpx.databinding.ActivityEditTrackBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController

class EditTrackActivity : AppCompatActivity() {

    private enum class EditMode {
        NONE,
        MARK_BAD,
        MARK_GOOD,
        MERGE_POINTS,
        INTERPOLATE_COORDINATES,
        CUT_BEFORE,
        CUT_AFTER,
        SPLIT
    }

    private lateinit var binding: ActivityEditTrackBinding
    private lateinit var editOverlay: EditTrackOverlay
    private lateinit var selectionOverlay: RectangleSelectionOverlay

    private var trackId = ""
    private var trackTitle = ""
    private var trackFolder: String? = null

    private var points: MutableList<TrackPoint> = mutableListOf()
    private var mode: EditMode = EditMode.NONE
    private var selectedIndices: List<Int> = emptyList()
    private var boundaryIndex: Int? = null
    private var hasEdits = false
    private var trackAlreadyEdited = false
    private var trackCalibrationCoefficient: Double = 1.0

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
        val options = listOf(
            "Move / zoom",
            "Mark bad points",
            "Mark good points",
            "Merge points",
            "Interpolate coordinates",
            "Cut before",
            "Cut after",
            "Split"
        )
        binding.editModeDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options))
        binding.editModeDropdown.setText(options.first(), false)
        binding.editModeDropdown.setOnItemClickListener { _, _, position, _ ->
            mode = when (position) {
                1 -> EditMode.MARK_BAD
                2 -> EditMode.MARK_GOOD
                3 -> EditMode.MERGE_POINTS
                4 -> EditMode.INTERPOLATE_COORDINATES
                5 -> EditMode.CUT_BEFORE
                6 -> EditMode.CUT_AFTER
                7 -> EditMode.SPLIT
                else -> EditMode.NONE
            }
            selectedIndices = emptyList()
            boundaryIndex = null
            refreshUiState()
        }

        binding.btnPrev.setOnClickListener {
            val current = boundaryIndex ?: return@setOnClickListener
            boundaryIndex = (current - 1).coerceAtLeast(0)
            refreshUiState()
        }

        binding.btnNext.setOnClickListener {
            val current = boundaryIndex ?: return@setOnClickListener
            boundaryIndex = (current + 1).coerceAtMost(points.lastIndex)
            refreshUiState()
        }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnApply.setOnClickListener { applyChanges() }
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
            trackAlreadyEdited = loaded.isEdited
            trackCalibrationCoefficient = loaded.cpsToUsvh
                ?: androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@EditTrackActivity)
                    .getString("cps_to_usvh", "1.0")?.toDoubleOrNull()
                ?: 1.0
            fitMapToTrack()
            refreshUiState()
        }
    }

    private fun fitMapToTrack() {
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        if (geoPoints.isEmpty()) return

        binding.mapView.post {
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

    private fun normalizedSelection(): List<Int> {
        if (selectedIndices.isEmpty()) return emptyList()
        return (selectedIndices.minOrNull()!!..selectedIndices.maxOrNull()!!).toList()
    }

    private fun refreshUiState() {
        editOverlay.points = points
        editOverlay.minDose = 0.0
        editOverlay.maxDose = DoseColorScale.clampColorbarMax(points.maxOfOrNull { it.doseRate })
        editOverlay.highlightedIndices = when (mode) {
            EditMode.MARK_BAD, EditMode.MARK_GOOD -> selectedIndices.toSet()
            EditMode.MERGE_POINTS, EditMode.INTERPOLATE_COORDINATES -> normalizedSelection().toSet()
            EditMode.CUT_BEFORE -> (0..(boundaryIndex ?: -1)).toSet()
            EditMode.CUT_AFTER -> boundaryIndex?.let { (it..points.lastIndex).toSet() } ?: emptySet()
            EditMode.SPLIT -> boundaryIndex?.let { setOf(it) } ?: emptySet()
            EditMode.NONE -> emptySet()
        }

        val adjustEnabled = mode == EditMode.CUT_BEFORE || mode == EditMode.CUT_AFTER || mode == EditMode.SPLIT
        binding.adjustButtonsRow.visibility = if (adjustEnabled) View.VISIBLE else View.GONE

        selectionOverlay.selectionEnabled = mode != EditMode.NONE
        binding.descriptionText.setText(descriptionText())
        binding.btnCancel.text = if (!hasEdits) "Cancel" else if (selectedIndices.isEmpty()) "Finish" else "Cancel"
        binding.mapView.invalidate()
    }

    private fun descriptionText(): String {
        return when (mode) {
            EditMode.NONE -> "Move / zoom map"
            EditMode.MARK_BAD -> "Mark ${selectedIndices.size} points as 'bad coordinates'"
            EditMode.MARK_GOOD -> "Mark ${selectedIndices.size} points as 'good coordinates'"
            EditMode.MERGE_POINTS -> {
                val selected = normalizedSelection()
                "Merge ${selected.size} points into one"
            }
            EditMode.INTERPOLATE_COORDINATES -> {
                val selected = normalizedSelection()
                "Interpolate coordinates for ${selected.size} points"
            }
            EditMode.CUT_BEFORE -> {
                val boundary = boundaryIndex ?: return ""
                "Remove first ${boundary + 1} points"
            }
            EditMode.CUT_AFTER -> {
                val boundary = boundaryIndex ?: return ""
                "Remove last ${points.size - boundary} points"
            }
            EditMode.SPLIT -> {
                val split = boundaryIndex ?: return ""
                val firstPart = split + 1
                val secondPart = points.size - firstPart
                "Split into $firstPart and $secondPart points"
            }
        }
    }

    private fun applyChanges() {
        val updatedPoints = points.toMutableList()
        var splitPoints: List<TrackPoint>? = null

        when (mode) {
            EditMode.NONE -> return
            EditMode.MARK_BAD -> {
                if (selectedIndices.isEmpty()) return
                selectedIndices.forEach { idx -> updatedPoints[idx] = updatedPoints[idx].copy(badCoordinates = true) }
            }
            EditMode.MARK_GOOD -> {
                if (selectedIndices.isEmpty()) return
                selectedIndices.forEach { idx -> updatedPoints[idx] = updatedPoints[idx].copy(badCoordinates = false) }
            }
            EditMode.MERGE_POINTS -> {
                val selected = normalizedSelection()
                if (selected.isEmpty()) return
                val source = selected.map { points[it] }
                val totalCounts = source.sumOf { it.counts }
                val totalSeconds = source.sumOf { it.seconds }
                val nonBad = source.filterNot { it.badCoordinates }
                val averagingPool = if (nonBad.isNotEmpty()) nonBad else source

                val merged = source.first().copy(
                    latitude = averagingPool.sumOf { it.latitude } / averagingPool.size,
                    longitude = averagingPool.sumOf { it.longitude } / averagingPool.size,
                    counts = totalCounts,
                    seconds = totalSeconds,
                    doseRate = if (totalSeconds > 0.0) {
                        totalCounts.toDouble() / totalSeconds * trackCalibrationCoefficient
                    } else {
                        0.0
                    },
                    timeMillis = (
                        (source.first().timeMillis - (source.first().seconds * 500.0).toLong()) +
                            (source.last().timeMillis + (source.last().seconds * 500.0).toLong())
                        ) / 2,
                    badCoordinates = nonBad.isEmpty()
                )

                updatedPoints.subList(selected.first(), selected.last() + 1).clear()
                updatedPoints.add(selected.first(), merged)
            }
            EditMode.INTERPOLATE_COORDINATES -> {
                val selected = normalizedSelection()
                if (selected.isEmpty()) return

                var start = selected.first()
                var end = selected.last()
                while (start > 0 && points[start - 1].badCoordinates) start -= 1
                while (end < points.lastIndex && points[end + 1].badCoordinates) end += 1

                if (start == 0 || end == points.lastIndex) {
                    Toast.makeText(this, "Cannot interpolate points at the beginning/end of the track", Toast.LENGTH_SHORT).show()
                    return
                }

                val prev = points[start - 1]
                val next = points[end + 1]
                for (i in start..end) {
                    val ratio = (i - start + 1).toDouble() / (end - start + 2).toDouble()
                    updatedPoints[i] = updatedPoints[i].copy(
                        latitude = prev.latitude + (next.latitude - prev.latitude) * ratio,
                        longitude = prev.longitude + (next.longitude - prev.longitude) * ratio,
                        badCoordinates = false
                    )
                }
                selectedIndices = (start..end).toList()
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

        lifecycleScope.launch {
            binding.btnApply.isEnabled = false
            try {
                val success = withContext(Dispatchers.IO) {
                    if (!hasEdits && !trackAlreadyEdited) {
                        EditableTrackStorage.createRcBackupIfNeeded(trackId)
                    }
                    splitPoints?.let { secondPart ->
                        val result = EditableTrackStorage.createSplitTrack(
                            this@EditTrackActivity,
                            trackId,
                            trackTitle,
                            trackFolder,
                            secondPart,
                            trackCalibrationCoefficient
                        ) ?: return@withContext false
                        TrackCatalog.onTrackSavedById(
                            this@EditTrackActivity,
                            result.newTrackId,
                            result.newTrackTitle,
                            trackFolder,
                            secondPart
                        )
                    }
                    EditableTrackStorage.overwriteTrack(
                        this@EditTrackActivity,
                        trackId,
                        updatedPoints,
                        edited = true,
                        calibrationOverride = trackCalibrationCoefficient
                    )
                    TrackCatalog.onTrackSavedById(this@EditTrackActivity, trackId, trackTitle, trackFolder, updatedPoints)
                    true
                }

                if (!success) {
                    Toast.makeText(this@EditTrackActivity, "Failed to save changes", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                points = updatedPoints
                hasEdits = true
                trackAlreadyEdited = true
                selectedIndices = emptyList()
                boundaryIndex = null
                refreshUiState()
                Toast.makeText(this@EditTrackActivity, "Track updated", Toast.LENGTH_SHORT).show()
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
