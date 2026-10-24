package com.djimatchlogger.ble

/**
 * Where one camera's BLE link is in its lifecycle.
 *
 * [Ready] is deliberately the only state that means "usable", and it sits two
 * steps past "connected". A camera whose CCCD was never written stays silent,
 * and one that never completed the `0x00 0x19` handshake accepts commands
 * while honouring none of them. Both look like a working link otherwise.
 */
sealed interface LinkState {

    data object Disconnected : LinkState

    data object Connecting : LinkState

    /** Connected; discovering the full GATT database. */
    data object Discovering : LinkState

    /** Services found; switching notifications on. */
    data object EnablingNotifications : LinkState

    /**
     * Notifications are on; running the DJI handshake (`0x00 0x19`).
     *
     * On a first pairing the camera asks its own user to confirm, so this can
     * sit here for as long as it takes someone to walk over and press it.
     */
    data object Handshaking : LinkState

    /** Handshake complete and subscribed to status. Commands will be honoured. */
    data object Ready : LinkState

    data class Failed(val reason: String) : LinkState
}
