package com.thermolog.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermolog.ble.FoundDevice
import com.thermolog.data.SyncState
import com.thermolog.data.entity.Sensor
import com.thermolog.viewmodel.SensorListViewModel
import com.thermolog.viewmodel.TempProbe
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorListScreen(
    viewModel: SensorListViewModel,
    onOpenSensor: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showScanSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Sensor?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            showScanSheet = true
            viewModel.scanForDevices()
        }
    }

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
        return "thermolog-backup-$stamp.json"
    }

    fun requestPermissionsAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(perms)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ThermoLog") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { requestPermissionsAndScan() }) {
                        Icon(Icons.Default.Add, "Add sensor",
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More",
                                tint = MaterialTheme.colorScheme.onPrimary)
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
                        }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            if (state.sensors.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
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
                    onSync = { viewModel.syncSensor(sensor.address) },
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

@Composable
private fun SensorCard(
    sensor: Sensor,
    syncState: SyncState?,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeviceThermostat, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sensor.alias ?: sensor.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(sensor.address, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    if (sensor.lastSyncMs > 0) {
                        Text("Last sync: ${formatTime(sensor.lastSyncMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, "Options")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

            Spacer(Modifier.height(8.dp))

            when (val s = syncState) {
                is SyncState.Connecting, is SyncState.Progress -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(if (s is SyncState.Progress) s.step else "Connecting…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                is SyncState.Done -> {
                    Text("✓ Synced (${s.newReadings} new readings)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Sync again") }
                }
                is SyncState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Retry Sync") }
                }
                else -> {
                    Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sync now")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanSheet(
    isScanning: Boolean,
    results: List<FoundDevice>,
    knownAddresses: Set<String>,
    liveTemps: Map<String, TempProbe>,
    onRescan: () -> Unit,
    onAdd: (FoundDevice) -> Unit
) {
    var showAll by remember { mutableStateOf(false) }

    // Strongest signal first — your nearest sensor sorts to the top
    val likely = results.filter { it.isLikelySensor }.sortedByDescending { it.rssi }
    val others = results.filterNot { it.isLikelySensor }.sortedByDescending { it.rssi }

    Column(Modifier.padding(16.dp).fillMaxWidth()) {
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
            likely.forEach { DeviceRow(it, it.address in knownAddresses, liveTemps[it.address], onAdd) }
        }

        if (others.isNotEmpty()) {
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
                others.forEach { DeviceRow(it, it.address in knownAddresses, liveTemps[it.address], onAdd) }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceRow(
    device: FoundDevice,
    alreadyAdded: Boolean,
    probe: TempProbe?,
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
                        Text("%.1f°C now".format(probe.celsius),
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
            Icon(
                if (device.isLikelySensor) Icons.Default.DeviceThermostat else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (device.isLikelySensor) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
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

private fun formatTime(ms: Long) =
    SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))
