package com.github.bocovp.geigergpx

import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.preference.PreferenceManager
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Locale

private const val CURRENT_TRACK_ID = "active-track"
private const val CURRENT_TRACK_TITLE = "Currently recording"
private val EXCLUDED_GPX_FILE_SET = setOf("Backup.gpx", "POI.gpx", "POI-Backup.gpx")

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
    private val cacheMutex = Mutex()
    private val rebuildMutex = Mutex()
    private val catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parseDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val _rebuildProgress = MutableStateFlow<Int?>(null)
    val rebuildProgress: StateFlow<Int?> = _rebuildProgress
    val isRebuilding: StateFlow<Boolean> = rebuildProgress
        .map { it != null }
        .stateIn(catalogScope, SharingStarted.Eagerly, false)
    private val _tracks = MutableStateFlow<Map<String, CachedParsedTrack>>(emptyMap())
    val allTracks: StateFlow<List<TrackListItem>> = _tracks
        .map { tracks ->
            tracks.values
                .sortedByDescending { it.displayName.lowercase() }
                .map { cached ->
                    TrackListItem(
                        id = cached.sourceId,
                        title = cached.displayName,
                        subtitle = formatStats(cached.stats),
                        mapTrack = null,
                        isCurrentTrack = false,
                        defaultVisible = false,
                        itemType = TrackListItemType.TRACK,
                        folderName = cached.folderName
                    )
                }
        }
        .stateIn(catalogScope, SharingStarted.Eagerly, emptyList())
    @Volatile
    private var diskCacheLoaded = false
    @Volatile
    private var hasScannedStorage = false

    fun currentTrackId(): String = CURRENT_TRACK_ID

    fun clearTrackCache(context: Context) {
        val appContext = context.applicationContext
        catalogScope.launch {
            cacheMutex.withLock {
                parsedTrackCache.clear()
                _tracks.value = emptyMap()
                _rebuildProgress.value = null
                diskCacheLoaded = false
                hasScannedStorage = false
                val cacheFile = trackCacheFile(appContext)
                if (cacheFile.exists() && !cacheFile.delete()) {
                    Log.w("GPX", "Unable to delete persisted track cache ${cacheFile.absolutePath}")
                }
            }
        }
    }

    fun isTrackCacheEmpty(): Boolean {
        return !diskCacheLoaded || (!hasScannedStorage && _tracks.value.isEmpty())
    }

    suspend fun rebuildTrackCache(context: Context) {
        ensureDiskCacheLoaded(context)
        rebuildMutex.withLock {
            rebuildTrackCacheLocked(context)
        }
    }

    private suspend fun rebuildTrackCacheIfNeeded(context: Context) {
        ensureDiskCacheLoaded(context)
        if (!isTrackCacheEmpty()) return
        rebuildMutex.withLock {
            if (!isTrackCacheEmpty()) return
            rebuildTrackCacheLocked(context)
        }
    }

    private suspend fun rebuildTrackCacheLocked(context: Context) {
        _rebuildProgress.value = 0

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        val sourceListing = listTrackFiles(context)
        if (sourceListing is TrackSourceListing.Failure) {
            Log.w("GPX", "Unable to rebuild track cache", sourceListing.error)
            hasScannedStorage = true
            _rebuildProgress.value = null
            return
        }

        val snapshot = (sourceListing as TrackSourceListing.Success).snapshot
        val allSources = snapshot.allSources()
        val totalCount = allSources.size
        val updatedTracks = linkedMapOf<String, CachedParsedTrack>()
        var processedCount = 0

        coroutineScope {
            val results = Channel<Pair<TrackSource, TrackStats?>>(Channel.UNLIMITED)
            allSources.forEach { source ->
                launch(parseDispatcher) {
                    yield()
                    val parsedStats = try {
                        source.openStream().use { parseGpxTrackStats(it, coeff) }
                    } catch (e: Exception) {
                        Log.e("GPX", "Unable to parse track ${source.displayName}", e)
                        null
                    }
                    results.send(source to parsedStats)
                }
            }

            repeat(totalCount) {
                val (source, stats) = results.receive()
                if (stats != null) {
                    updatedTracks[source.id] = CachedParsedTrack.from(source, stats)
                }
                processedCount += 1
                if (totalCount > 0) {
                    val progressPercent = (processedCount * 100) / totalCount
                    _rebuildProgress.value = maxOf(1, progressPercent)
                }
            }
            results.close()
        }

        cacheMutex.withLock {
            parsedTrackCache.clear()
            parsedTrackCache.putAll(updatedTracks)
            _tracks.value = parsedTrackCache.toMap()
            hasScannedStorage = true
        }
        persistTrackCache(context)
        _rebuildProgress.value = null
    }

    fun isTrackCacheRebuildInProgress(): Boolean = isRebuilding.value

    fun rebuildTrackCacheAsync(context: Context) {
        if (rebuildMutex.isLocked) return
        val appContext = context.applicationContext
        catalogScope.launch {
            rebuildTrackCache(appContext)
        }
    }

    suspend fun loadTrackListItems(
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
        rebuildTrackCacheIfNeeded(context)

        val items = mutableListOf<TrackListItem>()

        if (includeCurrentTrack && browseFolderName == null) {
            val currentTrack = currentTrackData(activePoints)
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

        val cachedTracksSnapshot = cacheMutex.withLock { parsedTrackCache.values.toList() }
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
                    val parsed = withContext(Dispatchers.IO) {
                        val coeff = PreferenceManager.getDefaultSharedPreferences(context)
                            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
                        openInputStreamForTrack(context, source.sourceId)?.use { parseGpxTrack(it, coeff) }
                    }
                    if (parsed != null) {
                        val updated = source.withPoints(parsed.points)
                        cacheMutex.withLock {
                            parsedTrackCache[source.sourceId] = updated
                            _tracks.value = parsedTrackCache.toMap()
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
            val subfoldersSnapshot = cachedTracksSnapshot.mapNotNull { it.folderName }.distinct()
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

    val allSubfolders: StateFlow<List<String>> = _tracks
        .map { tracks ->
            tracks.values
                .mapNotNull { it.folderName }
                .distinct()
                .sortedBy { it.lowercase() }
        }
        .stateIn(catalogScope, SharingStarted.Eagerly, emptyList())

    fun listTrackSubfolderNames(): List<String> = allSubfolders.value

    fun onTrackSaved(context: Context, relativePath: String, points: List<TrackPoint>) {
        val appContext = context.applicationContext
        catalogScope.launch {
            ensureDiskCacheLoaded(appContext)
            val source = sourceFromRelativePath(appContext, relativePath) ?: return@launch
            val stats = statsFromTrackPoints(points)
            cacheMutex.withLock {
                parsedTrackCache[source.id] = CachedParsedTrack(
                    sourceId = source.id,
                    displayName = source.displayName,
                    folderName = source.folderName,
                    stats = stats,
                    pointCache = points
                )
                _tracks.value = parsedTrackCache.toMap()
                hasScannedStorage = true
            }
            persistTrackCache(appContext)
        }
    }

    fun onTrackSavedById(
        context: Context,
        trackId: String,
        displayName: String,
        folderName: String?,
        points: List<TrackPoint>
    ) {
        val appContext = context.applicationContext
        catalogScope.launch {
            ensureDiskCacheLoaded(appContext)
            val stats = statsFromTrackPoints(points)
            cacheMutex.withLock {
                parsedTrackCache[trackId] = CachedParsedTrack(
                    sourceId = trackId,
                    displayName = displayName,
                    folderName = folderName,
                    stats = stats,
                    pointCache = points
                )
                _tracks.value = parsedTrackCache.toMap()
                hasScannedStorage = true
            }
            persistTrackCache(appContext)
        }
    }

    fun onTrackRenamed(context: Context, oldTrackId: String, newTrackId: String, newDisplayName: String) {
        val appContext = context.applicationContext
        catalogScope.launch {
            ensureDiskCacheLoaded(appContext)
            var renamed = false
            cacheMutex.withLock {
                val existing = parsedTrackCache.remove(oldTrackId)
                if (existing != null) {
                    parsedTrackCache[newTrackId] = existing.copy(sourceId = newTrackId, displayName = newDisplayName)
                    _tracks.value = parsedTrackCache.toMap()
                    hasScannedStorage = true
                    renamed = true
                }
            }
            if (renamed) persistTrackCache(appContext)
        }
    }

    fun onTrackMoved(context: Context, oldTrackId: String, newTrackId: String, destinationFolder: String?) {
        val appContext = context.applicationContext
        catalogScope.launch {
            ensureDiskCacheLoaded(appContext)
            var shouldPersist = false
            var needsFallbackLoad = false
            cacheMutex.withLock {
                val existing = parsedTrackCache.remove(oldTrackId)
                if (existing != null) {
                    parsedTrackCache[newTrackId] = existing.copy(sourceId = newTrackId, folderName = destinationFolder)
                    _tracks.value = parsedTrackCache.toMap()
                    hasScannedStorage = true
                    shouldPersist = true
                } else {
                    needsFallbackLoad = true
                }
            }
            if (needsFallbackLoad) {
                val loaded = loadTrackForCacheById(appContext, newTrackId, destinationFolder)
                if (loaded != null) {
                    cacheMutex.withLock {
                        if (!parsedTrackCache.containsKey(newTrackId)) {
                            parsedTrackCache[newTrackId] = loaded
                            _tracks.value = parsedTrackCache.toMap()
                            hasScannedStorage = true
                            shouldPersist = true
                        }
                    }
                }
            }
            if (shouldPersist) persistTrackCache(appContext)
        }
    }


    private suspend fun loadTrackForCacheById(context: Context, trackId: String, folderName: String?): CachedParsedTrack? {
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

        val coeff = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
        val stats = runCatching { source.openStream().use { parseGpxTrackStats(it, coeff) } }.getOrNull() ?: return null
        return CachedParsedTrack.from(source.copy(folderName = folderName), stats)
    }


    fun onTrackDeleted(context: Context, trackId: String) {
        val appContext = context.applicationContext
        catalogScope.launch {
            ensureDiskCacheLoaded(appContext)
            var deleted = false
            cacheMutex.withLock {
                if (parsedTrackCache.remove(trackId) != null) {
                    _tracks.value = parsedTrackCache.toMap()
                    hasScannedStorage = true
                    deleted = true
                }
            }
            if (deleted) persistTrackCache(appContext)
        }
    }

    data class TrackPlotData(
        val id: String,
        val title: String,
        val points: List<TrackPoint>
    )

    suspend fun loadTrackSamplesById(context: Context, trackId: String): TrackPlotData? {
        ensureDiskCacheLoaded(context)
        rebuildTrackCacheIfNeeded(context)

        val (points, displayName) = cacheMutex.withLock {
            val cached = parsedTrackCache[trackId] ?: return@withLock null to null
            if (cached.hasPoints()) {
                cached.pointsOrEmpty() to cached.displayName
            } else {
                null to cached.displayName
            }
        }

        if (points != null && displayName != null) {
            return TrackPlotData(id = trackId, title = displayName, points = points)
        }

        if (displayName == null) return null

        val coeff = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        // Need to parse outside the lock to avoid blocking other readers
        val parsed = withContext(Dispatchers.IO) {
            openInputStreamForTrack(context, trackId)?.use { parseGpxTrack(it, coeff) }
        } ?: return null

        cacheMutex.withLock {
            val cached = parsedTrackCache[trackId] ?: return@withLock
            val updated = cached.withPoints(parsed.points)
            parsedTrackCache[trackId] = updated
            _tracks.value = parsedTrackCache.toMap()
        }

        return TrackPlotData(id = trackId, title = displayName, points = parsed.points)
    }
    fun folderItemId(folderName: String): String = "folder:$folderName"

    private data class CurrentTrackData(
        val points: List<TrackPoint>,
        val stats: TrackStats
    )

    private fun currentTrackData(
        activePoints: List<TrackPoint>
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
        val durationMillis = (points.sumOf { it.seconds } * 1000.0).roundToLong().coerceAtLeast(0L)
        return TrackStats(points.size, durationMillis, distance)
    }

    private suspend fun parseGpxTrack(inputStream: InputStream, coeff: Double): GpxReader.TrackWithStats? {
        return withContext(Dispatchers.IO) {
            GpxReader.readTrackWithStats(inputStream, cpsCoefficient = coeff)
        }
    }

    private suspend fun parseGpxTrackStats(inputStream: InputStream, coeff: Double): TrackStats? {
        return withContext(Dispatchers.IO) {
            GpxReader.readTrackStats(inputStream, cpsCoefficient = coeff)
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
        val durationText = String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
        
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
        val pointCache: List<TrackPoint>? = null
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
            fun from(source: TrackSource, stats: TrackStats): CachedParsedTrack {
                return CachedParsedTrack(
                    sourceId = source.id,
                    displayName = source.displayName,
                    folderName = source.folderName,
                    stats = stats,
                    pointCache = null
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

    private suspend fun listTrackFiles(context: Context): TrackSourceListing {
        return withContext(Dispatchers.IO) {
            try {
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
    }

    private suspend fun ensureDiskCacheLoaded(context: Context) {
        if (diskCacheLoaded) return
        cacheMutex.withLock {
            if (diskCacheLoaded) return
            withContext(Dispatchers.IO) {
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
                    }
                }
            }
            _tracks.value = parsedTrackCache.toMap()
            diskCacheLoaded = true
        }
    }

    private suspend fun persistTrackCache(context: Context) {
        cacheMutex.withLock {
            val cacheFile = trackCacheFile(context)
            withContext(Dispatchers.IO) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    BufferedWriter(cacheFile.writer()).use { writer ->
                        val tracks = JSONArray()
                        parsedTrackCache.values
                            .sortedBy { it.displayName.lowercase() }
                            .forEach { tracks.put(it.toJson()) }
                        val subfolders = JSONArray()
                        parsedTrackCache.values
                            .mapNotNull { it.folderName }
                            .distinct()
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
