package com.github.bocovp.geigergpx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TrackingRepositoryTest {
    private fun poi(index: Int) = PoiEntry("$index", index.toLong(), 1.0, 2.0, 0.5, 5, 1.0, "POI $index")

    @Test
    fun finalSnapshotClosesAdmissionBeforeFileWriting() {
        val repo = TrackingRepository()
        repo.beginNewTrack()
        repo.updateTrackingState(true, "Working")
        assertTrue(repo.addActiveTrackPoi(poi(1)))

        val finalSnapshot = repo.finishActiveTrack(emptyList())
        // The service still reports tracking while the asynchronous writer is busy.
        assertTrue(repo.isTracking.value)
        assertFalse(repo.addActiveTrackPoi(poi(2)))
        repo.clearActiveTrackPois()
        assertEquals(listOf(poi(1)), finalSnapshot.pois)

        repo.updateTrackingState(false, "Waiting")
        repo.beginNewTrack()
        repo.updateTrackingState(true, "Working")
        assertTrue(repo.addActiveTrackPoi(poi(3)))
        assertEquals(listOf(poi(3)), repo.activeTrackSnapshot().pois)
    }

    @Test
    fun racingAdditionsAreEitherInFinalSnapshotOrRejected() {
        val repo = TrackingRepository()
        repo.beginNewTrack()
        repo.updateTrackingState(true, "Working")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val additions = (1..100).map { index ->
                executor.submit<Pair<PoiEntry, Boolean>> {
                    start.await()
                    val poi = poi(index)
                    poi to repo.addActiveTrackPoi(poi)
                }
            }
            val final = executor.submit<TrackingRepository.TrackSnapshot> {
                start.await()
                repo.finishActiveTrack(emptyList())
            }
            start.countDown()
            val accepted = additions.map { it.get(5, TimeUnit.SECONDS) }.filter { it.second }.map { it.first }
            assertEquals(accepted.toSet(), final.get(5, TimeUnit.SECONDS).pois.toSet())
            assertFalse(repo.addActiveTrackPoi(poi(101)))
        } finally {
            executor.shutdownNow()
        }
    }
}
