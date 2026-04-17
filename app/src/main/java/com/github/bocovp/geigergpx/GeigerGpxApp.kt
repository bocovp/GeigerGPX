package com.github.bocovp.geigergpx

import android.app.Application

class GeigerGpxApp : Application() {
    val trackingRepository: TrackingRepository by lazy { TrackingRepository() }
    private var restoredBackupName: String? = null
    private var backupRestoreAttempted: Boolean = false
    var isMainToolbarTitleHidden: Boolean = false
    var selectedTimePlotTrackId: String? = null
    @Volatile var sharedKdeSliderInternalValue: Float = 0f

    override fun onCreate() {
        super.onCreate()
    }

    @Synchronized
    fun restoreBackupIfNeeded(): String? {
        if (!backupRestoreAttempted) {
            backupRestoreAttempted = true // Mark as attempted immediately

            // Abort restoration if a tracking session is already active.
            // This prevents moving/deleting the Backup.gpx file that the
            // background service is currently writing to.
            if (trackingRepository.isTracking.value == true) {
                return null
            }

            restoredBackupName = GpxWriter.restoreBackupIfPresent(this)        }
        return consumeRestoredBackupName()
    }

    fun consumeRestoredBackupName(): String? {
        val name = restoredBackupName
        restoredBackupName = null
        return name
    }
}
