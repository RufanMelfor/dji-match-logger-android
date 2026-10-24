package com.djimatchlogger.ble

/**
 * Recognises DJI cameras in a BLE advertisement.
 *
 * `docs/ble-protocol.md` numbers the manufacturer-data bytes from the start of
 * the AD payload:
 *
 * ```
 * byte 0 = 0xAA, byte 1 = 0x08, byte 4 = 0xFA  -> this is a DJI camera
 * bytes 2-3                                    -> device id, little-endian u16
 * ```
 *
 * Android does not hand that payload over whole. It reads the first two bytes
 * as a little-endian company ID and keys the manufacturer data by it, so doc
 * bytes 0-1 arrive as [COMPANY_ID] and doc byte 2 is index 0 of what is left.
 * Every offset below is therefore two lower than the document's numbering —
 * getting this wrong yields a scan that silently matches nothing.
 */
object DjiAdvertisement {

    /** Doc bytes 0-1 (`0xAA`, `0x08`) read back as a little-endian company ID. */
    const val COMPANY_ID: Int = 0x08AA

    /** Doc byte 4, once Android has taken the two company-ID bytes off. */
    private const val MAGIC_INDEX = 2
    private const val MAGIC: Byte = 0xFA.toByte()

    /** Doc bytes 2-3. */
    private const val DEVICE_ID_LOW = 0
    private const val DEVICE_ID_HIGH = 1

    /**
     * Returns the camera's device id, or `null` if [payload] is not a DJI
     * camera advertisement. [payload] is the manufacturer data as Android
     * reports it, i.e. already stripped of the company ID.
     */
    fun deviceIdOrNull(payload: ByteArray): Int? {
        if (payload.size <= MAGIC_INDEX) return null
        if (payload[MAGIC_INDEX] != MAGIC) return null
        return (payload[DEVICE_ID_LOW].toInt() and 0xFF) or
            ((payload[DEVICE_ID_HIGH].toInt() and 0xFF) shl 8)
    }
}
