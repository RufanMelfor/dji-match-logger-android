package com.djimatchlogger.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The offsets here are the ones that go wrong quietly: a mismatch produces a
 * scan that finds nothing rather than an error, so pin them down in a test.
 *
 * Payloads are written as Android reports them — company ID already removed —
 * so index 0 is byte 2 of the advertisement as `docs/ble-protocol.md` counts.
 */
class DjiAdvertisementTest {

    private fun payload(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `company id is doc bytes 0xAA 0x08 read little-endian`() {
        assertEquals(0x08AA, DjiAdvertisement.COMPANY_ID)
    }

    @Test
    fun `reads the device id little-endian from doc bytes 2-3`() {
        // doc bytes: AA 08 | 34 12 | FA  ->  device id 0x1234
        assertEquals(0x1234, DjiAdvertisement.deviceIdOrNull(payload(0x34, 0x12, 0xFA)))
    }

    @Test
    fun `keeps the high bit of each device id byte`() {
        assertEquals(0xFFFE, DjiAdvertisement.deviceIdOrNull(payload(0xFE, 0xFF, 0xFA)))
    }

    @Test
    fun `rejects advertisements without the 0xFA marker`() {
        assertNull(DjiAdvertisement.deviceIdOrNull(payload(0x34, 0x12, 0xFB)))
    }

    @Test
    fun `rejects payloads too short to hold the marker`() {
        assertNull(DjiAdvertisement.deviceIdOrNull(payload(0x34, 0x12)))
        assertNull(DjiAdvertisement.deviceIdOrNull(ByteArray(0)))
    }

    @Test
    fun `ignores anything after the marker`() {
        assertEquals(0x1234, DjiAdvertisement.deviceIdOrNull(payload(0x34, 0x12, 0xFA, 0x01, 0x02)))
    }
}
