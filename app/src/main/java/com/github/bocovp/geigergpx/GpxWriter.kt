package com.github.bocovp.geigergpx

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.preference.PreferenceManager

object GpxWriter {

    private const val BACKUP_FILE_NAME = "Backup.gpx"
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

    data class SaveTrackResult(
        val displayPath: String,
        val sourceId: String,
        val warning: String? = null
    )

    fun saveTrack(context: Context, points: List<TrackPoint>): String? {
        return saveTrackWithResult(context, points)?.displayPath
    }

    fun saveTrackWithResult(context: Context, points: List<TrackPoint>): SaveTrackResult? {
        if (points.isEmpty()) return null
        val fileName = defaultTimestampFileName()
        val result = writeGpxFile(context, points, fileName)
        if (result != null) {
            TrackCatalog.onTrackSaved(context, fileName, points)
        }
        return result
    }

    fun saveBackup(context: Context, points: List<TrackPoint>): String? {
        if (points.isEmpty()) return null
        return writeGpxFile(context, points, BACKUP_FILE_NAME, forceDefaultFolder = true)?.displayPath
    }

    fun backupUri(context: Context): Uri? {
        val file = java.io.File(FileStorageManager.getRootDirectory(context), BACKUP_FILE_NAME)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    fun deleteBackupIfExists(context: Context) {
        runCatching {
            val file = java.io.File(FileStorageManager.getRootDirectory(context), BACKUP_FILE_NAME)
            if (file.exists()) file.delete()
        }
    }

    fun restoreBackupIfPresent(context: Context): String? {
        val backupFile = java.io.File(FileStorageManager.getRootDirectory(context), BACKUP_FILE_NAME)
        if (!backupFile.exists()) return null
        val newName = defaultRestoredFileName()
        return try {
            val writeResult = FileStorageManager.writeStreamDetailed(
                context = context,
                relativePath = newName
            ) { output ->
                backupFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            if (writeResult.uri == null) return null
            if (!backupFile.delete()) {
                // Keep restored file and avoid repeated restores by removing backup best-effort.
                backupFile.deleteOnExit()
            }
            val uri = writeResult.uri ?: return null
            val newTrackId = if (uri.scheme == "content") "tree:$uri" else "file:${uri.path}"
            TrackCatalog.onTrackMoved(context, "file:${backupFile.absolutePath}", newTrackId, null)
            newName
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun defaultTimestampFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        return "${sdf.format(Date())}.gpx"
    }

    private fun defaultRestoredFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        return "${sdf.format(Date())}-restored.gpx"
    }

    private fun writeGpxFile(
        context: Context,
        points: List<TrackPoint>,
        fileName: String,
        forceDefaultFolder: Boolean = false
    ): SaveTrackResult? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val primaryResult = FileStorageManager.writeStreamDetailed(
            context = context,
            relativePath = fileName,
            forceDefaultFolder = forceDefaultFolder
        ) { out ->
            out.bufferedWriter().use { writer ->
                writeXmlToStream(writer, points, saveDoseRateInEle, coeff)
            }
        }
        if (primaryResult.succeeded) {
            val savedUri = primaryResult.uri ?: return null
            return SaveTrackResult(
                displayPath = FileStorageManager.getDisplayPath(context, savedUri),
                sourceId = if (savedUri.scheme == "content") "tree:$savedUri" else "file:${savedUri.path}"
            )
        }
        if (forceDefaultFolder) return null

        val fallbackResult = FileStorageManager.writeStreamDetailed(
            context = context,
            relativePath = fileName,
            forceDefaultFolder = true
        ) { out ->
            out.bufferedWriter().use { writer ->
                writeXmlToStream(writer, points, saveDoseRateInEle, coeff)
            }
        }
        val fallbackUri = fallbackResult.uri ?: return null
        val primaryMessage = primaryResult.error?.localizedMessage ?: "unknown error"
        return SaveTrackResult(
            displayPath = FileStorageManager.getDisplayPath(context, fallbackUri),
            sourceId = if (fallbackUri.scheme == "content") "tree:$fallbackUri" else "file:${fallbackUri.path}",
            warning = "Primary save failed: $primaryMessage. Saved in default app folder."
        )
    }

    private fun writeXmlToStream(
        writer: java.io.BufferedWriter,
        points: List<TrackPoint>,
        saveDoseRateInEle: Boolean,
        coeff: Double
    ) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write("<gpx version=\"1.1\" creator=\"GeigerGPX\" xmlns=\"$GPX_NAMESPACE\" xmlns:rad=\"$RAD_NAMESPACE\">\n")
        writer.write("\t<trk>\n\t\t<trkseg>\n")

        for (p in points) {
            val timeStr = iso.format(Date(p.timeMillis))
            val doseRate = p.cps * coeff
            val doseStr = "%.5f".format(Locale.US, doseRate)
            val secondsStr = "%.3f".format(Locale.US, p.seconds)

            writer.write("\t\t\t<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">\n")
            writer.write("\t\t\t\t<time>$timeStr</time>\n")
            if (p.badCoordinates) {
                writer.write("\t\t\t\t<fix>none</fix>\n")
            }
            if (saveDoseRateInEle) {
                writer.write("\t\t\t\t<ele>$doseStr</ele>\n")
            }
            writer.write("\t\t\t\t<extensions>\n")
            writer.write("\t\t\t\t\t<rad:doseRate>$doseStr</rad:doseRate>\n")
            writer.write("\t\t\t\t\t<rad:counts>${p.counts}</rad:counts>\n")
            writer.write("\t\t\t\t\t<rad:seconds>$secondsStr</rad:seconds>\n")
            writer.write("\t\t\t\t</extensions>\n")
            writer.write("\t\t\t</trkpt>\n")
        }
        writer.write("\t\t</trkseg>\n\t</trk>\n</gpx>\n")
    }
}
