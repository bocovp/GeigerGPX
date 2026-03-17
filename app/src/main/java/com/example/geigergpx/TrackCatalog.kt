package com.example.geigergpx

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val CURRENT_TRACK_ID = "active-track"
private const val CURRENT_TRACK_TITLE = "Current recording"

data class TrackStats(
    val pointCount: Int,
    val durationMillis: Long,
    val distanceMeters: Double
)

data class TrackListItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val mapTrack: MapTrack,
    val isCurrentTrack: Boolean,
    val defaultVisible: Boolean
)

object TrackCatalog {

    private val parsedTrackCache = ConcurrentHashMap<String, ParsedTrack>()

    fun currentTrackId(): String = CURRENT_TRACK_ID

    fun clearTrackCache() {
        parsedTrackCache.clear()
    }

    fun loadTrackListItems(context: Context, activePoints: List<TrackPoint>): List<TrackListItem> {
        val items = mutableListOf<TrackListItem>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val currentTrack = currentTrackData(activePoints, coeff)
        items.add(
            TrackListItem(
                id = CURRENT_TRACK_ID,
                title = CURRENT_TRACK_TITLE,
                subtitle = formatStats(currentTrack.stats),
                mapTrack = MapTrack(CURRENT_TRACK_ID, CURRENT_TRACK_TITLE, currentTrack.samples),
                isCurrentTrack = true,
                defaultVisible = true
            )
        )

        runCatching { listTrackFiles(context) }
            .getOrDefault(emptyList())
            .forEach { source ->
                val parsed = parsedTrackCache[source.displayName] ?: try {
                    parseGpxTrack(source.openStream())?.also {
                        parsedTrackCache[source.displayName] = it
                    }
                } catch (e: Exception) {
                    Log.e("GPX", "Unable to parse track ${source.displayName}", e)
                    null
                } ?: return@forEach
                items.add(
                    TrackListItem(
                        id = source.id,
                        title = source.displayName,
                        subtitle = formatStats(parsed.stats),
                        mapTrack = MapTrack(source.id, source.displayName, parsed.samples),
                        isCurrentTrack = false,
                        defaultVisible = false
                    )
                )
            }

        return items
    }

    private data class CurrentTrackData(
        val samples: List<TrackSample>,
        val stats: TrackStats
    )

    private fun currentTrackData(
        activePoints: List<TrackPoint>,
        coeff: Double
    ): CurrentTrackData {
        val currentPoints = if (activePoints.isNotEmpty()) {
            activePoints
        } else {
            TrackingService.activeTrackPointsSnapshot()
        }

        if (currentPoints.isEmpty()) {
            return CurrentTrackData(emptyList(), TrackStats(0, 0L, 0.0))
        }

        val samples = currentPoints.map {
            TrackSample(it.latitude, it.longitude, it.cps * coeff)
        }
        return CurrentTrackData(samples, statsFromTrackPoints(currentPoints))
    }

    private fun statsFromTrackPoints(points: List<TrackPoint>): TrackStats {
        if (points.isEmpty()) return TrackStats(0, 0L, 0.0)
        var distance = 0.0
        for (i in 1 until points.size) {
            distance += distanceBetween(
                points[i - 1].latitude,
                points[i - 1].longitude,
                points[i].latitude,
                points[i].longitude
            )
        }
        val duration = (points.last().timeMillis - points.first().timeMillis).coerceAtLeast(0L)
        return TrackStats(points.size, duration, distance)
    }

    private fun parseGpxTrack(inputStream: InputStream): ParsedTrack? {
        inputStream.use { stream ->
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(stream, null)
            }

            val samples = mutableListOf<TrackSample>()
            val timestamps = mutableListOf<Long>()

            var lat = 0.0
            var lon = 0.0
            var ele = 0.0
            var timeMs = 0L
            var insideTrkpt = false
            var currentTag: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {

                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name
                        currentTag = parser.name
                        if (tagName.equals("trkpt", ignoreCase = true)) {
                            insideTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            ele = 0.0
                            timeMs = 0L
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (insideTrkpt) {
                            when (currentTag) {
                                "ele" -> ele = parser.text?.toDoubleOrNull() ?: 0.0
                                "time" -> timeMs = parseIsoTime(parser.text)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt" && insideTrkpt) {
                            samples.add(TrackSample(lat, lon, ele))
                            if (timeMs > 0L) timestamps.add(timeMs)
                            insideTrkpt = false
                        }
                        currentTag = null
                    }
                }
                parser.next()
            }

            if (samples.isEmpty()) return null
            var distance = 0.0
            for (i in 1 until samples.size) {
                distance += distanceBetween(
                    samples[i - 1].latitude,
                    samples[i - 1].longitude,
                    samples[i].latitude,
                    samples[i].longitude
                )
            }
            val duration = if (timestamps.size >= 2) {
                (timestamps.last() - timestamps.first()).coerceAtLeast(0L)
            } else {
                0L
            }

            return ParsedTrack(samples, TrackStats(samples.size, duration, distance))
        }
    }

    private fun parseIsoTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value.trim()).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun formatStats(stats: TrackStats): String {
        val durationSeconds = stats.durationMillis / 1000L
        val hh = durationSeconds / 3600
        val mm = (durationSeconds % 3600) / 60
        val ss = durationSeconds % 60
        val durationText = String.format("%02d:%02d:%02d", hh, mm, ss)
        return "${stats.pointCount} points · $durationText · %.1f m".format(stats.distanceMeters)
    }

    private data class ParsedTrack(
        val samples: List<TrackSample>,
        val stats: TrackStats
    )

    private data class TrackSource(
        val id: String,
        val displayName: String,
        val openStream: () -> InputStream
    )

    private fun listTrackFiles(context: Context): List<TrackSource> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        if (!treeUriStr.isNullOrBlank()) {
            val treeFiles = runCatching {
                val treeUri = Uri.parse(treeUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching emptyList()
                rootDoc.listFiles()
                    .filter { it.isFile && it.name?.endsWith(".gpx", true) == true && it.name != "Backup.gpx" }
                    .sortedByDescending { it.lastModified() }
                    .mapNotNull { doc ->
                        val name = doc.name ?: return@mapNotNull null
                        TrackSource(
                            id = "doc:${doc.uri}",
                            displayName = name,
                            openStream = { context.contentResolver.openInputStream(doc.uri) ?: throw IllegalStateException("Cannot open $name") }
                        )
                    }
            }.getOrElse { emptyList() }

            if (treeFiles.isNotEmpty()) {
                return treeFiles
            }
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return (root.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".gpx", true) && it.name != "Backup.gpx" }
            .sortedByDescending { it.lastModified() }
            .map {
                TrackSource(
                    id = "file:${it.absolutePath}",
                    displayName = it.name,
                    openStream = { it.inputStream() }
                )
            }
            .toList()
    }
}
