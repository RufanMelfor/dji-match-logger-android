package com.djimatchlogger.ble

/**
 * Builds one DUML frame.
 *
 * Layout, transcribed from `protocol_create_frame` in the firmware's
 * `dji_protocol_parser.c`:
 *
 * ```
 * [SOF 1][VER+LEN 2][CMDTYPE 1][ENC 1][RES 3][SEQ 2][CRC16 2][CMDSET 1][CMDID 1][DATA n][CRC32 4]
 * ```
 *
 * Three things the protocol document leaves to the source, and all three are
 * easy to get subtly wrong:
 *
 * - **VER+LEN packs the length into the low 10 bits**, version into the top
 *   six, and is written little-endian. A frame longer than 1023 bytes cannot
 *   be described at all, hence the [require] below.
 * - **CRC16 covers only the first 10 bytes** — SOF through SEQ, everything
 *   ahead of the CRC16 field itself. Not the whole header.
 * - **CRC32 covers everything from SOF to the end of DATA**, so the entire
 *   frame except the CRC32 field.
 *
 * Both checksums are little-endian, and both use [DjiCrc], whose init value
 * is what makes them DJI's rather than the standard variants.
 */
object DjiFrame {

    /** Start of frame; always this. */
    const val SOF: Byte = 0xAA.toByte()

    /** SOF through CmdID inclusive. */
    const val HEADER_LENGTH: Int = 14

    /** The trailing CRC32. */
    const val TAIL_LENGTH: Int = 4

    /** How much of the frame the CRC16 covers: SOF through SEQ. */
    const val CRC16_COVERAGE: Int = 10

    /** The only version the cameras speak. */
    private const val VERSION = 0

    /** The length field is 10 bits wide. */
    const val MAX_FRAME_LENGTH: Int = 0x03FF

    /**
     * Encodes one frame. [seq] is per camera — each link keeps its own
     * counter, and reusing one across two cameras will confuse the answers.
     */
    fun encode(
        cmdSet: Int,
        cmdId: Int,
        cmdType: Int,
        seq: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val total = HEADER_LENGTH + payload.size + TAIL_LENGTH
        require(total <= MAX_FRAME_LENGTH) {
            "Frame is $total bytes but the length field holds at most $MAX_FRAME_LENGTH"
        }

        val frame = ByteArray(total)
        var at = 0

        frame[at++] = SOF

        val verLength = (VERSION shl 10) or (total and MAX_FRAME_LENGTH)
        frame[at++] = (verLength and 0xFF).toByte()
        frame[at++] = ((verLength ushr 8) and 0xFF).toByte()

        frame[at++] = cmdType.toByte()
        frame[at++] = 0             // ENC: these cameras never encrypt.
        at += 3                     // RES: three reserved zeroes.

        frame[at++] = (seq and 0xFF).toByte()
        frame[at++] = ((seq ushr 8) and 0xFF).toByte()

        check(at == CRC16_COVERAGE) { "Header drifted: CRC16 would cover $at bytes" }
        val crc16 = DjiCrc.crc16(frame, CRC16_COVERAGE)
        frame[at++] = (crc16 and 0xFF).toByte()
        frame[at++] = ((crc16 ushr 8) and 0xFF).toByte()

        frame[at++] = cmdSet.toByte()
        frame[at++] = cmdId.toByte()

        payload.copyInto(frame, at)
        at += payload.size

        val crc32 = DjiCrc.crc32(frame, at)
        frame[at++] = (crc32 and 0xFF).toByte()
        frame[at++] = ((crc32 ushr 8) and 0xFF).toByte()
        frame[at++] = ((crc32 ushr 16) and 0xFF).toByte()
        frame[at] = ((crc32 ushr 24) and 0xFF).toByte()

        return frame
    }

    /**
     * A frame received from the camera, already checked.
     *
     * Not a data class on purpose: [payload] is a ByteArray, and generated
     * equality on it compares references, which would quietly mislead.
     */
    class Parsed(
        val version: Int,
        val cmdType: Int,
        val seq: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
    )

    /**
     * Decodes and validates one frame, or returns `null` if [bytes] is not a
     * sound one.
     *
     * Checks the same things `protocol_parse_notification` does: SOF, the
     * length field against the actual size, then both checksums. Callers
     * should count the nulls rather than ignore them — a stream of rejected
     * frames means the link is degrading, and that is invisible otherwise.
     */
    fun decode(bytes: ByteArray): Parsed? {
        if (bytes.size < HEADER_LENGTH + TAIL_LENGTH) return null
        if (bytes[0] != SOF) return null

        val verLength = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        if ((verLength and MAX_FRAME_LENGTH) != bytes.size) return null

        val storedCrc16 = (bytes[10].toInt() and 0xFF) or ((bytes[11].toInt() and 0xFF) shl 8)
        if (storedCrc16 != DjiCrc.crc16(bytes, CRC16_COVERAGE)) return null

        val body = bytes.size - TAIL_LENGTH
        val storedCrc32 = (bytes[body].toInt() and 0xFF) or
            ((bytes[body + 1].toInt() and 0xFF) shl 8) or
            ((bytes[body + 2].toInt() and 0xFF) shl 16) or
            ((bytes[body + 3].toInt() and 0xFF) shl 24)
        if (storedCrc32 != DjiCrc.crc32(bytes, body)) return null

        return Parsed(
            version = verLength ushr 10,
            cmdType = bytes[3].toInt() and 0xFF,
            seq = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8),
            cmdSet = bytes[12].toInt() and 0xFF,
            cmdId = bytes[13].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_LENGTH, body),
        )
    }

    /**
     * Reads the frame length out of a buffer's first three bytes, or `null`
     * if there are not enough of them yet.
     *
     * Used to cut frames out of a notification stream: BLE delivers packets,
     * not frames, and with a small MTU one frame arrives in several.
     */
    fun declaredLength(buffer: ByteArray, size: Int = buffer.size): Int? {
        if (size < 3) return null
        val verLength = (buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0xFF) shl 8)
        return verLength and MAX_FRAME_LENGTH
    }
}
