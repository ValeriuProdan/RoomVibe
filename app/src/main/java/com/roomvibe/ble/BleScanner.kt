package com.roomvibe.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    /** true if this looks like a Xiaomi temperature/humidity sensor */
    val isLikelySensor: Boolean
)

class BleScanner(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun scan(timeoutMs: Long = 15_000L): Flow<FoundDevice> = callbackFlow {
        if (!adapter.isEnabled) {
            close(IllegalStateException("Bluetooth is disabled"))
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            close(IllegalStateException("BLE scanner unavailable"))
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                // Name may live in the advertisement even when device.name is null
                val name = result.device.name ?: record?.deviceName

                val serviceUuids: List<ParcelUuid> = record?.serviceUuids ?: emptyList()
                val serviceDataKeys: Set<ParcelUuid> = record?.serviceData?.keys ?: emptySet()

                val nameMatches = name != null &&
                    LywsdProtocol.KNOWN_NAME_PREFIXES.any { name.startsWith(it) }
                val advertisesXiaomi =
                    serviceDataKeys.contains(XIAOMI_FE95) ||
                    serviceUuids.contains(XIAOMI_FE95) ||
                    serviceUuids.any { it.uuid == LywsdProtocol.SERVICE }

                val likely = nameMatches || advertisesXiaomi

                if (seen.add(result.device.address)) {
                    trySend(
                        FoundDevice(
                            address = result.device.address,
                            name = name ?: "(unnamed device)",
                            rssi = result.rssi,
                            isLikelySensor = likely
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(RuntimeException("BLE scan failed with code $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No ScanFilter: report everything, classify in the callback. Filtering by
        // name fails because these sensors usually advertise no name.
        scanner.startScan(null, settings, callback)

        delay(timeoutMs)
        try { scanner.stopScan(callback) } catch (_: Exception) {}
        close()

        awaitClose {
            try { scanner.stopScan(callback) } catch (_: Exception) {}
        }
    }

    companion object {
        /** Xiaomi MiBeacon service UUID, advertised by LYWSD03MMC & friends */
        val XIAOMI_FE95: ParcelUuid = ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    }
}
