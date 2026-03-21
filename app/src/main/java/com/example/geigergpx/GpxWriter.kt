package com.example.geigergpx

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.preference.PreferenceManager
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.DocumentsContract

object GpxWriter {

    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

    fun saveTrack(context: Context, points: List<TrackPoint>): String? {
        if (points.isEmpty()) return null
        return writeGpxFile(context, points, defaultTimestampFileName())
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
        // Force overwrite for backup to keep only the latest state
        return writeGpxFile(context, points, "Backup.gpx")
    }

    fun backupUri(context: Context): Uri? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        // 1) SAF folder, if configured
        if (!treeUriStr.isNullOrBlank()) {
            return try {
                val treeUri = Uri.parse(treeUriStr)
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri)
                dirDoc?.findFile("Backup.gpx")?.uri
            } catch (_: Exception) {
                null
            }
        }

        // 2) Fallback: app-specific external Documents folder
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val backupFile = File(root, "Backup.gpx")
        return if (backupFile.exists()) Uri.fromFile(backupFile) else null
    }

    /**
     * Delete the Backup.gpx file (if it exists) in either the configured GPX folder
     * or the app-specific documents directory.
     */
    fun deleteBackupIfExists(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        // 1) SAF folder, if configured
        if (!treeUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(treeUriStr)
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri)
                dirDoc?.findFile("Backup.gpx")?.delete()
            } catch (_: Exception) {
                // Ignore delete errors; this is best-effort cleanup
            }
        } else {
            // 2) Fallback: app-specific external Documents folder
            try {
                val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
                val backupFile = File(root, "Backup.gpx")
                if (backupFile.exists()) {
                    backupFile.delete()
                }
            } catch (_: Exception) {
                // Ignore delete errors; this is best-effort cleanup
            }
        }
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

        // Use a distinct name for restored files to make their origin clear
        val newName = defaultRestoredFileName()

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

    private fun defaultRestoredFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        return "${sdf.format(Date())}-restored.gpx"
    }

    /**
     * Shared implementation that writes the given GPX XML to either the user-selected
     * folder (via SAF tree URI) or the app-specific documents directory as a fallback.
     *
     * When using SAF and a file with the same name already exists, it is deleted first
     * so that the new file effectively overwrites it. For the fallback, the regular
     * File API already overwrites existing files.
     */
    private fun writeGpxFile(context: Context, points: List<TrackPoint>, fileName: String): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        try {
            if (!treeUriStr.isNullOrBlank()) {
                // --- SAF LOGIC ---
                val treeUri = Uri.parse(treeUriStr)
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri) ?: throw Exception("Folder access failed")

                // Delete existing to prevent "(1)" duplicates
                dirDoc.findFile(fileName)?.delete()
                val outDoc = dirDoc.createFile("application/gpx+xml", fileName) ?: throw Exception("File creation failed")

                context.contentResolver.openOutputStream(outDoc.uri)?.use { out ->
                    out.bufferedWriter().use { writer ->
                        writeXmlToStream(writer, points, saveDoseRateInEle, coeff)
                    }
                }

                // SUCCESS: Return the formatted path like "Documents/MyTracks/file.gpx"
                val docId = DocumentsContract.getDocumentId(outDoc.uri)
                return docId.split(":").getOrElse(1) { docId }

            } else {
                // --- FALLBACK LOGIC ---
                val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
                if (!root.exists()) root.mkdirs()
                val file = File(root, fileName)

                file.outputStream().use { out ->
                    out.bufferedWriter().use { writer ->
                        writeXmlToStream(writer, points, saveDoseRateInEle, coeff)
                    }
                }
                // Return absolute path for fallback files
                return file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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
