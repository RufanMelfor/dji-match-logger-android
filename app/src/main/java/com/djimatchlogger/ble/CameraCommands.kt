package com.djimatchlogger.ble

/**
 * The commands this app sends, as payloads for [DjiFrame.encode].
 *
 * Payload layouts are transcribed from `dji_protocol_data_structures.h` in
 * docs/ble-protocol.md. Every multi-byte field is little-endian.
 */
object CameraCommands {

    /** Everything here lives in command set 0x1D. */
    const val CMD_SET: Int = 0x1D

    /** The handshake lives in command set 0x00. */
    const val CMD_SET_COMMON: Int = 0x00
    const val CMD_CONNECTION: Int = 0x19
    const val CMD_KEY_REPORT: Int = 0x11
    const val CMD_POWER_MODE: Int = 0x1A
    const val CMD_GPS_PUSH: Int = 0x17

    const val CMD_STATUS_PUSH: Int = 0x02
    const val CMD_RECORD_CONTROL: Int = 0x03
    const val CMD_MODE_SWITCH: Int = 0x04
    const val CMD_STATUS_SUBSCRIPTION: Int = 0x05
    const val CMD_NEW_STATUS_PUSH: Int = 0x06

    /**
     * Record control (`0x1D03`).
     *
     * [deviceId] is **this app's** id from the handshake, not the camera's.
     * The id a camera advertises identifies its model — both Osmo Action 4s
     * report 0x0014 — and putting that here gets the command accepted and
     * then quietly ignored. Which camera acts is decided by the link the
     * frame is written to, nothing in the payload.
     */
    fun recordControl(deviceId: Int, start: Boolean): ByteArray = byteArrayOf(
        (deviceId and 0xFF).toByte(),
        ((deviceId ushr 8) and 0xFF).toByte(),
        ((deviceId ushr 16) and 0xFF).toByte(),
        ((deviceId ushr 24) and 0xFF).toByte(),
        if (start) 0x00 else 0x01,
        0x00, 0x00, 0x00, 0x00, // reserved[4]
    )

    /**
     * Status subscription (`0x1D05`).
     *
     * Without this the camera pushes nothing at all: enabling notifications
     * on the CCCD only opens the channel, it does not ask for traffic. That
     * is why a freshly connected camera stays silent indefinitely.
     */
    fun statusSubscription(
        pushMode: Int = PUSH_PERIODIC_AND_ON_CHANGE,
        pushFreq: Int = PUSH_FREQ_2HZ,
    ): ByteArray = byteArrayOf(
        pushMode.toByte(),
        pushFreq.toByte(),
        0x00, 0x00, 0x00, 0x00, // reserved[4]
    )

    const val PUSH_OFF: Int = 0
    const val PUSH_SINGLE: Int = 1
    const val PUSH_PERIODIC: Int = 2
    const val PUSH_PERIODIC_AND_ON_CHANGE: Int = 3

    /** Push frequency is expressed in 0.1 Hz and only 20 (2 Hz) is accepted. */
    const val PUSH_FREQ_2HZ: Int = 20

    /**
     * Connection request (`0x00 0x19`), 33 bytes.
     *
     * A GATT link is not a protocol connection. Until this handshake
     * completes the camera answers commands but honours nothing — a status
     * subscription sent too early is simply refused, which looks identical to
     * a camera that has nothing to say.
     *
     * [verifyMode] is [VERIFY_PAIR] the first time, which the camera asks its
     * user to confirm, and [VERIFY_RECONNECT] afterwards.
     */
    fun connectionRequest(
        deviceId: Int,
        protocolMac: ByteArray,
        fwVersion: Int = 0,
        verifyMode: Int = VERIFY_RECONNECT,
        verifyData: Int = 0,
    ): ByteArray {
        val payload = ByteArray(CONNECTION_REQUEST_LENGTH)
        writeInt(payload, 0, deviceId)
        payload[4] = protocolMac.size.toByte()
        protocolMac.copyInto(payload, 5, 0, minOf(protocolMac.size, MAC_FIELD_LENGTH))
        writeInt(payload, 21, fwVersion)
        payload[25] = 0 // conidx
        payload[26] = verifyMode.toByte()
        payload[27] = (verifyData and 0xFF).toByte()
        payload[28] = ((verifyData ushr 8) and 0xFF).toByte()
        return payload
    }

    /** Our closing ACK of the handshake (`0x00 0x19`), 9 bytes. */
    fun connectionResponse(deviceId: Int, retCode: Int = 0, cameraReserved: Int = 0): ByteArray {
        val payload = ByteArray(CONNECTION_RESPONSE_LENGTH)
        writeInt(payload, 0, deviceId)
        payload[4] = retCode.toByte()
        payload[5] = cameraReserved.toByte()
        return payload
    }

    const val VERIFY_RECONNECT: Int = 0
    const val VERIFY_PAIR: Int = 1

    /** What the camera sends back at us once it has decided. */
    const val VERIFY_CAMERA_REPLY: Int = 2

    /** Offsets into a connection request, for reading the camera's own. */
    const val OFFSET_VERIFY_MODE: Int = 26
    const val OFFSET_VERIFY_DATA: Int = 27

    /**
     * Power mode switch (`0x00 0x1A`), 1 byte.
     *
     * A sleeping camera refuses every command except this one with
     * [CameraStatusReport.POWER_NORMAL] — which is precisely why it is worth
     * having: the link survives sleep, so the camera can be brought back
     * without anyone walking over to it.
     */
    fun powerMode(mode: Int): ByteArray = byteArrayOf(mode.toByte())

    /**
     * GPS and time push (`0x00 0x17`), 45 bytes.
     *
     * One-way telemetry: the camera takes it, nothing comes back, and there is
     * no way to read its clock to check. Its value here is that it sets the
     * same wall clock on both cameras, so their files carry matching
     * timestamps and an editor can line them up without hunting.
     *
     * **The time must be expressed in UTC+8.** The struct fixes the timezone
     * there — the firmware achieves it by adding eight hours to a clock that
     * happens to be UTC, but stating it outright is safer on a phone that
     * knows its own timezone. Get this wrong and every file is hours out.
     *
     * [satelliteNumber] of zero means no valid fix. Whether the camera then
     * also discards the time is not known; sending a made-up position to avoid
     * finding out would write false coordinates into the footage, which is
     * worse than the uncertainty.
     */
    fun gpsAndTime(
        utcPlus8: java.time.ZonedDateTime,
        latitudeDegrees: Double = 0.0,
        longitudeDegrees: Double = 0.0,
        heightMetres: Double = 0.0,
        horizontalAccuracyMetres: Float = 0f,
        verticalAccuracyMetres: Float = 0f,
        satelliteNumber: Int = 0,
    ): ByteArray {
        val payload = ByteArray(GPS_PUSH_LENGTH)

        val date = utcPlus8.year * 10000 + utcPlus8.monthValue * 100 + utcPlus8.dayOfMonth
        val time = utcPlus8.hour * 10000 + utcPlus8.minute * 100 + utcPlus8.second
        writeInt(payload, 0, date)
        writeInt(payload, 4, time)

        writeInt(payload, 8, (longitudeDegrees * 1e7).toInt())
        writeInt(payload, 12, (latitudeDegrees * 1e7).toInt())
        writeInt(payload, 16, (heightMetres * 1000).toInt())

        writeFloat(payload, 20, 0f) // speed north, cm/s
        writeFloat(payload, 24, 0f) // speed east
        writeFloat(payload, 28, 0f) // speed down
        writeFloat(payload, 32, verticalAccuracyMetres)
        writeFloat(payload, 36, horizontalAccuracyMetres)
        writeFloat(payload, 40, 0f) // speed accuracy, m/s

        payload[44] = satelliteNumber.toByte()
        return payload
    }

    private const val GPS_PUSH_LENGTH = 45

    /** The timezone the GPS struct is fixed to. */
    val CAMERA_ZONE: java.time.ZoneOffset = java.time.ZoneOffset.ofHours(8)

    private fun writeFloat(into: ByteArray, at: Int, value: Float) =
        writeInt(into, at, java.lang.Float.floatToIntBits(value))

    private const val CONNECTION_REQUEST_LENGTH = 33
    private const val CONNECTION_RESPONSE_LENGTH = 9
    private const val MAC_FIELD_LENGTH = 16

    private fun writeInt(into: ByteArray, at: Int, value: Int) {
        into[at] = (value and 0xFF).toByte()
        into[at + 1] = ((value ushr 8) and 0xFF).toByte()
        into[at + 2] = ((value ushr 16) and 0xFF).toByte()
        into[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
