package com.thermolog.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thermolog.data.entity.Reading
import com.thermolog.ui.chart.Metric
import com.thermolog.ui.chart.MetricChart
import com.thermolog.ui.chart.Viewport
import com.thermolog.ui.chart.zoomLabel
import com.thermolog.viewmodel.SensorDetailViewModel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDetailScreen(
    viewModel: SensorDetailViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showGattExplorer by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<Viewport?>(null) }
    var scrubberMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.newestMs, state.oldestMs) {
        val newest = state.newestMs
        val oldest = state.oldestMs
        if (viewport == null && newest != null && oldest != null) {
            val start = (newest - 3 * DAY).coerceAtLeast(oldest)
            viewport = Viewport(start, newest)
        }
    }

    fun scaleZoom(factor: Float) {
        val vp = viewport ?: return
        val center = (vp.startMs + vp.endMs) / 2
        val newSpan = (vp.span * factor).toLong().coerceIn(3 * HOUR, Long.MAX_VALUE / 4)
        viewport = Viewport(center - newSpan / 2, center + newSpan / 2)
    }

    fun fitAll() {
        val o = state.oldestMs ?: return
        val n = state.newestMs ?: return
        val pad = ((n - o) * 0.02).toLong()
        viewport = Viewport(o - pad, n + pad)
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = { Text(state.sensorDisplayName.ifEmpty { state.sensorAddress }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { showGattExplorer = !showGattExplorer }) {
                        Icon(Icons.Default.Build, "GATT Explorer")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().background(ScreenBg).padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            CurrentReadingHeader(state.latest, state.newestMs)

            Spacer(Modifier.height(8.dp))

            // Zoom-level + range
            val vp = viewport
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

            if (state.isLoading || vp == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                ChartCard(Modifier.weight(1f)) {
                    MetricChart(
                        title = "Temperature",
                        unit = "°",
                        metric = Metric.TEMP,
                        accent = TempAccent,
                        readings = state.readings,
                        viewport = vp,
                        scrubberMs = scrubberMs,
                        showTimeLabel = true,
                        onViewportChange = { viewport = it },
                        onScrub = { scrubberMs = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(8.dp))
                ChartCard(Modifier.weight(1f)) {
                    MetricChart(
                        title = "Humidity",
                        unit = "%",
                        metric = Metric.HUMIDITY,
                        accent = HumidAccent,
                        readings = state.readings,
                        viewport = vp,
                        scrubberMs = scrubberMs,
                        showTimeLabel = false,
                        onViewportChange = { viewport = it },
                        onScrub = { scrubberMs = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Zoom controls
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = { scaleZoom(2f) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ZoomOut, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Zoom out")
                }
                FilledTonalIconButton(onClick = { fitAll() }) {
                    Icon(Icons.Default.FitScreen, "Fit all")
                }
                FilledTonalButton(onClick = { scaleZoom(0.5f) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ZoomIn, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Zoom in")
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
private fun CurrentReadingHeader(latest: Reading?, newestMs: Long?) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Thermostat, null, tint = TempAccent)
                Spacer(Modifier.width(6.dp))
                Text(latest?.let { "%.1f°C".format(it.temperatureCelsius) } ?: "—",
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
