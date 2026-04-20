package com.github.bocovp.geigergpx

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object EditableTrackStorage {
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

    data class LoadResult(val points: List<TrackPoint>)
    data class SplitResult(val newTrackId: String, val newTrackTitle: String)

    fun loadTrack(context: Context, trackId: String): LoadResult? {
        val input = openInputStream(context, trackId) ?: return null
        return parseTrack(input)?.let { LoadResult(it) }
    }

    fun overwriteTrack(context: Context, trackId: String, points: List<TrackPoint>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val saveDoseRateInEle = prefs.getBoolean("save_dose_rate_in_ele", false)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val output = openOutputStream(context, trackId) ?: return
        output.use { out ->
            out.bufferedWriter().use { writer ->
                writeTrack(writer, points, saveDoseRateInEle, coeff)
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
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val nextName = uniqueSplitFileName(context, sourceTitle, folderName)
        val uri = when {
            sourceTrackId.startsWith("file:") -> {
                val sourceFile = File(sourceTrackId.removePrefix("file:"))
                val parentDir = sourceFile.parentFile ?: return null
                val target = File(parentDir, nextName)
                target.outputStream().use { out ->
                    out.bufferedWriter().use { writer ->
                        writeTrack(writer, points, saveDoseRateInEle, coeff)
                    }
                }
                Uri.fromFile(target)
            }
            sourceTrackId.startsWith("tree:") || sourceTrackId.startsWith("doc:") -> {
                val root = currentTreeRoot(context) ?: return null
                val rootDoc = DocumentFile.fromTreeUri(context, root) ?: return null
                val targetDir = folderName?.let {
                    rootDoc.findFile(it) ?: rootDoc.createDirectory(it)
                } ?: rootDoc
                val created = targetDir?.createFile("application/gpx+xml", nextName) ?: return null
                context.contentResolver.openOutputStream(created.uri)?.use { out ->
                    out.bufferedWriter().use { writer ->
                        writeTrack(writer, points, saveDoseRateInEle, coeff)
                    }
                } ?: return null
                created.uri
            }
            else -> return null
        }
        val trackId = if (uri.scheme == "content") "tree:$uri" else "file:${uri.path}"
        return SplitResult(trackId, nextName)
    }

    private fun currentTreeRoot(context: Context): Uri? {
        val value = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SettingsFragment.KEY_GPX_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Uri.parse(value)
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

    private fun parseTrack(inputStream: InputStream): List<TrackPoint>? {
        inputStream.use { stream ->
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
                .newPullParser().apply { setInput(stream, null) }

            val points = mutableListOf<TrackPoint>()
            var lat = 0.0
            var lon = 0.0
            var counts = 0
            var seconds = 0.0
            var timeMs = 0L
            var bad = false
            var insideTrkpt = false
            var currentTag: String? = null
            var currentNs: String? = null
            var storedDoseRate = 0.0

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        currentNs = parser.namespace
                        if (parser.name == "trkpt") {
                            insideTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            counts = 0
                            seconds = 0.0
                            timeMs = 0L
                            bad = false
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (!insideTrkpt) {
                            parser.next()
                            continue
                        }
                        val value = parser.text?.trim().orEmpty()
                        when {
                            currentTag == "time" -> timeMs = parseIso(value)
                            currentTag == "fix" && value.equals("none", true) -> bad = true
                            currentTag == "badCoordinates" && currentNs == RAD_NAMESPACE -> bad = true
                            currentTag == "doseRate" && currentNs == RAD_NAMESPACE -> storedDoseRate = value.toDoubleOrNull() ?: 0.0
                            currentTag == "counts" && currentNs == RAD_NAMESPACE -> counts = value.toIntOrNull() ?: 0
                            currentTag == "seconds" && currentNs == RAD_NAMESPACE -> seconds = value.toDoubleOrNull() ?: 0.0
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt" && insideTrkpt) {
                            val cpsValue = when {
                                seconds > 0.00001 -> counts / seconds
                                storedDoseRate > 0.0 -> storedDoseRate
                                else -> 0.0
                            }
                            points.add(
                                TrackPoint(
                                    latitude = lat,
                                    longitude = lon,
                                    timeMillis = timeMs,
                                    distanceFromLast = 0.0,
                                    cps = cpsValue,
                                    counts = counts,
                                    seconds = seconds,
                                    badCoordinates = bad
                                )
                            )
                            insideTrkpt = false
                        }
                        currentTag = null
                        currentNs = null
                    }
                }
                parser.next()
            }
            return points
        }
    }

    private fun writeTrack(
        writer: java.io.BufferedWriter,
        points: List<TrackPoint>,
        saveDoseRateInEle: Boolean,
        coeff: Double
    ) {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write("<gpx version=\"1.1\" creator=\"GeigerGPX\" xmlns=\"$GPX_NAMESPACE\" xmlns:rad=\"$RAD_NAMESPACE\">\n")
        writer.write("\t<trk>\n\t\t<trkseg>\n")

        points.forEach { p ->
            val ts = if (p.timeMillis > 0L) DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(p.timeMillis)) else ""
            val doseRate = p.cps * coeff
            
            val latStr = "%.8f".format(Locale.US, p.latitude)
            val lonStr = "%.8f".format(Locale.US, p.longitude)
            val doseStr = "%.5f".format(Locale.US, doseRate)
            val secondsStr = "%.3f".format(Locale.US, p.seconds)

            writer.write("\t\t\t<trkpt lat=\"$latStr\" lon=\"$lonStr\">\n")
            if (ts.isNotEmpty()) writer.write("\t\t\t\t<time>$ts</time>\n")
            if (p.badCoordinates) writer.write("\t\t\t\t<fix>none</fix>\n")
            if (saveDoseRateInEle) writer.write("\t\t\t\t<ele>$doseStr</ele>\n")
            
            writer.write("\t\t\t\t<extensions>\n")
            writer.write("\t\t\t\t\t<rad:doseRate>$doseStr</rad:doseRate>\n")
            writer.write("\t\t\t\t\t<rad:counts>${p.counts}</rad:counts>\n")
            writer.write("\t\t\t\t\t<rad:seconds>$secondsStr</rad:seconds>\n")
            writer.write("\t\t\t\t</extensions>\n")
            writer.write("\t\t\t</trkpt>\n")
        }

        writer.write("\t\t</trkseg>\n\t</trk>\n</gpx>\n")
    }

    private fun parseIso(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }
}
