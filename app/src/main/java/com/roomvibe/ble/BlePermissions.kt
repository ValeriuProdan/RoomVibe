package com.roomvibe.ble

import android.Manifest
import android.os.Build

/**
 * The runtime permissions this app needs for BLE, split by what they're for.
 * Pure functions of the API level so they can be unit-tested without a device.
 */

/** Permissions required to *scan* for sensors at the given API level. */
fun blePermissionsFor(sdkInt: Int): Array<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * Permissions required to *connect* (sync) to a sensor at the given API level.
 * Pre-12 uses the normal, install-time BLUETOOTH permission, so nothing is needed at runtime.
 */
fun connectPermissionsFor(sdkInt: Int): Array<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }
