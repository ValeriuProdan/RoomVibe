package com.thermolog.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

class GattConnection(
    private val context: Context,
    private val device: BluetoothDevice
) {
    private var gatt: BluetoothGatt? = null

    // One-shot result channels (capacity 1 so we never block a callback)
    private val connectedChannel = Channel<Boolean>(1)
    private val servicesChannel = Channel<Boolean>(1)
    private val readChannel = Channel<Pair<UUID, ByteArray>>(1)
    private val writeChannel = Channel<UUID>(1)
    private val descWriteChannel = Channel<UUID>(1)

    // Unlimited capacity so fast-arriving history notifications aren't dropped
    private val notifyChannel = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)

    val notificationsFlow: Flow<Pair<UUID, ByteArray>> = notifyChannel.receiveAsFlow()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedChannel.trySend(true)
            } else {
                connectedChannel.trySend(false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesChannel.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS)
                readChannel.trySend(characteristic.uuid to value)
        }

        // Pre-API-33 fallback
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS)
                readChannel.trySend(characteristic.uuid to characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeChannel.trySend(characteristic.uuid)
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            notifyChannel.trySend(characteristic.uuid to value)
        }

        // Pre-API-33 fallback
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                notifyChannel.trySend(characteristic.uuid to characteristic.value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descWriteChannel.trySend(descriptor.characteristic.uuid)
        }
    }

    /** Connect and discover services. Returns true on success. */
    suspend fun connect(): Boolean {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val connected = withTimeoutOrNull(15_000L) { connectedChannel.receive() } ?: false
        if (!connected) return false
        gatt?.discoverServices()
        return withTimeoutOrNull(10_000L) { servicesChannel.receive() } ?: false
    }

    /** Read a characteristic value. Returns null on timeout or error. */
    suspend fun read(serviceUuid: UUID, charUuid: UUID): ByteArray? {
        val char = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return null
        gatt?.readCharacteristic(char)
        return withTimeoutOrNull(5_000L) { readChannel.receive() }?.second
    }

    /** Write bytes to a characteristic. Returns true on success. */
    suspend fun write(serviceUuid: UUID, charUuid: UUID, value: ByteArray): Boolean {
        val char = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(char, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(char)
        }
        return withTimeoutOrNull(5_000L) { writeChannel.receive() } != null
    }

    /** Enable BLE notifications on a characteristic. */
    suspend fun enableNotify(serviceUuid: UUID, charUuid: UUID): Boolean {
        val char = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        gatt?.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(LywsdProtocol.CCCD) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(descriptor)
        }
        return withTimeoutOrNull(5_000L) { descWriteChannel.receive() } != null
    }

    /** Return all discovered services and their characteristics (for the GATT Explorer). */
    fun discoverAll(): List<Pair<String, List<String>>> =
        gatt?.services?.map { svc ->
            svc.uuid.toString() to svc.characteristics.map { ch ->
                "${ch.uuid}  [${propsToString(ch.properties)}]"
            }
        } ?: emptyList()

    private fun propsToString(props: Int): String {
        val parts = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return parts.joinToString(",").ifEmpty { "—" }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
