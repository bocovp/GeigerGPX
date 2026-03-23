package com.example.geigergpx

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val POI_FILE_NAME = "POI.gpx"
private const val POI_BACKUP_FILE_NAME = "POI-Backup.gpx"
private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

data class PoiEntry(
    val id: String,
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val doseRate: Double,
    val counts: Int,
    val seconds: Double,
    val description: String
)

object PoiLibrary {

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

        val xml = FileStorageManager.readText(context, POI_FILE_NAME).orEmpty()
        if (xml.isBlank()) {
            return LoadResult(emptyList(), LoadState.EMPTY_LIBRARY)
        }

        val entries = parsePoiEntries(xml)
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
        seconds: Double
    ): Boolean {
        return modifyPoiFile(context) { existing ->
            val list = parsePoiEntries(existing).toMutableList()
            list.add(
                PoiEntry(
                    id = "${timestampMillis}_${latitude}_${longitude}",
                    timestampMillis = timestampMillis,
                    latitude = latitude,
                    longitude = longitude,
                    doseRate = doseRate,
                    counts = counts,
                    seconds = seconds,
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

        val updated = FileStorageManager.transactionalWrite(
            context = context,
            targetFile = POI_FILE_NAME,
            backupFile = POI_BACKUP_FILE_NAME,
            transform = transform
        )

        return updated
    }

    private fun ensurePoiFileExists(context: Context) {
        FileStorageManager.ensureFileExists(context, POI_FILE_NAME, emptyPoiXml())
    }

    private fun parsePoiEntries(xml: String): List<PoiEntry> {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser().apply {
            setInput(StringReader(xml))
        }

        val result = mutableListOf<PoiEntry>()

        var lat = 0.0
        var lon = 0.0
        var time = 0L
        var doseRate = 0.0
        var counts = 0
        var seconds = 0.0
        var name = ""
        var insideWpt = false
        var currentTag: String? = null
        var currentNamespace: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    currentNamespace = parser.namespace
                    if (parser.name.equals("wpt", ignoreCase = true)) {
                        insideWpt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        time = 0L
                        doseRate = 0.0
                        counts = 0
                        seconds = 0.0
                        name = ""
                    }
                }

                XmlPullParser.TEXT -> {
                    if (insideWpt) {
                        when {
                            currentTag == "time" -> time = parseIsoTime(parser.text)
                            currentTag == "name" -> name = parser.text.orEmpty().trim()
                            currentNamespace == RAD_NAMESPACE && currentTag == "doseRate" -> {
                                doseRate = parser.text?.trim()?.toDoubleOrNull() ?: 0.0
                            }
                            currentNamespace == RAD_NAMESPACE && currentTag == "counts" -> {
                                counts = parser.text?.trim()?.toIntOrNull() ?: 0
                            }
                            currentNamespace == RAD_NAMESPACE && currentTag == "seconds" -> {
                                seconds = parser.text?.trim()?.toDoubleOrNull() ?: 0.0
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("wpt", ignoreCase = true) && insideWpt) {
                        val id = "${time}_${lat}_${lon}_${name}_${counts}_${seconds}"
                        result.add(
                            PoiEntry(
                                id = id,
                                timestampMillis = time,
                                latitude = lat,
                                longitude = lon,
                                doseRate = doseRate,
                                counts = counts,
                                seconds = seconds,
                                description = name.ifBlank { "POI" }
                            )
                        )
                        insideWpt = false
                    }
                    currentTag = null
                    currentNamespace = null
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
        builder.append("<gpx version=\"1.1\" creator=\"GeigerGPX\" xmlns=\"$GPX_NAMESPACE\" xmlns:rad=\"$RAD_NAMESPACE\">\n")

        entries.sortedBy { it.timestampMillis }.forEach { poi ->
            val timeValue = if (poi.timestampMillis > 0) iso.format(Date(poi.timestampMillis)) else ""
            builder.append("\t<wpt lat=\"${poi.latitude}\" lon=\"${poi.longitude}\">\n")
            builder.append("\t\t<name>${escapeXml(poi.description)}</name>\n")
            if (timeValue.isNotBlank()) {
                builder.append("\t\t<time>${escapeXml(timeValue)}</time>\n")
            }
            builder.append("\t\t<extensions>\n")
            builder.append("\t\t\t<rad:doseRate>${"%.5f".format(Locale.US, poi.doseRate)}</rad:doseRate>\n")
            builder.append("\t\t\t<rad:counts>${poi.counts}</rad:counts>\n")
            builder.append("\t\t\t<rad:seconds>${"%.3f".format(Locale.US, poi.seconds)}</rad:seconds>\n")
            builder.append("\t\t</extensions>\n")
            builder.append("\t</wpt>\n")
        }

        builder.append("</gpx>\n")
        return builder.toString()
    }

    private fun emptyPoiXml(): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"GeigerGPX\" xmlns=\"$GPX_NAMESPACE\" xmlns:rad=\"$RAD_NAMESPACE\">\n" +
            "</gpx>\n"
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

}
