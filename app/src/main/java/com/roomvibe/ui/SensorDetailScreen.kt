package com.roomvibe.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomvibe.ble.connectPermissionsFor
import com.roomvibe.data.AppSettings
import com.roomvibe.data.formatTemp
import com.roomvibe.data.entity.Reading
import com.roomvibe.ui.chart.Metric
import com.roomvibe.ui.chart.MetricChart
import com.roomvibe.ui.chart.Viewport
import com.roomvibe.ui.chart.zoomLabel
import com.roomvibe.viewmodel.SensorDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private val ScreenBg = Color(0xFF0E0F12)
private val CardBg = Color(0xFF1B1D21)
private val TempAccent = Color(0xFFFF7043)
private val HumidAccent = Color(0xFF35A7FF)
private val TextHi = Color(0xFFECEFF1)
private val TextLo = Color(0xFF9AA0A6)

/** Persists the chart viewport across configuration changes (e.g. rotation). */
private val ViewportSaver = listSaver<Viewport?, Long>(
    save = { vp -> vp?.let { listOf(it.startMs, it.endMs) } ?: emptyList() },
    restore = { if (it.size == 2) Viewport(it[0], it[1]) else null }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDetailScreen(
    viewModel: SensorDetailViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val fahrenheit by AppSettings.get(context).fahrenheit.collectAsStateWithLifecycle()
    var showGattExplorer by remember { mutableStateOf(false) }
    // rememberSaveable so zoom/pan and the scrubber survive rotation
    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf<Viewport?>(null) }
    var scrubberMs by rememberSaveable { mutableStateOf<Long?>(null) }
    // Landscape shows one chart at a time: 0 = Temperature, 1 = Humidity
    var landscapeMetric by rememberSaveable { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Syncing connects over BLE, which needs BLUETOOTH_CONNECT on Android 12+.
    fun connectPerms(): Array<String> = connectPermissionsFor(Build.VERSION.SDK_INT)

    val refreshPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) viewModel.refresh()
        else viewModel.showMessage("Bluetooth permission is required to sync. Enable it in Settings → Permissions.")
    }

    fun requestRefresh() {
        val perms = connectPerms()
        if (perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.refresh()
        } else {
            refreshPermLauncher.launch(perms)
        }
    }

    LaunchedEffect(state.refreshMessage) {
        state.refreshMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRefreshMessage()
        }
    }

    LaunchedEffect(state.newestMs, state.oldestMs) {
        val newest = state.newestMs
        val oldest = state.oldestMs
        if (viewport == null && newest != null && oldest != null) {
            val start = (newest - 3 * DAY).coerceAtLeast(oldest)
            viewport = Viewport(start, newest)
        }
    }

    Scaffold(
        containerColor = ScreenBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Landscape floats the controls over the chart instead (see below)
            if (!isLandscape) {
                TopAppBar(
                    title = { Text(state.sensorDisplayName.ifEmpty { state.sensorAddress }) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0E0F12),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    ),
                    actions = {
                        if (state.isRefreshing) {
                            IconButton(onClick = { viewModel.cancelRefresh() }) {
                                Icon(Icons.Default.Close, "Cancel sync")
                            }
                        } else {
                            IconButton(onClick = { requestRefresh() }) {
                                Icon(Icons.Default.Refresh, "Refresh")
                            }
                        }
                        // GATT Explorer hidden for now — keep the code, just no entry point.
                        // IconButton(onClick = { showGattExplorer = !showGattExplorer }) {
                        //     Icon(Icons.Default.Build, "GATT Explorer")
                        // }
                    }
                )
            }
        }
    ) { pad ->
        val vp = viewport

        val tempChart: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m) {
                MetricChart(
                    title = "Temperature", unit = "°", metric = Metric.TEMP,
                    accent = TempAccent, readings = state.readings, viewport = vp ?: Viewport(0, 1),
                    scrubberMs = scrubberMs, showTimeLabel = true, showTitle = !isLandscape,
                    colorByValue = true, fahrenheit = fahrenheit,
                    onViewportChange = { viewport = it }, onScrub = { scrubberMs = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val humidChart: @Composable (Modifier) -> Unit = { m ->
            ChartCard(m) {
                MetricChart(
                    title = "Humidity", unit = "%", metric = Metric.HUMIDITY,
                    accent = HumidAccent, readings = state.readings, viewport = vp ?: Viewport(0, 1),
                    scrubberMs = scrubberMs, showTimeLabel = true, showTitle = !isLandscape,
                    colorByValue = true,
                    onViewportChange = { viewport = it }, onScrub = { scrubberMs = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (isLandscape) {
            // Maximise the chart: one metric at a time, filling the whole body,
            // with the back arrow + metric toggle floating on top of it.
            Box(Modifier.padding(pad).fillMaxSize().background(ScreenBg)) {
                when {
                    vp != null && landscapeMetric == 0 -> tempChart(Modifier.fillMaxSize().padding(4.dp))
                    vp != null -> humidChart(Modifier.fillMaxSize().padding(4.dp))
                    state.isLoading || state.isRefreshing ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NoDataMessage() }
                }

                // Floating controls (top-left), clear of any display cutout
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .displayCutoutPadding()
                        .padding(8.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.35f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        MetricToggle(selected = landscapeMetric, onSelect = { landscapeMetric = it })
                        if (state.isRefreshing) {
                            IconButton(onClick = { viewModel.cancelRefresh() }) {
                                Icon(Icons.Default.Close, "Cancel sync", tint = Color.White)
                            }
                        } else {
                            IconButton(onClick = { requestRefresh() }) {
                                Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                            }
                        }
                    }
                }

                // Sync progress (top-centre) so the counter is visible in landscape too
                if (state.isRefreshing) {
                    Box(Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(top = 8.dp)) {
                        RefreshBanner(state.refreshProgress)
                    }
                }
            }
            return@Scaffold
        }

        Column(
            Modifier.padding(pad).fillMaxSize().background(ScreenBg).padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            if (state.isRefreshing) {
                RefreshBanner(state.refreshProgress)
                Spacer(Modifier.height(8.dp))
            }

            CurrentReadingHeader(state.latest, state.newestMs, fahrenheit)

            Text(
                state.sensorAddress,
                style = MaterialTheme.typography.labelSmall,
                color = TextLo,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            Spacer(Modifier.height(8.dp))

            // Zoom-level + range
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timeline, null, Modifier.size(16.dp), tint = TextHi)
                        Spacer(Modifier.width(4.dp))
                        Text(vp?.let { zoomLabel(it.span) } ?: "—",
                            style = MaterialTheme.typography.labelMedium, color = TextHi)
                    }
                }
                Text(vp?.let { rangeLabel(it.startMs, it.endMs) } ?: "",
                    style = MaterialTheme.typography.labelMedium, color = TextLo)
            }

            Spacer(Modifier.height(6.dp))

            if (vp != null) {
                tempChart(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                humidChart(Modifier.weight(1f).fillMaxWidth())
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.isLoading || state.isRefreshing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        NoDataMessage()
                    }
                }
            }

            Text(
                "Upper area: move marker · Lower area: scroll time · Two fingers: zoom",
                style = MaterialTheme.typography.labelSmall, color = TextLo,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            AnimatedVisibility(visible = showGattExplorer) {
                GattExplorerPanel(
                    sensorName = state.sensorDisplayName,
                    sensorAddress = state.sensorAddress,
                    services = state.gattServices,
                    isExploring = state.isExploring,
                    onExplore = { viewModel.exploreGatt() }
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Landscape metric switcher shown in the top bar (Temperature / Humidity). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricToggle(selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("Temperature" to TempAccent, "Humidity" to HumidAccent).forEachIndexed { i, (label, accent) ->
            val isSel = selected == i
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(50),
                color = if (isSel) Color.White.copy(alpha = 0.22f) else Color.Transparent
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = if (isSel) Color.White else Color.White.copy(alpha = 0.6f),
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (i == 0) Spacer(Modifier.width(6.dp))
        }
    }
}

/** Shown when the sensor has no stored readings yet. */
@Composable
private fun NoDataMessage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(24.dp)
    ) {
        Icon(Icons.Default.Timeline, null, Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        Text("No data yet", style = MaterialTheme.typography.titleMedium, color = TextHi)
        Text(
            "Tap the refresh icon at the top to sync this sensor's history.",
            style = MaterialTheme.typography.bodySmall, color = TextLo
        )
    }
}

/** Small banner shown while syncing, with a spinner and the live progress/counter. */
@Composable
private fun RefreshBanner(progress: String?) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                progress ?: "Syncing…",
                style = MaterialTheme.typography.labelLarge,
                color = TextHi
            )
        }
    }
}

@Composable
private fun ChartCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardBg
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp), content = content)
    }
}

@Composable
private fun CurrentReadingHeader(latest: Reading?, newestMs: Long?, fahrenheit: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Thermostat, null, tint = TempAccent)
                Spacer(Modifier.width(6.dp))
                Text(latest?.let { formatTemp(it.temperatureCelsius, fahrenheit) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextHi)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WaterDrop, null, tint = HumidAccent)
                Spacer(Modifier.width(6.dp))
                Text(latest?.let { "${it.humidityPercent}%" } ?: "—",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextHi)
            }
            Text(newestMs?.let { fmtDateTime(it) } ?: "No data",
                style = MaterialTheme.typography.labelSmall, color = TextLo)
        }
    }
}

private fun rangeLabel(startMs: Long, endMs: Long): String {
    val span = endMs - startMs
    val fmt = if (span <= 2 * DAY) SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
              else SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return "${fmt.format(Date(startMs))} – ${fmt.format(Date(endMs))}"
}

private fun fmtDateTime(ms: Long) =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

// ── GATT Explorer (debug) ──────────────────────────────────────────────────

private fun buildGattDump(
    sensorName: String,
    sensorAddress: String,
    services: List<Pair<String, List<String>>>
): String =
    buildString {
        appendLine("Sensor: ${sensorName.ifEmpty { "(unnamed)" }}")
        appendLine("Address: $sensorAddress")
        appendLine()
        services.forEach { (svc, chars) ->
            appendLine("SERVICE $svc")
            chars.forEach { ch -> appendLine("  CHAR  $ch") }
        }
    }.trimEnd()

@Composable
private fun GattExplorerPanel(
    sensorName: String,
    sensorAddress: String,
    services: List<Pair<String, List<String>>>,
    isExploring: Boolean,
    onExplore: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val dump = remember(sensorName, sensorAddress, services) {
        buildGattDump(sensorName, sensorAddress, services)
    }

    Surface(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = CardBg
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("GATT Explorer", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = TextHi)
            Text("Lists the BLE characteristics on your sensor. Tap Copy and paste the result back to share it.",
                style = MaterialTheme.typography.bodySmall, color = TextLo)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExplore, enabled = !isExploring) {
                    if (isExploring) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Connect & List Services")
                }
                if (services.isNotEmpty()) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(dump)) }) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
            }

            if (services.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF0E0F12),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                ) {
                    Text(
                        text = dump,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextHi,
                        modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
