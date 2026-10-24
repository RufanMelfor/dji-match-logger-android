package com.djimatchlogger.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden frame was produced by replaying `protocol_create_frame` from the
 * firmware's `dji_protocol_parser.c` over the same inputs, so this pins the
 * whole layout at once: field order, the 10-bit length packing, both
 * checksums and their coverage, and the little-endian byte order throughout.
 */
class DjiFrameTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    /** Record control, start: CmdSet 0x1D, CmdID 0x03, device id 0x14. */
    private val recordStartPayload = byteArrayOf(
        0x14, 0x00, 0x00, 0x00, // device_id, u32 little-endian
        0x00, // record_ctrl: 0 starts, 1 stops
        0x00, 0x00, 0x00, 0x00, // reserved[4]
    )

    @Test
    fun `encodes a record start frame exactly as the firmware does`() {
        val frame = DjiFrame.encode(
            cmdSet = 0x1D,
            cmdId = 0x03,
            cmdType = CommandType.NO_RESPONSE,
            seq = 1,
            payload = recordStartPayload,
        )

        assertEquals(
            "AA 1B 00 00 00 00 00 00 01 00 45 EE 1D 03 " +
                "14 00 00 00 00 00 00 00 00 E5 57 F9 C3",
            frame.hex(),
        )
    }

    @Test
    fun `total length lands in the low ten bits little-endian`() {
        val frame = DjiFrame.encode(0x1D, 0x03, CommandType.NO_RESPONSE, 1, recordStartPayload)
        val verLength = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)

        assertEquals(frame.size, verLength and 0x03FF)
        assertEquals(0, verLength ushr 10) // version
    }

    @Test
    fun `crc16 covers the first ten bytes only`() {
        val frame = DjiFrame.encode(0x1D, 0x03, CommandType.NO_RESPONSE, 1, recordStartPayload)
        val stored = (frame[10].toInt() and 0xFF) or ((frame[11].toInt() and 0xFF) shl 8)

        assertEquals(DjiCrc.crc16(frame, DjiFrame.CRC16_COVERAGE), stored)
    }

    @Test
    fun `crc32 covers everything ahead of itself`() {
        val frame = DjiFrame.encode(0x1D, 0x03, CommandType.NO_RESPONSE, 1, recordStartPayload)
        val body = frame.size - DjiFrame.TAIL_LENGTH
        val stored = (frame[body].toInt() and 0xFF) or
            ((frame[body + 1].toInt() and 0xFF) shl 8) or
            ((frame[body + 2].toInt() and 0xFF) shl 16) or
            ((frame[body + 3].toInt() and 0xFF) shl 24)

        assertEquals(DjiCrc.crc32(frame, body), stored)
    }

    @Test
    fun `sequence number is little-endian`() {
        val frame = DjiFrame.encode(0x1D, 0x03, CommandType.NO_RESPONSE, 0xBEEF, recordStartPayload)
        assertEquals(0xEF, frame[8].toInt() and 0xFF)
        assertEquals(0xBE, frame[9].toInt() and 0xFF)
    }

    @Test
    fun `an empty payload still produces a valid frame`() {
        val frame = DjiFrame.encode(0x1D, 0x03, CommandType.NO_RESPONSE, 1)
        assertEquals(DjiFrame.HEADER_LENGTH + DjiFrame.TAIL_LENGTH, frame.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses a frame the length field cannot describe`() {
        DjiFrame.encode(0x1D, 0x03, CommandType.NO_RESPONSE, 1, ByteArray(DjiFrame.MAX_FRAME_LENGTH))
    }
}
