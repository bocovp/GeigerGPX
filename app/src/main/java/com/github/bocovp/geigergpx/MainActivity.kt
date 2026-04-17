package com.github.bocovp.geigergpx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.github.bocovp.geigergpx.databinding.ActivityMainBinding
import android.widget.Toast
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.icu.util.Measure
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var latestCpsSnapshot = TrackingRepository.CpsSnapshot()
    private var isMeasurementModeEnabled: Boolean = false
    private var doseRateDisplayMode: ConfidenceInterval.DisplayMode = ConfidenceInterval.DisplayMode.PLUS_MINUS
    private var keepScreenOnEnabled: Boolean = false
    private var openSavedTrackPlotAfterStop = false
    private var trackSavedReceiverRegistered = false
    private var pendingRestoreAfterStartupFolderValidation = false
    private val statePendingStartupRestore = "state_pending_startup_restore"

    private val trackSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingService.ACTION_TRACK_SAVED) return
            val trackId = intent.getStringExtra(TrackingService.EXTRA_TRACK_ID) ?: return
            TrackSelectionPrefs.replaceSelectedTrackId(this@MainActivity, TrackCatalog.currentTrackId(), trackId)
            TimePlotActivity.rememberTrackSelection(this@MainActivity, trackId)
            if (!openSavedTrackPlotAfterStop) return
            openSavedTrackPlotAfterStop = false
            startActivity(
                Intent(this@MainActivity, TimePlotActivity::class.java)
                    .putExtra(TimePlotActivity.EXTRA_TRACK_ID, trackId)
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // User can press Start again after granting permissions
    }

    private val folderPickerForStop = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(SettingsFragment.KEY_GPX_TREE_URI, uri.toString())
                .apply()
        }
        // Stop tracking regardless of whether a folder was chosen or cancelled
        stopTracking()
    }

    private val folderPickerForStartupValidation = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(SettingsFragment.KEY_GPX_TREE_URI, uri.toString())
                .apply()
        } else {
            FileStorageManager.clearConfiguredTreeUri(this)
        }
        if (pendingRestoreAfterStartupFolderValidation) {
            pendingRestoreAfterStartupFolderValidation = false
            restoreStartupBackupIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRestoreAfterStartupFolderValidation =
            savedInstanceState?.getBoolean(statePendingStartupRestore, false) ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        applyToolbarTitleVisibility()
        setupToolbarTitleLongPress()

        validateConfiguredSaveFolderAtStartup {
            restoreStartupBackupIfNeeded()
        }

        binding.buttonStart.setOnClickListener {
            if (viewModel.isTracking.value == true) {
                if ((viewModel.pointCount.value ?: 0) == 0) {
                    cancelTracking()
                } else {
                    showCancelTrackConfirmation()
                }
            } else if (ensureTrackingPrerequisites()) {
                startTracking()
            }
        }

        binding.buttonStop.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val treeUri = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)
            if (treeUri.isNullOrBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Choose save folder")
                    .setMessage("No save folder is set. Please choose a folder where the GPX file will be saved.")
                    .setPositiveButton("Choose folder") { _, _ ->
                        folderPickerForStop.launch(null)
                    }
                    .setNegativeButton("Use app folder") { _, _ ->
                        stopTracking()
                    }
                    .show()
            } else {
                stopTracking()
            }
        }

        binding.buttonMeasurementMode.setOnClickListener {
            dispatchTrackingAction(TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE)
        }

        binding.buttonSavePoi.setOnClickListener {
            showSavePoiDialog()
        }

        syncBottomNavigationSelection()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> true
                R.id.navigation_map -> {
                    openMap()
                    true
                }
                R.id.navigation_tracks -> {
                    openTracks()
                    true
                }
                R.id.navigation_poi -> {
                    openPoi()
                    true
                }
                R.id.navigation_time_plot -> {
                    openTimePlot()
                    true
                }
                else -> false
            }
        }

        binding.textCps.setOnClickListener {
            doseRateDisplayMode = when (doseRateDisplayMode) {
                ConfidenceInterval.DisplayMode.INTERVAL -> ConfidenceInterval.DisplayMode.PLUS_MINUS
                ConfidenceInterval.DisplayMode.PLUS_MINUS -> ConfidenceInterval.DisplayMode.INTERVAL
                ConfidenceInterval.DisplayMode.AUTO -> ConfidenceInterval.DisplayMode.PLUS_MINUS
            }
            updateCpsOrDoseLine(false)
        }

        observeViewModel()
        requestPermissionsOnAppStart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(statePendingStartupRestore, pendingRestoreAfterStartupFolderValidation)
    }

    private fun validateConfiguredSaveFolderAtStartup(onValidationComplete: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val treeUriString = prefs.getString(SettingsFragment.KEY_GPX_TREE_URI, null)
        val defaultFolderError = FileStorageManager.getDefaultFolderWriteProbeError(this)
        if (defaultFolderError != null) {
            Toast.makeText(
                this,
                "Warning: app default save folder is not writable: ${defaultFolderError.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
        if (treeUriString.isNullOrBlank()) {
            onValidationComplete()
            return
        }

        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull()
        val treeRoot = treeUri?.let { DocumentFile.fromTreeUri(this, it) }
        val isValidFolder = treeRoot?.isDirectory == true
        val canWrite = isValidFolder && FileStorageManager.isConfiguredFolderWritable(this)
        if (isValidFolder && canWrite) {
            onValidationComplete()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Save folder unavailable")
            .setMessage(
                "Configured save folder is invalid or not writable. Choose another folder or app will fall back to default folder."
            )
            .setPositiveButton("Choose folder") { _, _ ->
                pendingRestoreAfterStartupFolderValidation = true
                folderPickerForStartupValidation.launch(null)
            }
            .setNegativeButton("Use default folder") { _, _ ->
                FileStorageManager.clearConfiguredTreeUri(this)
                onValidationComplete()
            }
            .setCancelable(false)
            .show()
    }

    private fun restoreStartupBackupIfNeeded() {
        Thread {
            val restoredName = (application as GeigerGpxApp).restoreBackupIfNeeded()
            if (restoredName != null) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        "Backup file was restored and saved as $restoredName",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }


    private fun syncBottomNavigationSelection() {
        binding.bottomNavigation.menu.findItem(R.id.navigation_home)?.isChecked = true
    }

    private fun applyToolbarTitleVisibility() {
        val app = application as GeigerGpxApp
        binding.topAppBar.title = if (app.isMainToolbarTitleHidden) "" else getString(R.string.app_name)
    }

    private fun setupToolbarTitleLongPress() {
        binding.topAppBar.post {
            val titleView = findToolbarTitleTextView() ?: return@post
            titleView.setOnLongClickListener {
                val app = application as GeigerGpxApp
                if (!app.isMainToolbarTitleHidden) {
                    app.isMainToolbarTitleHidden = true
                    applyToolbarTitleVisibility()
                    Toast.makeText(this, "App name hidden until next launch", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun findToolbarTitleTextView(): TextView? {
        val toolbarTitle = binding.topAppBar.title?.toString() ?: return null
        for (index in 0 until binding.topAppBar.childCount) {
            val child = binding.topAppBar.getChildAt(index)
            if (child is TextView && child.text.toString() == toolbarTitle) {
                return child
            }
        }
        return null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
            menu.setGroupDividerEnabled(true)
        }
        refreshKeepScreenOnMenuItem(menu.findItem(R.id.action_keep_screen_on))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        refreshKeepScreenOnMenuItem(menu.findItem(R.id.action_keep_screen_on))
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_keep_screen_on -> {
                keepScreenOnEnabled = !keepScreenOnEnabled
                applyKeepScreenOnFlag()
                refreshKeepScreenOnMenuItem(item)
                true
            }
            R.id.action_map -> {
                openMap()
                true
            }
            R.id.action_poi -> {
                openPoi()
                true
            }
            R.id.action_tracks -> {
                openTracks()
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            R.id.action_plot -> {
                openTimePlot()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshKeepScreenOnMenuItem(item: MenuItem?) {
        item ?: return
        val title = if (keepScreenOnEnabled) "Screen stay awake: ON" else "Screen stay awake: OFF"
        val iconRes = if (keepScreenOnEnabled) {
            R.drawable.baseline_lock_24
        } else {
            R.drawable.baseline_lock_open_24
        }
        item.title = title
        item.setIcon(iconRes)
    }

    private fun applyKeepScreenOnFlag() {
        if (keepScreenOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun openMap() {
        startActivity(Intent(this, MapActivity::class.java))
    }

    private fun openPoi() {
        startActivity(Intent(this, PoiActivity::class.java))
    }

    private fun openTracks() {
        startActivity(Intent(this, TracksActivity::class.java))
    }

    private fun openTimePlot() {
        openSavedTrackPlotAfterStop = false
        startActivity(Intent(this, TimePlotActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        registerTrackSavedReceiverIfNeeded()
        syncBottomNavigationSelection()
        applyKeepScreenOnFlag()
        updateCpsOrDoseLine(false)
        refreshTrackDuration(viewModel.trackDurationSeconds.value ?: 0L)
        refreshMeasurementDurationFromTimer()
        updateCountDisplay(viewModel.countDisplayState.value ?: TrackingViewModel.CountDisplayState())
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        unregisterTrackSavedReceiverIfNeeded()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // onPause can coincide with a foreground->background transition.
        // During that handoff, plain startService() may throw IllegalStateException.
        // dispatchTrackingAction() uses foreground-safe dispatch so we never crash.
        // Keep monitoring active in background while tracking or while
        // measurement mode is enabled.
        if (viewModel.isTracking.value != true && !isMeasurementModeEnabled) {
            stopMonitoring()
        }
    }

    private fun refreshTrackDuration(seconds: Long) {
        binding.textDuration.text = "Duration: ${TrackingRepository.formatDuration(seconds)}"
    }

    private fun updateCountDisplay(state: TrackingViewModel.CountDisplayState) {
        binding.textTrackCounts.text = "Track counts: ${state.trackCounts}"
        binding.textMeasurementCounts.text = "Counts: ${state.measurementCounts}"
        binding.textTotalCounts.text = "Total counts: ${state.totalCounts}"
    }

    private fun refreshMeasurementDurationFromTimer() {
        val measurementDurationSeconds = if (isMeasurementModeEnabled) {
            val measurementStart = latestCpsSnapshot.measurementStartTimestampMillis
            if (measurementStart > 0L) {
                ((System.currentTimeMillis() - measurementStart).toDouble() / 1000.0)
                    .coerceAtLeast(0.0)
            } else {
                0.0
            }
        } else {
            0.0
        }
        binding.textMeasurementDuration.text = "Duration: ${measurementDurationSeconds.roundToInt()} s"
    }

    private fun observeViewModel() {
        viewModel.isTracking.observe(this) { tracking ->
            binding.buttonStart.text = if (tracking) "Cancel" else "Start track"
            binding.buttonStop.isEnabled = tracking
        }

        viewModel.trackDurationSeconds.observe(this) { seconds ->
            refreshTrackDuration(seconds)
        }

        viewModel.uiTickMillis.observe(this) {
            updateCpsOrDoseLine(false)
            refreshMeasurementDurationFromTimer()
        }

        viewModel.distanceMeters.observe(this) { dist ->
            binding.textDistance.text = "Distance: %.1f m".format(java.util.Locale.US, dist)
        }

        viewModel.pointCount.observe(this) { count ->
            binding.textPoints.text = "Points: $count"
        }

        viewModel.cpsUpdate.observe(this) { update ->
            latestCpsSnapshot = update.snapshot
            updateCpsOrDoseLine(update.onBeep)
            refreshMeasurementDurationFromTimer()
        }

        viewModel.countDisplayState.observe(this) { state ->
            updateCountDisplay(state)
        }

        viewModel.gpsStatus.observe(this) { status ->
            binding.textGpsStatus.text = "GPS status: $status"
            val color = when (status) {
                "Waiting" -> R.color.status_waiting
                "Working" -> R.color.status_working
                else -> if (status.startsWith("Spoofing detected")) {
                    R.color.status_spoofing
                } else {
                    R.color.status_waiting
                }
            }
            binding.textGpsStatus.setTextColor(ContextCompat.getColor(this, color))
        }

        viewModel.audioStatus.observe(this) { audioStatus ->
            binding.textAudioStatus.text = "Audio status: ${audioStatus.status}"
            val color = when (audioStatus.errorCode) {
                TrackingRepository.AUDIO_STATUS_WORKING -> R.color.status_working
                TrackingRepository.AUDIO_STATUS_ERROR -> R.color.status_spoofing
                else -> R.color.status_waiting
            }
            binding.textAudioStatus.setTextColor(ContextCompat.getColor(this, color))
        }

        viewModel.measurementModeEnabled.observe(this) { enabled ->
            isMeasurementModeEnabled = enabled
            binding.buttonMeasurementMode.text = if (enabled) {
                "Live Mode"
            } else {
                "Measure"
            }
            binding.buttonSavePoi.isEnabled = enabled
            updateCpsOrDoseLine(false)
            refreshMeasurementDurationFromTimer()
        }
    }

    private fun showSavePoiDialog() {
        if (!isMeasurementModeEnabled) {
            return
        }

        val input = EditText(this).apply {
            hint = "Description"
        }

        AlertDialog.Builder(this)
            .setTitle("Save POI")
            .setView(input)
            .setPositiveButton("Save POI") { _, _ ->
                val description = input.text?.toString()?.trim().orEmpty()
                val (counts, seconds) = getCurrentMeasurementCountsAndSeconds()
                val coeff = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0
                val doseRate = ConfidenceInterval(0.0, seconds, counts, false).scale(coeff).mean
//                  val doseRate = ConfidenceInterval(0.0, seconds, counts + 1).scale(coeff).mean
                val (latitude, longitude) = TrackingService.consumeMeasurementAverageCoordinates()

                val saveResult = PoiLibrary.addPoiWithResult(
                    context = this,
                    description = description,
                    timestampMillis = System.currentTimeMillis(),
                    latitude = latitude,
                    longitude = longitude,
                    doseRate = doseRate,
                    counts = counts,
                    seconds = seconds
                )
                if (saveResult.success) {
                    Toast.makeText(this, "POI saved", Toast.LENGTH_SHORT).show()
                    saveResult.warning?.let { warning ->
                        Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val message = saveResult.error?.let { "Unable to save POI: $it" } ?: "Unable to save POI"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }

                if (isMeasurementModeEnabled) {
                    dispatchTrackingAction(TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCurrentMeasurementCountsAndSeconds(): Pair<Int, Double> {
        val counts = latestCpsSnapshot.sampleCount.coerceAtLeast(0)
        val seconds = if (latestCpsSnapshot.measurementStartTimestampMillis > 0L) {
            ((System.currentTimeMillis() - latestCpsSnapshot.measurementStartTimestampMillis).toDouble() / 1000.0)
                .coerceAtLeast(0.0)
        } else {
            0.0
        }
        return Pair(counts, seconds)
    }

    private fun updateCpsOrDoseLine(onBeep: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val coeff = prefs.getString("cps_to_usvh", "1.0")?.toDoubleOrNull() ?: 1.0

        var t1: Double
        var ignoredFirst : Int
        if (isMeasurementModeEnabled) {
            // in Measurement mode the first point is inside the time interval
            t1 = latestCpsSnapshot.measurementStartTimestampMillis.toDouble() / 1000.0 //2026-03-25
            ignoredFirst = 0
        } else {
            // in Live mode the first point starts the interval and should not be accounted for
            t1 = latestCpsSnapshot.oldestTimestampMillis.toDouble() / 1000.0
            ignoredFirst = 1
        }

        val ci = if (latestCpsSnapshot.oldestTimestampMillis == 0L) {
            // Something not right we have no interval
            ConfidenceInterval(0.0, 0.0, 0.0, 0.0, 0)
        } else if (onBeep) {
            val tn = System.currentTimeMillis().toDouble() / 1000.0
            // if onBeep we have only n-1 intervals ot analyze (t1->t2) (t2->t1) ... (t{n-1}->tn)
            //ConfidenceInterval(t1, tn, latestCpsSnapshot.sampleCount)

            // Clamp because sampleCount - ignoredFirst - 1 can go negative during startup/race updates.
            val eventsInside = (latestCpsSnapshot.sampleCount - ignoredFirst - 1).coerceAtLeast(0)
            ConfidenceInterval(t1, tn, eventsInside, true) // disregarding t1 and tn
        } else {
            val t_now = System.currentTimeMillis().toDouble() / 1000.0
            // if not onBeep we have n intervals: (t1->t2) (t2->t1) ... (t{n-1}->tn) and (tn->now)
            //ConfidenceInterval(t1, t_now, latestCpsSnapshot.sampleCount + 1)

            // Clamp because sampleCount - ignoredFirst can be transiently negative with incomplete windows.
            val eventsInside = (latestCpsSnapshot.sampleCount - ignoredFirst).coerceAtLeast(0)
            ConfidenceInterval(t1, t_now, eventsInside, false) // disregarding t1
        }

        val doseRateMean = ci.mean * coeff
        val doseRateDelta = ci.delta * coeff

        val decimalDigits = if (isMeasurementModeEnabled) {
            if (doseRateDelta < 0.01  * (coeff / 0.1)) 4 else 3
        } else 2

        val doseColor = when {
            doseRateMean < 0.000001 -> R.color.dose_zero
            doseRateMean < 0.15 -> R.color.dose_low
            doseRateMean < 0.3 -> R.color.dose_medium
            else -> R.color.dose_high
        }
        binding.textCps.setTextColor(ContextCompat.getColor(this, doseColor))

        if (coeff == 1.0) {
            val cpsText = ci.toText(decimalDigits, doseRateDisplayMode)
            binding.textCps.text = "CPS: $cpsText"
        } else {
            val doseRateText = ci.scale(coeff).toText(decimalDigits, doseRateDisplayMode)
            binding.textCps.text = "Dose rate: $doseRateText μSv/h"
        }
    }

    private fun showCancelTrackConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Cancel track")
            .setMessage("Are you sure you want to discard track?")
            .setPositiveButton("Yes") { _, _ ->
                cancelTracking()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun cancelTracking() {
        openSavedTrackPlotAfterStop = false
        dispatchTrackingAction(TrackingService.ACTION_CANCEL_TRACK)
    }

    private fun startTracking() {
        openSavedTrackPlotAfterStop = false
        TrackSelectionPrefs.setTrackSelected(this, TrackCatalog.currentTrackId(), true)
        TimePlotActivity.rememberCurrentTrackSelection(this)
        dispatchTrackingAction(TrackingService.ACTION_START)
    }

    private fun stopTracking() {
        openSavedTrackPlotAfterStop = true
        dispatchTrackingAction(TrackingService.ACTION_STOP)
    }

    private fun dispatchTrackingAction(action: String) {
        val intent = Intent(this, TrackingService::class.java).apply {
            this.action = action
            putExtra(TrackingService.EXTRA_FOREGROUND_DISPATCH, true)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (error: IllegalStateException) {
            Log.w("MainActivity", "Unable to dispatch TrackingService action=$action", error)
            Toast.makeText(
                this,
                "Tracking action was delayed by Android background restrictions.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun registerTrackSavedReceiverIfNeeded() {
        if (trackSavedReceiverRegistered) return
        val filter = IntentFilter(TrackingService.ACTION_TRACK_SAVED)
        ContextCompat.registerReceiver(
            this,
            trackSavedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        trackSavedReceiverRegistered = true
    }

    private fun unregisterTrackSavedReceiverIfNeeded() {
        if (!trackSavedReceiverRegistered) return
        unregisterReceiver(trackSavedReceiver)
        trackSavedReceiverRegistered = false
    }

    private fun requestPermissionsOnAppStart() {
        ensurePermissions()
        requestIgnoreBatteryOptimizations()
    }

    private fun ensureTrackingPrerequisites(): Boolean {
        val missingRuntimePermissions = getMissingRuntimePermissions()
        val isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations()
        if (missingRuntimePermissions.isEmpty() && isBatteryOptimizationIgnored) {
            return true
        }

        showPermissionsExplanationAndRequestAgain(
            missingRuntimePermissions = missingRuntimePermissions,
            needsBatteryOptimizationExemption = !isBatteryOptimizationIgnored
        )
        return false
    }

    private fun getMissingRuntimePermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        val bluetoothMicPreferred = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, false)
        if (bluetoothMicPreferred &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }

        return needed
    }

    private fun ensurePermissions(): Boolean {
        val needed = getMissingRuntimePermissions()
        return if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun startMonitoring() {
        if (ensurePermissions()) {
            dispatchTrackingAction(TrackingService.ACTION_START_MONITORING)
        }
    }

    private fun stopMonitoring() {
        dispatchTrackingAction(TrackingService.ACTION_STOP_MONITORING)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            return
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun showPermissionsExplanationAndRequestAgain(
        missingRuntimePermissions: List<String>,
        needsBatteryOptimizationExemption: Boolean
    ) {
        val reasons = mutableListOf<String>()
        if (Manifest.permission.ACCESS_FINE_LOCATION in missingRuntimePermissions) {
            reasons += "Location access is required to save GPX points."
        }
        if (Manifest.permission.RECORD_AUDIO in missingRuntimePermissions) {
            reasons += "Microphone access is required to detect Geiger counter clicks."
        }
        if (Manifest.permission.BLUETOOTH_CONNECT in missingRuntimePermissions) {
            reasons += "Bluetooth permission is required to use a connected Bluetooth microphone."
        }
        if (needsBatteryOptimizationExemption) {
            reasons += "Battery usage must be set to 'Unrestricted' so Android does not stop tracking in the background."
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions required")
            .setMessage(reasons.joinToString("\n\n"))
            .setPositiveButton("Grant permissions") { _, _ ->
                if (missingRuntimePermissions.isNotEmpty()) {
                    permissionLauncher.launch(missingRuntimePermissions.toTypedArray())
                }
                if (needsBatteryOptimizationExemption) {
                    requestIgnoreBatteryOptimizations()
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
