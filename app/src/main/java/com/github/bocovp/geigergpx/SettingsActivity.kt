package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

private enum class SettingsPage(val title: String) { Main("Settings"), Device("Device settings"), ChooseDevice("Choose device") }

class SettingsActivity : ComponentActivity() {
    private var calibrationDetector: CalibrationSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GeigerSettingsApp(onFinish = { finish() }) }
    }

    override fun onDestroy() {
        calibrationDetector?.stop()
        calibrationDetector = null
        super.onDestroy()
    }

    @Composable
    private fun GeigerSettingsApp(onFinish: () -> Unit) {
        var page by remember { mutableStateOf(SettingsPage.Main) }
        val context = LocalContext.current
        val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
        var refresh by remember { mutableIntStateOf(0) }
        val listener = remember {
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh++ }
        }
        DisposableEffect(prefs, listener) {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        MaterialTheme(colorScheme = expressiveColors()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Scaffold(
                    floatingActionButton = {
                        if (page == SettingsPage.ChooseDevice) ExtendedFloatingActionButton(
                            onClick = { showCloneDialog { refresh++; page = SettingsPage.Device } },
                            icon = { Icon(Icons.Rounded.Add, null) },
                            text = { Text("New device") }
                        )
                    }
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        Header(page.title) {
                            if (page == SettingsPage.Main) onFinish() else page = if (page == SettingsPage.ChooseDevice) SettingsPage.Device else SettingsPage.Main
                        }
                        when (page) {
                            SettingsPage.Main -> MainSettings(refresh, onDevice = { page = SettingsPage.Device }, onRefresh = { refresh++ })
                            SettingsPage.Device -> DeviceSettings(refresh, onChoose = { page = SettingsPage.ChooseDevice }, onRefresh = { refresh++ })
                            SettingsPage.ChooseDevice -> ChooseDevice(refresh, onSelected = { page = SettingsPage.Device; refresh++ })
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Header(title: String, onBack: () -> Unit) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Spacer(Modifier.height(26.dp))
            Text(title, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(26.dp))
        }
    }

    @Composable private fun MainSettings(@Suppress("UNUSED_PARAMETER") refresh: Int, onDevice: () -> Unit, onRefresh: () -> Unit) {
        val context = LocalContext.current; val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); prefs.edit { putString(SettingsKeys.KEY_GPX_TREE_URI, uri.toString()) }; onRefresh() }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Section("Signal detection") {
                SettingsRow("Dosimeter", DeviceConfigManager.currentDeviceName(prefs), onClick = onDevice)
                SettingsRow("Threshold", thresholdSummary(false), "Press to calibrate • long-press to set manually", onClick = { startCalibration(false, onRefresh) }, onLongClick = { showManualThresholdDialog(false, onRefresh) })
                SettingsRow("Bluetooth threshold", thresholdSummary(true), "Press to calibrate • long-press to set manually", onClick = { if (!AudioInputManager.isBluetoothMicAvailable(context)) toast("Bluetooth microphone not available.") else startCalibration(true, onRefresh) }, onLongClick = { showManualThresholdDialog(true, onRefresh) })
                SwitchRow("Use Bluetooth mic", prefs.getBoolean(SettingsKeys.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, false)) { prefs.edit { putBoolean(SettingsKeys.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, it) }; onRefresh() }
                SwitchRow("Visualize beeps", prefs.getBoolean("visualize_beeps", false), "Show a real-time particle waterfall on the main screen") { prefs.edit { putBoolean("visualize_beeps", it) }; onRefresh() }
            } }
            item { Section("Dose rate measurement") {
                ChoiceRow("Counts for dose rate averaging", prefs.getString("dose_rate_avg_timestamps_n", "10") ?: "10", listOf("5", "10", "20", "50", "100")) { prefs.edit { putString("dose_rate_avg_timestamps_n", it) }; onRefresh() }
                EditRow("Alert at dose rate", alertSummary(), prefs.getString("alert_dose_rate", "0") ?: "0", decimal = true) { prefs.edit { putString("alert_dose_rate", it) }; onRefresh() }
            } }
            item { Section("Track recording") {
                SettingsRow("Save folder", folderSummary(), "Press to change", onClick = { folderLauncher.launch(null) })
                editPref("GPS Spoofing detection speed", "max_speed_kmh", "30000.0", "km/h", true, onRefresh)
                editPref("Min distance between points", "point_spacing_m", "10.0", "m", true, onRefresh)
                editPref("Min counts per point", "min_counts_per_point", "10", null, false, onRefresh)
                editPref("Max time without counts", "max_time_without_counts_s", "10", "s", true, onRefresh)
                editPref("Max time without GPS", "max_time_without_gps_s", "60", "s", true, onRefresh)
                SwitchRow("Also save dose rate in <ele> tag", prefs.getBoolean("save_dose_rate_in_ele", false)) { prefs.edit { putBoolean("save_dose_rate_in_ele", it) }; onRefresh() }
            } }
        }
    }

    @Composable private fun DeviceSettings(@Suppress("UNUSED_PARAMETER") refresh: Int, onChoose: () -> Unit, onRefresh: () -> Unit) {
        val context = LocalContext.current; val device = DeviceConfigManager.currentDevice(context) ?: return
        val active = (application as GeigerGpxApp).trackingRepository.let { it.isTracking.value || it.measurementModeEnabled.value }
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Section("Device") {
                SettingsRow("Change device", device.name, onClick = { if (active) toast("Cannot change device while tracking or measuring") else onChoose() })
                SettingsRow("Device name", device.name, if (device.isCustom) "Custom" else "Built-in", enabled = device.isCustom, onClick = { if (active) toast("Cannot rename device while tracking or measuring") else renameDevice(device, onRefresh) })
            } }
            item { Section("Current device parameters") {
                deviceParam("Sensitivity", RadiationCalibration.KEY_SENSITIVITY, DeviceConfigManager.getPropertyValue(device, RadiationCalibration.KEY_SENSITIVITY), device.isCustom, active, onRefresh)
                SettingsRow("Beep detector", "Goertzel detector", enabled = false)
                deviceParam("Counts per beep", DeviceConfigManager.KEY_COUNTS_PER_BEEP, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_COUNTS_PER_BEEP), device.isCustom, active, onRefresh)
                deviceParam("Low frequency", DeviceConfigManager.KEY_FREQ_LOW, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_FREQ_LOW), device.isCustom, active, onRefresh)
                deviceParam("Main frequency", DeviceConfigManager.KEY_FREQ_MAIN, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_FREQ_MAIN), device.isCustom, active, onRefresh)
                deviceParam("High frequency", DeviceConfigManager.KEY_FREQ_HIGH, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_FREQ_HIGH), device.isCustom, active, onRefresh)
                deviceParam("Beep duration", DeviceConfigManager.KEY_DURATION, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_DURATION), device.isCustom, active, onRefresh)
                deviceParam("Dominance threshold", DeviceConfigManager.KEY_DOMINANCE_THRESHOLD, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_DOMINANCE_THRESHOLD), device.isCustom, active, onRefresh)
                deviceParam("Dominance threshold fade-out", DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END), device.isCustom, active, onRefresh)
                deviceParam("Window size", DeviceConfigManager.KEY_WINDOW_SIZE, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_WINDOW_SIZE), device.isCustom, active, onRefresh)
                deviceParam("Step size", DeviceConfigManager.KEY_STEP_SIZE, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_STEP_SIZE), device.isCustom, active, onRefresh)
                deviceParam("Single beep duration tolerance", DeviceConfigManager.KEY_ONE_BEEP_TOL, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_ONE_BEEP_TOL), device.isCustom, active, onRefresh)
                deviceParam("Double beep duration tolerance", DeviceConfigManager.KEY_TWO_BEEP_TOL, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_TWO_BEEP_TOL), device.isCustom, active, onRefresh)
                deviceParam("Triple beep duration tolerance", DeviceConfigManager.KEY_THREE_BEEP_TOL, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_THREE_BEEP_TOL), device.isCustom, active, onRefresh)
                deviceParam("Quad beep duration tolerance", DeviceConfigManager.KEY_FOUR_BEEP_TOL, DeviceConfigManager.getPropertyValue(device, DeviceConfigManager.KEY_FOUR_BEEP_TOL), device.isCustom, active, onRefresh)
            } }
        }
    }

    @Composable private fun ChooseDevice(@Suppress("UNUSED_PARAMETER") refresh: Int, onSelected: () -> Unit) {
        val context = LocalContext.current; val devices = DeviceConfigManager.devices(context); val current = DeviceConfigManager.currentDevice(context)?.name
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(devices) { d ->
                ElevatedCard(shape = RoundedCornerShape(28.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), onClick = { DeviceConfigManager.selectDevice(context, d.name); onSelected() }) {
                    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        deviceIconRes(d.name)?.let { Image(painter = painterResource(it), contentDescription = null, modifier = Modifier.size(36.dp)); Spacer(Modifier.width(18.dp)) }
                        Column(Modifier.weight(1f)) { Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text((if (d.isCustom) "Custom" else "Built-in") + if (d.name == current) " • ACTIVE" else "", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (d.name == current) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    @Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) { ElevatedCard(shape = RoundedCornerShape(28.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); content() } } }
    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun SettingsRow(title: String, value: String? = null, subtitle: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).then(if ((onClick != null || onLongClick != null) && enabled) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier).padding(vertical = 8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (value != null) Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    @Composable private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onCheckedChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onCheckedChange) } }
    @Composable private fun ChoiceRow(title: String, value: String, choices: List<String>, onChoice: (String) -> Unit) { var open by remember { mutableStateOf(false) }; Box { SettingsRow(title, value, onClick = { open = true }); DropdownMenu(open, onDismissRequest = { open = false }) { choices.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { open = false; onChoice(it) }) } } } }
    @Composable private fun EditRow(title: String, summary: String, default: String, decimal: Boolean, onSave: (String) -> Unit) { SettingsRow(title, summary, onClick = { showEditDialog(title, default, decimal, false, onSave) }) }
    @Composable private fun editPref(title: String, key: String, default: String, unit: String?, decimal: Boolean, onRefresh: () -> Unit) { val context = LocalContext.current; val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }; val value = prefs.getString(key, default) ?: default; SettingsRow(title, if (unit == null) value else "$value $unit", onClick = { showEditDialog(title, value, decimal, false) { prefs.edit { putString(key, it) }; onRefresh() } }) }
    @Composable private fun deviceParam(title: String, key: String, value: String, isCustom: Boolean, trackingActive: Boolean, onRefresh: () -> Unit) { SettingsRow(title, formatDeviceSummary(key, value), enabled = isCustom, onClick = { if (trackingActive) toast("Cannot edit parameters while tracking or measuring") else showEditDialog(title, formatValue(key, value), key != DeviceConfigManager.KEY_COUNTS_PER_BEEP, key == RadiationCalibration.KEY_SENSITIVITY) { DeviceConfigManager.updateActiveDeviceProperty(this, key, it); onRefresh() } }) }

    private fun showEditDialog(title: String, value: String, decimal: Boolean, signed: Boolean, onSave: (String) -> Unit) { val input = EditText(this).apply { setText(value); setSelection(text.length); isSingleLine = true; inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0) }; AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }.setNegativeButton("Cancel", null).show() }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun thresholdKey(bluetooth: Boolean) = if (bluetooth) SettingsKeys.KEY_BLUETOOTH_AUDIO_THRESHOLD else SettingsKeys.KEY_AUDIO_THRESHOLD
    private fun defaultThreshold(bluetooth: Boolean) = if (bluetooth) AudioInputManager.DEFAULT_BLUETOOTH_MAG_THRESHOLD else AudioInputManager.DEFAULT_MAG_THRESHOLD
    private fun toDb(intensity: Float) = 10.0 * log10(intensity.toDouble() / 100.0)
    private fun thresholdSummary(bluetooth: Boolean): String { val v = PreferenceManager.getDefaultSharedPreferences(this).getFloat(thresholdKey(bluetooth), Float.NaN); return if (v.isNaN()) "Uncalibrated (%.2f dB)".format(java.util.Locale.US, toDb(defaultThreshold(bluetooth))) else "Current threshold: %.2f dB".format(java.util.Locale.US, toDb(v)) }
    private fun startCalibration(bluetooth: Boolean, onRefresh: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (bluetooth) "Calibration (bluetooth)" else "Calibration")
            .setMessage("Estimating signal level...")
            .setNegativeButton("Cancel") { d, _ ->
                calibrationDetector?.stop()
                calibrationDetector = null
                d.dismiss()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        calibrationDetector = CalibrationSession(
            context = this,
            onProgress = { phase, current, total ->
                lifecycleScope.launch {
                    if (phase == 2) {
                        dialog.setMessage("Calibrating... $current/$total")
                    }
                }
            },
            onFinished = {
                lifecycleScope.launch {
                    calibrationDetector = null
                    dialog.dismiss()
                    onRefresh()
                    toast("Calibration finished.")
                }
            },
            onAudioStatus = { status, error ->
                lifecycleScope.launch {
                    if (error != AudioInputManager.AUDIO_STATUS_WORKING) {
                        dialog.setMessage(status)
                    }
                }
            },
            useBluetoothMicIfAvailable = bluetooth,
            thresholdPreferenceKey = thresholdKey(bluetooth),
            fallbackThreshold = defaultThreshold(bluetooth)
        )
        calibrationDetector?.start()
    }
    private fun alertSummary(): String { val prefs = PreferenceManager.getDefaultSharedPreferences(this); val alert = prefs.getString("alert_dose_rate", "0")?.toDoubleOrNull() ?: 0.0; if (alert <= 0.0) return "Not set"; val avg = prefs.getString("dose_rate_avg_timestamps_n", "10")?.toIntOrNull() ?: 10; val sens = RadiationCalibration.sensitivityFromPrefs(prefs); val rate = ConfidenceInterval.getFalseAlarmRate(alert, avg, sens); val unit = if (sens == 1.0) "cps" else "μSv/h"; return "%.2f %s      False alarms: %.1f / hour".format(java.util.Locale.US, alert, unit, rate) }
    private fun folderSummary(): String { val uri = PreferenceManager.getDefaultSharedPreferences(this).getString(SettingsKeys.KEY_GPX_TREE_URI, null) ?: return "Not set (uses app folder)"; return try { DocumentFile.fromTreeUri(this, uri.toUri())?.name ?: uri } catch (_: Exception) { uri } }
    private fun fromDb(value: Float) = 10.0.pow(value / 10.0) * 100.0
    private fun showManualThresholdDialog(bluetooth: Boolean, onRefresh: () -> Unit) { val prefs = PreferenceManager.getDefaultSharedPreferences(this); val key = thresholdKey(bluetooth); val current = prefs.getFloat(key, Float.NaN); val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED; hint = "e.g. 42.1"; if (!current.isNaN()) { setText("%.2f".format(java.util.Locale.US, toDb(current))); setSelection(text.length) } }; AlertDialog.Builder(this).setTitle("Set threshold manually").setMessage("Enter a positive threshold value.").setView(input).setPositiveButton("Save") { _, _ -> val value = input.text.toString().trim().toFloatOrNull(); if (value != null && value > 0f && value.isFinite()) { prefs.edit { putFloat(key, fromDb(value).toFloat()) }; onRefresh(); toast("Threshold updated.") } else { toast("Invalid threshold value.") } }.setNegativeButton("Cancel", null).show() }
    private fun renameDevice(device: DeviceConfigManager.Device, onRefresh: () -> Unit) { if (!device.isCustom) return; val input = EditText(this).apply { setText(device.name); setSelection(text.length); isSingleLine = true }; AlertDialog.Builder(this).setTitle("Rename device").setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create().also { dialog -> dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val name = input.text.toString().trim(); if (name.isEmpty()) input.error = "Name cannot be empty" else if (DeviceConfigManager.renameActiveDevice(this, name)) { dialog.dismiss(); onRefresh() } else input.error = "Name already exists" } }; dialog.show() } }
    private fun showCloneDialog(done: () -> Unit) { val names = DeviceConfigManager.devices(this).map { it.name }.toTypedArray(); AlertDialog.Builder(this).setTitle("Choose base device to copy from").setItems(names) { _, which -> val input = EditText(this).apply { hint = "New device name"; isSingleLine = true }; AlertDialog.Builder(this).setTitle("Enter name for new device").setView(input).setPositiveButton("Create", null).setNegativeButton("Cancel", null).create().also { d -> d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val n = input.text.toString().trim(); if (n.isEmpty()) input.error = "Name cannot be empty" else if (DeviceConfigManager.cloneDevice(this, names[which], n)) { toast("Device created and selected"); d.dismiss(); done() } else input.error = "Device name already exists" } }; d.show() } }.setNegativeButton("Cancel", null).show() }
    private fun deviceIconRes(name: String): Int? = when (name) { "RADEX RD1008" -> R.drawable.rd1008_24; "RADEX RD1224Si" -> R.drawable.rd1224si_24; else -> null }
    private fun formatDeviceSummary(key: String, value: String): String { val unit = when (key) { RadiationCalibration.KEY_SENSITIVITY -> "cps per μSv/h"; DeviceConfigManager.KEY_FREQ_LOW, DeviceConfigManager.KEY_FREQ_MAIN, DeviceConfigManager.KEY_FREQ_HIGH -> "Hz"; DeviceConfigManager.KEY_DURATION, DeviceConfigManager.KEY_WINDOW_SIZE, DeviceConfigManager.KEY_STEP_SIZE, DeviceConfigManager.KEY_ONE_BEEP_TOL, DeviceConfigManager.KEY_TWO_BEEP_TOL, DeviceConfigManager.KEY_THREE_BEEP_TOL, DeviceConfigManager.KEY_FOUR_BEEP_TOL -> "s"; else -> "" }; val v = formatValue(key, value); return if (unit.isEmpty()) v else "$v $unit" }
    private fun formatValue(key: String, value: String): String { val bd = try { java.math.BigDecimal(value) } catch (_: Exception) { return value }; return if (key == DeviceConfigManager.KEY_FREQ_LOW || key == DeviceConfigManager.KEY_FREQ_MAIN || key == DeviceConfigManager.KEY_FREQ_HIGH) "%.1f".format(java.util.Locale.US, bd.toDouble()) else bd.stripTrailingZeros().toPlainString() }
    private fun expressiveColors() = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFEBC248), onPrimary = androidx.compose.ui.graphics.Color(0xFF3F2E00), secondary = androidx.compose.ui.graphics.Color(0xFFD6C4A1), background = androidx.compose.ui.graphics.Color(0xFF211000), onBackground = androidx.compose.ui.graphics.Color(0xFFFFEDE2), surface = androidx.compose.ui.graphics.Color(0xFF2A1605), onSurface = androidx.compose.ui.graphics.Color(0xFFFFEDE2), surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF3A2410), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFE0CFC2))
}
