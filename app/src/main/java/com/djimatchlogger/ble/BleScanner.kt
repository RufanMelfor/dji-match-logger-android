package com.djimatchlogger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A scan that Android accepted but that cannot produce results. */
class BleScanFailedException(val errorCode: Int) :
    IllegalStateException("BLE scan failed with error code $errorCode")

/**
 * Scans for DJI cameras.
 *
 * Deliberately scans **unfiltered** and matches in Kotlin rather than handing
 * a manufacturer-data [android.bluetooth.le.ScanFilter] to the platform. A
 * wrong filter fails the same way a wrong offset does — an empty list and no
 * error — and doing the matching here keeps that debuggable. Revisit if
 * battery cost during a match ever becomes noticeable.
 */
class BleScanner(private val context: Context) {

    /**
     * Emits every DJI camera seen, repeatedly, as long as it is collected.
     * The same camera arrives many times with a fresh RSSI; de-duplicating is
     * the caller's job.
     *
     * Check [BleEnvironment.blocker] first — this throws
     * [BleScanFailedException] on a rejected scan, but an unmet precondition
     * such as location services being off produces silence, not an error.
     */
    @SuppressLint("MissingPermission") // guarded by BleEnvironment.blocker()
    fun cameras(): Flow<DjiCamera> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bluetoothLeScanner
            ?: throw IllegalStateException("No BLE scanner — is Bluetooth on?")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toDjiCameraOrNull()?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> result.toDjiCameraOrNull()?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleScanFailedException(errorCode))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(emptyList(), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }
}

/**
 * Reads the name from the scan record rather than from `device.name`: the
 * latter needs BLUETOOTH_CONNECT on API 31+, which scanning otherwise does
 * not require.
 */
private fun ScanResult.toDjiCameraOrNull(): DjiCamera? {
    val record = scanRecord ?: return null
    val payload = record.manufacturerSpecificData?.get(DjiAdvertisement.COMPANY_ID) ?: return null
    val deviceId = DjiAdvertisement.deviceIdOrNull(payload) ?: return null
    return DjiCamera(
        address = device.address,
        deviceId = deviceId,
        advertisedName = record.deviceName,
        rssi = rssi,
    )
}
