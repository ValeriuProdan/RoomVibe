package com.roomvibe.ui

import android.content.res.Configuration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roomvibe.data.AppSettings
import com.roomvibe.ui.chart.CompareChart
import com.roomvibe.ui.chart.CompareSeries
import com.roomvibe.ui.chart.Lod
import com.roomvibe.ui.chart.Metric
import com.roomvibe.ui.chart.SeriesData
import com.roomvibe.ui.chart.SeriesPoint
import com.roomvibe.ui.chart.SeriesStyle
import com.roomvibe.ui.chart.Viewport
import com.roomvibe.ui.chart.bucketAt
import com.roomvibe.ui.chart.lodFor
import com.roomvibe.ui.chart.rememberTimeLabels
import com.roomvibe.ui.chart.seriesStyle
import com.roomvibe.ui.chart.zoomLabel
import com.roomvibe.viewmodel.CompareUiState
import com.roomvibe.viewmodel.CompareViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private val ScreenBg = Color(0xFF0E0F12)
private val CardBg = Color(0xFF1B1D21)
private val TextHi = Color(0xFFECEFF1)
private val TextLo = Color(0xFF9AA0A6)

/** One line of the marker readout. A null [bucket] means "recorded nothing here". */
internal data class ReadoutRow(
    val label: String,
    val style: SeriesStyle,
    val bucket: SeriesPoint?
)

/**
 * Devices that measured something rank warmest first; the rest park at the bottom
 * in the order they came in, which is fixed palette order.
 *
 * Keeping the no-data devices in the list at all — rather than dropping them — is
 * what stops rows jumping around as the marker crosses the start of one device's
 * history.
 */
internal fun List<ReadoutRow>.rankedForReadout(): List<ReadoutRow> =
    sortedWith(compareBy({ it.bucket == null }, { -(it.bucket?.mid ?: 0f) }))

/** A device's identity plus its bucketed history, before it is cut to a viewport. */
private data class Prepared(
    val address: String,
    val label: String,
    val style: SeriesStyle,
    val data: SeriesData
)

/** Persists the chart viewport across configuration changes (e.g. rotation). */
private val ViewportSaver = listSaver<Viewport?, Long>(
    save = { vp -> vp?.let { listOf(it.startMs, it.endMs) } ?: emptyList() },
    restore = { if (it.size == 2) Viewport(it[0], it[1]) else null }
)

/**
 * Plots several devices on one chart so differences between rooms are visible
 * directly, rather than by flipping between two single-sensor charts.
 *
 * Temperature and humidity get their own view (never two scales on one chart);
 * any number of devices can be checked on and off, each keeping the colour it was
 * assigned when it was added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    viewModel: CompareViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fahrenheit by AppSettings.get(context).fahrenheit.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf<Viewport?>(null) }
    var scrubberMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var metricIndex by rememberSaveable { mutableIntStateOf(0) }

    val metric = if (metricIndex == 0) Metric.TEMP else Metric.HUMIDITY
    val unit = if (metric == Metric.TEMP) "°" else "%"

    val oldest = state.oldestMs
    val newest = state.newestMs

    // First data in: show the last three days and park the marker on the newest
    // sample, so the readout below the chart is populated before any interaction.
    LaunchedEffect(oldest, newest) {
        if (viewport == null && oldest != null && newest != null) {
            viewport = Viewport((newest - 3 * DAY).coerceAtLeast(oldest), newest)
            if (scrubberMs == null) scrubberMs = newest
        }
    }

    val vp = viewport
    val lod = if (vp != null) lodFor(vp.span) else Lod.HOURLY

    // Ordered by colour slot so the legend, the lines and the readout agree, and
    // so checking a device on or off never reshuffles the others.
    val selectedAddresses = remember(state.selected, state.slots) {
        state.selected.sortedBy { state.slots[it] ?: Int.MAX_VALUE }
    }

    // Bucketing every selected device is the expensive step, so it is kept out of
    // the viewport's way entirely: SeriesData caches each zoom level on first use,
    // and this only rebuilds when the devices or the metric change.
    val prepared = remember(selectedAddresses, state.readingsBySensor, state.sensors, state.slots, metric) {
        selectedAddresses.mapNotNull { address ->
            val readings = state.readingsBySensor[address]?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Prepared(
                address = address,
                label = state.displayName(address),
                style = seriesStyle(state.slots[address] ?: 0),
                data = SeriesData(readings, metric)
            )
        }
    }

    // Per frame this is just a binary search into the buckets above.
    val series: List<CompareSeries> = remember(prepared, lod, vp) {
        if (vp == null) emptyList()
        else prepared.map {
            CompareSeries(it.address, it.label, it.style, it.data.visible(lod, vp.startMs, vp.endMs))
        }
    }

    // Remembered rather than passed as `viewModel::toggle` — a bare method
    // reference is a new object every recomposition, which would make the picker
    // rebuild all its chips on every frame of a drag.
    val onToggle = remember(viewModel) { { address: String -> viewModel.toggle(address) } }
    val onAll = remember(viewModel) { { viewModel.selectAll() } }
    val onNone = remember(viewModel) { { viewModel.selectNone() } }

    // The plot itself, plus the empty states that stand in for it. Shared so
    // landscape can hand it the whole screen instead of a slot in a column.
    val chartBody: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            when {
                state.sensors.isEmpty() ->
                    EmptyHint("No sensors yet", "Swipe back and tap + to add one.")
                vp == null && state.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                state.selected.isEmpty() ->
                    EmptyHint("Nothing selected", "Tick devices to plot them together.")
                vp == null ->
                    EmptyHint("No data yet", "Sync a sensor to see its history here.")
                else -> CompareChart(
                    unit = unit,
                    metric = metric,
                    series = series,
                    viewport = vp,
                    scrubberMs = scrubberMs,
                    dataMin = oldest ?: vp.startMs,
                    dataMax = newest ?: vp.endMs,
                    fahrenheit = fahrenheit,
                    onViewportChange = { viewport = it },
                    onScrub = { scrubberMs = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    Scaffold(
        containerColor = ScreenBg,
        topBar = {
            // Landscape floats its controls over the chart instead — see below.
            if (!isLandscape) {
                TopAppBar(
                    title = { Text("Compare") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to sensors")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ScreenBg,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        }
    ) { pad ->
        if (isLandscape) {
            // There isn't the height for a stacked layout here — the chart would be
            // squeezed to nothing — so it takes the whole screen and everything else
            // floats over it, matching the single-sensor screen.
            CompareLandscape(
                pad = pad,
                chartBody = chartBody,
                state = state,
                prepared = prepared,
                metricIndex = metricIndex,
                onMetric = { metricIndex = it },
                zoom = vp?.let { zoomLabel(it.span) },
                range = vp?.let { rangeLabel(it.startMs, it.endMs) },
                scrubberMs = scrubberMs,
                metric = metric,
                unit = unit,
                lod = lod,
                fahrenheit = fahrenheit,
                onToggleDevice = onToggle,
                onAll = onAll,
                onNone = onNone,
                onBack = onBack
            )
            return@Scaffold
        }

        Column(Modifier.padding(pad).fillMaxSize().background(ScreenBg).padding(horizontal = 12.dp)) {

            MetricSwitch(selected = metricIndex, onSelect = { metricIndex = it })

            Spacer(Modifier.height(8.dp))

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

            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardBg
            ) {
                chartBody(Modifier.fillMaxSize().padding(8.dp))
            }

            if (prepared.isNotEmpty() && scrubberMs != null) {
                Spacer(Modifier.height(8.dp))
                ScrubReadout(prepared, scrubberMs!!, metric, unit, lod, fahrenheit)
            }

            Spacer(Modifier.height(8.dp))

            DevicePicker(state = state, onToggle = onToggle, onAll = onAll, onNone = onNone)

            Text(
                "Tap to place the marker · Drag to scroll time · Two fingers: zoom",
                style = MaterialTheme.typography.labelSmall, color = TextLo,
                // Bottom gap keeps the hint clear of the page dots.
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp, bottom = 32.dp)
            )
        }
    }
}

/**
 * Landscape gives the chart the whole screen. There is no height here for the
 * portrait stack — metric switch, chart, readout and picker together leave the
 * plot a few pixels tall — so everything except the plot floats over it, and the
 * device picker hides behind a button until it's wanted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareLandscape(
    pad: PaddingValues,
    chartBody: @Composable (Modifier) -> Unit,
    state: CompareUiState,
    prepared: List<Prepared>,
    metricIndex: Int,
    onMetric: (Int) -> Unit,
    zoom: String?,
    range: String?,
    scrubberMs: Long?,
    metric: Metric,
    unit: String,
    lod: Lod,
    fahrenheit: Boolean,
    onToggleDevice: (String) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onBack: () -> Unit
) {
    var showDevices by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.padding(pad).fillMaxSize().background(ScreenBg)) {
        chartBody(Modifier.fillMaxSize().padding(4.dp))

        // Controls (top-left), clear of any display cutout
        Surface(
            modifier = Modifier.align(Alignment.TopStart).displayCutoutPadding().padding(8.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.45f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to sensors", tint = Color.White)
                }
                listOf("Temperature", "Humidity").forEachIndexed { i, label ->
                    val isSel = metricIndex == i
                    Surface(
                        onClick = { onMetric(i) },
                        shape = RoundedCornerShape(50),
                        color = if (isSel) Color.White.copy(alpha = 0.22f) else Color.Transparent
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = if (isSel) Color.White else Color.White.copy(alpha = 0.6f),
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (i == 0) Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { showDevices = !showDevices }) {
                    Icon(
                        Icons.Default.Tune,
                        if (showDevices) "Hide devices" else "Choose devices",
                        tint = if (showDevices) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                // Zoom and range ride along here rather than in their own corner:
                // the chart draws the marker's timestamp top-right, and a second
                // pill there would sit on top of it.
                if (zoom != null) {
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.Timeline, null, Modifier.size(14.dp), tint = TextHi)
                    Spacer(Modifier.width(4.dp))
                    Text(zoom, style = MaterialTheme.typography.labelSmall, color = TextHi)
                    if (range != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(range, style = MaterialTheme.typography.labelSmall, color = TextLo)
                    }
                    Spacer(Modifier.width(10.dp))
                }
            }
        }

        // Marker readout (bottom-left), narrow so it leaves the plot readable
        if (prepared.isNotEmpty() && scrubberMs != null && !showDevices) {
            ScrubReadout(
                devices = prepared,
                scrubberMs = scrubberMs,
                metric = metric,
                unit = unit,
                lod = lod,
                fahrenheit = fahrenheit,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(8.dp)
                    .widthIn(max = 320.dp)
            )
        }

        // Device picker, revealed by the controls button
        if (showDevices) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(8.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(16.dp),
                // Near-opaque: at 0.85 the lines showed through the chip labels.
                color = Color(0xFF0E0F12).copy(alpha = 0.95f)
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DevicePicker(
                        state = state,
                        onToggle = onToggleDevice,
                        onAll = onAll,
                        onNone = onNone,
                        maxChipHeight = 96.dp
                    )
                }
            }
        }
    }
}

/** Temperature / Humidity. They never share an axis, so this switches the chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("Temperature", "Humidity").forEachIndexed { i, label ->
            val isSel = selected == i
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                color = if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else CardBg
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = if (isSel) TextHi else TextLo,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Every device's value at the marker, biggest first, with the spread across them —
 * the number that answers "how much colder was it outside?". Zoomed out to whole
 * days or months, each row also carries that bucket's min–max, since the line
 * itself is only the average.
 */
@Composable
private fun ScrubReadout(
    devices: List<Prepared>,
    scrubberMs: Long,
    metric: Metric,
    unit: String,
    lod: Lod,
    fahrenheit: Boolean,
    modifier: Modifier = Modifier
) {
    // Looked up against each device's whole history rather than the slice on
    // screen, so the reading stays put when the chart is scrolled away from the
    // marker — it only changes when a new point is tapped.
    //
    // Every selected device keeps a row, whether or not it recorded anything at
    // the marker, so dragging across the start of one device's history doesn't
    // make the rows under it jump. A device without data there reads "no data"
    // rather than borrowing the nearest value it happens to have.
    val rows = remember(devices, scrubberMs, lod) {
        devices
            .map { d -> ReadoutRow(d.label, d.style, d.data.bucketAt(scrubberMs, lod)) }
            .rankedForReadout()
    }
    if (rows.isEmpty()) return

    fun show(v: Float) = if (metric == Metric.TEMP && fahrenheit) v * 9f / 5f + 32f else v

    val labels = rememberTimeLabels()
    val measured = rows.mapNotNull { it.bucket }
    val anchor = measured.minByOrNull { abs(it.tMs - scrubberMs) }
    val spread = if (measured.size > 1) measured.maxOf { it.mid } - measured.minOf { it.mid } else null

    Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The reading the marker snapped to, so this agrees with the
                // timestamp drawn on the chart rather than with the raw finger
                // position between two readings.
                Text(
                    labels.tooltip(lod, anchor?.bucketStart ?: scrubberMs),
                    style = MaterialTheme.typography.labelMedium, color = TextLo
                )
                // Only meaningful across devices that actually measured something here.
                if (spread != null) {
                    Text(
                        "spread %.1f%s".format(
                            show(measured.maxOf { it.mid }) - show(measured.minOf { it.mid }), unit),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (abs(spread) >= 1f) MaterialTheme.colorScheme.primary else TextLo
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Capped so a long device list can't squeeze the chart off the screen.
            Column(Modifier.heightIn(max = 152.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    val bucket = row.bucket
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // A dimmed swatch and label mark a device that was simply
                        // not recording here, matching how its chip reads.
                        SeriesSwatch(row.style, selected = bucket != null)
                        Spacer(Modifier.width(8.dp))
                        Text(row.label, style = MaterialTheme.typography.bodySmall,
                            color = if (bucket != null) TextHi else TextLo,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (bucket == null) {
                            Text("no data", style = MaterialTheme.typography.labelMedium, color = TextLo)
                        } else {
                            if (lod != Lod.HOURLY) {
                                Text(
                                    "%.1f–%.1f".format(show(bucket.lo), show(bucket.hi)),
                                    style = MaterialTheme.typography.labelSmall, color = TextLo
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("%.1f%s".format(show(bucket.mid), unit),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, color = TextHi)
                        }
                    }
                }
            }
        }
    }
}

/** The legend and the filter in one control: tick a device to plot it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePicker(
    state: CompareUiState,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    maxChipHeight: Dp = 132.dp
) {
    if (state.sensors.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${state.selected.size} of ${state.sensors.size} shown",
                style = MaterialTheme.typography.labelMedium, color = TextLo)
            Row {
                TextButton(onClick = onAll, enabled = state.selected.size < state.sensors.size) {
                    Text("All")
                }
                TextButton(onClick = onNone, enabled = state.selected.isNotEmpty()) { Text("None") }
            }
        }
        // Wraps rather than scrolls sideways, so it never fights the page swipe.
        FlowRow(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxChipHeight).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.sensors.forEach { sensor ->
                DeviceChip(
                    label = sensor.alias ?: sensor.name,
                    style = seriesStyle(state.slots[sensor.address] ?: 0),
                    selected = sensor.address in state.selected,
                    hasData = state.readingsBySensor[sensor.address]?.isNotEmpty() != false,
                    onClick = { onToggle(sensor.address) }
                )
            }
        }
    }
}

@Composable
private fun DeviceChip(
    label: String,
    style: SeriesStyle,
    selected: Boolean,
    hasData: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) style.color.copy(alpha = 0.18f) else CardBg,
        modifier = Modifier
            .border(1.dp, if (selected) style.color.copy(alpha = 0.7f) else Color(0xFF33363B),
                RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SeriesSwatch(style, selected)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) TextHi else TextLo,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = style.color)
            }
            if (!hasData) {
                Spacer(Modifier.width(6.dp))
                Text("no data", style = MaterialTheme.typography.labelSmall, color = TextLo)
            }
        }
    }
}

/**
 * A short piece of the device's actual line — same colour and, past the eighth
 * device, the same dash pattern, so the legend matches what's drawn.
 */
@Composable
private fun SeriesSwatch(style: SeriesStyle, selected: Boolean) {
    val effect = style.pathEffect
    Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
        drawLine(
            color = if (selected) style.color else style.color.copy(alpha = 0.45f),
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 3.5f * density,
            cap = StrokeCap.Round,
            pathEffect = effect
        )
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Default.Timeline, null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi)
            Text(body, style = MaterialTheme.typography.bodySmall, color = TextLo)
        }
    }
}

private fun rangeLabel(startMs: Long, endMs: Long): String {
    val span = endMs - startMs
    val fmt = if (span <= 2 * DAY) SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
              else SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return "${fmt.format(Date(startMs))} – ${fmt.format(Date(endMs))}"
}


