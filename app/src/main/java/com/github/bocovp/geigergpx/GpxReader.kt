package com.github.bocovp.geigergpx

import android.location.Location
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import java.time.Instant

object GpxReader {

    private const val RAD_NAMESPACE = "https://github.com/bocovp/GeigerGPX"

    private val parserFactory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }
    data class TrackReadResult(
        val samples: List<TrackSample>,
        val stats: TrackStats,
        val points: List<TrackPoint>
    )

    fun readTrack(inputStream: InputStream, cpsCoefficient: Double = 1.0): TrackReadResult? {
        inputStream.use { stream ->
            val parser = parserFactory.newPullParser().apply {
                setInput(stream, null)
            }

            val samples = mutableListOf<TrackSample>()
            val points = mutableListOf<TrackPoint>()
            var firstTimestamp = Long.MAX_VALUE
            var lastTimestamp = Long.MIN_VALUE

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
                        }
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
                            val cps = when {
                                seconds > 0.000001 -> counts / seconds
                                cpsCoefficient > 0.0 -> doseRate / cpsCoefficient
                                else -> doseRate
                            }
                            val resolvedDoseRate = when {
                                doseRate > 0.0 -> doseRate
                                cpsCoefficient > 0.0 -> cps * cpsCoefficient
                                else -> cps
                            }
                            samples.add(TrackSample(lat, lon, resolvedDoseRate, counts, seconds, badCoordinates))
                            points.add(
                                TrackPoint(
                                    latitude = lat,
                                    longitude = lon,
                                    timeMillis = timeMs,
                                    distanceFromLast = 0.0,
                                    cps = cps,
                                    counts = counts,
                                    seconds = seconds,
                                    badCoordinates = badCoordinates
                                )
                            )
                            if (timeMs > 0L && !badCoordinates) {
                                if (timeMs < firstTimestamp) {
                                    firstTimestamp = timeMs
                                }
                                if (timeMs > lastTimestamp) {
                                    lastTimestamp = timeMs
                                }
                            }
                            insideTrkpt = false
                        }
                        currentTag = null
                        currentNamespace = null
                    }
                }
                parser.next()
            }

            if (samples.isEmpty()) return null
            return TrackReadResult(
                samples = samples,
                stats = buildTrackStats(samples, firstTimestamp, lastTimestamp),
                points = points
            )
        }
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

    private fun buildTrackStats(samples: List<TrackSample>, firstTimestamp: Long, lastTimestamp: Long): TrackStats {
        var distance = 0.0
        var lastValid: TrackSample? = null
        for (sample in samples) {
            if (sample.badCoordinates) continue
            val previous = lastValid
            if (previous != null) {
                distance += distanceBetween(
                    previous.latitude,
                    previous.longitude,
                    sample.latitude,
                    sample.longitude
                )
            }
            lastValid = sample
        }

        val duration = if (lastTimestamp > firstTimestamp) lastTimestamp - firstTimestamp else 0L
        return TrackStats(samples.size, duration, distance)
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
