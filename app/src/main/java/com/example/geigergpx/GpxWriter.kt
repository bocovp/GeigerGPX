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
import android.provider.DocumentsContract

object GpxWriter {

    fun saveTrack(context: Context, points: List<TrackPoint>): String? {
        if (points.isEmpty()) return null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tagType = prefs.getString("dose_tag_type", "ele") ?: "ele"
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val fileName = defaultTimestampFileName()
        val xml = buildGpx(points, tagType, coeff)

        return writeGpxFile(context, xml, fileName)
    }

    /**
     * Save a backup copy of the currently recording track.
     *
     * The file name is always "Backup.gpx" and will be written into the
     * configured GPX folder (if any), otherwise into the app-specific
     * documents directory. Existing files with this name are overwritten.
     */
    fun saveBackup(context: Context, points: List<TrackPoint>): String? {
        if (points.isEmpty()) return null

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tagType = prefs.getString("dose_tag_type", "ele") ?: "ele"
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val fileName = "Backup.gpx"
        val xml = buildGpx(points, tagType, coeff)

        return writeGpxFile(context, xml, fileName)
    }

    /**
     * If a stale "Backup.gpx" exists in the currently configured save location (or
     * in the app default directory when no save folder is configured), rename it
     * to the default timestamp-based GPX filename and return that new filename.
     *
     * Returns null if no backup was found/restored.
     */
    fun restoreBackupIfPresent(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        val newName = defaultTimestampFileName()

        // 1) SAF folder, if configured
        if (!treeUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
                val backupDoc = dirDoc.findFile("Backup.gpx") ?: return null

                // Avoid name collision (rare but possible)
                dirDoc.findFile(newName)?.delete()

                // First try an actual rename
                val renamed = backupDoc.renameTo(newName)
                if (renamed) return newName

                // Fallback: copy contents to a newly created file, then delete backup
                val newDoc = dirDoc.createFile("application/gpx+xml", newName) ?: return null
                context.contentResolver.openInputStream(backupDoc.uri)?.use { input ->
                    context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                backupDoc.delete()
                return newName
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        // 2) Fallback: app-specific external Documents folder
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val backupFile = File(root, "Backup.gpx")
        if (!backupFile.exists()) return null

        return try {
            val newFile = File(root, newName)
            if (newFile.exists()) newFile.delete()
            val ok = backupFile.renameTo(newFile)
            if (ok) newName else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun defaultTimestampFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        return "${sdf.format(Date())}.gpx"
    }

    /**
     * Shared implementation that writes the given GPX XML to either the user-selected
     * folder (via SAF tree URI) or the app-specific documents directory as a fallback.
     *
     * When using SAF and a file with the same name already exists, it is deleted first
     * so that the new file effectively overwrites it. For the fallback, the regular
     * File API already overwrites existing files.
     */
    private fun writeGpxFile(
        context: Context,
        xml: String,
        fileName: String,
        treeUriStrOverride: String? = null
    ): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = treeUriStrOverride ?: prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        // 1. Storage Access Framework (SAF)
        if (!treeUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri)

                // If a file with this name already exists, delete it so we can recreate it.
                val existing = dirDoc?.findFile(fileName)
                if (existing != null) {
                    existing.delete()
                }

                val outDoc = dirDoc?.createFile("application/gpx+xml", fileName)

                if (outDoc != null) {
                    context.contentResolver.openOutputStream(outDoc.uri)?.use { out ->
                        out.write(xml.toByteArray(Charsets.UTF_8))
                    }

                    // Get Document ID (e.g., "primary:Documents/MyTracks/file.gpx")
                    val docId = DocumentsContract.getDocumentId(outDoc.uri)
                    // Split by ':' and return the path part (e.g., "Documents/MyTracks/file.gpx")
                    return docId.split(":").getOrElse(1) { docId }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fallback: App-specific external Documents folder
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        if (!root.exists()) root.mkdirs()

        val file = File(root, fileName)
        try {
            file.outputStream().use { out ->
                out.write(xml.toByteArray(Charsets.UTF_8))
            }
            // For consistency, we can return the path relative to the storage root if possible,
            // but usually absolutePath is safest for local files.
            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun buildGpx(points: List<TrackPoint>, tagType: String, coeff: Double): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="GeigerGPX" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')

        sb.append("\t<trk>\n")
        sb.append("\t\t<trkseg>\n")

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        iso.timeZone = java.util.TimeZone.getTimeZone("UTC")

        for (p in points) {
            sb.append("\t\t\t<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">\n")

            val timeStr = iso.format(Date(p.timeMillis))
            sb.append("\t\t\t\t<time>$timeStr</time>\n")

            val outValue = p.cps * coeff
            val doseStr = "%.3f".format(outValue)

            if (tagType == "ele") {
                sb.append("\t\t\t\t<ele>$doseStr</ele>\n")
            } else {
                sb.append("\t\t\t\t<cmt>$doseStr</cmt>\n")
            }

            sb.append("\t\t\t</trkpt>\n")
        }

        sb.append("\t\t</trkseg>\n")
        sb.append("\t</trk>\n")
        sb.append("</gpx>\n")

        return sb.toString()
    }
}

