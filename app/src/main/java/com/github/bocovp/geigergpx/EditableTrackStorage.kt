package com.github.bocovp.geigergpx

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import androidx.core.net.toUri

object EditableTrackStorage {
    data class LoadResult(val points: List<TrackPoint>, val isEdited: Boolean)
    data class SplitResult(val newTrackId: String, val newTrackTitle: String)

    suspend fun loadTrack(context: Context, trackId: String): LoadResult? = withContext(Dispatchers.IO) {
        val coeff = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val input = openInputStream(context, trackId) ?: return@withContext null
        val loaded = GpxReader.readTrackWithMetadata(input, cpsCoefficient = coeff) ?: return@withContext null
        LoadResult(loaded.points, loaded.isEdited)
    }

    suspend fun overwriteTrack(context: Context, trackId: String, points: List<TrackPoint>, edited: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val calibrationCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val output = openOutputStream(context, trackId) ?: return@withContext
        output.use { out ->
            out.bufferedWriter().use { writer ->
                GpxWriter.writeTrackXml(writer, points, saveDoseRateInEle, calibrationCoefficient, edited = edited)
            }
        }
    }

    suspend fun createSplitTrack(
        context: Context,
        sourceTrackId: String,
        sourceTitle: String,
        folderName: String?,
        points: List<TrackPoint>
    ): SplitResult? = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val calibrationCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val nextName = uniqueSplitFileName(context, sourceTitle, folderName)
        val uri = when {
            sourceTrackId.startsWith("file:") -> {
                val sourceFile = File(sourceTrackId.removePrefix("file:"))
                val parentDir = sourceFile.parentFile ?: return@withContext null
                val target = File(parentDir, nextName)
                target.outputStream().use { out ->
                    out.bufferedWriter().use { writer ->
                        GpxWriter.writeTrackXml(writer, points, saveDoseRateInEle, calibrationCoefficient, edited = true)
                    }
                }
                Uri.fromFile(target)
            }
            sourceTrackId.startsWith("tree:") || sourceTrackId.startsWith("doc:") -> {
                val relativePath = folderName?.let { "$it/$nextName" } ?: nextName
                val result = FileStorageManager.writeStreamDetailed(
                    context = context,
                    relativePath = relativePath
                ) { out ->
                    out.bufferedWriter().use { writer ->
                        GpxWriter.writeTrackXml(writer, points, saveDoseRateInEle, calibrationCoefficient, edited = true)
                    }
                }
                result.uri ?: return@withContext null
            }
            else -> return@withContext null
        }
        val trackId = if (uri.scheme == "content") "tree:$uri" else "file:${uri.path}"
        SplitResult(trackId, nextName)
    }

    private suspend fun uniqueSplitFileName(context: Context, sourceTitle: String, folderName: String?): String {
        val baseName = sourceTitle.removeSuffix(".gpx")
        val items = TrackCatalog.loadTrackListItems(
            context = context,
            activePoints = emptyList(),
            includeCurrentTrack = false,
            includeMapTracks = false,
            browseFolderName = folderName
        )
        val existingTitles = items.map { it.title.lowercase() }.toSet()
        var index = 2
        while (true) {
            val candidate = "$baseName-part$index.gpx"
            if (candidate.lowercase() !in existingTitles) return candidate
            index += 1
        }
    }

    private fun openInputStream(context: Context, trackId: String): InputStream? {
        return when {
            trackId.startsWith("file:") -> {
                val file = File(trackId.removePrefix("file:"))
                if (file.exists()) file.inputStream() else null
            }
            trackId.startsWith("tree:") -> context.contentResolver.openInputStream(trackId.removePrefix("tree:").toUri())
            trackId.startsWith("doc:") -> context.contentResolver.openInputStream(trackId.removePrefix("doc:").toUri())
            else -> null
        }
    }

    private fun openOutputStream(context: Context, trackId: String): OutputStream? {
        return when {
            trackId.startsWith("file:") -> {
                val file = File(trackId.removePrefix("file:"))
                file.outputStream()
            }
            trackId.startsWith("tree:") -> context.contentResolver.openOutputStream(trackId.removePrefix("tree:").toUri(), "wt")
            trackId.startsWith("doc:") -> context.contentResolver.openOutputStream(trackId.removePrefix("doc:").toUri(), "wt")
            else -> null
        }
    }
    suspend fun createRcBackupIfNeeded(context: Context, trackId: String) = withContext(Dispatchers.IO) {
        if (!trackId.startsWith("file:")) return@withContext
        val source = File(trackId.removePrefix("file:"))
        if (!source.exists()) return@withContext
        val backup = File(source.parentFile, source.nameWithoutExtension + ".rc")
        if (!backup.exists()) source.copyTo(backup, overwrite = false)
    }
}
