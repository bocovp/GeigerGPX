package com.github.bocovp.geigergpx

import android.location.Location
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import kotlin.math.roundToLong

object GpxReader {

    private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

    private val parserFactory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    data class TrackMetadata(
        val pointCount: Int?,
        val distanceMeters: Double,
        val counts: Long,
        val seconds: Double,
        val doseMuSv: Double?,
        val cpsToUsvh: Double?
    )

    data class TrackWithStats(
        val points: List<TrackPoint>,
        val stats: TrackStats
    )

    private data class ParsedTrackData(
        val points: List<TrackPoint>,
        val stats: TrackStats,
        val metadata: TrackMetadata?
    )

    fun readTrack(inputStream: InputStream, cpsCoefficient: Double = 1.0): List<TrackPoint>? {
        val parsed = readTrackInternal(inputStream, cpsCoefficient, parsePoints = true, preferMetadataStats = false)
            ?: return null
        return parsed.points
    }

    fun readTrackWithStats(inputStream: InputStream, cpsCoefficient: Double = 1.0): TrackWithStats? {
        val parsed = readTrackInternal(inputStream, cpsCoefficient, parsePoints = true, preferMetadataStats = false)
            ?: return null
        return TrackWithStats(parsed.points, parsed.stats)
    }

    fun readTrackStats(inputStream: InputStream, cpsCoefficient: Double = 1.0): TrackStats? {
        val parsed = readTrackInternal(inputStream, cpsCoefficient, parsePoints = false, preferMetadataStats = true)
            ?: return null
        return parsed.stats
    }

    fun readPois(xml: String): List<PoiEntry> {
        if (xml.isBlank()) return emptyList()
        val parser = parserFactory.newPullParser().apply {
            setInput(StringReader(xml))
        }
        return readPois(parser)
    }

    fun readPois(inputStream: InputStream): List<PoiEntry> {
        inputStream.use { stream ->
            val parser = parserFactory.newPullParser().apply {
                setInput(stream, null)
            }
            return readPois(parser)
        }
    }

    private fun readTrackInternal(
        inputStream: InputStream,
        cpsCoefficient: Double,
        parsePoints: Boolean,
        preferMetadataStats: Boolean
    ): ParsedTrackData? {
        inputStream.use { stream ->
            val parser = parserFactory.newPullParser().apply { setInput(stream, null) }

            val points = mutableListOf<TrackPoint>()
            var firstTimestamp = Long.MAX_VALUE
            var lastTimestamp = Long.MIN_VALUE
            var pointCount = 0
            var calculatedDistance = 0.0
            var lastValidLat: Double? = null
            var lastValidLon: Double? = null

            var lat = 0.0
            var lon = 0.0
            var doseRate = 0.0
            var counts = 0
            var seconds = 0.0
            var badCoordinates = false
            var timeMs = 0L
            var insideTrkpt = false

            var metadataDistance: Double? = null
            var metadataPointCount: Int? = null
            var metadataCounts: Long? = null
            var metadataSeconds: Double? = null
            var metadataDurationMillis: Long? = null
            var metadataDose: Double? = null
            var metadataCpsToUsvh: Double? = null

            var currentTag: String? = null
            var currentNamespace: String? = null
            var insideMetadata = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        currentNamespace = parser.namespace

                        if (parser.name.equals("metadata", ignoreCase = true)) {
                            insideMetadata = true
                        }

                        if (parser.name.equals("trkpt", ignoreCase = true)) {
                            if (preferMetadataStats && metadataDistance != null && (metadataDurationMillis != null || metadataSeconds != null)) {
                                val durationFromMetadata = metadataDurationMillis
                                    ?: ((metadataSeconds ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L)
                                val stats = TrackStats(
                                    pointCount = metadataPointCount ?: 0,
                                    durationMillis = durationFromMetadata,
                                    distanceMeters = metadataDistance ?: 0.0
                                )
                                return ParsedTrackData(emptyList(), stats, buildMetadata(
                                    metadataPointCount,
                                    metadataDistance,
                                    metadataCounts,
                                    metadataSeconds,
                                    metadataDose,
                                    metadataCpsToUsvh
                                ))
                            }

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
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val value = parser.text?.trim()
                        if (insideTrkpt) {
                            when {
                                currentTag == "time" -> timeMs = parseIsoTime(value)
                                currentTag == "fix" && value.equals("none", ignoreCase = true) -> {
                                    badCoordinates = true
                                }
                                currentNamespace == RAD_NAMESPACE && currentTag == "doseRate" -> {
                                    doseRate = value?.toDoubleOrNull() ?: 0.0
                                }
                                currentNamespace == RAD_NAMESPACE && currentTag == "counts" -> {
                                    counts = value?.toIntOrNull() ?: 0
                                }
                                currentNamespace == RAD_NAMESPACE && currentTag == "seconds" -> {
                                    seconds = value?.toDoubleOrNull() ?: 0.0
                                }
                            }
                        } else if (insideMetadata && currentNamespace == RAD_NAMESPACE) {
                            when (currentTag) {
                                "pointCount" -> metadataPointCount = value?.toIntOrNull()
                                "distance" -> metadataDistance = value?.toDoubleOrNull()
                                "counts" -> metadataCounts = value?.toLongOrNull()
                                "seconds" -> metadataSeconds = value?.toDoubleOrNull()
                                "duration" -> metadataDurationMillis = value?.toLongOrNull()
                                "dose" -> metadataDose = value?.toDoubleOrNull()
                                "cpsToUsvh" -> metadataCpsToUsvh = value?.toDoubleOrNull()
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("metadata", ignoreCase = true)) {
                            insideMetadata = false
                        }

                        if (parser.name == "trkpt" && insideTrkpt) {
                            val resolvedDoseRate = when {
                                doseRate > 0.0 -> doseRate
                                seconds > 0.000001 -> (counts / seconds) * cpsCoefficient
                                else -> 0.0
                            }

                            if (parsePoints) {
                                points.add(
                                    TrackPoint(
                                        latitude = lat,
                                        longitude = lon,
                                        timeMillis = timeMs,
                                        doseRate = resolvedDoseRate,
                                        counts = counts,
                                        seconds = seconds,
                                        badCoordinates = badCoordinates
                                    )
                                )
                            }

                            if (timeMs > 0L && !badCoordinates) {
                                if (timeMs < firstTimestamp) firstTimestamp = timeMs
                                if (timeMs > lastTimestamp) lastTimestamp = timeMs
                            }

                            pointCount += 1
                            if (!badCoordinates) {
                                val previousLat = lastValidLat
                                val previousLon = lastValidLon
                                if (previousLat != null && previousLon != null) {
                                    calculatedDistance += distanceBetween(previousLat, previousLon, lat, lon)
                                }
                                lastValidLat = lat
                                lastValidLon = lon
                            }
                            insideTrkpt = false
                        }
                        currentTag = null
                        currentNamespace = null
                    }
                }
                parser.next()
            }

            val metadata = buildMetadata(
                metadataPointCount,
                metadataDistance,
                metadataCounts,
                metadataSeconds,
                metadataDose,
                metadataCpsToUsvh
            )

            if (parsePoints && points.isEmpty()) return null
            val stats = when {
                preferMetadataStats && metadataDistance != null && (metadataDurationMillis != null || metadataSeconds != null) -> {
                    TrackStats(
                        pointCount = metadataPointCount ?: 0,
                        durationMillis = metadataDurationMillis
                            ?: ((metadataSeconds ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L),
                        distanceMeters = metadataDistance ?: 0.0
                    )
                }
                else -> {
                    val duration = if (lastTimestamp > firstTimestamp) lastTimestamp - firstTimestamp else 0L
                    TrackStats(pointCount = pointCount, durationMillis = duration, distanceMeters = calculatedDistance)
                }
            }

            return ParsedTrackData(points, stats, metadata)
        }
    }

    private fun buildMetadata(
        pointCount: Int?,
        distanceMeters: Double?,
        counts: Long?,
        seconds: Double?,
        dose: Double?,
        cpsToUsvh: Double?
    ): TrackMetadata? {
        if (pointCount == null && distanceMeters == null && counts == null && seconds == null && dose == null && cpsToUsvh == null) {
            return null
        }
        return TrackMetadata(
            pointCount = pointCount,
            distanceMeters = distanceMeters ?: 0.0,
            counts = counts ?: 0L,
            seconds = seconds ?: 0.0,
            doseMuSv = dose,
            cpsToUsvh = cpsToUsvh
        )
    }

    private fun readPois(parser: XmlPullParser): List<PoiEntry> {
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
                        val id = buildPoiId(time, lat, lon)
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

    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun parseIsoTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value.trim()).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
