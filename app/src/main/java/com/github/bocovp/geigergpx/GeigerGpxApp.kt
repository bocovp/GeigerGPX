package com.github.bocovp.geigergpx

import android.app.Application

class GeigerGpxApp : Application() {
    val trackingRepository: TrackingRepository by lazy { TrackingRepository() }
    private var restoredBackupName: String? = null
    private var backupRestoreAttempted: Boolean = false
    var isMainToolbarTitleHidden: Boolean = false
    var selectedTimePlotTrackId: String? = null

    override fun onCreate() {
        super.onCreate()
    }

    @Synchronized
    fun restoreBackupIfNeeded(): String? {
        if (!backupRestoreAttempted) {
            // Restore abandoned backup once per process start. This avoids restoring during
            // Activity recreation (e.g. screen rotation) while tracking is ongoing.
            restoredBackupName = GpxWriter.restoreBackupIfPresent(this)
            backupRestoreAttempted = true
        }
        return consumeRestoredBackupName()
    }

    fun consumeRestoredBackupName(): String? {
        val name = restoredBackupName
        restoredBackupName = null
        return name
    }
}
