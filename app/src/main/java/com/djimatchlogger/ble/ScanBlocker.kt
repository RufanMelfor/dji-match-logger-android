package com.djimatchlogger.ble

/** Something that stops a scan from returning results. */
sealed interface ScanBlocker {

    /** The user has not granted what this Android version needs to scan. */
    data class MissingPermissions(val permissions: List<String>) : ScanBlocker

    data object BluetoothDisabled : ScanBlocker

    /**
     * Only reachable below API 31. Android tied BLE scanning to location back
     * then, and with location services off a scan reports success and then
     * returns nothing at all — no error, no results.
     */
    data object LocationServicesDisabled : ScanBlocker
}
