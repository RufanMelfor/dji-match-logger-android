package com.djimatchlogger.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Everything that has to be true before a scan can find anything.
 *
 * The two branches here mirror the API 31 permission split documented in
 * `AndroidManifest.xml`. They are not interchangeable: below API 31 scanning
 * is a location-grade operation and needs both the runtime permission and the
 * device's location services switched on.
 */
class BleEnvironment(private val context: Context) {

    /**
     * Runtime permissions this Android version needs to scan and connect.
     *
     * Below API 31 there is nothing to ask for beyond location: BLUETOOTH and
     * BLUETOOTH_ADMIN are install-time permissions there, granted the moment
     * the app is installed.
     */
    val requiredPermissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val missingPermissions: List<String>
        get() = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    private val isBluetoothEnabled: Boolean
        get() = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.isEnabled == true

    /**
     * Below API 31 a scan with location services off succeeds and then stays
     * empty forever, so treat it as a blocker rather than letting the user
     * stare at an empty list.
     */
    private val isLocationRequirementMet: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            true
        } else {
            context.getSystemService(LocationManager::class.java)
                ?.let { LocationManagerCompat.isLocationEnabled(it) } == true
        }

    /** The first thing standing in the way of a scan, or null if nothing is. */
    fun blocker(): ScanBlocker? {
        val missing = missingPermissions
        return when {
            missing.isNotEmpty() -> ScanBlocker.MissingPermissions(missing)
            !isBluetoothEnabled -> ScanBlocker.BluetoothDisabled
            !isLocationRequirementMet -> ScanBlocker.LocationServicesDisabled
            else -> null
        }
    }
}
