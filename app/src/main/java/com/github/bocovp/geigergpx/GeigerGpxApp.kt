package com.github.bocovp.geigergpx

import android.app.Application

class GeigerGpxApp : Application() {
    val trackingRepository: TrackingRepository by lazy { TrackingRepository() }
    private var restoredBackupName: String? = null
    var isMainToolbarTitleHidden: Boolean = false
    var selectedTimePlotTrackId: String? = null

    override fun onCreate() {
        super.onCreate()
        // Restore abandoned backup once per process start. This avoids restoring during
        // Activity recreation (e.g. screen rotation) while tracking is ongoing.
        restoredBackupName = GpxWriter.restoreBackupIfPresent(this)
    }

    fun consumeRestoredBackupName(): String? {
        val name = restoredBackupName
        restoredBackupName = null
        return name
    }
}
