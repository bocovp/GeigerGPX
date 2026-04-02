package com.github.bocovp.geigergpx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.icu.util.Measure
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.os.Build
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private var latestCpsSnapshot = TrackingRepository.CpsSnapshot()
    private var isMeasurementModeEnabled: Boolean = false
    private var doseRateDisplayMode: ConfidenceInterval.DisplayMode = ConfidenceInterval.DisplayMode.PLUS_MINUS
    private var keepScreenOnEnabled: Boolean = false

    private val cpsRefreshHandler = Handler(Looper.getMainLooper())
    private val cpsRefreshRunnable = object : Runnable {
        override fun run() {
            updateCpsOrDoseLine(false)
            cpsRefreshHandler.postDelayed(this, 1000L)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        applyToolbarTitleVisibility()
        setupToolbarTitleLongPress()

        val restoredName = (application as GeigerGpxApp).consumeRestoredBackupName()
        if (restoredName != null) {
            Toast.makeText(
                this,
                "Backup file was restored and saved as $restoredName",
                Toast.LENGTH_SHORT
            ).show()
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
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE
            }
            startService(intent)
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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        applyKeepScreenOnFlag()
        updateCpsOrDoseLine(false)
        startCpsRefreshLoop()
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopCpsRefreshLoop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Keep monitoring active in background while tracking or while
        // measurement mode is enabled.
        if (viewModel.isTracking.value != true && !isMeasurementModeEnabled) {
            stopMonitoring()
        }
    }

    private fun startCpsRefreshLoop() {
        cpsRefreshHandler.removeCallbacks(cpsRefreshRunnable)
        cpsRefreshHandler.postDelayed(cpsRefreshRunnable, 1000L)
    }

    private fun stopCpsRefreshLoop() {
        cpsRefreshHandler.removeCallbacks(cpsRefreshRunnable)
    }

    private fun updateCountDisplay(
        isTracking: Boolean = viewModel.isTracking.value ?: false,
        totalCounts: Int = viewModel.totalCounts.value ?: 0,
        trackCounts: Int = viewModel.trackCounts.value ?: 0,
        savedTrackCounts: Int? = viewModel.savedTrackCounts.value
    ) {
        val displayedTrackCounts = if (isTracking) trackCounts else (savedTrackCounts ?: trackCounts)
        val measurementCount = if (isMeasurementModeEnabled) latestCpsSnapshot.sampleCount else 0
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

        binding.textTrackCounts.text = "Track counts: $displayedTrackCounts"
        binding.textMeasurementCounts.text = "Counts: $measurementCount"
        binding.textMeasurementDuration.text = "Duration: ${measurementDurationSeconds.roundToInt()} s"
        binding.textTotalCounts.text = "Total counts: $totalCounts"
    }

    private fun observeViewModel() {
        viewModel.isTracking.observe(this) { tracking ->
            binding.buttonStart.text = if (tracking) "Cancel" else "Start track"
            binding.buttonStop.isEnabled = tracking

            updateCountDisplay(isTracking = tracking)
        }

        viewModel.durationText.observe(this) {
            binding.textDuration.text = "Duration: $it"
        }

        viewModel.distanceMeters.observe(this) { dist ->
            binding.textDistance.text = "Distance: %.1f m".format(dist)
        }

        viewModel.pointCount.observe(this) { count ->
            binding.textPoints.text = "Points: $count"
        }

        viewModel.cpsUpdate.observe(this) { update ->
            latestCpsSnapshot = update.snapshot
            updateCpsOrDoseLine(update.onBeep)
            updateCountDisplay()
        }

        viewModel.totalCounts.observe(this) { totalCounts ->
            updateCountDisplay(totalCounts = totalCounts)
        }

        viewModel.trackCounts.observe(this) { trackCount ->
            updateCountDisplay(trackCounts = trackCount)
        }

        viewModel.savedTrackCounts.observe(this) { savedCounts ->
            updateCountDisplay(savedTrackCounts = savedCounts)
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
            updateCountDisplay()
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

                val ok = PoiLibrary.addPoi(
                    context = this,
                    description = description,
                    timestampMillis = System.currentTimeMillis(),
                    latitude = latitude,
                    longitude = longitude,
                    doseRate = doseRate,
                    counts = counts,
                    seconds = seconds
                )
                if (ok) {
                    Toast.makeText(this, "POI saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Unable to save POI", Toast.LENGTH_SHORT).show()
                }

                if (isMeasurementModeEnabled) {
                    val intent = Intent(this, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_TOGGLE_MEASUREMENT_MODE
                    }
                    startService(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCurrentMeasurementCountsAndSeconds(): Pair<Int, Double> {
        val counts = latestCpsSnapshot.sampleCount.coerceAtLeast(0)
        val seconds = if (latestCpsSnapshot.oldestTimestampMillis > 0L) {
            ((System.currentTimeMillis() - latestCpsSnapshot.oldestTimestampMillis).toDouble() / 1000.0)
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

            // if onBeep we the last point ends the interval and thus not counted
            ConfidenceInterval(t1, tn, latestCpsSnapshot.sampleCount - ignoredFirst - 1, true) // disregarding t1 and tn
        } else {
            val t_now = System.currentTimeMillis().toDouble() / 1000.0
            // if not onBeep we have n intervals: (t1->t2) (t2->t1) ... (t{n-1}->tn) and (tn->now)
            //ConfidenceInterval(t1, t_now, latestCpsSnapshot.sampleCount + 1)

            // if not onBeep the last point is inside the intervsal
            ConfidenceInterval(t1, t_now, latestCpsSnapshot.sampleCount - ignoredFirst, false) // disregarding t1
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
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_CANCEL_TRACK
        }
        startService(intent)
    }

    private fun startTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)
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
            .getBoolean(SettingsFragment.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, true)
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
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START_MONITORING
            }
            startService(intent)
        }
    }

    private fun stopMonitoring() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP_MONITORING
        }
        startService(intent)
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
