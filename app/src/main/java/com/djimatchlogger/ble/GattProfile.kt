package com.djimatchlogger.ble

import java.util.UUID

/**
 * The GATT layer from `docs/ble-protocol.md`.
 *
 * The document lists 16-bit UUIDs; Bluetooth expands those into the 128-bit
 * base UUID, which is the only form Android's API accepts.
 */
object GattProfile {

    /** Remote-control service (R SDK / DUML), not the WiFi pairing one. */
    val SERVICE: UUID = uuid16(0xFFF0)

    /** Commands go to the camera here. */
    val WRITE_CHARACTERISTIC: UUID = uuid16(0xFFF5)

    /** Status and answers come back from the camera here. */
    val NOTIFY_CHARACTERISTIC: UUID = uuid16(0xFFF4)

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = uuid16(0x2902)

    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))
}
