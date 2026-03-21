package com.example.geigergpx

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val CURRENT_TRACK_ID = "active-track"
private const val CURRENT_TRACK_TITLE = "Currently recording"
private val EXCLUDED_GPX_FILES = setOf("Backup.gpx", "POI.gpx", "POI-Backup.gpx")

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

    private val parsedTrackCache = ConcurrentHashMap<String, CachedParsedTrack>()
    @Volatile private var diskCacheLoaded = false

    fun currentTrackId(): String = CURRENT_TRACK_ID

    fun clearTrackCache(context: Context) {
        synchronized(this) {
            parsedTrackCache.clear()
            diskCacheLoaded = true
            trackCacheFile(context).delete()
        }
    }

    fun isTrackCacheEmpty(context: Context): Boolean {
        if (parsedTrackCache.isNotEmpty()) return false
        val cacheFile = trackCacheFile(context)
        return !cacheFile.exists() || cacheFile.length() == 0L
    }

    fun loadTrackListItems(
        context: Context,
        activePoints: List<TrackPoint>,
        includeCurrentTrack: Boolean
    ): List<TrackListItem> {
        ensureDiskCacheLoaded(context)

        val items = mutableListOf<TrackListItem>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        if (includeCurrentTrack) {
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
        }

        val sourceListing = listTrackFiles(context)
        if (sourceListing is TrackSourceListing.Failure) {
            Log.w("GPX", "Unable to enumerate track files; keeping existing cache", sourceListing.error)
            return items
        }

        val sources = (sourceListing as TrackSourceListing.Success).sources
        val sourceIds = sources.mapTo(mutableSetOf()) { it.id }
        var cacheChanged = removeMissingSources(sourceIds)

        sources.forEach { source ->
            val cached = parsedTrackCache[source.id]
            val parsed = if (cached != null && cached.matches(source)) {
                cached.parsedTrack
            } else {
                try {
                    parseGpxTrack(source.openStream())?.also {
                        if (source.metadataReliable) {
                            val updatedCacheEntry = CachedParsedTrack.from(source, it)
                            if (parsedTrackCache[source.id] != updatedCacheEntry) {
                                parsedTrackCache[source.id] = updatedCacheEntry
                                cacheChanged = true
                            }
                        } else if (parsedTrackCache.remove(source.id) != null) {
                            cacheChanged = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GPX", "Unable to parse track ${source.displayName}", e)
                    null
                }
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

        if (cacheChanged) {
            persistTrackCache(context)
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
            TrackSample(it.latitude, it.longitude, it.cps * coeff, (it.cps*100).toInt(), 100.0)// REVISE: The last two argument are counts and seconds6
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
                            samples.add(TrackSample(lat, lon, ele, (ele*10*100).toInt(), 100.0)) // REVISE: The last two argument are counts and seconds
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


    private data class CachedParsedTrack(
        val sourceId: String,
        val displayName: String,
        val lastModified: Long,
        val sizeBytes: Long,
        val metadataReliable: Boolean,
        val parsedTrack: ParsedTrack
    ) {
        fun matches(source: TrackSource): Boolean {
            if (!metadataReliable || !source.metadataReliable) return false
            return sourceId == source.id &&
                displayName == source.displayName &&
                lastModified == source.lastModified &&
                sizeBytes == source.sizeBytes
        }

        fun toJson(): JSONObject {
            return JSONObject()
                .put("sourceId", sourceId)
                .put("displayName", displayName)
                .put("lastModified", lastModified)
                .put("sizeBytes", sizeBytes)
                .put("metadataReliable", metadataReliable)
                .put("stats", JSONObject()
                    .put("pointCount", parsedTrack.stats.pointCount)
                    .put("durationMillis", parsedTrack.stats.durationMillis)
                    .put("distanceMeters", parsedTrack.stats.distanceMeters))
                .put("samples", JSONArray().apply {
                    parsedTrack.samples.forEach { sample ->
                        put(JSONObject()
                            .put("latitude", sample.latitude)
                            .put("longitude", sample.longitude)
                            .put("doseRate", sample.doseRate)
                            .put("counts", sample.counts)
                            .put("seconds", sample.seconds))
                    }
                })
        }

        companion object {
            fun from(source: TrackSource, parsedTrack: ParsedTrack): CachedParsedTrack {
                return CachedParsedTrack(
                    sourceId = source.id,
                    displayName = source.displayName,
                    lastModified = source.lastModified,
                    sizeBytes = source.sizeBytes,
                    metadataReliable = source.metadataReliable,
                    parsedTrack = parsedTrack
                )
            }

            fun fromJson(json: JSONObject): CachedParsedTrack {
                val statsJson = json.getJSONObject("stats")
                val samplesJson = json.getJSONArray("samples")
                val samples = buildList(samplesJson.length()) {
                    for (i in 0 until samplesJson.length()) {
                        val sampleJson = samplesJson.getJSONObject(i)
                        add(TrackSample(
                            latitude = sampleJson.getDouble("latitude"),
                            longitude = sampleJson.getDouble("longitude"),
                            doseRate = sampleJson.getDouble("doseRate"),
                            counts = sampleJson.getInt("counts"),
                            seconds = sampleJson.getDouble("seconds")
                        ))
                    }
                }
                return CachedParsedTrack(
                    sourceId = json.getString("sourceId"),
                    displayName = json.getString("displayName"),
                    lastModified = json.optLong("lastModified", 0L),
                    sizeBytes = json.optLong("sizeBytes", 0L),
                    metadataReliable = json.optBoolean("metadataReliable", true),
                    parsedTrack = ParsedTrack(
                        samples = samples,
                        stats = TrackStats(
                            pointCount = statsJson.getInt("pointCount"),
                            durationMillis = statsJson.getLong("durationMillis"),
                            distanceMeters = statsJson.getDouble("distanceMeters")
                        )
                    )
                )
            }
        }
    }

    private data class ParsedTrack(
        val samples: List<TrackSample>,
        val stats: TrackStats
    )

    private data class TrackSource(
        val id: String,
        val displayName: String,
        val lastModified: Long,
        val sizeBytes: Long,
        val metadataReliable: Boolean,
        val openStream: () -> InputStream
    )

    private sealed interface TrackSourceListing {
        data class Success(val sources: List<TrackSource>) : TrackSourceListing
        data class Failure(val error: Throwable) : TrackSourceListing
    }

    private fun listTrackFiles(context: Context): TrackSourceListing {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        if (!treeUriStr.isNullOrBlank()) {
            return runCatching {
                val treeUri = Uri.parse(treeUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IllegalStateException("Cannot open GPX tree URI")
                TrackSourceListing.Success(
                    rootDoc.listFiles()
                        .filter { it.isFile && it.name?.endsWith(".gpx", true) == true && it.name !in EXCLUDED_GPX_FILES }
                        .sortedByDescending { it.lastModified() }
                        .mapNotNull { doc ->
                            val name = doc.name ?: return@mapNotNull null
                            val lastModified = doc.lastModified()
                            val sizeBytes = doc.length()
                            TrackSource(
                                id = "doc:${doc.uri}",
                                displayName = name,
                                lastModified = lastModified,
                                sizeBytes = sizeBytes,
                                metadataReliable = lastModified > 0L || sizeBytes > 0L,
                                openStream = { context.contentResolver.openInputStream(doc.uri) ?: throw IllegalStateException("Cannot open $name") }
                            )
                        }
                )
            }.getOrElse { TrackSourceListing.Failure(it) }
        }

        return runCatching {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            TrackSourceListing.Success(
                (root.listFiles() ?: emptyArray())
                    .asSequence()
                    .filter { it.isFile && it.name.endsWith(".gpx", true) && it.name !in EXCLUDED_GPX_FILES }
                    .sortedByDescending { it.lastModified() }
                    .map {
                        TrackSource(
                            id = "file:${it.absolutePath}",
                            displayName = it.name,
                            lastModified = it.lastModified(),
                            sizeBytes = it.length(),
                            metadataReliable = true,
                            openStream = { it.inputStream() }
                        )
                    }
                    .toList()
            )
        }.getOrElse { TrackSourceListing.Failure(it) }
    }


    private fun ensureDiskCacheLoaded(context: Context) {
        if (diskCacheLoaded) return
        synchronized(this) {
            if (diskCacheLoaded) return
            val cacheFile = trackCacheFile(context)
            if (!cacheFile.exists()) {
                diskCacheLoaded = true
                return
            }

            runCatching {
                BufferedReader(cacheFile.reader()).use { reader ->
                    val root = JSONObject(reader.readText())
                    val entries = root.optJSONArray("entries") ?: JSONArray()
                    parsedTrackCache.clear()
                    for (i in 0 until entries.length()) {
                        val cached = CachedParsedTrack.fromJson(entries.getJSONObject(i))
                        parsedTrackCache[cached.sourceId] = cached
                    }
                }
            }.onFailure {
                Log.w("GPX", "Unable to restore persisted track cache", it)
                parsedTrackCache.clear()
                cacheFile.delete()
            }
            diskCacheLoaded = true
        }
    }

    private fun persistTrackCache(context: Context) {
        synchronized(this) {
            val cacheFile = trackCacheFile(context)
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val json = JSONObject().put("entries", JSONArray().apply {
                    parsedTrackCache.values
                        .sortedBy { it.sourceId }
                        .forEach { put(it.toJson()) }
                })
                BufferedWriter(cacheFile.writer()).use { writer ->
                    writer.write(json.toString())
                }
            }.onFailure {
                Log.w("GPX", "Unable to persist track cache", it)
            }
        }
    }

    private fun removeMissingSources(sourceIds: Set<String>): Boolean {
        val missingIds = parsedTrackCache.keys.filterNot { it in sourceIds }
        if (missingIds.isEmpty()) return false
        missingIds.forEach(parsedTrackCache::remove)
        return true
    }

    private fun trackCacheFile(context: Context): File {
        return File(context.filesDir, "track_catalog_cache.json")
    }

}
