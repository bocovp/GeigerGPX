package com.github.bocovp.geigergpx

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object EditableTrackStorage {
    data class LoadResult(val points: List<TrackPoint>)
    data class SplitResult(val newTrackId: String, val newTrackTitle: String)

    fun loadTrack(context: Context, trackId: String): LoadResult? {
        val coeff = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val input = openInputStream(context, trackId) ?: return null
        val points = GpxReader.readTrack(input, cpsCoefficient = coeff) ?: return null
        return LoadResult(points)
    }

    fun overwriteTrack(context: Context, trackId: String, points: List<TrackPoint>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val calibrationCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val output = openOutputStream(context, trackId) ?: return
        output.use { out ->
            out.bufferedWriter().use { writer ->
                GpxWriter.writeTrackXml(writer, points, saveDoseRateInEle, calibrationCoefficient)
            }
        }
    }

    fun createSplitTrack(
        context: Context,
        sourceTrackId: String,
        sourceTitle: String,
        folderName: String?,
        points: List<TrackPoint>
    ): SplitResult? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val calibrationCoefficient = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val nextName = uniqueSplitFileName(context, sourceTitle, folderName)
        val uri = when {
            sourceTrackId.startsWith("file:") -> {
                val sourceFile = File(sourceTrackId.removePrefix("file:"))
                val parentDir = sourceFile.parentFile ?: return null
                val target = File(parentDir, nextName)
                target.outputStream().use { out ->
                    out.bufferedWriter().use { writer ->
                        GpxWriter.writeTrackXml(writer, points, saveDoseRateInEle, calibrationCoefficient)
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
                        GpxWriter.writeTrackXml(writer, points, saveDoseRateInEle, calibrationCoefficient)
                    }
                }
                result.uri ?: return null
            }
            else -> return null
        }
        val trackId = if (uri.scheme == "content") "tree:$uri" else "file:${uri.path}"
        return SplitResult(trackId, nextName)
    }

    private fun uniqueSplitFileName(context: Context, sourceTitle: String, folderName: String?): String {
        val baseName = sourceTitle.removeSuffix(".gpx")
        val existingTitles = TrackCatalog.loadTrackListItems(
            context = context,
            activePoints = emptyList(),
            includeCurrentTrack = false,
            includeMapTracks = false,
            browseFolderName = folderName
        ).map { it.title.lowercase() }.toSet()
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
            trackId.startsWith("tree:") -> context.contentResolver.openInputStream(Uri.parse(trackId.removePrefix("tree:")))
            trackId.startsWith("doc:") -> context.contentResolver.openInputStream(Uri.parse(trackId.removePrefix("doc:")))
            else -> null
        }
    }

    private fun openOutputStream(context: Context, trackId: String): OutputStream? {
        return when {
            trackId.startsWith("file:") -> {
                val file = File(trackId.removePrefix("file:"))
                file.outputStream()
            }
            trackId.startsWith("tree:") -> context.contentResolver.openOutputStream(Uri.parse(trackId.removePrefix("tree:")), "wt")
            trackId.startsWith("doc:") -> context.contentResolver.openOutputStream(Uri.parse(trackId.removePrefix("doc:")), "wt")
            else -> null
        }
    }
}
