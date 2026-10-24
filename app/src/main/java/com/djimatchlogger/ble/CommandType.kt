package com.djimatchlogger.ble

/**
 * Frame types from the CMDTYPE byte (`cmd_type_t` in the firmware's
 * `logic/enums_logic.h`).
 *
 * Bit 5 separates the two directions: commands are `0x0x`, the camera's
 * answers come back as `0x2x`.
 */
object CommandType {

    /** Command; the camera is not expected to answer at all. */
    const val NO_RESPONSE: Int = 0x00

    /** Command; an answer is welcome but its absence is not an error. */
    const val RESPONSE_OPTIONAL: Int = 0x01

    /** Command; an answer is required and its absence is an error. */
    const val WAIT_RESULT: Int = 0x02

    const val ACK_NO_RESPONSE: Int = 0x20
    const val ACK_RESPONSE_OPTIONAL: Int = 0x21
    const val ACK_WAIT_RESULT: Int = 0x22
}
