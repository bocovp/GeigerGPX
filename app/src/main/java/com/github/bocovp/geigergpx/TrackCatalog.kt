package com.github.bocovp.geigergpx

import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val cachedSubfolders = linkedSetOf<String>()
    private val cacheRebuildExecutor = Executors.newSingleThreadExecutor()
    private val isCacheRebuildInProgress = AtomicBoolean(false)
    @Volatile private var diskCacheLoaded = false

    fun currentTrackId(): String = CURRENT_TRACK_ID

    fun clearTrackCache(context: Context) {
        synchronized(this) {
            parsedTrackCache.clear()
            cachedSubfolders.clear()
            diskCacheLoaded = true
            val cacheFile = trackCacheFile(context)
            if (cacheFile.exists() && !cacheFile.delete()) {
                Log.w("GPX", "Unable to delete persisted track cache ${cacheFile.absolutePath}")
            }
        }
    }

    fun isTrackCacheEmpty(context: Context): Boolean {
        if (parsedTrackCache.isNotEmpty() || cachedSubfolders.isNotEmpty()) return false
        val cacheFile = trackCacheFile(context)
        return !cacheFile.exists() || cacheFile.length() == 0L
    }

    fun rebuildTrackCache(context: Context) {
        ensureDiskCacheLoaded(context)
        val sourceListing = listTrackFiles(context)
        if (sourceListing is TrackSourceListing.Failure) {
            Log.w("GPX", "Unable to rebuild track cache", sourceListing.error)
            return
        }

        val snapshot = (sourceListing as TrackSourceListing.Success).snapshot
        val updatedTracks = linkedMapOf<String, CachedParsedTrack>()
        snapshot.allSources().forEach { source ->
            try {
                val parsed = parseGpxTrack(source.openStream()) ?: return@forEach
                updatedTracks[source.id] = CachedParsedTrack.from(source, parsed)
            } catch (e: Exception) {
                Log.e("GPX", "Unable to parse track ${source.displayName}", e)
            }
        }

        synchronized(this) {
            parsedTrackCache.clear()
            parsedTrackCache.putAll(updatedTracks)
            cachedSubfolders.clear()
            snapshot.subfolders.mapTo(cachedSubfolders) { it.name }
        }
        persistTrackCache(context)
    }

    fun isTrackCacheRebuildInProgress(): Boolean = isCacheRebuildInProgress.get()

    fun rebuildTrackCacheAsync(context: Context, onComplete: (() -> Unit)? = null) {
        if (!isCacheRebuildInProgress.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        cacheRebuildExecutor.execute {
            try {
                rebuildTrackCache(appContext)
            } finally {
                isCacheRebuildInProgress.set(false)
                onComplete?.invoke()
            }
        }
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
        if (isTrackCacheEmpty(context)) {
            rebuildTrackCache(context)
        }

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

        val cachedTracks = parsedTrackCache.values.toList()
        val sources = when {
            browseFolderName != null -> cachedTracks.filter { it.folderName == browseFolderName }
            includeSubfolderTracks -> cachedTracks
            else -> cachedTracks.filter { it.folderName == null }
        }

        val sortedSources = sources.sortedByDescending { it.displayName.lowercase() }

        sortedSources.forEach { source ->
            val shouldIncludeMapTrack = includeMapTracks && (mapTrackIds == null || source.sourceId in mapTrackIds)
            val cached = if (shouldIncludeMapTrack && !source.hasSamples()) {
                try {
                    val parsed = openInputStreamForTrack(context, source.sourceId)?.use { parseGpxTrack(it) }
                    if (parsed != null) {
                        val updated = source.withSamples(parsed.samples)
                        parsedTrackCache[source.sourceId] = updated
                        updated
                    } else {
                        source
                    }
                } catch (e: Exception) {
                    Log.e("GPX", "Unable to load track samples ${source.displayName}", e)
                    source
                }
            } else {
                source
            }

            val stats = cached.stats
            val mapTrack = when {
                !shouldIncludeMapTrack -> null
                else -> MapTrack(source.sourceId, source.displayName, cached.samplesOrEmpty())
            }

            items.add(
                TrackListItem(
                    id = source.sourceId,
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
            cachedSubfolders
                .sortedBy { it.lowercase() }
                .forEach { subfolder ->
                    val trackCount = parsedTrackCache.values.count { it.folderName == subfolder }
                    items.add(
                        TrackListItem(
                            id = folderItemId(subfolder),
                            title = subfolder,
                            subtitle = "Folder with $trackCount tracks",
                            mapTrack = null,
                            isCurrentTrack = false,
                            defaultVisible = false,
                            itemType = TrackListItemType.FOLDER,
                            folderName = subfolder
                        )
                    )
                }
        }

        return items
    }

    fun listTrackSubfolderNames(context: Context): List<String> {
        ensureDiskCacheLoaded(context)
        if (isTrackCacheEmpty(context)) {
            rebuildTrackCache(context)
        }
        return cachedSubfolders.toList()
    }

    fun onTrackSaved(context: Context, relativePath: String, points: List<TrackPoint>) {
        ensureDiskCacheLoaded(context)
        val source = sourceFromRelativePath(context, relativePath) ?: return
        val stats = statsFromTrackPoints(points)
        synchronized(this) {
            parsedTrackCache[source.id] = CachedParsedTrack(
                sourceId = source.id,
                displayName = source.displayName,
                folderName = source.folderName,
                stats = stats
            )
            source.folderName?.let { cachedSubfolders.add(it) }
        }
        persistTrackCache(context)
    }

    fun onTrackRenamed(context: Context, oldTrackId: String, newTrackId: String, newDisplayName: String) {
        ensureDiskCacheLoaded(context)
        synchronized(this) {
            val existing = parsedTrackCache.remove(oldTrackId) ?: return
            parsedTrackCache[newTrackId] = existing.copy(
                sourceId = newTrackId,
                displayName = newDisplayName
            )
        }
        persistTrackCache(context)
    }

    fun onTrackMoved(context: Context, oldTrackId: String, newTrackId: String, destinationFolder: String?) {
        ensureDiskCacheLoaded(context)

        var shouldPersist = false
        var needsFallbackLoad = false

        synchronized(this) {
            val existing = parsedTrackCache.remove(oldTrackId)
            if (existing != null) {
                parsedTrackCache[newTrackId] = existing.copy(
                    sourceId = newTrackId,
                    folderName = destinationFolder
                )
                destinationFolder?.let { cachedSubfolders.add(it) }
                shouldPersist = true
            } else {
                needsFallbackLoad = true
            }
        }

        if (needsFallbackLoad) {
            val loaded = loadTrackForCacheById(context, newTrackId, destinationFolder)
            if (loaded != null) {
                synchronized(this) {
                    if (!parsedTrackCache.containsKey(newTrackId)) {
                        parsedTrackCache[newTrackId] = loaded
                        destinationFolder?.let { cachedSubfolders.add(it) }
                        shouldPersist = true
                    }
                }
            }
        }

        if (shouldPersist) {
            persistTrackCache(context)
        }
    }


    private fun loadTrackForCacheById(context: Context, trackId: String, folderName: String?): CachedParsedTrack? {
        val sourceListing = listTrackFiles(context)
        if (sourceListing !is TrackSourceListing.Success) return null
        val source = sourceListing.snapshot.allSources().firstOrNull { it.id == trackId } ?: return null
        val parsed = runCatching { source.openStream().use { parseGpxTrack(it) } }.getOrNull() ?: return null
        return CachedParsedTrack.from(source.copy(folderName = folderName), parsed)
    }

    fun onTrackDeleted(context: Context, trackId: String) {
        ensureDiskCacheLoaded(context)
        synchronized(this) {
            parsedTrackCache.remove(trackId) ?: return
        }
        persistTrackCache(context)
    }

    data class TrackPlotData(
        val id: String,
        val title: String,
        val samples: List<TrackSample>
    )

    fun loadTrackSamplesById(context: Context, trackId: String): TrackPlotData? {
        ensureDiskCacheLoaded(context)
        if (isTrackCacheEmpty(context)) {
            rebuildTrackCache(context)
        }

        val cached = parsedTrackCache[trackId] ?: return null
        val samples = if (cached.hasSamples()) {
            cached.samplesOrEmpty()
        } else {
            val parsed = openInputStreamForTrack(context, trackId)?.use { parseGpxTrack(it) } ?: return null
            synchronized(this) {
                val updated = cached.withSamples(parsed.samples)
                parsedTrackCache[trackId] = updated
            }
            persistTrackCache(context)
            parsed.samples
        }
        return TrackPlotData(id = trackId, title = cached.displayName, samples = samples)
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
            TrackSample(it.latitude, it.longitude, it.cps * coeff, it.counts, it.seconds, it.badCoordinates)
        }
        return CurrentTrackData(samples, statsFromTrackPoints(currentPoints))
    }

    private fun statsFromTrackPoints(points: List<TrackPoint>): TrackStats {
        if (points.isEmpty()) return TrackStats(0, 0L, 0.0)
        val validPoints = points.filterNot { it.badCoordinates }
        var distance = 0.0
        for (i in 1 until validPoints.size) {
            distance += distanceBetween(
                validPoints[i - 1].latitude,
                validPoints[i - 1].longitude,
                validPoints[i].latitude,
                validPoints[i].longitude
            )
        }
        val duration = if (validPoints.size >= 2) {
            (validPoints.last().timeMillis - validPoints.first().timeMillis).coerceAtLeast(0L)
        } else {
            0L
        }
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
            var badCoordinates = false
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
                            badCoordinates = false
                            timeMs = 0L
                        }
                        if (insideTrkpt && currentNamespace == RAD_NAMESPACE && parser.name == "badCoordinates") {
                            badCoordinates = true
                        } // Keeping this for backward compatibility. Remove in the future
                    }

                    XmlPullParser.TEXT -> {
                        if (insideTrkpt) {
                            when {
                                currentTag == "time" -> timeMs = parseIsoTime(parser.text)
                                currentTag == "fix" && parser.text?.trim()?.equals("none", ignoreCase = true) == true -> {
                                    badCoordinates = true
                                }
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
                            samples.add(TrackSample(lat, lon, doseRate, counts, seconds, badCoordinates))
                            if (timeMs > 0L && !badCoordinates) timestamps.add(timeMs)
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
            val validSamples = samples.filterNot { it.badCoordinates }
            for (i in 1 until validSamples.size) {
                distance += distanceBetween(
                    validSamples[i - 1].latitude,
                    validSamples[i - 1].longitude,
                    validSamples[i].latitude,
                    validSamples[i].longitude
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
        return "${stats.pointCount} points · $durationText · %.1f m".format(java.util.Locale.US, stats.distanceMeters)
    }

    private data class CachedParsedTrack(
        val sourceId: String,
        val displayName: String,
        val folderName: String?,
        val stats: TrackStats,
        @Volatile private var sampleCache: List<TrackSample>? = null
    ) {
        fun hasSamples(): Boolean = sampleCache != null

        fun samplesOrEmpty(): List<TrackSample> = sampleCache ?: emptyList()

        fun withSamples(samples: List<TrackSample>): CachedParsedTrack = copy(sampleCache = samples)

        fun toJson(): JSONObject {
            return JSONObject()
                .put("sourceId", sourceId)
                .put("displayName", displayName)
                .put("folderName", folderName)
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
                    folderName = source.folderName,
                    stats = parsedTrack.stats,
                    sampleCache = parsedTrack.samples
                )
            }

            fun fromJson(json: JSONObject): CachedParsedTrack {
                val statsJson = json.getJSONObject("stats")
                val folderName = if (json.has("folderName") && !json.isNull("folderName")) {
                    json.getString("folderName")
                } else {
                    null
                }
                return CachedParsedTrack(
                    sourceId = json.getString("sourceId"),
                    displayName = json.getString("displayName"),
                    folderName = folderName,
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


    private fun FileStorageManager.StorageFile.isTrackFile(): Boolean {
        return !isDirectory && name.endsWith(".gpx", ignoreCase = true) && name !in EXCLUDED_GPX_FILE_SET
    }

    private fun FileStorageManager.StorageFile.toTrackSource(folderName: String?): TrackSource {
        return TrackSource(
            id = when (uri.scheme) {
                "content" -> "tree:$uri"
                else -> "file:${uri.path}"
            },
            displayName = name,
            lastModified = lastModified,
            sizeBytes = size,
            metadataReliable = uri.scheme != "content" || lastModified > 0L || size > 0L,
            folderName = folderName,
            openStream = openStream
        )
    }

    private fun listTrackFiles(context: Context): TrackSourceListing {
        return try {
            val rootEntries = FileStorageManager.listFiles(context)
            val rootTracks = rootEntries
                .filter { it.isTrackFile() }
                .map { it.toTrackSource(folderName = null) }

            val subfolders = rootEntries
                .filter { it.isDirectory }
                .map { directory ->
                    val tracks = FileStorageManager.listFilesInDirectory(context, directory.path) { fileName, isDirectory ->
                        !isDirectory && fileName.endsWith(".gpx", ignoreCase = true) && fileName !in EXCLUDED_GPX_FILE_SET
                    }.map { it.toTrackSource(folderName = directory.name) }
                    TrackSubfolder(directory.name, tracks)
                }

            TrackSourceListing.Success(TrackDirectorySnapshot(rootTracks, subfolders))
        } catch (e: Exception) {
            TrackSourceListing.Failure(e)
        }
    }

    private fun ensureDiskCacheLoaded(context: Context) {
        if (diskCacheLoaded) return
        synchronized(this) {
            if (diskCacheLoaded) return
            val cacheFile = trackCacheFile(context)
            if (cacheFile.exists()) {
                runCatching {
                    BufferedReader(cacheFile.reader()).use { reader ->
                        val raw = reader.readText()
                        val root = if (raw.trimStart().startsWith("[")) {
                            JSONObject().put("tracks", JSONArray(raw))
                        } else {
                            JSONObject(raw)
                        }
                        val folders = root.optJSONArray("subfolders") ?: JSONArray()
                        for (i in 0 until folders.length()) {
                            folders.optString(i)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { cachedSubfolders.add(it) }
                        }
                        val tracks = root.optJSONArray("tracks") ?: JSONArray()
                        for (i in 0 until tracks.length()) {
                            val entry = CachedParsedTrack.fromJson(tracks.getJSONObject(i))
                            parsedTrackCache[entry.sourceId] = entry
                        }
                    }
                }.onFailure {
                    Log.w("GPX", "Unable to load track cache ${cacheFile.absolutePath}", it)
                    parsedTrackCache.clear()
                    cachedSubfolders.clear()
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
                    val tracks = JSONArray()
                    parsedTrackCache.values
                        .sortedBy { it.displayName.lowercase() }
                        .forEach { tracks.put(it.toJson()) }
                    val subfolders = JSONArray()
                    cachedSubfolders
                        .sortedBy { it.lowercase() }
                        .forEach { subfolders.put(it) }
                    writer.write(
                        JSONObject()
                            .put("tracks", tracks)
                            .put("subfolders", subfolders)
                            .toString()
                    )
                }
            }.onFailure {
                Log.w("GPX", "Unable to persist track cache ${cacheFile.absolutePath}", it)
            }
        }
    }

    private fun sourceFromRelativePath(context: Context, relativePath: String): TrackSource? {
        val normalized = relativePath.trim().replace('\\', '/').trim('/')
        val fileName = normalized.substringAfterLast('/')
        val folderName = normalized.substringBeforeLast('/', "").ifBlank { null }
        val uri = FileStorageManager.getFileUri(context, normalized) ?: return null
        return TrackSource(
            id = sourceIdForUri(uri),
            displayName = fileName,
            lastModified = 0L,
            sizeBytes = 0L,
            metadataReliable = false,
            folderName = folderName,
            openStream = {
                openInputStreamForTrack(context, sourceIdForUri(uri))
                    ?: throw IllegalStateException("Missing stream")
            }
        )
    }

    private fun sourceIdForUri(uri: Uri): String {
        return if (uri.scheme == "content") "tree:$uri" else "file:${uri.path}"
    }

    private fun openInputStreamForTrack(context: Context, sourceId: String): InputStream? {
        return when {
            sourceId.startsWith("tree:") -> {
                val uri = Uri.parse(sourceId.removePrefix("tree:"))
                context.contentResolver.openInputStream(uri)
            }
            sourceId.startsWith("file:") -> {
                val file = File(sourceId.removePrefix("file:"))
                if (file.exists()) file.inputStream() else null
            }
            else -> null
        }
    }

    private fun trackCacheFile(context: Context): File {
        val root = FileStorageManager.getRootDirectory(context)
        return File(root, "track-cache.json")
    }
}
