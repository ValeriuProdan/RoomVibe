package com.thermolog.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.thermolog.ble.BleScanner
import com.thermolog.ble.FoundDevice
import com.thermolog.ble.GattConnection
import com.thermolog.ble.LywsdProtocol
import com.thermolog.data.entity.Reading
import com.thermolog.data.entity.Sensor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "SensorRepository"

// Cap how many history records to pull per sync. The device only retains ~3700
// (a few months); this is a safety ceiling. Each sync resumes where the last stopped.
private const val MAX_HISTORY_PER_SYNC = 10_000

sealed class SyncState {
    data object Idle : SyncState()
    data object Connecting : SyncState()
    data class Progress(val step: String) : SyncState()
    data class Done(val newReadings: Int) : SyncState()
    data class Error(val message: String) : SyncState()
}

class SensorRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val sensorDao = db.sensorDao()
    private val readingDao = db.readingDao()
    private val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ── Sensor CRUD ──────────────────────────────────────────────────────────

    fun sensorsFlow(): Flow<List<Sensor>> = sensorDao.getAllFlow()

    suspend fun addSensor(device: FoundDevice) {
        sensorDao.upsert(Sensor(address = device.address, name = device.name))
    }

    suspend fun removeSensor(sensor: Sensor) = sensorDao.delete(sensor)

    suspend fun renameSensor(address: String, alias: String?) =
        sensorDao.updateAlias(address, alias?.takeIf { it.isNotBlank() })

    // ── Readings ─────────────────────────────────────────────────────────────

    fun readingsInRange(address: String, fromMs: Long, toMs: Long): Flow<List<Reading>> =
        readingDao.getReadingsInRange(address, fromMs, toMs)

    fun allReadings(address: String): Flow<List<Reading>> =
        readingDao.getAllForSensor(address)

    suspend fun oldestReadingMs(address: String): Long? = readingDao.getOldestTimestampMs(address)
    suspend fun newestReadingMs(address: String): Long? = readingDao.getNewestTimestampMs(address)
    suspend fun readingCount(address: String): Int = readingDao.getCount(address)

    // ── Backup / restore ──────────────────────────────────────────────────────

    data class RestoreStats(val sensorsAdded: Int, val readingsAdded: Int, val readingsTotal: Int)

    /** Serialize all sensors + readings and write them to the picked document. */
    suspend fun backupTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val sensors = sensorDao.getAllOnce()
        val readings = readingDao.getAllOnce()
        val json = BackupSerializer.toJson(sensors, readings)
        context.contentResolver.openOutputStream(uri)
            ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Could not open the selected file for writing")
        readings.size
    }

    /** Read a backup document and merge it into the database (no existing data is lost). */
    suspend fun restoreFrom(uri: Uri): RestoreStats = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Could not open the selected file for reading")

        val parsed = BackupSerializer.fromJson(json)
        // Insert sensors that don't exist yet (keeps current aliases/sync state).
        val sensorsAdded = sensorDao.insertIfAbsent(parsed.sensors).count { it >= 0 }
        // Readings de-dup on the (sensorAddress, timestampMs) unique index.
        val readingsAdded = parsed.readings
            .chunked(2000)
            .sumOf { chunk -> readingDao.insertAll(chunk).count { it > 0 } }
        RestoreStats(sensorsAdded, readingsAdded, parsed.readings.size)
    }

    // ── BLE scan ─────────────────────────────────────────────────────────────

    fun scanForDevices(timeoutMs: Long = 15_000L): Flow<FoundDevice> =
        BleScanner(context).scan(timeoutMs)

    /** Briefly connect and read the live temperature (°C), e.g. to identify a sensor. */
    suspend fun readCurrentTemp(address: String): Float? {
        val device = btAdapter.getRemoteDevice(address)
        val conn = GattConnection(context, device)
        return try {
            if (!conn.connect()) return null
            conn.enableNotify(LywsdProtocol.SERVICE, LywsdProtocol.DATA_CHAR)
            val bytes = withTimeoutOrNull(4_000L) {
                conn.notificationsFlow.filter { it.first == LywsdProtocol.DATA_CHAR }.first()
            }?.second
            bytes?.let { LywsdProtocol.parseRealtimeData(it)?.temperatureCelsius }
        } catch (e: Exception) {
            Log.w(TAG, "readCurrentTemp failed for $address", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── Sync a sensor ────────────────────────────────────────────────────────

    fun syncSensor(address: String): Flow<SyncState> = flow {
        val bleDevice = btAdapter.getRemoteDevice(address)
        val conn = GattConnection(context, bleDevice)

        emit(SyncState.Connecting)
        val connected = conn.connect()
        if (!connected) {
            emit(SyncState.Error("Could not connect to device. Make sure Bluetooth is on and you are near the sensor."))
            return@flow
        }

        val records = mutableListOf<Reading>()

        // 1. Current (live) reading via DATA notify
        emit(SyncState.Progress("Reading current data…"))
        conn.enableNotify(LywsdProtocol.SERVICE, LywsdProtocol.DATA_CHAR)
        val realtimeBytes = withTimeoutOrNull(5_000L) {
            conn.notificationsFlow.filter { it.first == LywsdProtocol.DATA_CHAR }.first()
        }?.second
        LywsdProtocol.parseRealtimeData(realtimeBytes ?: ByteArray(0))?.let { live ->
            val now = System.currentTimeMillis()
            records.add(
                Reading(
                    sensorAddress = address,
                    timestampMs = now,
                    temperatureCelsius = live.temperatureCelsius,
                    humidityPercent = live.humidityPercent,
                    tempMinC = live.temperatureCelsius,
                    tempMaxC = live.temperatureCelsius,
                    humMin = live.humidityPercent,
                    humMax = live.humidityPercent
                )
            )
        }

        // 2a. Read the device clock so we can map device-epoch timestamps to real time.
        //     The device runs on its own epoch (~1973); records share it, so a single
        //     constant offset corrects every record.
        val deviceTimeBytes = conn.read(LywsdProtocol.SERVICE, LywsdProtocol.TIME_CHAR)
        val deviceNowSec = LywsdProtocol.parseDeviceTimeSec(deviceTimeBytes)
        val realNowSec = System.currentTimeMillis() / 1000L
        val offsetSec = if (deviceNowSec != null && deviceNowSec > 0) realNowSec - deviceNowSec else 0L

        // 2b. Ring-buffer bookkeeping: lifetimeCounter = next index, storedCount = retained.
        emit(SyncState.Progress("Checking stored history…"))
        val numRecBytes = conn.read(LywsdProtocol.SERVICE, LywsdProtocol.NUM_RECORDS_CHAR)
        val lifetimeCounter = LywsdProtocol.parseLifetimeCounter(numRecBytes)
        val storedCount = LywsdProtocol.parseStoredCount(numRecBytes)
        val newestIndex = lifetimeCounter - 1
        val oldestAvailable = (lifetimeCounter - storedCount).coerceAtLeast(0)

        val sensor = sensorDao.getByAddress(address)
        val resumeFrom = ((sensor?.lastHistoryIndex ?: -1) + 1).coerceAtLeast(0)
        val startIdx = maxOf(resumeFrom, oldestAvailable)
        val expected = (newestIndex - startIdx + 1).coerceAtLeast(0).coerceAtMost(MAX_HISTORY_PER_SYNC)

        var maxIndexSeen = sensor?.lastHistoryIndex ?: -1

        // 3. Stream history records from startIdx. Records arrive as notifications;
        //    a ~5s gap with no record means the stream has finished.
        if (expected > 0) {
            emit(SyncState.Progress("Downloading history… 0 / $expected"))

            conn.enableNotify(LywsdProtocol.SERVICE, LywsdProtocol.HISTORY_CHAR)
            // Writing the start index triggers the device to stream from there
            conn.write(LywsdProtocol.SERVICE, LywsdProtocol.RECORDS_IDX_CHAR, LywsdProtocol.uint32le(startIdx))

            var historyCount = 0
            withTimeoutOrNull(180_000L) {
                while (true) {
                    val bytes = withTimeoutOrNull(5_000L) {
                        conn.notificationsFlow
                            .filter { it.first == LywsdProtocol.HISTORY_CHAR }
                            .first()
                    }?.second ?: break   // idle → stream finished

                    val rec = LywsdProtocol.parseHistoryRecord(bytes) ?: continue
                    val realMs = (rec.deviceTsSec + offsetSec) * 1000L
                    if (rec.index > maxIndexSeen) maxIndexSeen = rec.index.toInt()
                    records.add(
                        Reading(
                            sensorAddress = address,
                            timestampMs = realMs,
                            temperatureCelsius = (rec.tempMinC + rec.tempMaxC) / 2f,
                            humidityPercent = (rec.humMin + rec.humMax) / 2,
                            tempMinC = rec.tempMinC,
                            tempMaxC = rec.tempMaxC,
                            humMin = rec.humMin,
                            humMax = rec.humMax
                        )
                    )
                    historyCount++
                    if (historyCount % 25 == 0) {
                        emit(SyncState.Progress("Downloading history… $historyCount / $expected"))
                    }
                    if (historyCount >= MAX_HISTORY_PER_SYNC) break   // TESTING cap
                }
            }
        }

        conn.disconnect()

        // 4. Persist
        val historyInserted = readingDao.insertAll(records).count { it > 0 }
        if (maxIndexSeen >= 0) sensorDao.updateLastHistoryIndex(address, maxIndexSeen)
        sensorDao.updateLastSync(address, System.currentTimeMillis())

        if (records.isEmpty()) {
            emit(SyncState.Error("Connected but received no data. Move closer to the sensor and try again."))
        } else {
            emit(SyncState.Done(historyInserted))
        }
    }.catch { e ->
        Log.e(TAG, "Sync error", e)
        emit(SyncState.Error(e.message ?: "Unknown error"))
    }

    // ── GATT Explorer (debug) ─────────────────────────────────────────────────

    suspend fun exploreGatt(address: String): List<Pair<String, List<String>>> {
        val bleDevice = btAdapter.getRemoteDevice(address)
        val conn = GattConnection(context, bleDevice)
        return try {
            if (conn.connect()) conn.discoverAll() else emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
