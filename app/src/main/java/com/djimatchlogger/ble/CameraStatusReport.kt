package com.djimatchlogger.ble

/**
 * Everything a `0x1D02` status push carries.
 *
 * Field order and widths are transcribed from
 * `camera_status_push_command_frame` in the firmware's
 * `dji_protocol_data_structures.h`. The struct is packed and little-endian,
 * so the offsets below are simply the running byte total — there is no
 * padding to account for.
 *
 * Parsing is tolerant of a short payload: older firmwares send fewer fields,
 * and a truncated push is still worth the fields it does contain.
 */
class CameraStatusReport private constructor(private val payload: ByteArray) {

    val cameraMode: Int? get() = u8(0)

    /** See [CameraStatus]. */
    val cameraStatus: Int? get() = u8(1)

    val videoResolution: Int? get() = u8(2)
    val fpsIndex: Int? get() = u8(3)
    val eisMode: Int? get() = u8(4)

    /** Seconds recorded so far, pre-recording included. */
    val recordTimeSeconds: Int? get() = u16(5)

    val fovType: Int? get() = u8(7)
    val photoRatio: Int? get() = u8(8)
    val timelapseIntervalTenths: Int? get() = u16(11)
    val timelapseDurationSeconds: Int? get() = u16(13)
    val nextCameraMode: Int? get() = u8(29)
    val photoCountdownMs: Int? get() = u32(31)
    val loopRecordSeconds: Int? get() = u16(35)
    val countdownSeconds: Int? get() = u16(9)

    /** Free space on the card, in MB. Zero with no card in the camera. */
    val remainingCapacityMb: Int? get() = u32(15)

    val remainingPhotos: Int? get() = u32(19)

    /** Seconds of video the card still has room for. */
    val remainingRecordSeconds: Int? get() = u32(23)

    val userMode: Int? get() = u8(27)

    /** [POWER_NORMAL] or [POWER_SLEEP]. */
    val powerMode: Int? get() = u8(28)

    /** 0 normal, 1 warm, 2 too hot to record, 3 about to shut down. */
    val temperature: Int? get() = u8(30)

    val batteryPercent: Int? get() = u8(37)

    val isRecording: Boolean get() = cameraStatus?.let(CameraStatus::isRecording) == true

    /**
     * The camera has gone to sleep. It keeps its BLE link and keeps pushing
     * status, but refuses every command except being woken.
     */
    val isAsleep: Boolean get() = powerMode == POWER_SLEEP

    private fun u8(at: Int): Int? =
        if (payload.size <= at) null else payload[at].toInt() and 0xFF

    private fun u16(at: Int): Int? =
        if (payload.size <= at + 1) null else {
            (payload[at].toInt() and 0xFF) or ((payload[at + 1].toInt() and 0xFF) shl 8)
        }

    private fun u32(at: Int): Int? =
        if (payload.size <= at + 3) null else {
            (payload[at].toInt() and 0xFF) or
                ((payload[at + 1].toInt() and 0xFF) shl 8) or
                ((payload[at + 2].toInt() and 0xFF) shl 16) or
                ((payload[at + 3].toInt() and 0xFF) shl 24)
        }

    companion object {
        const val POWER_NORMAL: Int = 0
        const val POWER_SLEEP: Int = 3

        const val TEMPERATURE_NORMAL: Int = 0
        const val TEMPERATURE_WARNING: Int = 1
        const val TEMPERATURE_TOO_HOT_TO_RECORD: Int = 2
        const val TEMPERATURE_SHUTTING_DOWN: Int = 3

        /** The full struct; anything shorter is an older firmware. */
        const val FULL_LENGTH: Int = 38

        fun from(payload: ByteArray): CameraStatusReport? =
            if (payload.isEmpty()) null else CameraStatusReport(payload)
    }
}
