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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var latestCpsSnapshot = TrackingRepository.CpsSnapshot()
    private var isMeasurementModeEnabled: Boolean = false
    private var doseRateFormatting: DoseRateFormatting = DoseRateFormatting.ABSOLUTE_USV
    private var keepScreenOnEnabled: Boolean = false
    private var openSavedTrackPlotAfterStop = false
    private var trackSavedReceiverRegistered = false
    private var pendingRestoreAfterStartupFolderValidation = false
    private var defaultTextCpsColor: Int = 0
    private val statePendingStartupRestore = "state_pending_startup_restore"

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "visualize_beeps") {
            binding.beepVisualizer.visibility = if (prefs.getBoolean("visualize_beeps", false)) View.VISIBLE else View.GONE
        }
        if (key == SettingsKeys.KEY_DOSE_RATE_FORMATTING) {
            val sensitivity = RadiationCalibration.sensitivityFromPrefs(prefs)
            doseRateFormatting = DoseRateFormatting.validForSensitivity(DoseRateFormatting.fromPrefs(prefs), sensitivity)
            updateCpsOrDoseLine(false)
        }
    }
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
                .edit {
                    putString(SettingsKeys.KEY_GPX_TREE_URI, uri.toString())
                }
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
                .edit {
                    putString(SettingsKeys.KEY_GPX_TREE_URI, uri.toString())
                }
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
        if (savedInstanceState != null) {
            keepScreenOnEnabled = savedInstanceState.getBoolean("keep_screen_on", false)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        defaultTextCpsColor = binding.textCps.currentTextColor
        setSupportActionBar(binding.topAppBar)
        applyToolbarTitleVisibility()
        setupToolbarTitleLongPress()

        validateConfiguredSaveFolderAtStartup {
            restoreStartupBackupIfNeeded()
        }

        binding.buttonStart.setOnClickListener {
            if (viewModel.isTracking.value) {
                if (viewModel.pointCount.value == 0) {
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
            val treeUri = prefs.getString(SettingsKeys.KEY_GPX_TREE_URI, null)
            if (treeUri.isNullOrBlank()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.choose_save_folder)
                    .setMessage(R.string.no_save_folder_message)
                    .setPositiveButton(R.string.choose_folder) { _, _ ->
                        folderPickerForStop.launch(null)
                    }
                    .setNegativeButton(R.string.use_app_folder) { _, _ ->
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val sensitivity = RadiationCalibration.sensitivityFromPrefs(prefs)
            val nextFormatting = DoseRateFormatting.validForSensitivity(doseRateFormatting.nextSameUnit(), sensitivity)
            prefs.edit { putString(SettingsKeys.KEY_DOSE_RATE_FORMATTING, nextFormatting.preferenceLabel) }
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        doseRateFormatting = DoseRateFormatting.normalizePrefsForSensitivity(prefs, RadiationCalibration.sensitivityFromPrefs(prefs))
        binding.beepVisualizer.visibility = if (prefs.getBoolean("visualize_beeps", false)) View.VISIBLE else View.GONE
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        observeViewModel()
        requestPermissionsOnAppStart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(statePendingStartupRestore, pendingRestoreAfterStartupFolderValidation)
        outState.putBoolean("keep_screen_on", keepScreenOnEnabled)
    }

    private fun validateConfiguredSaveFolderAtStartup(onValidationComplete: () -> Unit) {
        lifecycleScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            val treeUriString = prefs.getString(SettingsKeys.KEY_GPX_TREE_URI, null)
            val (defaultFolderError, isValidFolder, canWrite) = withContext(Dispatchers.IO) {
                val probeError = FileStorageManager.getDefaultFolderWriteProbeError(this@MainActivity)
                if (treeUriString.isNullOrBlank()) {
                    Triple(probeError, true, false)
                } else {
                    val treeUri = runCatching { treeUriString.toUri() }.getOrNull()
                    val treeRoot = treeUri?.let { DocumentFile.fromTreeUri(this@MainActivity, it) }
                    val validFolder = treeRoot?.isDirectory == true
                    val writable = validFolder && FileStorageManager.isConfiguredFolderWritable(this@MainActivity)
                    Triple(probeError, validFolder, writable)
                }
            }

            if (defaultFolderError != null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.warning_default_save_folder_not_writable, defaultFolderError.localizedMessage),
                    Toast.LENGTH_LONG
                ).show()
            }

            if (treeUriString.isNullOrBlank() || (isValidFolder && canWrite)) {
                onValidationComplete()
                return@launch
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.save_folder_unavailable)
                .setMessage(
                    getString(R.string.save_folder_unavailable_message)
                )
                .setPositiveButton(R.string.choose_folder) { _, _ ->
                    pendingRestoreAfterStartupFolderValidation = true
                    folderPickerForStartupValidation.launch(null)
                }
                .setNegativeButton(R.string.use_default_folder) { _, _ ->
                    FileStorageManager.clearConfiguredTreeUri(this@MainActivity)
                    onValidationComplete()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun restoreStartupBackupIfNeeded() {
        lifecycleScope.launch {
            val restoredName = withContext(Dispatchers.IO) {
                (application as GeigerGpxApp).restoreBackupIfNeeded()
            }
            if (restoredName != null && !isFinishing && !isDestroyed) {
                Toast.makeText(
                    applicationContext,
                    "Backup file was restored and saved as $restoredName",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
                    Toast.makeText(this, getString(R.string.app_name_hidden_until_next_launch), Toast.LENGTH_SHORT).show()
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
        val title = getString(if (keepScreenOnEnabled) R.string.screen_stay_awake_on else R.string.screen_stay_awake_off)
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
        //updateCpsOrDoseLine(false)
        //refreshTrackDuration(viewModel.trackDurationSeconds.value)
        //refreshMeasurementDurationFromTimer()
        //updateCountDisplay(viewModel.countDisplayState.value)
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
        if (!viewModel.isTracking.value && !isMeasurementModeEnabled) {
            stopMonitoring()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun refreshTrackDuration(seconds: Long) {
        binding.textDuration.text = getString(R.string.duration_format, TrackingRepository.formatDuration(seconds))
    }

    private fun formatCounts(counts: Int, countsPerBeep: Int): String {
        return if (countsPerBeep > 1) {
            val beeps = counts / countsPerBeep
            "$countsPerBeep \u00D7 $beeps"
        } else {
            counts.toString()
        }
    }
    private fun updateCountDisplay(state: TrackingViewModel.CountDisplayState) {
        val cpb = state.countsPerBeep
        binding.textTrackCounts.text = getString(R.string.track_counts_format, formatCounts(state.trackCounts, cpb))
        binding.textMeasurementCounts.text = getString(R.string.counts_format, formatCounts(state.measurementCounts, cpb))
        binding.textTotalCounts.text = getString(R.string.total_counts_format, formatCounts(state.totalCounts, cpb))
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
        binding.textMeasurementDuration.text = getString(R.string.measurement_duration_format, measurementDurationSeconds.roundToInt())
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isTracking.collect { tracking ->
                        binding.buttonStart.text = if (tracking) getString(R.string.cancel) else getString(R.string.start_track_lower)
                        binding.buttonStop.isEnabled = tracking
                    }
                }
                launch { viewModel.trackDurationSeconds.collect { seconds -> refreshTrackDuration(seconds) } }
                launch {
                    viewModel.uiTickMillis.collect {
                        updateCpsOrDoseLine(false)
                        refreshMeasurementDurationFromTimer()
                    }
                }
                launch {
                    viewModel.distanceMeters.collect { dist ->
                        binding.textDistance.text = String.format(java.util.Locale.US, getString(R.string.distance_m_format), dist)
                    }
                }
                launch { viewModel.pointCount.collect { count -> binding.textPoints.text = getString(R.string.points_format, count) } }
                launch {
                    viewModel.cpsUpdate.collect { update ->
                        latestCpsSnapshot = update.snapshot
                        updateCpsOrDoseLine(update.onBeep)
                        refreshMeasurementDurationFromTimer()
                    }
                }
                launch { viewModel.countDisplayState.collect { state -> updateCountDisplay(state) } }
                launch {
                    viewModel.gpsStatus.collect { status ->
                        binding.textGpsStatus.text = getString(R.string.gps_status_format, status)
                        val color = when (status) {
                            "Waiting" -> R.color.status_waiting
                            "Working" -> R.color.status_working
                            else -> if (status.startsWith("Spoofing detected")) R.color.status_spoofing else R.color.status_waiting
                        }
                        binding.textGpsStatus.setTextColor(ContextCompat.getColor(this@MainActivity, color))
                    }
                }
                launch {
                    viewModel.audioStatus.collect { audioStatus ->
                        binding.textAudioStatus.text = getString(R.string.audio_status_format, audioStatus.status)
                        val color = when (audioStatus.errorCode) {
                            TrackingRepository.AUDIO_STATUS_WORKING -> R.color.status_working
                            TrackingRepository.AUDIO_STATUS_ERROR -> R.color.status_spoofing
                            else -> R.color.status_waiting
                        }
                        binding.textAudioStatus.setTextColor(ContextCompat.getColor(this@MainActivity, color))
                    }
                }
                launch {
                    viewModel.measurementModeEnabled.collect { enabled ->
                        isMeasurementModeEnabled = enabled
                        binding.buttonMeasurementMode.text = if (enabled) getString(R.string.live_mode) else getString(R.string.measure)
                        binding.buttonSavePoi.isEnabled = enabled
                        updateCpsOrDoseLine(false)
                        refreshMeasurementDurationFromTimer()
                    }
                }
                launch {
                    viewModel.beepEvents.collect { timestamp ->
                        if (binding.beepVisualizer.visibility == View.VISIBLE) {
                            binding.beepVisualizer.addBeep(timestamp)
                        }
                    }
                }
            }
        }
    }

    private fun showSavePoiDialog() {
        if (!isMeasurementModeEnabled) {
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.description)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.save_poi)
            .setView(input)
            .setPositiveButton(R.string.save_poi) { _, _ ->
                val description = input.text?.toString()?.trim().orEmpty()
                val (counts, seconds) = getCurrentMeasurementCountsAndSeconds()

                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val sensitivity = RadiationCalibration.sensitivityFromPrefs(prefs)
                val deviceName = DeviceConfigManager.currentDeviceName(prefs)
                val doseRate = ConfidenceInterval(0.0, seconds, counts, false).scale(1.0 / sensitivity).mean
//                  val doseRate = ConfidenceInterval(0.0, seconds, counts + 1).scale(1.0 / sensitivity).mean
                val (latitude, longitude) = TrackingService.consumeMeasurementAverageCoordinates()

                lifecycleScope.launch {
                    try {
                        val timestampMillis = System.currentTimeMillis()
                        val saveResult = withContext(NonCancellable + Dispatchers.IO) {
                            PoiLibrary.addPoiWithResult(
                                context = applicationContext,
                                description = description,
                                timestampMillis = timestampMillis,
                                latitude = latitude,
                                longitude = longitude,
                                doseRate = doseRate,
                                counts = counts,
                                seconds = seconds,
                                deviceName = deviceName
                            )
                        }
                        if (saveResult.success) {
                            Toast.makeText(this@MainActivity, getString(R.string.poi_saved), Toast.LENGTH_SHORT).show()
                            saveResult.warning?.let { warning ->
                                Toast.makeText(this@MainActivity, warning, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val message = saveResult.error?.let { getString(R.string.unable_to_save_poi_error_format, it) } ?: getString(R.string.unable_to_save_poi)
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error saving POI", e)
                    } finally {
                        if (isMeasurementModeEnabled) {
                            dispatchTrackingAction(TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE)
                        }
                    }

                }
            }
            .setNegativeButton(R.string.cancel, null)
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
        val sensitivity = RadiationCalibration.sensitivityFromPrefs(prefs)
        val inverseSensitivity = 1.0 / sensitivity

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

        val doseRateMean = ci.mean * inverseSensitivity
        val doseRateDelta = ci.delta * inverseSensitivity

        val decimalDigits = if (isMeasurementModeEnabled) {
            if (doseRateDelta < 0.01 * (10.0 / sensitivity)) 4 else 3
        } else 2

        val doseColor = when {
            doseRateMean < 0.000001 -> R.color.dose_zero
            doseRateMean < 0.15 -> R.color.dose_low
            doseRateMean < 0.3 -> R.color.dose_medium
            else -> R.color.dose_high
        }
        val labelColor = ContextCompat.getColor(this, doseColor)

        val formatted = DoseRateFormatting.format(
            ci = ci,
            sensitivity = sensitivity,
            decimalDigits = decimalDigits,
            formatting = doseRateFormatting
        )
        val label = if (doseRateFormatting.isDoseRate) "Dose rate:" else "CPS:"
        binding.textCps.setTextColor(defaultTextCpsColor)
        binding.textCps.text = colorDoseRateLabel(label, formatted, labelColor)
    }

    private fun colorDoseRateLabel(label: String, value: String, labelColor: Int): SpannableString {
        val line = "$label $value"
        return SpannableString(line).apply {
            setSpan(
                ForegroundColorSpan(labelColor),
                0,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun showCancelTrackConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cancel_track)
            .setMessage(R.string.discard_track_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                cancelTracking()
            }
            .setNegativeButton(R.string.no, null)
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
            .getBoolean(SettingsKeys.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, false)
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
            data = "package:$packageName".toUri()
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
            .setTitle(R.string.permissions_required)
            .setMessage(reasons.joinToString("\n\n"))
            .setPositiveButton(R.string.grant_permissions) { _, _ ->
                if (missingRuntimePermissions.isNotEmpty()) {
                    permissionLauncher.launch(missingRuntimePermissions.toTypedArray())
                }
                if (needsBatteryOptimizationExemption) {
                    requestIgnoreBatteryOptimizations()
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }
}
