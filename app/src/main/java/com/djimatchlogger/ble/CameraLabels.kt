package com.djimatchlogger.ble

/**
 * Turns the numeric fields of a status push into readable text.
 *
 * All tables are transcribed from DJI's published camera enums.
 * Unknown values are shown as the raw number rather than hidden: a camera
 * model this app has not met before should still be legible.
 */
object CameraLabels {

    fun cameraMode(value: Int): String = when (value) {
        0x00 -> "Slow motion"
        0x01 -> "Video"
        0x02 -> "Timelapse"
        0x05 -> "Photo"
        0x0A -> "Hyperlapse"
        0x1A -> "Live streaming"
        0x23 -> "UVC streaming"
        0x28 -> "SuperNight"
        0x34 -> "Subject tracking"
        else -> "Mode $value"
    }

    fun cameraStatus(value: Int): String = when (value) {
        CameraStatus.SCREEN_OFF -> "Screen off"
        CameraStatus.LIVE_VIEW -> "Live view"
        CameraStatus.PLAYBACK -> "Playback"
        CameraStatus.PHOTO_OR_RECORDING -> "Recording"
        CameraStatus.PRE_RECORDING -> "Pre-recording"
        else -> "Status $value"
    }

    fun resolution(value: Int): String = when (value) {
        10 -> "1080p"
        16 -> "4K 16:9"
        45 -> "2.7K 16:9"
        66 -> "1080p 9:16"
        67 -> "2.7K 9:16"
        95 -> "2.7K 4:3"
        103 -> "4K 4:3"
        109 -> "4K 9:16"
        else -> "Res $value"
    }

    fun fps(value: Int): String = when (value) {
        1 -> "24 fps"
        2 -> "25 fps"
        3 -> "30 fps"
        4 -> "48 fps"
        5 -> "50 fps"
        6 -> "60 fps"
        7 -> "120 fps"
        8 -> "240 fps"
        10 -> "100 fps"
        19 -> "200 fps"
        else -> "$value fps?"
    }

    fun eis(value: Int): String = when (value) {
        0 -> "Off"
        1 -> "RS"
        2 -> "HS"
        3 -> "RS+"
        4 -> "HB"
        else -> "EIS $value"
    }

    fun powerMode(value: Int): String = when (value) {
        CameraStatusReport.POWER_NORMAL -> "Normal"
        CameraStatusReport.POWER_SLEEP -> "Sleep"
        else -> "Power $value"
    }

    fun temperature(value: Int): String = when (value) {
        CameraStatusReport.TEMPERATURE_NORMAL -> "Normal"
        CameraStatusReport.TEMPERATURE_WARNING -> "Warm"
        CameraStatusReport.TEMPERATURE_TOO_HOT_TO_RECORD -> "Too hot to record"
        CameraStatusReport.TEMPERATURE_SHUTTING_DOWN -> "Overheating"
        else -> "Temp $value"
    }

    /** Seconds as m:ss — for a stopwatch, where seconds matter. */
    fun duration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /**
     * Seconds as hours and minutes.
     *
     * Separate from [duration] because hours of remaining capacity rendered as
     * m:ss reads like a clock time: "491:28" is eight hours, but nobody sees
     * that at a glance.
     */
    fun longDuration(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"
    }

    /** Megabytes as GB, which is the only scale a card is ever discussed in. */
    fun capacity(megabytes: Int): String = "%.1f GB".format(megabytes / 1024.0)
}
