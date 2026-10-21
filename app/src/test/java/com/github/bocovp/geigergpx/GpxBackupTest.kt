package com.github.bocovp.geigergpx

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = GeigerGpxApp::class)
class GpxBackupTest {
    private val poi = PoiEntry("poi", 1_700_000_000_000L, 1.0, 2.0, 0.5, 5, 1.0, "POI & name")
    private val point = TrackPoint(1.0, 2.0, poi.timestampMillis, 0.5, 5, 1.0)

    @Test
    fun backupRecoveryPreservesTrackAndPoi() = runBlocking {
        assertBackupRecovery(listOf(point))
    }

    @Test
    fun backupRecoveryAlsoPreservesPoiWithoutTrackPoints() = runBlocking {
        assertBackupRecovery(emptyList())
    }

    private suspend fun assertBackupRecovery(points: List<TrackPoint>) {
        val context = RuntimeEnvironment.getApplication() as GeigerGpxApp
        val repo = context.trackingRepository
        repo.beginNewTrack()
        repo.updateTrackingState(true, "Working")
        repo.setActiveTrackPoints(points)
        assertTrue(repo.addActiveTrackPoi(poi))
        val snapshot = repo.activeTrackSnapshot()
        assertNotNull(GpxWriter.saveBackup(context, snapshot.points, snapshot.pois))

        // Recovery must rely on the backup file, with no surviving in-memory queue.
        repo.finishActiveTrack(points)
        repo.clearActiveTrackPois()
        val restoredName = GpxWriter.restoreBackupIfPresent(context)
        assertNotNull(restoredName)
        assertNull(GpxWriter.backupUri(context))
        val restored = File(FileStorageManager.getRootDirectory(context), restoredName!!)
        val loaded = EditableTrackStorage.loadTrack(context, "file:${restored.absolutePath}")!!
        assertEquals(points, loaded.points)
        assertEquals(listOf(poi.description), loaded.pois.map { it.description })
        assertEquals(poi.timestampMillis, loaded.pois.single().timestampMillis)
        assertEquals(poi.counts, loaded.pois.single().counts)
        assertEquals(poi.seconds, loaded.pois.single().seconds, 0.0)
        assertEquals(poi.latitude, loaded.pois.single().latitude, 0.0)
        assertEquals(poi.longitude, loaded.pois.single().longitude, 0.0)
        assertEquals(poi.doseRate, loaded.pois.single().doseRate, 0.0)
    }
}
