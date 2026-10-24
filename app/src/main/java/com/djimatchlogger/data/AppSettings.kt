package com.djimatchlogger.data

import android.content.Context

/** Which side of the pitch a camera is pointed from. */
enum class CameraSlot { LEFT, RIGHT }

/**
 * The handful of things the app has to remember between runs.
 *
 * Kept in SharedPreferences rather than in a match file: none of it belongs to
 * a match. A camera keeps its side because of where it is bolted, and the
 * delay after the final whistle is how this operator works.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /**
     * The side this camera is on, tied to its Bluetooth address.
     *
     * Scan order is not good enough: it changes between scans, so left and
     * right would swap under the operator between one match and the next. The
     * address does not change.
     */
    fun slotFor(address: String): CameraSlot? = prefs.getString(slotKey(address), null)
        ?.let { name -> runCatching { CameraSlot.valueOf(name) }.getOrNull() }

    fun addressFor(slot: CameraSlot): String? = prefs.all.entries
        .firstOrNull { it.key.startsWith(SLOT_PREFIX) && it.value == slot.name }
        ?.key
        ?.removePrefix(SLOT_PREFIX)

    /** Assigns a side, taking it off whichever camera had it before. */
    fun assign(address: String, slot: CameraSlot) {
        val editor = prefs.edit()
        addressFor(slot)?.takeIf { it != address }?.let { editor.remove(slotKey(it)) }
        editor.putString(slotKey(address), slot.name)
        editor.apply()
    }

    /** Gives this camera the first side nobody else has, if it has none yet. */
    fun assignFreeSlot(address: String) {
        if (slotFor(address) != null) return
        val free = CameraSlot.entries.firstOrNull { addressFor(it) == null } ?: return
        assign(address, free)
    }

    fun swapSlots() {
        val left = addressFor(CameraSlot.LEFT)
        val right = addressFor(CameraSlot.RIGHT)
        val editor = prefs.edit()
        left?.let { editor.putString(slotKey(it), CameraSlot.RIGHT.name) }
        right?.let { editor.putString(slotKey(it), CameraSlot.LEFT.name) }
        editor.apply()
    }

    /**
     * How long the cameras keep rolling after the final whistle.
     *
     * The whistle is not the end of what is worth filming — the handshakes and
     * whatever the referee is being told about all happen after it.
     */
    var stopDelaySeconds: Int
        get() = prefs.getInt(KEY_STOP_DELAY, DEFAULT_STOP_DELAY_SECONDS)
        set(value) = prefs.edit()
            .putInt(KEY_STOP_DELAY, value.coerceIn(0, MAX_STOP_DELAY_SECONDS))
            .apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()

    private fun slotKey(address: String) = SLOT_PREFIX + address

    companion object {
        const val DEFAULT_STOP_DELAY_SECONDS = 30
        const val MAX_STOP_DELAY_SECONDS = 300
        private const val SLOT_PREFIX = "slot_"
        private const val KEY_STOP_DELAY = "stop_delay_seconds"
        private const val KEY_DARK_MODE = "dark_mode"
    }
}
