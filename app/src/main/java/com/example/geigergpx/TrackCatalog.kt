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
private val EXCLUDED_GPX_FILE_SET = setOf("Backup.gpx", "POI.gpx", "POI-Backup.gpx")
private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

data class TrackStats(
    val pointCount: Int,
    val durationMillis: Long,
    val distanceMeters: Double
)

data class TrackListItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val mapTrack: MapTrack?,
    val isCurrentTrack: Boolean,
    val defaultVisible: Boolean,
    val itemType: TrackListItemType = TrackListItemType.TRACK,
    val folderName: String? = null
)

enum class TrackListItemType {
    TRACK,
    FOLDER
}

object TrackCatalog {

    private val parsedTrackCache = ConcurrentHashMap<String, CachedParsedTrack>()
    @Volatile private var diskCacheLoaded = false

    fun currentTrackId(): String = CURRENT_TRACK_ID

    fun clearTrackCache(context: Context) {
        synchronized(this) {
            parsedTrackCache.clear()
            diskCacheLoaded = true
            val cacheFile = trackCacheFile(context)
            if (cacheFile.exists() && !cacheFile.delete()) {
                Log.w("GPX", "Unable to delete persisted track cache ${cacheFile.absolutePath}")
            }
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
        includeCurrentTrack: Boolean,
        includeMapTracks: Boolean = true,
        mapTrackIds: Set<String>? = null,
        browseFolderName: String? = null,
        includeSubfolderTracks: Boolean = false,
        includeFolderEntries: Boolean = false
    ): List<TrackListItem> {
        ensureDiskCacheLoaded(context)

        val items = mutableListOf<TrackListItem>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        if (includeCurrentTrack && browseFolderName == null) {
            val currentTrack = currentTrackData(activePoints, coeff)
            val includeCurrentMapTrack = includeMapTracks && (mapTrackIds == null || CURRENT_TRACK_ID in mapTrackIds)
            items.add(
                TrackListItem(
                    id = CURRENT_TRACK_ID,
                    title = CURRENT_TRACK_TITLE,
                    subtitle = formatStats(currentTrack.stats),
                    mapTrack = if (includeCurrentMapTrack) {
                        MapTrack(CURRENT_TRACK_ID, CURRENT_TRACK_TITLE, currentTrack.samples)
                    } else {
                        null
                    },
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

        val snapshot = (sourceListing as TrackSourceListing.Success).snapshot
        val sourceIds = snapshot.allSources().mapTo(mutableSetOf()) { it.id }
        var cacheChanged = removeMissingSources(sourceIds)

        val sources = when {
            browseFolderName != null -> snapshot.subfolders
                .firstOrNull { it.name == browseFolderName }
                ?.tracks
                .orEmpty()
            includeSubfolderTracks -> snapshot.allSources()
            else -> snapshot.rootTracks
        }

        sources.forEach { source ->
            val shouldIncludeMapTrack = includeMapTracks && (mapTrackIds == null || source.id in mapTrackIds)
            var cached = parsedTrackCache[source.id]
            if (cached == null || !cached.matches(source)) {
                cached = try {
                    parseGpxTrack(source.openStream())?.let {
                        CachedParsedTrack.from(source, it).also { updatedCacheEntry ->
                            parsedTrackCache[source.id] = updatedCacheEntry
                            cacheChanged = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GPX", "Unable to parse track ${source.displayName}", e)
                    null
                }
            } else if (shouldIncludeMapTrack && !cached.hasSamples()) {
                cached = try {
                    parseGpxTrack(source.openStream())?.let {
                        CachedParsedTrack.from(source, it).also { updatedCacheEntry ->
                            parsedTrackCache[source.id] = updatedCacheEntry
                        }
                    } ?: cached
                } catch (e: Exception) {
                    Log.e("GPX", "Unable to load track samples ${source.displayName}", e)
                    cached
                }
            }

            val stats = cached?.stats ?: return@forEach
            val mapTrack = when {
                !shouldIncludeMapTrack -> null
                else -> MapTrack(source.id, source.displayName, cached.samplesOrEmpty())
            }

            items.add(
                TrackListItem(
                    id = source.id,
                    title = source.displayName,
                    subtitle = formatStats(stats),
                    mapTrack = mapTrack,
                    isCurrentTrack = false,
                    defaultVisible = false,
                    itemType = TrackListItemType.TRACK,
                    folderName = source.folderName
                )
            )
        }

        if (browseFolderName == null && includeFolderEntries) {
            snapshot.subfolders
                .filter { it.tracks.isNotEmpty() }
                .forEach { subfolder ->
                    items.add(
                        TrackListItem(
                            id = folderItemId(subfolder.name),
                            title = subfolder.name,
                            subtitle = "Folder with ${subfolder.tracks.size} tracks",
                            mapTrack = null,
                            isCurrentTrack = false,
                            defaultVisible = false,
                            itemType = TrackListItemType.FOLDER,
                            folderName = subfolder.name
                        )
                    )
                }
        }

        if (cacheChanged) {
            persistTrackCache(context)
        }

        return items
    }

    fun listTrackSubfolderNames(context: Context): List<String> {
        val sourceListing = listTrackFiles(context)
        if (sourceListing is TrackSourceListing.Failure) {
            Log.w("GPX", "Unable to enumerate subfolders", sourceListing.error)
            return emptyList()
        }
        return (sourceListing as TrackSourceListing.Success).snapshot.subfolders.map { it.name }
    }

    fun folderItemId(folderName: String): String = "folder:$folderName"

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
            TrackSample(it.latitude, it.longitude, it.cps * coeff, it.counts, it.seconds)
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
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val parser = factory.newPullParser().apply {
                setInput(stream, null)
            }

            val samples = mutableListOf<TrackSample>()
            val timestamps = mutableListOf<Long>()

            var lat = 0.0
            var lon = 0.0
            var doseRate = 0.0
            var counts = 0
            var seconds = 0.0
            var timeMs = 0L
            var insideTrkpt = false
            var currentTag: String? = null
            var currentNamespace: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        currentNamespace = parser.namespace
                        if (parser.name.equals("trkpt", ignoreCase = true)) {
                            insideTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            doseRate = 0.0
                            counts = 0
                            seconds = 0.0
                            timeMs = 0L
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (insideTrkpt) {
                            when {
                                currentTag == "time" -> timeMs = parseIsoTime(parser.text)
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
                        if (parser.name == "trkpt" && insideTrkpt) {
                            samples.add(TrackSample(lat, lon, doseRate, counts, seconds))
                            if (timeMs > 0L) timestamps.add(timeMs)
                            insideTrkpt = false
                        }
                        currentTag = null
                        currentNamespace = null
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
        val stats: TrackStats,
        @Volatile private var sampleCache: List<TrackSample>? = null
    ) {
        fun matches(source: TrackSource): Boolean {
            if (sourceId != source.id) return false
            if (!metadataReliable || !source.metadataReliable) return true
            return lastModified == source.lastModified && sizeBytes == source.sizeBytes
        }

        fun hasSamples(): Boolean = sampleCache != null

        fun samplesOrEmpty(): List<TrackSample> = sampleCache ?: emptyList()

        fun toJson(): JSONObject {
            return JSONObject()
                .put("sourceId", sourceId)
                .put("displayName", displayName)
                .put("lastModified", lastModified)
                .put("sizeBytes", sizeBytes)
                .put("metadataReliable", metadataReliable)
                .put("stats", JSONObject()
                    .put("pointCount", stats.pointCount)
                    .put("durationMillis", stats.durationMillis)
                    .put("distanceMeters", stats.distanceMeters))
        }

        companion object {
            fun from(source: TrackSource, parsedTrack: ParsedTrack): CachedParsedTrack {
                return CachedParsedTrack(
                    sourceId = source.id,
                    displayName = source.displayName,
                    lastModified = source.lastModified,
                    sizeBytes = source.sizeBytes,
                    metadataReliable = source.metadataReliable,
                    stats = parsedTrack.stats,
                    sampleCache = parsedTrack.samples
                )
            }

            fun fromJson(json: JSONObject): CachedParsedTrack {
                val statsJson = json.getJSONObject("stats")
                return CachedParsedTrack(
                    sourceId = json.getString("sourceId"),
                    displayName = json.getString("displayName"),
                    lastModified = json.optLong("lastModified", 0L),
                    sizeBytes = json.optLong("sizeBytes", 0L),
                    metadataReliable = json.optBoolean("metadataReliable", true),
                    stats = TrackStats(
                        pointCount = statsJson.getInt("pointCount"),
                        durationMillis = statsJson.getLong("durationMillis"),
                        distanceMeters = statsJson.getDouble("distanceMeters")
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
        val folderName: String?,
        val openStream: () -> InputStream
    )

    private data class TrackSubfolder(
        val name: String,
        val tracks: List<TrackSource>
    )

    private data class TrackDirectorySnapshot(
        val rootTracks: List<TrackSource>,
        val subfolders: List<TrackSubfolder>
    ) {
        fun allSources(): List<TrackSource> = rootTracks + subfolders.flatMap { it.tracks }
    }

    private sealed interface TrackSourceListing {
        data class Success(val snapshot: TrackDirectorySnapshot) : TrackSourceListing
        data class Failure(val error: Throwable) : TrackSourceListing
    }

    private fun listTrackFiles(context: Context): TrackSourceListing {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)

        if (!treeUriStr.isNullOrBlank()) {
            return try {
                val treeUri = Uri.parse(treeUriStr)
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return TrackSourceListing.Success(TrackDirectorySnapshot(emptyList(), emptyList()))
                val entries = dirDoc.listFiles().sortedBy { it.name?.lowercase() ?: "" }
                val rootTracks = entries
                    .filter { it.isFile && it.name?.endsWith(".gpx", true) == true && it.name !in EXCLUDED_GPX_FILE_SET }
                    .map { doc ->
                        TrackSource(
                            id = "tree:${doc.uri}",
                            displayName = doc.name ?: "Track",
                            lastModified = doc.lastModified(),
                            sizeBytes = doc.length(),
                            metadataReliable = doc.lastModified() > 0L || doc.length() > 0L,
                            folderName = null,
                            openStream = {
                                context.contentResolver.openInputStream(doc.uri)
                                    ?: throw IllegalStateException("Unable to open ${doc.uri}")
                            }
                        )
                    }
                val subfolders = entries
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .mapNotNull { directory ->
                        val folderName = directory.name ?: return@mapNotNull null
                        val tracks = directory.listFiles()
                            .filter { it.isFile && it.name?.endsWith(".gpx", true) == true && it.name !in EXCLUDED_GPX_FILE_SET }
                            .sortedBy { it.name?.lowercase() ?: "" }
                            .map { doc ->
                                TrackSource(
                                    id = "tree:${doc.uri}",
                                    displayName = doc.name ?: "Track",
                                    lastModified = doc.lastModified(),
                                    sizeBytes = doc.length(),
                                    metadataReliable = doc.lastModified() > 0L || doc.length() > 0L,
                                    folderName = folderName,
                                    openStream = {
                                        context.contentResolver.openInputStream(doc.uri)
                                            ?: throw IllegalStateException("Unable to open ${doc.uri}")
                                    }
                                )
                            }
                        TrackSubfolder(folderName, tracks)
                    }
                TrackSourceListing.Success(TrackDirectorySnapshot(rootTracks, subfolders))
            } catch (e: Exception) {
                TrackSourceListing.Failure(e)
            }
        }

        return try {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val entries = root.listFiles()
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            val rootTracks = entries
                .filter { it.isFile && it.name.endsWith(".gpx", true) && it.name !in EXCLUDED_GPX_FILE_SET }
                .map { file ->
                    TrackSource(
                        id = "file:${file.absolutePath}",
                        displayName = file.name,
                        lastModified = file.lastModified(),
                        sizeBytes = file.length(),
                        metadataReliable = true,
                        folderName = null,
                        openStream = { file.inputStream() }
                    )
                }
            val subfolders = entries
                .filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
                .map { directory ->
                    val tracks = directory.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".gpx", true) && it.name !in EXCLUDED_GPX_FILE_SET }
                        ?.sortedBy { it.name.lowercase() }
                        ?.map { file ->
                            TrackSource(
                                id = "file:${file.absolutePath}",
                                displayName = file.name,
                                lastModified = file.lastModified(),
                                sizeBytes = file.length(),
                                metadataReliable = true,
                                folderName = directory.name,
                                openStream = { file.inputStream() }
                            )
                        }
                        .orEmpty()
                    TrackSubfolder(directory.name, tracks)
                }
            TrackSourceListing.Success(TrackDirectorySnapshot(rootTracks, subfolders))
        } catch (e: Exception) {
            TrackSourceListing.Failure(e)
        }
    }

    private fun removeMissingSources(sourceIds: Set<String>): Boolean {
        val iterator = parsedTrackCache.keys.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id !in sourceIds) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    private fun ensureDiskCacheLoaded(context: Context) {
        if (diskCacheLoaded) return
        synchronized(this) {
            if (diskCacheLoaded) return
            val cacheFile = trackCacheFile(context)
            if (cacheFile.exists()) {
                runCatching {
                    BufferedReader(cacheFile.reader()).use { reader ->
                        val array = JSONArray(reader.readText())
                        for (i in 0 until array.length()) {
                            val entry = CachedParsedTrack.fromJson(array.getJSONObject(i))
                            parsedTrackCache[entry.sourceId] = entry
                        }
                    }
                }.onFailure {
                    Log.w("GPX", "Unable to load track cache ${cacheFile.absolutePath}", it)
                    parsedTrackCache.clear()
                }
            }
            diskCacheLoaded = true
        }
    }

    private fun persistTrackCache(context: Context) {
        synchronized(this) {
            val cacheFile = trackCacheFile(context)
            runCatching {
                cacheFile.parentFile?.mkdirs()
                BufferedWriter(cacheFile.writer()).use { writer ->
                    val array = JSONArray()
                    parsedTrackCache.values
                        .sortedBy { it.displayName.lowercase() }
                        .forEach { array.put(it.toJson()) }
                    writer.write(array.toString())
                }
            }.onFailure {
                Log.w("GPX", "Unable to persist track cache ${cacheFile.absolutePath}", it)
            }
        }
    }

    private fun trackCacheFile(context: Context): File {
        val root = context.cacheDir
        return File(root, "track-cache.json")
    }
}
