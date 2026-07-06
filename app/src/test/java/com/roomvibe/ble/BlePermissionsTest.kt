package com.roomvibe.ble

import android.Manifest
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BlePermissionsTest {

    // ── scanning ──────────────────────────────────────────────────────────────

    @Test fun scan_below12_usesFineLocation() {
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            blePermissionsFor(Build.VERSION_CODES.R) // API 30
        )
        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            blePermissionsFor(Build.VERSION_CODES.O) // API 26 (minSdk)
        )
    }

    @Test fun scan_12AndAbove_usesNearbyDevices() {
        val expected = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        assertArrayEquals(expected, blePermissionsFor(Build.VERSION_CODES.S))        // API 31
        assertArrayEquals(expected, blePermissionsFor(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) // API 34
    }

    // ── connecting (sync) ─────────────────────────────────────────────────────

    @Test fun connect_below12_needsNoRuntimePermission() {
        assertArrayEquals(emptyArray<String>(), connectPermissionsFor(Build.VERSION_CODES.R))
        assertArrayEquals(emptyArray<String>(), connectPermissionsFor(Build.VERSION_CODES.O))
    }

    @Test fun connect_12AndAbove_needsBluetoothConnect() {
        val expected = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        assertArrayEquals(expected, connectPermissionsFor(Build.VERSION_CODES.S))
        assertArrayEquals(expected, connectPermissionsFor(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    @Test fun connect_neverRequestsScanOrLocation() {
        // Sync must not drag in scan/location permissions on any API level.
        for (sdk in intArrayOf(26, 30, 31, 34, 35)) {
            val perms = connectPermissionsFor(sdk)
            assert(perms.none { it == Manifest.permission.BLUETOOTH_SCAN }) { "sdk=$sdk" }
            assert(perms.none { it == Manifest.permission.ACCESS_FINE_LOCATION }) { "sdk=$sdk" }
        }
    }
}
