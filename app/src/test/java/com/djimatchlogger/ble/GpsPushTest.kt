package com.djimatchlogger.ble

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The GPS push is fire-and-forget: no acknowledgement, and no way to read the
 * camera's clock back. A wrong layout is therefore invisible at runtime, which
 * makes pinning the bytes down here the only check there is.
 *
 * Expected bytes were packed independently rather than read back through
 * [CameraCommands], so this tests the layout and not just self-consistency.
 */
class GpsPushTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    private val amsterdam = ZonedDateTime.of(
        2026, 9, 6, 10, 30, 0, 0, CameraCommands.CAMERA_ZONE,
    )

    @Test
    fun `packs date, time, position and accuracy little-endian`() {
        val payload = CameraCommands.gpsAndTime(
            utcPlus8 = amsterdam,
            latitudeDegrees = 52.3702,
            longitudeDegrees = 4.8952,
            heightMetres = 12.5,
            horizontalAccuracyMetres = 7.5f,
            satelliteNumber = 6,
        )

        assertEquals(45, payload.size)
        assertEquals("2A 28 35 01 58 92 01 00 C0 F2 EA 02 EF 0E 37 1F D4 30 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 F0 40 00 00 00 00 06", payload.hex())
    }

    @Test
    fun `date and time are packed as YYYYMMDD and HHMMSS decimals`() {
        val payload = CameraCommands.gpsAndTime(amsterdam)
        fun int(at: Int) = (payload[at].toInt() and 0xFF) or
            ((payload[at + 1].toInt() and 0xFF) shl 8) or
            ((payload[at + 2].toInt() and 0xFF) shl 16) or
            ((payload[at + 3].toInt() and 0xFF) shl 24)

        assertEquals(20260906, int(0))
        assertEquals(103000, int(4))
    }

    /**
     * Without a fix the satellite count stays zero. Inventing one would put a
     * coordinate the phone never measured into the recorded footage.
     */
    @Test
    fun `reports no satellites when there is no position`() {
        val payload = CameraCommands.gpsAndTime(amsterdam)
        assertEquals(0, payload[44].toInt())
    }

    /** The struct fixes the timezone; the camera reads the value as UTC+8. */
    @Test
    fun `camera zone is UTC plus eight`() {
        assertEquals(8 * 3600, CameraCommands.CAMERA_ZONE.totalSeconds)
    }
}
