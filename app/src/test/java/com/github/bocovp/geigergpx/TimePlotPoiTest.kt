package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Looper
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = GeigerGpxApp::class)
class TimePlotPoiTest {
    @Test
    fun poiAppearsOnTheDisplayedSavedTrackWithoutSwitchingTracks() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as GeigerGpxApp
        val point = TrackPoint(1.0, 2.0, 1_700_000_000_000L, 0.5, 5, 1.0)
        val points = listOf(point)
        val file = File(FileStorageManager.getRootDirectory(context), "plot.gpx")
        file.parentFile!!.mkdirs()
        file.bufferedWriter().use { GpxWriter.writeTrackXml(it, points, false) }
        val id = "file:${file.absolutePath}"
        TrackCatalog.onTrackSavedById(context, id, file.name, null, points)
        withTimeout(5_000) { TrackCatalog.allTracks.first { tracks -> tracks.any { it.id == id } } }
        TrackSelectionPrefs.setTrackSelected(context, id, true)

        val intent = Intent(context, TimePlotActivity::class.java).putExtra(TimePlotActivity.EXTRA_TRACK_ID, id)
        val controller = Robolectric.buildActivity(TimePlotActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            awaitUi {
                ReflectionHelpers.getField<String?>(activity, "selectedTrackIdForPlot") == id &&
                    ReflectionHelpers.getField<List<TrackPoint>>(activity, "currentPoints").isNotEmpty()
            }
            val view = activity.findViewById<TimePlotView>(R.id.timePlotView)
            assertTrue(ReflectionHelpers.getField<List<Pair<Double, String>>>(view, "poiMarkers").isEmpty())

            val poi = PoiEntry("poi", point.timeMillis, 1.0, 2.0, 0.5, 5, 1.0, "New marker")
            assertTrue(TrackPoiStorage.addPoi(context, id, poi))
            awaitUi { ReflectionHelpers.getField<List<Pair<Double, String>>>(view, "poiMarkers").isNotEmpty() }

            assertEquals(id, ReflectionHelpers.getField<String>(activity, "selectedTrackIdForPlot"))
            assertEquals(listOf("New marker"), ReflectionHelpers.getField<List<Pair<Double, String>>>(view, "poiMarkers").map { it.second })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private suspend fun awaitUi(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (true) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) break
                delay(10)
            }
        }
    }
}
