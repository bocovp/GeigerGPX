package com.github.bocovp.geigergpx

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Delete
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

private enum class SettingsPage(val title: String) {
    Main("Settings"), Device("Device settings"), ChooseDevice(
        "Choose device"
    )
}

class SettingsActivity : ComponentActivity() {
    private val activeDialogs = mutableSetOf<AlertDialog>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GeigerSettingsApp(onFinish = { finish() }) }
    }

    override fun onDestroy() {
        dismissActiveDialogs()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GeigerSettingsApp(onFinish: () -> Unit) {
        var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.Main) }
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

        BackHandler(enabled = page != SettingsPage.Main) {
            page = if (page == SettingsPage.ChooseDevice) SettingsPage.Device else SettingsPage.Main
        }

        // 1. Define the collapsing scroll behavior
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        val myCustomLightScheme = remember { lightColorScheme(
            primary = Color(0xFF005FB0),     // Deep Blue instead of purple
            onPrimary = Color.White,
            secondary = Color(0xFF565F71),   // Cool Gray instead of slate purple
            surfaceVariant = Color(0xFFE0E2EC), // Light gray card backgrounds
            primaryContainer = Color(0xFFD6E3FF),
            onPrimaryContainer = Color(0xFF001B3E),
            inversePrimary = Color(0xFFAAC7FF),
            background = Color(0xFFFEF7FF),       // Clean light page backdrop
            surface = Color(0xFFFEF7FF),          // Surface backing
            onSurface = Color(0xFF1D1B20),         // Dark text/icon color
            onSurfaceVariant = Color(0xFF49454F),  // Inactive toggle color
            outline = Color(0xFF79747E)
        )}

        val myCustomDarkScheme = remember {darkColorScheme(
            primary = Color(0xFFAAC7FF),     // Bright Blue for contrast on dark backgrounds
            onPrimary = Color(0xFF003062),
            secondary = Color(0xFFBEC6DC),   // Light Gray for readability
            surfaceVariant = Color(0xFF43474E), // Dark gray card backgrounds
            primaryContainer = Color(0xFF004787),
            onPrimaryContainer = Color(0xFFD6E3FF),
            inversePrimary = Color(0xFF005FB0),
            background = Color(0xFF141218),       // Deep dark page backdrop
            surface = Color(0xFF141218),          // Surface backing
            onSurface = Color(0xFFE6E1E5),         // Light text/icon color
            onSurfaceVariant = Color(0xFFCAC4D0),  // Inactive dark toggle color
            outline = Color(0xFF938F99)            // Dark toggle boundary line
        )}

        val colorScheme = if (isSystemInDarkTheme()) myCustomDarkScheme else myCustomLightScheme

        MaterialTheme(colorScheme = colorScheme) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Scaffold(
                    // 2. Link the scroll behavior to the Scaffold
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        // 3. Use the native Material 3 Large Top App Bar
                        MediumTopAppBar(
                            title = { Text(page.title) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (page == SettingsPage.Main) onFinish()
                                    else page = if (page == SettingsPage.ChooseDevice) SettingsPage.Device else SettingsPage.Main
                                }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                                }
                            },
                            scrollBehavior = scrollBehavior
                        )
                    },
                    floatingActionButton = {
                        if (page == SettingsPage.ChooseDevice) ExtendedFloatingActionButton(
                            onClick = { showCloneDialog { refresh++; page = SettingsPage.Device } },
                            icon = { Icon(Icons.Rounded.Add, null) },
                            text = { Text(context.getString(R.string.new_device)) }
                        )
                    }
                ) { padding ->
                    // 4. Content container (LazyColumns will now drive the collapsing toolbar)
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (page) {
                            SettingsPage.Main -> MainSettings(padding,refresh, onDevice = { page = SettingsPage.Device }, onRefresh = { refresh++ })
                            SettingsPage.Device -> DeviceSettings(padding,refresh, onChoose = { page = SettingsPage.ChooseDevice }, onRefresh = { refresh++ })
                            SettingsPage.ChooseDevice -> ChooseDevice(padding,refresh, onSelected = { page = SettingsPage.Device; refresh++ }, onRefresh = { refresh++ })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MainSettings(padding: PaddingValues, refresh: Int, onDevice: () -> Unit, onRefresh: () -> Unit) {
        val context = LocalContext.current
        val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
        val folderLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit {
                        putString(
                            SettingsKeys.KEY_GPX_TREE_URI,
                            uri.toString()
                        )
                    }
                    onRefresh()
                }
            }

        val currentDeviceName = remember(refresh) { DeviceConfigManager.currentDeviceName(prefs) }
        val thresholdSummaryVal = remember(refresh) { thresholdSummary(false) }
        val thresholdSubtitleVal = remember(refresh) { thresholdSubtitle(false) }
        val btThresholdSummaryVal = remember(refresh) { thresholdSummary(true) }
        val btThresholdSubtitleVal = remember(refresh) { thresholdSubtitle(true) }
        val useBtMic = remember(refresh) { prefs.getBoolean(SettingsKeys.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE, false) }
        val doseRateAvg = remember(refresh) { prefs.getString("dose_rate_avg_timestamps_n", "10") ?: "10" }
        val relativeErr = remember(refresh) { getRelativeErrString(prefs)}
        val sensitivity = remember(refresh) { RadiationCalibration.sensitivityFromPrefs(prefs) }
        val storedDoseRateFormatting = remember(refresh) { DoseRateFormatting.fromPrefs(prefs) }
        val doseRateFormatting = remember(refresh, sensitivity) {
            DoseRateFormatting.validForSensitivity(storedDoseRateFormatting, sensitivity)
        }
        LaunchedEffect(sensitivity) {
            DoseRateFormatting.normalizePrefsForSensitivity(prefs, sensitivity)
        }
        val doseRateFormattingChoices = remember(refresh, sensitivity) {
            DoseRateFormatting.values()
                .filter { (sensitivity != 1.0 && sensitivity > 0.0) || !it.isDoseRate }
                .map { it.preferenceLabel }
        }
        val (alertVal, alertSub) = remember(refresh) { getAlertStrings(prefs) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                    start = 24.dp,
                    end = 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Section("Signal detection") {
                SettingsRow(
                    "Dosimeter",
                    currentDeviceName,
                    onClick = onDevice,
                    showChevron = true
                )
                SettingsRow(
                    "Threshold",
                    thresholdSummaryVal,
                    thresholdSubtitleVal,
                    onClick = { startActivity(Intent(this@SettingsActivity, CalibrationActivity::class.java).putExtra(CalibrationActivity.EXTRA_BLUETOOTH, false)) },
                    showChevron = true
                )
                SettingsRow(
                    "Bluetooth threshold",
                    btThresholdSummaryVal,
                    btThresholdSubtitleVal,
                    onClick = {
                        if (!AudioInputManager.isBluetoothMicAvailable(context)) {
                            toast("Bluetooth microphone not available.")
                        } else {
                            startActivity(Intent(this@SettingsActivity, CalibrationActivity::class.java).putExtra(CalibrationActivity.EXTRA_BLUETOOTH, true))
                        }
                    },
                    showChevron = true
                )
                SwitchRow(
                    "Use Bluetooth mic",
                    useBtMic
                ) {
                    prefs.edit {
                        putBoolean(
                            SettingsKeys.KEY_USE_BLUETOOTH_MIC_IF_AVAILABLE,
                            it
                        )
                    }
                    onRefresh()
                }
            }
            Section("Dose rate measurement") {
                ChoiceRow(
                    "Counts for dose rate averaging",
                    doseRateAvg,
                    subtitle = relativeErr,
                    listOf("5", "10", "15", "20", "30", "50", "100")
                ) {
                    prefs.edit {
                        putString(
                            "dose_rate_avg_timestamps_n",
                            it
                        )
                    }
                    onRefresh()
                }

                ChoiceRow(
                    "Dose rate formatting",
                    doseRateFormatting.sampleValue,
                    subtitle = doseRateFormatting.preferenceLabel,
                    doseRateFormattingChoices
                ) {
                    val selected = DoseRateFormatting.fromLabel(it) ?: doseRateFormatting
                    val normalized = DoseRateFormatting.validForSensitivity(selected, sensitivity)
                    prefs.edit { putString(SettingsKeys.KEY_DOSE_RATE_FORMATTING, normalized.preferenceLabel) }
                    onRefresh()
                }

                SettingsRow(
                    title = context.getString(R.string.alert_at_dose_rate),
                    value = alertVal,
                    subtitle = alertSub,
                    onClick = {
                        showEditDialog(
                            "Alert at dose rate",
                            prefs.getString("alert_dose_rate", "0") ?: "0",
                            decimal = true,
                            signed = false
                        ) {
                            prefs.edit { putString("alert_dose_rate", it) }
                            onRefresh()
                        }
                    }
                )
            }
            Section("Track recording") {
                SettingsRow(
                    "Save folder",
                    rememberFolderSummary(
                        prefs.getString(
                            SettingsKeys.KEY_GPX_TREE_URI,
                            null
                        )
                    ),
                    "Press to change",
                    onClick = { folderLauncher.launch(null) })
                EditPref(
                    "GPS Spoofing detection speed",
                    "max_speed_kmh",
                    "30000.0",
                    "km/h",
                    true,
                    refresh,
                    onRefresh
                )
                EditPref(
                    "Min distance between points",
                    "point_spacing_m",
                    "10.0",
                    "m",
                    true,
                    refresh,
                    onRefresh
                )
                EditPref(
                    "Min counts per point",
                    "min_counts_per_point",
                    "10",
                    null,
                    false,
                    refresh,
                    onRefresh
                )
                EditPref(
                    "Max time without counts",
                    "max_time_without_counts_s",
                    "10",
                    "s",
                    true,
                    refresh,
                    onRefresh
                )
                EditPref(
                    "Max time without GPS",
                    "max_time_without_gps_s",
                    "30",
                    "s",
                    true,
                    refresh,
                    onRefresh
                )
                SwitchRow(
                    "Also save dose rate in <ele> tag",
                    prefs.getBoolean("save_dose_rate_in_ele", false)
                ) { prefs.edit { putBoolean("save_dose_rate_in_ele", it) }; onRefresh() }
            }
        }
    }

    @Composable
    private fun DeviceSettings(padding: PaddingValues, refresh: Int, onChoose: () -> Unit, onRefresh: () -> Unit) {
        val context = LocalContext.current
        val device = remember(refresh) { DeviceConfigManager.currentDevice(context) } ?: return

        val isTracking by (application as GeigerGpxApp).trackingRepository.isTracking.collectAsState()
        val measurementModeEnabled by (application as GeigerGpxApp).trackingRepository.measurementModeEnabled.collectAsState()
        val active = isTracking || measurementModeEnabled

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                    start = 24.dp,
                    end = 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Button(
                onClick = { if (active) toast("Cannot change device while tracking or measuring") else onChoose() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
            ) { Text(context.getString(R.string.change_device)) }
            Section("Current device parameters") {
                SettingsRow(
                    "Device name",
                    device.name,
                    if (device.isCustom) "Custom" else "Built-in",
                    enabled = device.isCustom,
                    onClick = {
                        if (active) toast("Cannot rename device while tracking or measuring") else renameDevice(
                            device,
                            onRefresh
                        )
                    })
                DeviceParam(
                    "Sensitivity",
                    RadiationCalibration.KEY_SENSITIVITY,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        RadiationCalibration.KEY_SENSITIVITY
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                SettingsRow("Beep detector", "Goertzel detector", enabled = false)
                DeviceParam(
                    "Counts per beep",
                    DeviceConfigManager.KEY_COUNTS_PER_BEEP,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_COUNTS_PER_BEEP
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Low frequency",
                    DeviceConfigManager.KEY_FREQ_LOW,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_FREQ_LOW
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Main frequency",
                    DeviceConfigManager.KEY_FREQ_MAIN,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_FREQ_MAIN
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "High frequency",
                    DeviceConfigManager.KEY_FREQ_HIGH,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_FREQ_HIGH
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Beep duration",
                    DeviceConfigManager.KEY_DURATION,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_DURATION
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Dominance threshold",
                    DeviceConfigManager.KEY_DOMINANCE_THRESHOLD,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_DOMINANCE_THRESHOLD
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Dominance threshold fade-out",
                    DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_DOMINANCE_THRESHOLD_END
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Window size",
                    DeviceConfigManager.KEY_WINDOW_SIZE,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_WINDOW_SIZE
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Step size",
                    DeviceConfigManager.KEY_STEP_SIZE,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_STEP_SIZE
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Single beep duration tolerance",
                    DeviceConfigManager.KEY_ONE_BEEP_TOL,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_ONE_BEEP_TOL
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Double beep duration tolerance",
                    DeviceConfigManager.KEY_TWO_BEEP_TOL,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_TWO_BEEP_TOL
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Triple beep duration tolerance",
                    DeviceConfigManager.KEY_THREE_BEEP_TOL,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_THREE_BEEP_TOL
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
                DeviceParam(
                    "Quad beep duration tolerance",
                    DeviceConfigManager.KEY_FOUR_BEEP_TOL,
                    DeviceConfigManager.getPropertyValue(
                        device,
                        DeviceConfigManager.KEY_FOUR_BEEP_TOL
                    ),
                    device.isCustom,
                    active,
                    onRefresh
                )
            }
        }
    }

    @Composable
    private fun ChooseDevice(padding: PaddingValues, refresh: Int, onSelected: () -> Unit, onRefresh: () -> Unit) {
        val context = LocalContext.current
        val devices = remember(refresh) { DeviceConfigManager.devices(context) }
        val current = remember(refresh) { DeviceConfigManager.currentDevice(context)?.name }

        LazyColumn(contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
            start = 24.dp,
            end = 24.dp
        ),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            items(devices, key = { d -> d.name }) { d ->
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    onClick = { DeviceConfigManager.selectDevice(context, d.name); onSelected() }
                ) {
                    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        deviceIconRes(d.name)?.let {
                            Image(painter = painterResource(it), contentDescription = null, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.width(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text((if (d.isCustom) "Custom" else "Built-in") + if (d.name == current) " • ACTIVE" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Show delete icon for custom devices
                        if (d.isCustom) {
                            IconButton(onClick = { showDeleteConfirmation(d.name, onRefresh) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        // Show checkmark if it's the active device
                        if (d.name == current) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column {
            // 1. The Title
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                // Align start with the card's inner content (16.dp)
                // Add a little padding to the bottom so it doesn't hug the card
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
            )

            // 2. The Card
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    // 16.dp horizontal to match the title above it
                    Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    content()
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SettingsRow(
        title: String,
        value: String? = null,
        subtitle: String? = null,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        showChevron: Boolean = false
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if ((onClick != null || onLongClick != null) && enabled) Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick
                    ) else Modifier
                )
                .defaultMinSize(minHeight = 32.dp)
        ) {
            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showChevron) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(R.drawable.baseline_navigate_next_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            )
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        checked: Boolean,
        subtitle: String? = null,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (subtitle != null) Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked, onCheckedChange)
                }
                HorizontalDivider(
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                )
            }
        }
    }

    @Composable
    private fun ChoiceRow(
        title: String,
        value: String,
        subtitle: String?,
        choices: List<String>,
        onChoice: (String) -> Unit
    ) {
        var open by remember { mutableStateOf(false) }
        Box {
            SettingsRow(
                title,
                value,
                subtitle = subtitle,
                onClick = { open = true }
            )
            DropdownMenu(
                open,
                onDismissRequest = { open = false }
            ) {
                choices.forEach {
                    DropdownMenuItem(
                        text = { Text(it) },
                        onClick = { open = false; onChoice(it) }
                    )
                }
            }
        }
    }

    @Composable
    private fun EditPref(
        title: String,
        key: String,
        default: String,
        unit: String?,
        decimal: Boolean,
        refresh: Int,
        onRefresh: () -> Unit
    ) {
        val context = LocalContext.current
        val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
        val value = remember(refresh, key) { prefs.getString(key, default) ?: default }

        // Restored the blank check logic here
        val summary = if (value.isBlank()) "Not set" else if (unit == null) value else "$value $unit"

        SettingsRow(title, summary, onClick = {
            showEditDialog(title, value, decimal, false) {
                prefs.edit { putString(key, it) }
                onRefresh()
            }
        })
    }

    @Composable
    private fun DeviceParam(
        title: String,
        key: String,
        value: String,
        isCustom: Boolean,
        trackingActive: Boolean,
        onRefresh: () -> Unit
    ) {
        SettingsRow(
            title,
            formatDeviceSummary(key, value),
            enabled = isCustom,
            onClick = {
                if (trackingActive) toast("Cannot edit parameters while tracking or measuring") else showEditDialog(
                    title,
                    formatValue(key, value),
                    key != DeviceConfigManager.KEY_COUNTS_PER_BEEP,
                    key == RadiationCalibration.KEY_SENSITIVITY
                ) { newValue ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        DeviceConfigManager.updateActiveDeviceProperty(this@SettingsActivity, key, newValue)
                        withContext(Dispatchers.Main) {
                            onRefresh()
                        }
                    }
                }
            }
        )
    }

    private fun showEditDialog(
        title: String,
        value: String,
        decimal: Boolean,
        signed: Boolean,
        onSave: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(value)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        }
        trackDialog(
            AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton(R.string.save) { _, _ -> onSave(input.text.toString().trim()) }
                .setNegativeButton(R.string.cancel, null).create()
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun thresholdKey(bluetooth: Boolean) =
        if (bluetooth) SettingsKeys.KEY_BLUETOOTH_AUDIO_THRESHOLD else SettingsKeys.KEY_AUDIO_THRESHOLD

    private fun defaultThreshold(bluetooth: Boolean) =
        if (bluetooth) AudioInputManager.DEFAULT_BLUETOOTH_MAG_THRESHOLD else AudioInputManager.DEFAULT_MAG_THRESHOLD

    private fun toDb(intensity: Float) = 10.0 * log10(intensity.toDouble() / 100.0)
    private fun thresholdSummary(bluetooth: Boolean): String {
        val v = PreferenceManager.getDefaultSharedPreferences(this)
            .getFloat(thresholdKey(bluetooth), Float.NaN)
        val threshold = if (v.isNaN()) defaultThreshold(bluetooth) else v
        return "%.2f dB".format(
            java.util.Locale.US,
            toDb(threshold)
        )
    }

    private fun thresholdSubtitle(bluetooth: Boolean): String {
        val v = PreferenceManager.getDefaultSharedPreferences(this).getFloat(
            thresholdKey(bluetooth),
            Float.NaN
        )
        return if (v.isNaN()) "Uncalibrated" else "Press to calibrate"
    }

    private fun  getRelativeErrString(prefs: android.content.SharedPreferences): String? {
        val avg = prefs.getString("dose_rate_avg_timestamps_n", "10")?.toIntOrNull() ?: 10
        val err = ConfidenceInterval.relativeErrPercent(avg) ?: return null
        return "Relative error: %.0f%%".format(java.util.Locale.US, err)
    }

    private fun getAlertStrings(prefs: android.content.SharedPreferences): Pair<String, String?> {
        val alertValStr = prefs.getString("alert_dose_rate", "0") ?: "0"
        val alertVal = alertValStr.toDoubleOrNull() ?: 0.0
        val sens = RadiationCalibration.sensitivityFromPrefs(prefs)
        val unit = if (sens == 1.0) "cps" else "μSv/h"

        val valueStr = if (alertVal <= 0.0) "Not set" else "%.2f %s".format(java.util.Locale.US, alertVal, unit)

        val subtitleStr = if (alertVal <= 0.0 || sens == 1.0) null else {
            val avg = prefs.getString("dose_rate_avg_timestamps_n", "10")?.toIntOrNull() ?: 10
            val rate = ConfidenceInterval.getFalseAlarmRate(alertVal, avg, sens)
            "False alarms: %.1f / hour".format(java.util.Locale.US, rate)
        }

        return Pair(valueStr, subtitleStr)
    }

    private fun fromDb(value: Float) = 10.0.pow(value / 10.0) * 100.0

    private fun renameDevice(device: DeviceConfigManager.Device, onRefresh: () -> Unit) {
        if (!device.isCustom) return
        val input = EditText(this).apply {
            setText(device.name)
            setSelection(text.length)
            isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle(R.string.rename_device).setView(input)
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            input.error = "Name cannot be empty"
                            return@setOnClickListener
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val success = DeviceConfigManager.renameActiveDevice(this@SettingsActivity, name)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    dialog.dismiss()
                                    onRefresh()
                                } else {
                                    input.error = "Name already exists"
                                }
                            }
                        }
                    }
                }
                trackDialog(dialog)
            }
    }

    private fun showDeleteConfirmation(name: String, onRefresh: () -> Unit) {
        trackDialog(AlertDialog.Builder(this)
            .setTitle(R.string.delete_device)
            .setMessage(getString(R.string.delete_device_confirmation, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = DeviceConfigManager.deleteDevice(this@SettingsActivity, name)
                    if (success) {
                        withContext(Dispatchers.Main) {
                            toast("Device deleted")
                            onRefresh()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create())
    }

    private fun showCloneDialog(done: () -> Unit) {
        val names = DeviceConfigManager.devices(this).map { it.name }.toTypedArray()
        trackDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_base_device)
                .setItems(names) { _, which ->
                    val input = EditText(this).apply {
                        hint = getString(R.string.new_device_name)
                        isSingleLine = true
                    }
                    val d = AlertDialog.Builder(this)
                        .setTitle(R.string.enter_name_new_device)
                        .setView(input)
                        .setPositiveButton(R.string.create, null)
                        .setNegativeButton(R.string.cancel, null)
                        .create()

                    d.setOnShowListener {
                        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val n = input.text.toString().trim()
                            if (n.isEmpty()) {
                                input.error = "Name cannot be empty"
                                return@setOnClickListener
                            }
                            lifecycleScope.launch(Dispatchers.IO) {
                                val success = DeviceConfigManager.cloneDevice(
                                    this@SettingsActivity,
                                    names[which],
                                    n
                                )
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        toast("Device created and selected")
                                        d.dismiss()
                                        done()
                                    } else {
                                        input.error = "Device name already exists"
                                    }
                                }
                            }
                        }
                    }
                    trackDialog(d)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
    }

    private fun deviceIconRes(name: String): Int? = when (name) {
        "RADEX RD1008" -> R.drawable.rd1008_24
        "RADEX RD1224Si" -> R.drawable.rd1224si_24
        else -> null
    }

    private fun formatDeviceSummary(key: String, value: String): String {
        val unit = when (key) {
            RadiationCalibration.KEY_SENSITIVITY -> "cps per μSv/h"
            DeviceConfigManager.KEY_FREQ_LOW, DeviceConfigManager.KEY_FREQ_MAIN, DeviceConfigManager.KEY_FREQ_HIGH -> "Hz"
            DeviceConfigManager.KEY_DURATION, DeviceConfigManager.KEY_WINDOW_SIZE, DeviceConfigManager.KEY_STEP_SIZE, DeviceConfigManager.KEY_ONE_BEEP_TOL, DeviceConfigManager.KEY_TWO_BEEP_TOL, DeviceConfigManager.KEY_THREE_BEEP_TOL, DeviceConfigManager.KEY_FOUR_BEEP_TOL -> "s" else -> ""
        }
        val v = formatValue(key, value)
        return if (unit.isEmpty()) v else "$v $unit"
    }

    private fun formatValue(key: String, value: String): String {
        val bd = try {
            java.math.BigDecimal(value)
        } catch (_: Exception) {
            return value
        }
        return if (key == DeviceConfigManager.KEY_FREQ_LOW || key == DeviceConfigManager.KEY_FREQ_MAIN || key == DeviceConfigManager.KEY_FREQ_HIGH) "%.1f".format(
            java.util.Locale.US,
            bd.toDouble()
        ) else bd.stripTrailingZeros().toPlainString()
    }

    @Composable
    private fun rememberFolderSummary(uriString: String?): String {
        val context = LocalContext.current
        var summary by remember(uriString) { mutableStateOf("Loading...") }
        LaunchedEffect(
            uriString
        ) {
            if (uriString.isNullOrBlank()) {
                summary = "App folder"
                return@LaunchedEffect
            }
            summary = withContext(Dispatchers.IO) {
                try {
                    val uri = uriString.toUri()
                    DocumentFile.fromTreeUri(context, uri)?.name
                        ?: uriString
                } catch (_: Exception) {
                    uriString
                }
            }
        }
        return summary
    }

    private fun trackDialog(dialog: AlertDialog) {
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
        if (!isFinishing && !isDestroyed) dialog.show()
    }

    private fun dismissActiveDialogs() {
        activeDialogs.toList()
            .forEach { dialog -> if (dialog.isShowing) dialog.dismiss() }
        activeDialogs.clear()
    }
}
