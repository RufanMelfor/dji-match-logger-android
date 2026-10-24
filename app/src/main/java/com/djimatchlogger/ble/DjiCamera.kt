package com.djimatchlogger.ble

/**
 * A DJI camera as seen in a scan. [advertisedName] is whatever the camera put
 * in its Local Name AD field; it is null often enough that callers must have a
 * fallback ready.
 */
data class DjiCamera(
    val address: String,
    val deviceId: Int,
    val advertisedName: String?,
    val rssi: Int,
)
