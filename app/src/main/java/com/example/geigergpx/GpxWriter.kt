package com.example.geigergpx

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

    fun saveTrack(context: Context, points: List<TrackPoint>): String? {
        if (points.isEmpty()) return null
        return writeGpxFile(context, points, defaultTimestampFileName())
    }

    fun saveBackup(context: Context, points: List<TrackPoint>): String? {
        if (points.isEmpty()) return null
        return writeGpxFile(context, points, BACKUP_FILE_NAME)
    }

    fun backupUri(context: Context): Uri? = FileStorageManager.getFileUri(context, BACKUP_FILE_NAME)

    fun deleteBackupIfExists(context: Context) {
        runCatching { FileStorageManager.deleteFile(context, BACKUP_FILE_NAME) }
    }

    fun restoreBackupIfPresent(context: Context): String? {
        if (!FileStorageManager.exists(context, BACKUP_FILE_NAME)) return null
        val newName = defaultRestoredFileName()
        return try {
            if (FileStorageManager.moveFile(context, BACKUP_FILE_NAME, newName)) newName else null
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

    private fun writeGpxFile(context: Context, points: List<TrackPoint>, fileName: String): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        return try {
            val savedUri = FileStorageManager.writeStream(context, fileName) { out ->
                out.bufferedWriter().use { writer ->
                    writeXmlToStream(writer, points, saveDoseRateInEle, coeff)
                }
            } ?: return null
            FileStorageManager.getDisplayPath(context, savedUri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
