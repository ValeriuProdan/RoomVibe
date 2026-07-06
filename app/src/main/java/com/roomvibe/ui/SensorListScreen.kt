package com.roomvibe.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomvibe.R
import com.roomvibe.ble.FoundDevice
import com.roomvibe.ble.LywsdProtocol
import com.roomvibe.ble.blePermissionsFor
import com.roomvibe.ble.connectPermissionsFor
import com.roomvibe.data.AppSettings
import com.roomvibe.data.SyncState
import com.roomvibe.data.formatTemp
import com.roomvibe.data.entity.Sensor
import com.roomvibe.viewmodel.SensorListViewModel
import com.roomvibe.viewmodel.TempProbe
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private fun requiredBlePermissions(): Array<String> = blePermissionsFor(Build.VERSION.SDK_INT)
private fun requiredConnectPermissions(): Array<String> = connectPermissionsFor(Build.VERSION.SDK_INT)

// Brand wordmark styling (Pacifico script, orange to match the app icon)
private val BrandFont = FontFamily(Font(R.font.pacifico_regular))
private val BrandOrange = Color(0xFFFF7A1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorListScreen(
    viewModel: SensorListViewModel,
    onOpenSensor: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val fahrenheit by settings.fahrenheit.collectAsStateWithLifecycle()
    var showScanSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Sensor?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    var showDeniedSettings by remember { mutableStateOf(false) }
    // Permissions to request, and the action to run once they're granted.
    var pendingPerms by remember { mutableStateOf(requiredBlePermissions()) }
    var pendingAction by remember { mutableStateOf<() -> Unit>({}) }

    fun hasPermissions(perms: Array<String>): Boolean = perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun startScanning() {
        showScanSheet = true
        viewModel.scanForDevices()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            pendingAction()
        } else {
            // Denied — offer to enable it from system settings
            showDeniedSettings = true
        }
    }

    // Run [action], first requesting [perms] (with a rationale) if they're missing.
    fun withPermission(perms: Array<String>, action: () -> Unit) {
        if (hasPermissions(perms)) action()
        else { pendingPerms = perms; pendingAction = action; showRationale = true }
    }

    fun onAddClicked() = withPermission(requiredBlePermissions()) { startScanning() }

    // "Save to…" — the dialog includes Google Drive as a destination
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.backupTo(it) } }

    // "Open…" — pick a previously saved backup (e.g. from Google Drive)
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.restoreFrom(it) } }

    fun defaultBackupName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "roomvibe-backup-$stamp.json"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "RoomVibe",
                        fontFamily = BrandFont,
                        color = BrandOrange,
                        fontSize = 26.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0E0F12),
                    titleContentColor = BrandOrange
                ),
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More", tint = Color.White)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Back up to Drive…") },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                                enabled = !state.backupBusy,
                                onClick = {
                                    menuOpen = false
                                    backupLauncher.launch(defaultBackupName())
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Restore from Drive…") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                enabled = !state.backupBusy,
                                onClick = {
                                    menuOpen = false
                                    restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (fahrenheit) "Show in °C" else "Show in °F") },
                                leadingIcon = { Icon(Icons.Default.Thermostat, null) },
                                onClick = {
                                    settings.setFahrenheit(!fahrenheit)
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddClicked() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "Add sensor")
            }
        }
    ) { pad ->
        // Two sensors per row.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            if (state.sensors.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DeviceThermostat, null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            Text("No sensors yet", style = MaterialTheme.typography.titleMedium)
                            Text("Tap + to scan for nearby devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            items(state.sensors, key = { it.address }) { sensor ->
                SensorCard(
                    sensor = sensor,
                    syncState = state.syncStates[sensor.address],
                    onClick = { onOpenSensor(sensor.address) },
                    onSync = { withPermission(requiredConnectPermissions()) { viewModel.syncSensor(sensor.address) } },
                    onCancelSync = { viewModel.cancelSync(sensor.address) },
                    onRename = { renameTarget = sensor },
                    onDelete = { viewModel.removeSensor(sensor) }
                )
            }
        }
    }

    if (showScanSheet) {
        ModalBottomSheet(onDismissRequest = { showScanSheet = false }) {
            ScanSheet(
                isScanning = state.isScanning,
                results = state.scanResults,
                knownAddresses = state.sensors.map { it.address }.toSet(),
                liveTemps = state.liveTemps,
                fahrenheit = fahrenheit,
                onRescan = { viewModel.scanForDevices() },
                onAdd = { device ->
                    viewModel.addSensor(device)
                    showScanSheet = false
                }
            )
        }
    }

    renameTarget?.let { sensor ->
        RenameDialog(
            current = sensor.alias ?: sensor.name,
            onConfirm = { newName ->
                viewModel.renameSensor(sensor.address, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // Explain why we need Bluetooth/nearby permission, then request it
    if (showRationale) {
        val needsLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(Icons.Default.Bluetooth, null) },
            title = { Text("Bluetooth permission needed") },
            text = {
                Text(
                    if (needsLocation)
                        "To find your sensors over Bluetooth, Android needs the Location " +
                        "permission for BLE scanning. RoomVibe never uses or stores your location."
                    else
                        "RoomVibe needs the “Nearby devices” permission to scan for and connect " +
                        "to your Xiaomi sensors over Bluetooth. It is only used to talk to the sensors."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(pendingPerms)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Cancel") } }
        )
    }

    // Permission was denied — guide the user to enable it in system settings
    if (showDeniedSettings) {
        AlertDialog(
            onDismissRequest = { showDeniedSettings = false },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Permission required") },
            text = {
                Text(
                    "Without Bluetooth permission RoomVibe can't find your sensors. " +
                    "You can enable it in Settings → Permissions."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeniedSettings = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showDeniedSettings = false }) { Text("Not now") } }
        )
    }

    state.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } },
            title = { Text("Error") },
            text = { Text(msg) }
        )
    }

    state.infoMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearInfo() },
            confirmButton = { TextButton(onClick = { viewModel.clearInfo() }) { Text("OK") } },
            title = { Text("Done") },
            text = { Text(msg) }
        )
    }

    if (state.backupBusy) {
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("Working…")
                }
            }
        }
    }
}

/** Product photo for recognised Xiaomi sensors, generic thermometer icon otherwise. */
@Composable
private fun SensorAvatar(isXiaomi: Boolean, size: androidx.compose.ui.unit.Dp) {
    if (isXiaomi) {
        Image(
            painter = painterResource(R.drawable.xiaomi_sensor),
            contentDescription = "Xiaomi temperature & humidity sensor",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    } else {
        Icon(
            Icons.Default.DeviceThermostat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.8f)
        )
    }
}

@Composable
private fun SensorCard(
    sensor: Sensor,
    syncState: SyncState?,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onCancelSync: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val syncing = syncState is SyncState.Connecting || syncState is SyncState.Progress

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SensorAvatar(
                    isXiaomi = LywsdProtocol.isKnownThermometer(sensor.name),
                    size = 84.dp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    sensor.alias ?: sensor.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                SyncStatusLine(syncState)
            }

            // Overflow menu (Sync / Rename / Delete) in the top-right corner
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "Options")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (syncing) {
                        DropdownMenuItem(
                            text = { Text("Cancel sync") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = { expanded = false; onCancelSync() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Sync now") },
                            leadingIcon = { Icon(Icons.Default.Sync, null) },
                            onClick = { expanded = false; onSync() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { expanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { expanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** Compact, centered one-line sync status shown on a sensor tile. */
@Composable
private fun SyncStatusLine(syncState: SyncState?) {
    when (val s = syncState) {
        is SyncState.Connecting, is SyncState.Progress -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (s is SyncState.Progress) s.step else "Connecting…",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        is SyncState.Done -> Text(
            "✓ ${s.newReadings} new",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 1
        )
        is SyncState.Error -> Text(
            "Sync failed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error, maxLines = 1
        )
        else -> { /* idle: nothing — last-sync info lives on the detail screen */ }
    }
}

/** Tappable / hoverable help that explains the most common "can't find it" cause. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotFindingDeviceHelp() {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        state = tooltipState,
        tooltip = {
            RichTooltip(
                title = { Text("Not finding your device?") },
                action = {
                    TextButton(onClick = { scope.launch { tooltipState.dismiss() } }) { Text("Got it") }
                }
            ) {
                Text(
                    "A sensor can only talk to one app at a time. If the official Xiaomi " +
                    "app (Mi Home / Xiaomi Home) is running, it holds the Bluetooth " +
                    "connection and RoomVibe can't reach the sensor.\n\n" +
                    "Fix: close and force-stop the Xiaomi app\n" +
                    "(long-press its icon → App info → Force stop), then scan again.\n\n" +
                    "Also make sure you're close to the sensor and its battery is OK."
                )
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { scope.launch { tooltipState.show() } }
                .padding(vertical = 4.dp)
        ) {
            Icon(Icons.Outlined.HelpOutline, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text("Not finding your device?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ScanSheet(
    isScanning: Boolean,
    results: List<FoundDevice>,
    knownAddresses: Set<String>,
    liveTemps: Map<String, TempProbe>,
    fahrenheit: Boolean,
    onRescan: () -> Unit,
    onAdd: (FoundDevice) -> Unit
) {
    var showAll by remember { mutableStateOf(false) }

    // Strongest signal first — your nearest sensor sorts to the top
    val likely = results.filter { it.isLikelySensor }.sortedByDescending { it.rssi }
    val others = results.filterNot { it.isLikelySensor }.sortedByDescending { it.rssi }

    // Scrollable so all devices are reachable even when there are dozens of them.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Scan for sensors", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRescan, enabled = !isScanning) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan again")
                }
            }
            NotFindingDeviceHelp()
            Spacer(Modifier.height(8.dp))
            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Scanning… walk right up to the sensor", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (results.isEmpty() && !isScanning) {
                Text("No Bluetooth devices found at all. Check that the phone's Location " +
                    "switch is ON (Samsung needs it for scanning) and Bluetooth is enabled.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (likely.isNotEmpty()) {
                Text("Likely sensors", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        lazyItems(likely, key = { "L" + it.address }) {
            DeviceRow(it, it.address in knownAddresses, liveTemps[it.address], fahrenheit, onAdd)
        }

        if (others.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Hide other devices (${others.size})"
                         else "Show all other Bluetooth devices (${others.size})")
                }
                if (showAll) {
                    Text("If your sensor isn't above, it may show here as \"(unnamed device)\". " +
                        "Pick the one with the strongest signal while standing next to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            if (showAll) {
                lazyItems(others, key = { "O" + it.address }) {
                    DeviceRow(it, it.address in knownAddresses, liveTemps[it.address], fahrenheit, onAdd)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DeviceRow(
    device: FoundDevice,
    alreadyAdded: Boolean,
    probe: TempProbe?,
    fahrenheit: Boolean,
    onAdd: (FoundDevice) -> Unit
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name)
                if (probe is TempProbe.Value) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        shape = MaterialTheme.shapes.small) {
                        Text(formatTemp(probe.celsius, fahrenheit) + " now",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        },
        supportingContent = {
            val tempLine = when (probe) {
                is TempProbe.Loading -> "reading temperature…"
                is TempProbe.Failed -> "couldn't read (in use or far away)"
                else -> null
            }
            Text(buildString {
                append("${device.address}   •   signal ${device.rssi} dBm")
                if (tempLine != null) append("\n$tempLine")
            })
        },
        leadingContent = {
            if (device.isLikelySensor) {
                SensorAvatar(isXiaomi = true, size = 40.dp)
            } else {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        },
        trailingContent = {
            if (alreadyAdded) {
                Text("Added", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            } else if (probe is TempProbe.Loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { onAdd(device) }) { Text("Add") }
            }
        }
    )
    HorizontalDivider()
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename sensor") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
