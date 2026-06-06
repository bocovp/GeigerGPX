package com.github.bocovp.geigergpx

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val POI_FILE_NAME = "POI.gpx"
private const val POI_BACKUP_FILE_NAME = "POI-Backup.gpx"

private val ISO_INSTANT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
data class PoiEntry(
    val id: String,
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val doseRate: Double,
    val counts: Int,
    val seconds: Double,
    val description: String,
    val sensitivity: Double = if (counts > 0 && seconds > 0.000001 && doseRate > 0.0) {
        (counts / seconds) / doseRate
    } else {
        RadiationCalibration.DEFAULT_SENSITIVITY
    }
)

fun buildPoiId(timestampMillis: Long, latitude: Double, longitude: Double): String {
    // Using the same str formatters as in GpxWriter
    val timeStr = ISO_INSTANT_FORMATTER.format(Instant.ofEpochMilli(timestampMillis))
    val latStr = "%.8f".format(Locale.US, latitude)
    val lonStr = "%.8f".format(Locale.US, longitude)
    return "${timeStr}_${latStr}_${lonStr}"
}

object PoiLibrary {
    const val PREF_MAP_VISIBLE_POI_IDS = "map_visible_poi_ids"
    const val PREF_MAP_VISIBLE_POI_IDS_INITIALIZED = "map_visible_poi_ids_initialized"

    data class SaveResult(
        val success: Boolean,
        val warning: String? = null,
        val error: String? = null
    )

    enum class LoadState {
        MISSING_FILE,
        EMPTY_LIBRARY,
        HAS_POIS
    }

    data class LoadResult(
        val entries: List<PoiEntry>,
        val state: LoadState
    )

    fun loadPoiLibrary(context: Context): LoadResult {
        if (!FileStorageManager.exists(context, POI_FILE_NAME)) {
            return LoadResult(emptyList(), LoadState.MISSING_FILE)
        }

        val entries = FileStorageManager.readStream(context, POI_FILE_NAME)
            ?.use { GpxReader.readPois(it) }
            .orEmpty()
        val state = if (entries.isEmpty()) LoadState.EMPTY_LIBRARY else LoadState.HAS_POIS
        return LoadResult(entries, state)
    }

    fun loadPois(context: Context): List<PoiEntry> = loadPoiLibrary(context).entries

    fun addPoi(
        context: Context,
        description: String,
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        doseRate: Double,
        counts: Int,
        seconds: Double,
        selected: Boolean = true
    ): Boolean {
        return addPoiWithResult(context, description, timestampMillis, latitude, longitude, doseRate, counts, seconds, selected).success
    }

    fun addPoiWithResult(
        context: Context,
        description: String,
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        doseRate: Double,
        counts: Int,
        seconds: Double,
        selected: Boolean = true
    ): SaveResult {
        val poiId = buildPoiId(timestampMillis, latitude, longitude)
        val result = modifyPoiFile(context) { list ->
            list + PoiEntry(
                id = poiId,
                timestampMillis = timestampMillis,
                latitude = latitude,
                longitude = longitude,
                doseRate = doseRate,
                counts = counts,
                seconds = seconds,
                description = description.ifBlank { "POI" }
            )
        }
        if (result.success && selected) {
            selectPoi(context, poiId)
        }
        return result
    }




    fun selectedPoiIds(context: Context): Set<String> {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_MAP_VISIBLE_POI_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun ensurePoiSelectionInitialized(context: Context, allPoiIds: Set<String>): Set<String> {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val initialized = prefs.getBoolean(PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, false)
        if (!initialized) {
            prefs.edit {
                putBoolean(PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, true)
                putStringSet(PREF_MAP_VISIBLE_POI_IDS, emptySet())
            }
            return emptySet()
        }

        val selected = selectedPoiIds(context)
        val sanitized = selected.intersect(allPoiIds)
        if (sanitized != selected) {
            prefs.edit { putStringSet(PREF_MAP_VISIBLE_POI_IDS, sanitized) }
        }
        return sanitized
    }

    fun setPoiSelected(context: Context, poiId: String, selected: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val selectedIds = selectedPoiIds(context).toMutableSet()
        if (selected) selectedIds.add(poiId) else selectedIds.remove(poiId)
        prefs.edit {
            putBoolean(PREF_MAP_VISIBLE_POI_IDS_INITIALIZED, true)
            putStringSet(PREF_MAP_VISIBLE_POI_IDS, selectedIds)
        }
    }

    fun selectPoi(context: Context, poiId: String) {
        setPoiSelected(context, poiId, selected = true)
    }

    fun renamePoi(context: Context, poi: PoiEntry, description: String): Boolean {
        val updatedDescription = description.ifBlank { "POI" }
        return modifyPoiFile(context) { list ->
            list.map { entry ->
                if (entry.id == poi.id) {
                    entry.copy(description = updatedDescription)
                } else {
                    entry
                }
            }
        }.success
    }

    fun removePoi(context: Context, poi: PoiEntry): Boolean {
        return modifyPoiFile(context) { list ->
            list.filterNot { it.id == poi.id }
        }.success
    }

    private fun modifyPoiFile(
        context: Context,
        transform: (List<PoiEntry>) -> List<PoiEntry>
    ): SaveResult {
        ensurePoiFileExists(context)

        val updated = FileStorageManager.transactionalWrite(
            context = context,
            targetFile = POI_FILE_NAME,
            backupFile = POI_BACKUP_FILE_NAME
        ) { original ->
            val parsed = GpxReader.readPois(original)
            GpxWriter.serializePoiEntries(transform(parsed))
        }
        if (updated) return SaveResult(success = true)

        val defaultRoot = FileStorageManager.getRootDirectory(context)
        val targetFile = java.io.File(defaultRoot, POI_FILE_NAME)
        val backupFile = java.io.File(defaultRoot, POI_BACKUP_FILE_NAME)
        return try {
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists()) {
                targetFile.writeText(GpxWriter.emptyPoiXml())
            }
            val original = targetFile.readText()
            backupFile.writeText(original)
            val transformed = GpxWriter.serializePoiEntries(transform(GpxReader.readPois(original)))
            targetFile.writeText(transformed)
            if (backupFile.exists()) {
                backupFile.delete()
            }
            SaveResult(
                success = true,
                warning = "Primary POI save failed. Saved in default app folder."
            )
        } catch (e: Exception) {
            SaveResult(
                success = false,
                warning = "Primary POI save failed. Fallback also failed.",
                error = e.localizedMessage ?: e.toString()
            )
        }
    }

    private fun ensurePoiFileExists(context: Context) {
        FileStorageManager.ensureFileExists(context, POI_FILE_NAME, GpxWriter.emptyPoiXml())
    }
}
