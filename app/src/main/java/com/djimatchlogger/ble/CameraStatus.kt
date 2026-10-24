package com.djimatchlogger.ble

/**
 * Camera status as reported in a `0x1D02` push.
 *
 * Only `0x1D02` carries this. `0x1D06`, despite being described as the newer
 * status push, holds ASCII mode names and parameters and no status byte at
 * all — reading recording state out of it is not possible.
 */
object CameraStatus {

    const val SCREEN_OFF: Int = 0x00
    const val LIVE_VIEW: Int = 0x01
    const val PLAYBACK: Int = 0x02

    /** Taking a photo or recording video. */
    const val PHOTO_OR_RECORDING: Int = 0x03

    const val PRE_RECORDING: Int = 0x05

    /** Offset of `camera_status` in a `0x1D02` payload; `camera_mode` is at 0. */
    private const val STATUS_OFFSET = 1

    /**
     * Reads `camera_status` from a `0x1D02` payload, or `null` if the payload
     * is too short to hold one.
     */
    fun fromStatusPush(payload: ByteArray): Int? =
        if (payload.size <= STATUS_OFFSET) null else payload[STATUS_OFFSET].toInt() and 0xFF

    /**
     * Whether [status] means the camera is capturing.
     *
     * Pre-recording counts. The firmware treats it as recording, and for a
     * match log that is the honest reading: the camera is committing footage.
     * `docs/ble-protocol.md` mentions only 0x03, which would miss it.
     */
    fun isRecording(status: Int): Boolean =
        status == PHOTO_OR_RECORDING || status == PRE_RECORDING
}
