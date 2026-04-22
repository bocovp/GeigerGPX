package com.github.bocovp.geigergpx

import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import androidx.preference.PreferenceManager
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val CURRENT_TRACK_ID = "active-track"
private const val CURRENT_TRACK_TITLE = "Currently recording"
private val EXCLUDED_GPX_FILE_SET = setOf("Backup.gpx", "POI.gpx", "POI-Backup.gpx")

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
    private val _rebuildProgress = MutableLiveData<Int?>(null)
    val rebuildProgress: LiveData<Int?> = _rebuildProgress
    @Volatile private var diskCacheLoaded = false

    fun currentTrackId(): String = CURRENT_TRACK_ID

    fun clearTrackCache(context: Context) {
        synchronized(this) {
            parsedTrackCache.clear()
            cachedSubfolders.clear()
            _rebuildProgress.postValue(null)
            diskCacheLoaded = true
            val cacheFile = trackCacheFile(context)
            if (cacheFile.exists() && !cacheFile.delete()) {
                Log.w("GPX", "Unable to delete persisted track cache ${cacheFile.absolutePath}")
            }
        }
    }

    fun isTrackCacheEmpty(context: Context): Boolean {
        synchronized(this) {
            if (parsedTrackCache.isNotEmpty() || cachedSubfolders.isNotEmpty()) return false
            val cacheFile = trackCacheFile(context)
            return !cacheFile.exists() || cacheFile.length() == 0L
        }
    }

    fun rebuildTrackCache(context: Context) {
        ensureDiskCacheLoaded(context)
        _rebuildProgress.postValue(0)
        val sourceListing = listTrackFiles(context)
        if (sourceListing is TrackSourceListing.Failure) {
            Log.w("GPX", "Unable to rebuild track cache", sourceListing.error)
            _rebuildProgress.postValue(null)
            return
        }

        val snapshot = (sourceListing as TrackSourceListing.Success).snapshot
        val allSources = snapshot.allSources()
        val totalCount = allSources.size
        val updatedTracks = linkedMapOf<String, CachedParsedTrack>()

        allSources.forEachIndexed { index, source ->
            try {
                val parsed = parseGpxTrack(context, source.openStream())
                if (parsed != null) {
                    updatedTracks[source.id] = CachedParsedTrack.from(source, parsed)
                }
            } catch (e: Exception) {
                Log.e("GPX", "Unable to parse track ${source.displayName}", e)
            }
            if (totalCount > 0) {
                val progressPercent = ((index + 1) * 100) / totalCount
                _rebuildProgress.postValue(maxOf(1, progressPercent))
            }
        }

        synchronized(this) {
            parsedTrackCache.clear()
            parsedTrackCache.putAll(updatedTracks)
            refreshCachedSubfolders()
        }
        persistTrackCache(context)
        _rebuildProgress.postValue(null)
    }

    private fun refreshCachedSubfolders() {
        synchronized(this) {
            cachedSubfolders.clear()
            parsedTrackCache.values
                .mapNotNull { it.folderName }
                .distinct()
                .forEach { cachedSubfolders.add(it) }
        }
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
                        MapTrack(CURRENT_TRACK_ID, CURRENT_TRACK_TITLE, currentTrack.points)
                    } else {
                        null
                    },
                    isCurrentTrack = true,
                    defaultVisible = true
                )
            )
        }

        val cachedTracksSnapshot = synchronized(this) { parsedTrackCache.values.toList() }
        val sources = when {
            browseFolderName != null -> cachedTracksSnapshot.filter { it.folderName == browseFolderName }
            includeSubfolderTracks -> cachedTracksSnapshot
            else -> cachedTracksSnapshot.filter { it.folderName == null }
        }

        val sortedSources = sources.sortedByDescending { it.displayName.lowercase() }

        sortedSources.forEach { source ->
            val shouldIncludeMapTrack = includeMapTracks && (mapTrackIds == null || source.sourceId in mapTrackIds)
            val cached = if (shouldIncludeMapTrack && !source.hasPoints()) {
                try {
                    val parsed = openInputStreamForTrack(context, source.sourceId)?.use { parseGpxTrack(context, it) }
                    if (parsed != null) {
                        val updated = source.withPoints(parsed.points)
                        synchronized(this) {
                            parsedTrackCache[source.sourceId] = updated
                        }
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
                else -> MapTrack(source.sourceId, source.displayName, cached.pointsOrEmpty())
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
            val subfoldersSnapshot = synchronized(this) { cachedSubfolders.toList() }
            subfoldersSnapshot
                .sortedBy { it.lowercase() }
                .forEach { subfolder ->
                    val trackCount = cachedTracksSnapshot.count { it.folderName == subfolder }
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
        synchronized(this) {
            return cachedSubfolders.toList()
        }
    }

    fun onTrackSaved(context: Context, relativePath: String, points: List<TrackPoint>, coefficient: Double) {
        ensureDiskCacheLoaded(context)
        val source = sourceFromRelativePath(context, relativePath) ?: return
        val stats = statsFromTrackPoints(points)
        // Note: TrackingService already calculated doseRate for these points
        synchronized(this) {
            parsedTrackCache[source.id] = CachedParsedTrack(
                sourceId = source.id,
                displayName = source.displayName,
                folderName = source.folderName,
                stats = stats,
                pointCache = points
            )
            refreshCachedSubfolders()
        }
        persistTrackCache(context)
    }

    fun onTrackSavedById(
        context: Context,
        trackId: String,
        displayName: String,
        folderName: String?,
        points: List<TrackPoint>,
        coefficient: Double
    ) {
        ensureDiskCacheLoaded(context)
        val stats = statsFromTrackPoints(points)
        // Note: The points passed here (e.g. from EditTrackActivity) should already have 
        // their doseRate set correctly.
        synchronized(this) {
            parsedTrackCache[trackId] = CachedParsedTrack(
                sourceId = trackId,
                displayName = displayName,
                folderName = folderName,
                stats = stats,
                pointCache = points
            )
            refreshCachedSubfolders()
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
            refreshCachedSubfolders()
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
                refreshCachedSubfolders()
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
                        refreshCachedSubfolders()
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
        // Try to avoid full listTrackFiles scan if we can resolve the source directly
        val source = when {
            trackId.startsWith("file:") -> {
                val path = trackId.removePrefix("file:")
                val file = File(path)
                if (file.exists()) {
                    TrackSource(
                        id = trackId,
                        displayName = file.name,
                        lastModified = file.lastModified(),
                        sizeBytes = file.length(),
                        metadataReliable = true,
                        folderName = folderName,
                        openStream = { file.inputStream() }
                    )
                } else null
            }
            else -> {
                // For tree: URIs or others, fall back to listing if we can't easily resolve.
                // However, GpxWriter.restoreBackupIfPresent provides a normalized path/uri.
                val sourceListing = listTrackFiles(context)
                if (sourceListing !is TrackSourceListing.Success) return null
                sourceListing.snapshot.allSources().firstOrNull { it.id == trackId }
            }
        } ?: return null

        val parsed = runCatching { source.openStream().use { parseGpxTrack(context, it) } }.getOrNull() ?: return null
        return CachedParsedTrack.from(source.copy(folderName = folderName), parsed)
    }

    fun onTrackDeleted(context: Context, trackId: String) {
        ensureDiskCacheLoaded(context)
        synchronized(this) {
            parsedTrackCache.remove(trackId) ?: return
            refreshCachedSubfolders()
        }
        persistTrackCache(context)
    }

    data class TrackPlotData(
        val id: String,
        val title: String,
        val points: List<TrackPoint>
    )

    fun loadTrackSamplesById(context: Context, trackId: String): TrackPlotData? {
        ensureDiskCacheLoaded(context)
        if (isTrackCacheEmpty(context)) {
            rebuildTrackCache(context)
        }

        val cached = parsedTrackCache[trackId] ?: return null
        val points = if (cached.hasPoints()) {
            cached.pointsOrEmpty()
        } else {
            val parsed = openInputStreamForTrack(context, trackId)?.use { parseGpxTrack(context, it) } ?: return null
            synchronized(this) {
                val updated = cached.withPoints(parsed.points)
                parsedTrackCache[trackId] = updated
            }
            persistTrackCache(context)
            parsed.points
        }
        return TrackPlotData(id = trackId, title = cached.displayName, points = points)
    }

    fun folderItemId(folderName: String): String = "folder:$folderName"

    private data class CurrentTrackData(
        val points: List<TrackPoint>,
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

        return CurrentTrackData(currentPoints, statsFromTrackPoints(currentPoints))
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

    private fun parseGpxTrack(context: Context, inputStream: InputStream): ParsedTrack? {
        val coeff = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val parsed = GpxReader.readTrack(inputStream, cpsCoefficient = coeff) ?: return null
        return ParsedTrack(parsed.points, parsed.stats)
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
        
        val distanceText = if (stats.distanceMeters < 1000.0) {
            "%.0f m".format(java.util.Locale.US, stats.distanceMeters)
        } else {
            "%.1f km".format(java.util.Locale.US, stats.distanceMeters / 1000.0)
        }
        
        return "${stats.pointCount} points · $durationText · $distanceText"
    }

    private data class CachedParsedTrack(
        val sourceId: String,
        val displayName: String,
        val folderName: String?,
        val stats: TrackStats,
        @Volatile private var pointCache: List<TrackPoint>? = null
    ) {
        fun hasPoints(): Boolean = pointCache != null

        fun pointsOrEmpty(): List<TrackPoint> = pointCache ?: emptyList()

        fun withPoints(points: List<TrackPoint>): CachedParsedTrack = copy(pointCache = points)

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
                    pointCache = parsedTrack.points
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
        val points: List<TrackPoint>,
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
                            runCatching {
                                val entry = CachedParsedTrack.fromJson(tracks.getJSONObject(i))
                                parsedTrackCache[entry.sourceId] = entry
                            }.onFailure {
                                Log.w("GPX", "Failed to parse track cache entry at index $i", it)
                            }
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
