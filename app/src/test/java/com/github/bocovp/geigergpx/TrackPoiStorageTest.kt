package com.github.bocovp.geigergpx

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = GeigerGpxApp::class)
class TrackPoiStorageTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://test/track.gpx")
    private val trackId = "doc:$uri"
    private val poi = PoiEntry("poi", 1_700_000_000_000L, 1.0, 2.0, 0.5, 5, 1.0, "Test POI")

    private fun context(resolver: ContentResolver) = object : ContextWrapper(app) {
        override fun getContentResolver(): ContentResolver = resolver
    }

    private fun trackXml(): String = java.io.StringWriter().also { text ->
        val points = listOf(TrackPoint(1.0, 2.0, poi.timestampMillis, 0.5, 5, 1.0))
        text.buffered().use { GpxWriter.writeTrackXml(it, points, false) }
    }.toString()

    @Test
    fun overlappingSavesPreserveBothPois() = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val xml = AtomicReference(trackXml())
        val firstRead = CountDownLatch(1)
        val secondRead = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val reads = AtomicInteger()
        `when`(resolver.openInputStream(uri)).thenAnswer {
            val snapshot = xml.get()
            if (reads.incrementAndGet() == 1) {
                firstRead.countDown()
                check(releaseFirstRead.await(5, TimeUnit.SECONDS))
            } else {
                secondRead.countDown()
            }
            snapshot.byteInputStream()
        }
        `when`(resolver.openOutputStream(uri, "wt")).thenAnswer {
            object : ByteArrayOutputStream() {
                override fun close() {
                    xml.set(toString("UTF-8"))
                }
            }
        }
        val context = context(resolver)
        val first = async(Dispatchers.Default) { TrackPoiStorage.addPoi(context, trackId, poi) }
        try {
            assertTrue(firstRead.await(5, TimeUnit.SECONDS))
            // Exercise the alternative catalog prefix for the same document, too.
            val second = async(Dispatchers.Default) {
                TrackPoiStorage.addPoi(context, "tree:$uri", poi.copy(description = "Second POI"))
            }
            val overlappedRead = secondRead.await(200, TimeUnit.MILLISECONDS)
            releaseFirstRead.countDown()
            assertTrue(first.await())
            assertTrue(second.await())
            assertFalse("The load/append/write transaction must hold the document lock", overlappedRead)
            assertEquals(setOf("Test POI", "Second POI"), GpxReader.readPois(xml.get()).map { it.description }.toSet())
        } finally {
            releaseFirstRead.countDown()
        }
    }

    @Test
    fun unavailableOrMalformedInputReturnsFailure(): Unit = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val context = context(resolver)
        `when`(resolver.openInputStream(uri)).thenThrow(FileNotFoundException("deleted"))
        assertFalse(TrackPoiStorage.addPoi(context, trackId, poi))
        doThrow(SecurityException("permission revoked")).`when`(resolver).openInputStream(uri)
        assertFalse(TrackPoiStorage.addPoi(context, trackId, poi))
        doReturn("<gpx><wpt".byteInputStream()).`when`(resolver).openInputStream(uri)
        assertFalse(TrackPoiStorage.addPoi(context, trackId, poi))
        verify(resolver, never()).openOutputStream(any(Uri::class.java), eq("wt"))
    }

    @Test
    fun nullOutputStreamIsFailure() = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val original = trackXml()
        `when`(resolver.openInputStream(uri)).thenAnswer { original.byteInputStream() }
        `when`(resolver.openOutputStream(uri, "wt")).thenReturn(null)
        val context = context(resolver)
        val cachedPoi = poi.copy(description = "Already cached")
        val points = listOf(TrackPoint(1.0, 2.0, poi.timestampMillis, 0.5, 5, 1.0))
        TrackCatalog.onTrackSavedById(app, trackId, "track.gpx", null, points, pois = listOf(cachedPoi))
        withTimeout(5_000) { TrackCatalog.allTracks.first { tracks -> tracks.any { it.id == trackId } } }
        assertFalse(EditableTrackStorage.overwriteTrack(context, trackId, emptyList(), pois = listOf(poi)))
        assertFalse(TrackPoiStorage.addPoi(context, trackId, poi))
        assertEquals(listOf(cachedPoi), TrackCatalog.loadTrackSamplesById(context, trackId)!!.pois)
    }

    @Test
    fun successfulSaveRefreshesTheAlreadyLoadedTrackPois() = runBlocking {
        val file = File(app.cacheDir, "saved-track.gpx")
        file.writeText(trackXml())
        val id = "file:${file.absolutePath}"
        val points = listOf(TrackPoint(1.0, 2.0, poi.timestampMillis, 0.5, 5, 1.0))
        TrackCatalog.onTrackSavedById(app, id, file.name, null, points)
        withTimeout(5_000) { TrackCatalog.allTracks.first { tracks -> tracks.any { it.id == id } } }
        assertTrue(TrackCatalog.loadTrackSamplesById(app, id)!!.pois.isEmpty())
        assertTrue(TrackPoiStorage.addPoi(app, id, poi))
        assertEquals(listOf(poi.description), TrackCatalog.loadTrackSamplesById(app, id)!!.pois.map { it.description })
        assertEquals(listOf(poi.description), EditableTrackStorage.loadTrack(app, id)!!.pois.map { it.description })
        assertEquals(trackXml(), File(file.parentFile, "saved-track.rc").readText())
    }

    @Test
    fun currentTrackSaveReportsAdmissionResult() = runBlocking {
        val repo = (app as GeigerGpxApp).trackingRepository
        repo.beginNewTrack()
        repo.updateTrackingState(true, "Working")
        assertTrue(TrackPoiStorage.addPoi(app, TrackCatalog.currentTrackId(), poi.copy(deviceName = "device")))
        val final = repo.finishActiveTrack(emptyList())
        assertFalse(TrackPoiStorage.addPoi(app, TrackCatalog.currentTrackId(), poi))
        assertEquals(listOf(poi), final.pois)
    }
}
