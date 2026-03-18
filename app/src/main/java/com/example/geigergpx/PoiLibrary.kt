package com.example.geigergpx

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val POI_FILE_NAME = "POI.gpx"
private const val POI_BACKUP_FILE_NAME = "POI-Backup.gpx"

data class PoiEntry(
    val id: String,
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val doseRateText: String,
    val description: String
)

object PoiLibrary {

    fun loadPois(context: Context): List<PoiEntry> {
        ensurePoiFileExists(context)
        val xml = readText(context, POI_FILE_NAME) ?: return emptyList()
        return parsePoiEntries(xml)
    }

    fun addPoi(
        context: Context,
        description: String,
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        doseRate: Double,
        delta: Double
    ): Boolean {
        return modifyPoiFile(context) { existing ->
            val list = parsePoiEntries(existing).toMutableList()
            val doseRateText = String.format(Locale.US, "%.3f ± %.3f", doseRate, delta)
            list.add(
                PoiEntry(
                    id = "${timestampMillis}_${latitude}_${longitude}",
                    timestampMillis = timestampMillis,
                    latitude = latitude,
                    longitude = longitude,
                    doseRateText = doseRateText,
                    description = description.ifBlank { "POI" }
                )
            )
            serializePoiEntries(list)
        }
    }

    fun renamePoi(context: Context, poi: PoiEntry, description: String): Boolean {
        val updatedDescription = description.ifBlank { "POI" }
        return modifyPoiFile(context) { existing ->
            val updated = parsePoiEntries(existing).map { entry ->
                if (entry.id == poi.id) {
                    entry.copy(description = updatedDescription)
                } else {
                    entry
                }
            }
            serializePoiEntries(updated)
        }
    }

    fun removePoi(context: Context, poi: PoiEntry): Boolean {
        return modifyPoiFile(context) { existing ->
            val filtered = parsePoiEntries(existing)
                .filterNot { it.id == poi.id }
            serializePoiEntries(filtered)
        }
    }

    private fun modifyPoiFile(context: Context, transform: (String) -> String): Boolean {
        ensurePoiFileExists(context)

        if (!copyFile(context, POI_FILE_NAME, POI_BACKUP_FILE_NAME)) {
            return false
        }

        val original = readText(context, POI_FILE_NAME) ?: emptyPoiXml()
        val updatedXml = transform(original)

        val saved = writeText(context, POI_FILE_NAME, updatedXml)
        return if (saved) {
            deleteFile(context, POI_BACKUP_FILE_NAME)
            true
        } else {
            deleteFile(context, POI_FILE_NAME)
            restoreBackup(context)
            false
        }
    }

    private fun restoreBackup(context: Context): Boolean {
        if (renameFile(context, POI_BACKUP_FILE_NAME, POI_FILE_NAME)) {
            return true
        }
        val copied = copyFile(context, POI_BACKUP_FILE_NAME, POI_FILE_NAME)
        if (copied) {
            deleteFile(context, POI_BACKUP_FILE_NAME)
        }
        return copied
    }

    private fun ensurePoiFileExists(context: Context) {
        if (!exists(context, POI_FILE_NAME)) {
            writeText(context, POI_FILE_NAME, emptyPoiXml())
        }
    }

    private fun parsePoiEntries(xml: String): List<PoiEntry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xml))
        }

        val result = mutableListOf<PoiEntry>()

        var lat = 0.0
        var lon = 0.0
        var time = 0L
        var cmt = ""
        var name = ""
        var insideTrkpt = false
        var currentTag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name.equals("trkpt", ignoreCase = true)) {
                        insideTrkpt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        time = 0L
                        cmt = ""
                        name = ""
                    }
                }

                XmlPullParser.TEXT -> {
                    if (insideTrkpt) {
                        when (currentTag) {
                            "time" -> time = parseIsoTime(parser.text)
                            "cmt" -> cmt = parser.text.orEmpty().trim()
                            "name" -> name = parser.text.orEmpty().trim()
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("trkpt", ignoreCase = true) && insideTrkpt) {
                        val id = "${time}_${lat}_${lon}_${name}_${cmt}"
                        result.add(
                            PoiEntry(
                                id = id,
                                timestampMillis = time,
                                latitude = lat,
                                longitude = lon,
                                doseRateText = cmt,
                                description = name
                            )
                        )
                        insideTrkpt = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return result.sortedByDescending { it.timestampMillis }
    }

    private fun serializePoiEntries(entries: List<PoiEntry>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"GeigerGPX\" xmlns=\"http://www.topografix.com\">\n")
        builder.append("\t<trk>\n\t\t<trkseg>\n")

        entries.sortedBy { it.timestampMillis }.forEach { poi ->
            val timeValue = if (poi.timestampMillis > 0) iso.format(Date(poi.timestampMillis)) else ""
            builder.append("\t\t\t<trkpt lat=\"${poi.latitude}\" lon=\"${poi.longitude}\">\n")
            if (timeValue.isNotBlank()) {
                builder.append("\t\t\t\t<time>${escapeXml(timeValue)}</time>\n")
            }
            builder.append("\t\t\t\t<cmt>${escapeXml(poi.doseRateText)}</cmt>\n")
            builder.append("\t\t\t\t<name>${escapeXml(poi.description)}</name>\n")
            builder.append("\t\t\t</trkpt>\n")
        }

        builder.append("\t\t</trkseg>\n\t</trk>\n</gpx>\n")
        return builder.toString()
    }

    private fun emptyPoiXml(): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="GeigerGPX" xmlns="http://www.topografix.com">
            	<trk>
            		<trkseg>
            		</trkseg>
            	</trk>
            </gpx>
        """.trimIndent() + "\n"
    }

    private fun parseIsoTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(value.trim()).toEpochMilli() }.getOrDefault(0L)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun exists(context: Context, fileName: String): Boolean {
        val treeFile = findDocumentFile(context, fileName)
        if (treeFile != null) {
            return true
        }
        return File(getRootDirectory(context), fileName).exists()
    }

    private fun readText(context: Context, fileName: String): String? {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) {
            return context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() }
        }

        val file = File(getRootDirectory(context), fileName)
        if (!file.exists()) return null
        return file.readText()
    }

    private fun writeText(context: Context, fileName: String, text: String): Boolean {
        val treeUri = configuredTreeUri(context)
        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            root.findFile(fileName)?.delete()
            val outDoc = root.createFile("application/gpx+xml", fileName) ?: return false
            return runCatching {
                context.contentResolver.openOutputStream(outDoc.uri)?.bufferedWriter()?.use { writer ->
                    writer.write(text)
                } ?: return false
                true
            }.getOrDefault(false)
        }

        val file = File(getRootDirectory(context), fileName)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            true
        }.getOrDefault(false)
    }

    private fun deleteFile(context: Context, fileName: String): Boolean {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) {
            return doc.delete()
        }
        val file = File(getRootDirectory(context), fileName)
        return !file.exists() || file.delete()
    }

    private fun renameFile(context: Context, from: String, to: String): Boolean {
        val doc = findDocumentFile(context, from)
        if (doc != null) {
            findDocumentFile(context, to)?.delete()
            return doc.renameTo(to)
        }
        val root = getRootDirectory(context)
        val src = File(root, from)
        val dest = File(root, to)
        if (!src.exists()) return false
        if (dest.exists()) {
            dest.delete()
        }
        return src.renameTo(dest)
    }

    private fun copyFile(context: Context, from: String, to: String): Boolean {
        val content = readText(context, from) ?: return false
        return writeText(context, to, content)
    }

    private fun configuredTreeUri(context: Context): Uri? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)
        return if (treeUriStr.isNullOrBlank()) null else Uri.parse(treeUriStr)
    }

    private fun findDocumentFile(context: Context, name: String): DocumentFile? {
        val treeUri = configuredTreeUri(context) ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return root.findFile(name)
    }

    private fun getRootDirectory(context: Context): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
    }
}
