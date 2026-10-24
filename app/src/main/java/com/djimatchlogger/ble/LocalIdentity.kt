package com.djimatchlogger.ble

import android.content.Context
import kotlin.random.Random

/**
 * This app's own identity in the DJI protocol handshake.
 *
 * The camera remembers whoever paired with it, so both values must survive
 * restarts — regenerating them turns every launch into a fresh pairing that
 * needs confirming on the camera.
 *
 * Neither value is a real address. The firmware sends a hard-coded six-byte
 * "protocol MAC" and a device id randomised on first boot, so the camera
 * clearly only needs something stable and unique. That matters here: since
 * Android 6 an app cannot read its own Bluetooth MAC at all — it gets
 * 02:00:00:00:00:00 — so a real one was never an option.
 */
class LocalIdentity(context: Context) {

    private val prefs = context.getSharedPreferences("ble_identity", Context.MODE_PRIVATE)

    /** Our device id, as sent in the connection request. */
    val deviceId: Int = prefs.getInt(KEY_DEVICE_ID, 0).takeIf { it != 0 }
        ?: Random.nextInt().also { prefs.edit().putInt(KEY_DEVICE_ID, it).apply() }

    /** Six bytes standing in for a MAC address. */
    val protocolMac: ByteArray = prefs.getString(KEY_MAC, null)?.let(::decode)
        ?: Random.nextBytes(MAC_LENGTH).also {
            prefs.edit().putString(KEY_MAC, encode(it)).apply()
        }

    /** Whether this install has completed a pairing with [address] before. */
    fun hasPaired(address: String): Boolean = prefs.getBoolean(pairedKey(address), false)

    fun rememberPairing(address: String) {
        prefs.edit().putBoolean(pairedKey(address), true).apply()
    }

    private fun pairedKey(address: String) = "paired_$address"

    private fun encode(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    private fun decode(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_MAC = "protocol_mac"
        const val MAC_LENGTH = 6
    }
}
