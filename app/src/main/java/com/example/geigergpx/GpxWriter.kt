package com.example.geigergpx

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.preference.PreferenceManager
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GpxWriter {

    fun saveTrack(context: Context, points: List<TrackPoint>): File? {
        if (points.isEmpty()) return null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tagType = prefs.getString("dose_tag_type", "ele") ?: "ele"
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileName = "${sdf.format(Date())}.gpx"

        val xml = buildGpx(points, tagType, coeff)

        // Preferred: user-selected folder (SAF), works on Android 12 for root folders too.
        if (!treeUriStr.isNullOrBlank()) {
            val treeUri = Uri.parse(treeUriStr)
            val dirDoc = DocumentFile.fromTreeUri(context, treeUri)
            val outDoc = dirDoc?.createFile("application/gpx+xml", fileName)
            if (outDoc != null) {
                context.contentResolver.openOutputStream(outDoc.uri, "wt")?.use { out ->
                    out.write(xml.toByteArray(Charsets.UTF_8))
                }
                // No direct filesystem path; return null to indicate "saved via SAF"
                return null
            }
        }

        // Fallback: app-specific external Documents folder (always writable).
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        root?.let {
            if (!it.exists()) it.mkdirs()
        }
        val file = File(root, fileName)
        FileOutputStream(file).use { out -> out.write(xml.toByteArray(Charsets.UTF_8)) }
        return file
    }

    private fun buildGpx(points: List<TrackPoint>, tagType: String, coeff: Double): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="GeigerGPX" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("<trk>\n<trkseg>\n")

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        iso.timeZone = java.util.TimeZone.getTimeZone("UTC")

        for (p in points) {
            sb.append(
                """<trkpt lat="${p.latitude}" lon="${p.longitude}">"""
            ).append('\n')
            val timeStr = iso.format(Date(p.timeMillis))
            sb.append("<time>$timeStr</time>\n")
            val outValue = if (coeff == 1.0) p.cps else (p.cps * coeff)
            val doseStr = "%.3f".format(outValue)
            if (tagType == "ele") {
                sb.append("<ele>$doseStr</ele>\n")
            } else {
                sb.append("<cmt>$doseStr</cmt>\n")
            }
            sb.append("</trkpt>\n")
        }

        sb.append("</trkseg>\n</trk>\n</gpx>\n")
        return sb.toString()
    }
}

